package store

import (
	"context"
	"testing"
)

// TestDeleteSourceCleansDependents 是本轮 O05 的核心回归。
//
// 修复前必然失败：DeleteSource 直接 `DELETE FROM channels WHERE source_id=?`，
// 而 favorites / watch_progress / epg_reminders / play_events 的 channel_id
// **都没有外键**，不会级联。删源于是留下一堆孤儿行：
// TV 端「我的收藏」里点一个已删的台永远播不了、跨端续播指向不存在的频道、
// 播放统计记在没人认识的 id 上。
//
// 注意：必须沿 DeleteSource 这条路径测。DeleteChannel 早就是对的，
// 拿它测等于没测到这个缺陷。
func TestDeleteSourceCleansDependents(t *testing.T) {
	st := openTestStore(t)
	ctx := context.Background()

	srcID, err := st.CreateSource(ctx, Source{Name: "测试源", Type: "m3u", URL: "http://example.com/x.m3u"})
	if err != nil {
		t.Fatalf("建源失败: %v", err)
	}

	chID, err := st.CreateChannel(ctx, Channel{
		Number: 1, Name: "源里的台", Group: "测试", Enabled: true, SourceID: srcID,
	})
	if err != nil {
		t.Fatalf("建频道失败: %v", err)
	}

	devID, err := st.CreateDevice(ctx, "key-o05", "hash-o05", "电视", "android", "TV")
	if err != nil {
		t.Fatalf("建设备失败: %v", err)
	}
	if err := st.SetFavorite(ctx, devID, chID, true); err != nil {
		t.Fatalf("收藏失败: %v", err)
	}
	if err := st.UpsertProgress(ctx, devID, chID, 42); err != nil {
		t.Fatalf("写进度失败: %v", err)
	}
	if err := st.AddPlayEvent(ctx, PlayEvent{DeviceID: devID, ChannelID: chID, Event: "start"}); err != nil {
		t.Fatalf("写播放事件失败: %v", err)
	}
	if _, err := st.DB().ExecContext(ctx,
		`INSERT INTO epg_reminders(device_id,channel_id,channel_name,title,start_at,fired,created_at)
		 VALUES(?,?,?,?,?,0,?)`, devID, chID, "源里的台", "节目", Now(), Now()); err != nil {
		t.Fatalf("写提醒失败: %v", err)
	}

	// 前置条件：引用行确实存在
	for _, tbl := range []string{"favorites", "watch_progress", "epg_reminders", "play_events"} {
		if got := countRows(t, st, tbl, chID); got == 0 {
			t.Fatalf("前置条件不成立：%s 里没有引用行", tbl)
		}
	}

	if err := st.DeleteSource(ctx, srcID); err != nil {
		t.Fatalf("删源失败: %v", err)
	}

	// 源和频道都要没了
	if _, err := st.GetChannel(ctx, chID); err == nil {
		t.Fatal("源删了，频道还在")
	}
	if _, err := st.GetSource(ctx, srcID); err == nil {
		t.Fatal("源没有被删掉")
	}

	// 派生引用必须清零
	for _, tbl := range []string{"favorites", "watch_progress", "epg_reminders", "play_events"} {
		if got := countRows(t, st, tbl, chID); got != 0 {
			t.Errorf("%s 里还有 %d 条孤儿行（引用已删除的频道）", tbl, got)
		}
	}
}

// TestDeleteSourceCancelsScheduledRecording 覆盖 recordings 的分流规则。
//
// scheduled 的录制必须置 canceled：调度器每 5 秒扫一次 status='scheduled'，
// 频道已经不存在，那行会无限失败重试刷日志。
// finished 的录制必须**保留行**：它是用户数据，文件还在盘上，
// 删了行就等于把 .mp4 变成没人发现的磁盘孤儿。
func TestDeleteSourceCancelsScheduledRecording(t *testing.T) {
	st := openTestStore(t)
	ctx := context.Background()

	srcID, err := st.CreateSource(ctx, Source{Name: "录制源", Type: "m3u", URL: "http://example.com/r.m3u"})
	if err != nil {
		t.Fatalf("建源失败: %v", err)
	}
	chID, err := st.CreateChannel(ctx, Channel{
		Number: 2, Name: "录制的台", Group: "测试", Enabled: true, SourceID: srcID,
	})
	if err != nil {
		t.Fatalf("建频道失败: %v", err)
	}

	// 两条录制：一条 scheduled，一条 finished
	for _, st0 := range []string{"scheduled", "finished"} {
		if _, err := st.DB().ExecContext(ctx,
			`INSERT INTO recordings(channel_id,name,start_at,end_at,status,path,created_at)
			 VALUES(?,?,?,?,?,?,?)`,
			chID, "节目-"+st0, Now(), Now()+60, st0, "/tmp/x-"+st0+".mp4", Now()); err != nil {
			t.Fatalf("写录制(%s)失败: %v", st0, err)
		}
	}

	if err := st.DeleteSource(ctx, srcID); err != nil {
		t.Fatalf("删源失败: %v", err)
	}

	var scheduled, finished int
	if err := st.DB().QueryRowContext(ctx,
		`SELECT COUNT(*) FROM recordings WHERE channel_id=? AND status='scheduled'`, chID).
		Scan(&scheduled); err != nil {
		t.Fatalf("查 scheduled 失败: %v", err)
	}
	if scheduled != 0 {
		t.Errorf("还有 %d 条 scheduled 录制（调度器会无限重试）", scheduled)
	}
	if err := st.DB().QueryRowContext(ctx,
		`SELECT COUNT(*) FROM recordings WHERE channel_id=? AND status='finished'`, chID).
		Scan(&finished); err != nil {
		t.Fatalf("查 finished 失败: %v", err)
	}
	if finished != 1 {
		t.Errorf("finished 录制行应保留（文件还在盘上），实际 %d 条", finished)
	}
}
