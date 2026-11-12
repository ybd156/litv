package store

import (
	"context"
	"errors"
	"testing"
)

// openTestStore 由 repo_device_write_test.go 提供（同一个包，别重复定义）。

// countRows 数一个表里某 channel_id 的行数。
func countRows(t *testing.T, st *Store, table string, channelID int64) int {
	t.Helper()
	var n int
	// table 是测试内的字面量，不是外部输入。
	if err := st.DB().QueryRowContext(context.Background(),
		`SELECT COUNT(*) FROM `+table+` WHERE channel_id=?`, channelID).Scan(&n); err != nil {
		t.Fatalf("数 %s 失败: %v", table, err)
	}
	return n
}

// seedChannelWithDependents 造一个"到处都有引用"的频道。
func seedChannelWithDependents(t *testing.T, st *Store) (chID, devID int64) {
	t.Helper()
	ctx := context.Background()

	id, err := st.CreateChannel(ctx, Channel{Number: 1, Name: "被删的台", Group: "测试", Enabled: true})
	if err != nil {
		t.Fatalf("建频道失败: %v", err)
	}
	devID, err = st.CreateDevice(ctx, "key-1", "hash-1", "电视", "android", "TV")
	if err != nil {
		t.Fatalf("建设备失败: %v", err)
	}
	if err := st.SetFavorite(ctx, devID, id, true); err != nil {
		t.Fatalf("收藏失败: %v", err)
	}
	if err := st.UpsertProgress(ctx, devID, id, 123); err != nil {
		t.Fatalf("写进度失败: %v", err)
	}
	if err := st.AddPlayEvent(ctx, PlayEvent{DeviceID: devID, ChannelID: id, Event: "start"}); err != nil {
		t.Fatalf("写播放事件失败: %v", err)
	}
	if _, err := st.DB().ExecContext(ctx,
		`INSERT INTO epg_reminders(device_id,channel_id,channel_name,title,start_at,fired,created_at)
		 VALUES(?,?,?,?,?,0,?)`, devID, id, "被删的台", "节目", Now(), Now()); err != nil {
		t.Fatalf("写提醒失败: %v", err)
	}
	return id, devID
}

// TestDeleteChannelCleansDependents 是本轮 A5 的核心回归。
//
// 修复前必然失败：DeleteChannel 只发一条 `DELETE FROM channels WHERE id=?`，
// 而 favorites / watch_progress / epg_reminders / play_events 的 channel_id
// **都没有外键**（建表时故意没加），所以不会级联。
// 留下的孤儿行不报错，只会安静地坏掉：
// TV 端"我的收藏"里点一个已删的台永远播不了、跨端续播指向不存在的频道、
// 播放统计把事件记到一个没人认识的 id 上。
func TestDeleteChannelCleansDependents(t *testing.T) {
	st := openTestStore(t)
	chID, _ := seedChannelWithDependents(t, st)

	for _, tbl := range []string{"favorites", "watch_progress", "epg_reminders", "play_events"} {
		if got := countRows(t, st, tbl, chID); got == 0 {
			t.Fatalf("前置条件不成立：%s 里没有引用行", tbl)
		}
	}

	if err := st.DeleteChannel(context.Background(), chID); err != nil {
		t.Fatalf("删频道失败: %v", err)
	}

	if _, err := st.GetChannel(context.Background(), chID); err == nil {
		t.Fatal("频道没有被删掉")
	}
	for _, tbl := range []string{"favorites", "watch_progress", "epg_reminders", "play_events"} {
		if got := countRows(t, st, tbl, chID); got != 0 {
			t.Errorf("%s 里还有 %d 条孤儿行（引用已删除的频道）", tbl, got)
		}
	}
}

// TestBatchDeleteChannelsCleansDependents 同上，走批量入口。
func TestBatchDeleteChannelsCleansDependents(t *testing.T) {
	st := openTestStore(t)
	chID, _ := seedChannelWithDependents(t, st)

	n, err := st.BatchChannels(context.Background(), []int64{chID}, "delete", "")
	if err != nil {
		t.Fatalf("批量删除失败: %v", err)
	}
	if n != 1 {
		t.Errorf("affected 应为 1，实际 %d", n)
	}
	for _, tbl := range []string{"favorites", "watch_progress", "epg_reminders", "play_events"} {
		if got := countRows(t, st, tbl, chID); got != 0 {
			t.Errorf("%s 里还有 %d 条孤儿行", tbl, got)
		}
	}
}

// TestDeleteChannelKeepsFinishedRecording 覆盖 recordings 的分流规则。
//
// 这一条是本轮最容易做错的地方：recordings 是**用户数据**，不是派生状态。
// 盲删行会把 .mp4 永久留在磁盘上，而且再没有任何入口能发现它——
// 把"数据库孤儿"换成了"磁盘孤儿"，更难查。
//
// 规则：
//   - finished 行必须保留（行在 → 文件可达 → 用户能在录制中心自己删）
//   - scheduled / recording 行置 canceled（不能留 scheduled：
//     调度器每 5 秒扫一次 status='scheduled'，频道已不存在会无限失败刷日志）
func TestDeleteChannelKeepsFinishedRecording(t *testing.T) {
	st := openTestStore(t)
	ctx := context.Background()

	chID, err := st.CreateChannel(ctx, Channel{Number: 1, Name: "录制过的台", Enabled: true})
	if err != nil {
		t.Fatalf("建频道失败: %v", err)
	}
	doneID, err := st.CreateRecording(ctx, Recording{ChannelID: chID, Name: "已录完", StartAt: 1, EndAt: 2})
	if err != nil {
		t.Fatalf("建录制失败: %v", err)
	}
	if err := st.UpdateRecording(ctx, doneID, "finished", "/tmp/whatever.ts", 10); err != nil {
		t.Fatalf("更新录制失败: %v", err)
	}
	schedID, err := st.CreateRecording(ctx, Recording{ChannelID: chID, Name: "待录", StartAt: 3, EndAt: 4})
	if err != nil {
		t.Fatalf("建预约失败: %v", err)
	}

	if err := st.DeleteChannel(ctx, chID); err != nil {
		t.Fatalf("删频道失败: %v", err)
	}

	// finished：行还在，path 还在（文件才能被找到）。
	done, err := st.GetRecording(ctx, doneID)
	if err != nil {
		t.Fatalf("finished 录制行被删了（会留下磁盘孤儿文件）: %v", err)
	}
	if done.Path != "/tmp/whatever.ts" {
		t.Errorf("finished 录制的 path 被清掉了：%q", done.Path)
	}

	// scheduled：改成 canceled，不再被调度器捞起来。
	sched, err := st.GetRecording(ctx, schedID)
	if err != nil {
		t.Fatalf("scheduled 录制行被删了: %v", err)
	}
	if sched.Status != "canceled" {
		t.Errorf("未开始的录制应置 canceled，实际 %q", sched.Status)
	}
}

// TestPatchChannelFields 覆盖部分更新的三个易错点：
// 显式清空生效、数值 0 生效、nil 不动。
func TestPatchChannelFields(t *testing.T) {
	st := openTestStore(t)
	ctx := context.Background()

	id, err := st.CreateChannel(ctx, Channel{
		Number: 7, Name: "原名", Group: "原组", Logo: "l.png", TvgID: "t1", Enabled: true,
	})
	if err != nil {
		t.Fatalf("建频道失败: %v", err)
	}
	zero := 0
	empty := ""
	if err := st.PatchChannel(ctx, id, ChannelPatch{Number: &zero, Group: &empty}); err != nil {
		t.Fatalf("部分更新失败: %v", err)
	}
	ch, err := st.GetChannel(ctx, id)
	if err != nil {
		t.Fatalf("读回失败: %v", err)
	}
	if ch.Number != 0 {
		t.Errorf("台号没有设成 0，实际 %d", ch.Number)
	}
	if ch.Group != "" {
		t.Errorf("分组没有被清空，实际 %q", ch.Group)
	}
	// 没传的列必须原样保留。
	if ch.Name != "原名" || ch.Logo != "l.png" || ch.TvgID != "t1" || !ch.Enabled {
		t.Errorf("未传入的字段被改动了：%+v", ch)
	}
}

// TestUpdateChannelDoesNotTouchEpgID 锁定 store 层的行为。
//
// UpdateChannel 是台标上传等"读-改-写"路径在用的全量更新。
// 它的 SET 里**不能**有 epg_id：handler 从不设置那个字段，
// 一旦写回去就会把用户对好的节目单映射抹掉。
func TestUpdateChannelDoesNotTouchEpgID(t *testing.T) {
	st := openTestStore(t)
	ctx := context.Background()

	id, err := st.CreateChannel(ctx, Channel{Number: 1, Name: "台", Enabled: true})
	if err != nil {
		t.Fatalf("建频道失败: %v", err)
	}
	if err := st.SetChannelEpgID(ctx, id, "keep.me"); err != nil {
		t.Fatalf("设置映射失败: %v", err)
	}

	// 模拟台标上传：读出来、改 logo、写回去（EpgID 字段故意留空）。
	ch, err := st.GetChannel(ctx, id)
	if err != nil {
		t.Fatalf("读回失败: %v", err)
	}
	ch.EpgID = "" // 模拟"调用方没有正确回填"
	ch.Logo = "new-logo.png"
	if err := st.UpdateChannel(ctx, *ch); err != nil {
		t.Fatalf("更新失败: %v", err)
	}

	after, err := st.GetChannel(ctx, id)
	if err != nil {
		t.Fatalf("读回失败: %v", err)
	}
	if after.EpgID != "keep.me" {
		t.Errorf("UpdateChannel 动到了 epg_id（映射被抹掉了）：%q", after.EpgID)
	}
	if after.Logo != "new-logo.png" {
		t.Errorf("logo 没有被更新：%q", after.Logo)
	}
}

// TestRegisterRejectsPlatformChangeOnPendingDevice 覆盖 A7 的 store 层边界。
//
// 修复前必然失败：只有 existStatus == "bound" 才拒绝平台变更，
// pending 行会被直接覆盖 token_hash 并返回成功（status=pending, err=nil）。
func TestRegisterRejectsPlatformChangeOnPendingDevice(t *testing.T) {
	st := openTestStore(t)
	ctx := context.Background()

	if _, status, err := st.RegisterWithBindCode(ctx, "K1", "hash-a", "电视", "android", "TV", ""); err != nil || status != "pending" {
		t.Fatalf("首次注册失败: status=%q err=%v", status, err)
	}
	_, _, err := st.RegisterWithBindCode(ctx, "K1", "hash-b", "电视", "ios", "iPhone", "")
	if err == nil {
		t.Fatal("同 device_key 换平台没有被拒绝——知道 key 就能顶掉设备")
	}
	if !errors.Is(err, ErrDeviceKeyConflict) {
		t.Fatalf("错误类型不对：%v", err)
	}
}
