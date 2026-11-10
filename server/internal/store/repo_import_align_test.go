package store

import (
	"context"
	"path/filepath"
	"testing"
)

// 本文件覆盖 A1（源刷新对齐上游）。
//
// 分两类，缺一不可：
//   - 前几条是**新行为**：在旧代码上必红（旧代码只会追加，从不改名/不改台标/不标缺失）；
//   - 后几条是**保护性**：旧代码也绿，用来证明"修 A 没破 B"——
//     用户手动停用、台号、排序、EPG 映射、收藏都必须毫发无伤。

func openImportStore(t *testing.T) *Store {
	t.Helper()
	st, err := Open(filepath.Join(t.TempDir(), "imp.db"))
	if err != nil {
		t.Fatalf("打开库失败: %v", err)
	}
	t.Cleanup(func() { st.Close() })
	return st
}

// seedSource 建一个源，返回 id。
func seedSource(t *testing.T, st *Store, name string) int64 {
	t.Helper()
	id, err := st.CreateSource(context.Background(), Source{
		Name: name, Type: "m3u", URL: "https://example.invalid/" + name + ".m3u", Enabled: true,
	})
	if err != nil {
		t.Fatalf("建源失败: %v", err)
	}
	return id
}

// item 造一条解析结果。
func item(name, group, tvgID, logo, url string) Channel {
	return Channel{Name: name, Group: group, TvgID: tvgID, Logo: logo, Number: 1, PendingURL: url}
}

func chanByName(t *testing.T, st *Store, srcID int64, name string) Channel {
	t.Helper()
	list, _, err := st.ListChannels(context.Background(), ChannelQuery{SourceID: srcID, IncludeMissing: true, Limit: 500})
	if err != nil {
		t.Fatalf("列频道失败: %v", err)
	}
	for _, c := range list {
		if c.Name == name {
			return c
		}
	}
	t.Fatalf("找不到频道 %q（共有 %d 个）", name, len(list))
	return Channel{}
}

// TestImportRenamesInPlace 是「上游改名」的核心回归。
//
// 旧代码必然失败：判重只看 (name, group)。上游把 "CCTV1" 改成 "CCTV-1 综合"
// 之后匹配不上，于是**新插一条**，旧的那条留在库里变成死台——
// 用户看到的就是「上游更新，里面没有自动更新」+ 频道列表越来越长。
//
// 新行为：tvg_id 相同就认为是同一个台，原地改名，不新增。
func TestImportRenamesInPlace(t *testing.T) {
	st := openImportStore(t)
	ctx := context.Background()
	srcID := seedSource(t, st, "src-a")

	stats, err := st.ImportChannels(ctx, srcID, []Channel{
		item("CCTV1", "央视", "cctv1.epg", "", "http://origin.example/a/1.ts"),
	}, ImportOptions{Align: true})
	if err != nil {
		t.Fatalf("首次导入失败: %v", err)
	}
	if stats.Inserted != 1 {
		t.Fatalf("首次导入应新增 1，实际 %d", stats.Inserted)
	}

	// 上游改名（tvg_id 不变）
	stats, err = st.ImportChannels(ctx, srcID, []Channel{
		item("CCTV-1 综合", "央视", "cctv1.epg", "", "http://origin.example/a/1.ts"),
	}, ImportOptions{Align: true})
	if err != nil {
		t.Fatalf("二次导入失败: %v", err)
	}

	if stats.Inserted != 0 {
		t.Errorf("改名不该新增频道，实际新增 %d", stats.Inserted)
	}
	if stats.Updated != 1 {
		t.Errorf("改名应记为更新 1 个，实际 %d", stats.Updated)
	}

	list, total, err := st.ListChannels(ctx, ChannelQuery{SourceID: srcID, IncludeMissing: true, Limit: 500})
	if err != nil {
		t.Fatalf("列频道失败: %v", err)
	}
	if total != 1 {
		t.Fatalf("库里应只有 1 个频道，实际 %d（旧行为是插一条新的）", total)
	}
	if list[0].Name != "CCTV-1 综合" {
		t.Errorf("名称没有原地更新，实际 %q", list[0].Name)
	}
}

// TestImportUpdatesLogo 覆盖「上游换台标」。
//
// 旧代码必然失败：它从不更新 logo，用户永远看着第一次导入的那个图。
func TestImportUpdatesLogo(t *testing.T) {
	st := openImportStore(t)
	ctx := context.Background()
	srcID := seedSource(t, st, "src-a")

	if _, err := st.ImportChannels(ctx, srcID, []Channel{
		item("CCTV5", "央视", "cctv5.epg", "http://old.example/logo.png", "http://origin.example/5.ts"),
	}, ImportOptions{Align: true}); err != nil {
		t.Fatalf("首次导入失败: %v", err)
	}

	stats, err := st.ImportChannels(ctx, srcID, []Channel{
		item("CCTV5", "央视", "cctv5.epg", "http://new.example/logo.png", "http://origin.example/5.ts"),
	}, ImportOptions{Align: true})
	if err != nil {
		t.Fatalf("二次导入失败: %v", err)
	}
	if stats.Updated != 1 {
		t.Errorf("换台标应记为更新，实际 updated=%d", stats.Updated)
	}
	if got := chanByName(t, st, srcID, "CCTV5").Logo; got != "http://new.example/logo.png" {
		t.Errorf("台标没有更新，实际 %q", got)
	}
}

// TestImportMarksMissingNotDeleted 覆盖「上游下线」。
//
// 旧代码必然失败：没有任何下线语义，台永远留在列表里，用户点了播不出来。
//
// 关键断言：**行还在**、`src_missing=1`、`enabled` **不变**。
// 用 enabled=0 表示"上游移除"会和"用户手动停用"永久混淆——
// 用户手动启用一个已被移除的台就会把它重新激活。
func TestImportMarksMissingNotDeleted(t *testing.T) {
	st := openImportStore(t)
	ctx := context.Background()
	srcID := seedSource(t, st, "src-a")

	if _, err := st.ImportChannels(ctx, srcID, []Channel{
		item("保留台", "组", "keep.epg", "", "http://origin.example/keep.ts"),
		item("下线台", "组", "gone.epg", "", "http://origin.example/gone.ts"),
	}, ImportOptions{Align: true}); err != nil {
		t.Fatalf("首次导入失败: %v", err)
	}

	// 上游只给了 1 个台（另一条已下线）。
	stats, err := st.ImportChannels(ctx, srcID, []Channel{
		item("保留台", "组", "keep.epg", "", "http://origin.example/keep.ts"),
	}, ImportOptions{Align: true})
	if err != nil {
		t.Fatalf("二次导入失败: %v", err)
	}
	if stats.Missing != 1 {
		t.Fatalf("应标记 1 个缺失，实际 %d", stats.Missing)
	}

	gone := chanByName(t, st, srcID, "下线台")
	if gone.MissingSince == 0 {
		t.Error("missing_since 没有写（管理台要靠它显示「多久前没的」）")
	}
	if !gone.Enabled {
		t.Error("enabled 被改动了：上游移除不应该碰 enabled（那是用户的手动开关）")
	}

	// 默认列表（电视端/终端）里必须看不到它。
	visible, _, err := st.ListChannels(ctx, ChannelQuery{SourceID: srcID, Limit: 50})
	if err != nil {
		t.Fatalf("列频道失败: %v", err)
	}
	for _, c := range visible {
		if c.Name == "下线台" {
			t.Fatal("已被上游移除的频道仍然出现在默认列表里")
		}
	}
	// 但管理台显式要时能看到（否则没法解释"台少了"）。
	all, _, err := st.ListChannels(ctx, ChannelQuery{SourceID: srcID, IncludeMissing: true, Limit: 50})
	if err != nil {
		t.Fatalf("列频道失败: %v", err)
	}
	if len(all) != 2 {
		t.Errorf("管理台应能看到 2 个（含已移除），实际 %d", len(all))
	}
}

// TestImportRestoresWhenUpstreamReturns 覆盖「台回来了」。
func TestImportRestoresWhenUpstreamReturns(t *testing.T) {
	st := openImportStore(t)
	ctx := context.Background()
	srcID := seedSource(t, st, "src-a")

	full := []Channel{
		item("台A", "组", "a.epg", "", "http://origin.example/a.ts"),
		item("台B", "组", "b.epg", "", "http://origin.example/b.ts"),
	}
	if _, err := st.ImportChannels(ctx, srcID, full, ImportOptions{Align: true}); err != nil {
		t.Fatalf("首次导入失败: %v", err)
	}
	// B 消失
	if _, err := st.ImportChannels(ctx, srcID, full[:1], ImportOptions{Align: true}); err != nil {
		t.Fatalf("二次导入失败: %v", err)
	}
	// B 回来
	stats, err := st.ImportChannels(ctx, srcID, full, ImportOptions{Align: true})
	if err != nil {
		t.Fatalf("三次导入失败: %v", err)
	}
	if stats.Restored != 1 {
		t.Errorf("应记录 1 个恢复，实际 %d", stats.Restored)
	}
	if got := chanByName(t, st, srcID, "台B").MissingSince; got != 0 {
		t.Errorf("恢复后 missing_since 应清零，实际 %d", got)
	}
}

// TestImportSkipsAlignmentWhenNotHealthy 覆盖 A1-2 的骤降护栏。
//
// 旧代码必然失败（它根本没有对齐，也就没有"跳过"这个概念）。
//
// 场景：上游返回一个残缺页（只剩 1 个台），而上次有 40 个。
// 无护栏的对齐会把 39 个台一次性标记缺失——比"没自动更新"严重得多。
func TestImportSkipsAlignmentWhenNotHealthy(t *testing.T) {
	st := openImportStore(t)
	ctx := context.Background()
	srcID := seedSource(t, st, "src-a")

	many := make([]Channel, 0, 40)
	for i := 0; i < 40; i++ {
		many = append(many, Channel{
			Name: "台" + itoaTest(i), Group: "组", TvgID: "tvg" + itoaTest(i),
			Number: 1, PendingURL: "http://origin.example/" + itoaTest(i) + ".ts",
		})
	}
	if _, err := st.ImportChannels(ctx, srcID, many, ImportOptions{Align: true}); err != nil {
		t.Fatalf("首次导入失败: %v", err)
	}

	// 调用方判定"这次解析不健康"（Align=false），仍会写入解析到的条目。
	stats, err := st.ImportChannels(ctx, srcID, many[:1], ImportOptions{Align: false})
	if err != nil {
		t.Fatalf("二次导入失败: %v", err)
	}
	if stats.Missing != 0 {
		t.Errorf("跳过对齐时不该标记任何缺失，实际 %d", stats.Missing)
	}
	if !stats.AlignmentSkipped {
		t.Error("应置 alignment_skipped=true（管理台要能解释为什么没下线）")
	}
}

// TestImportDoesNotTouchForeignSourceFields 覆盖 A1-1「内容冒名」。
//
// 旧代码必然失败：它的判重是 `WHERE name=? AND group_name=?`，**不带 source_id**。
// 于是源 B 只要提供一个和他源同名的台（甚至同 tvg_id），就能命中别人那一行，
// 把 name/logo 改成自己想要的，再把自家线路塞进去——
// 用户看到的是信任的台名+台标，实际播的是攻击者的源。
//
// 新行为：字段更新只作用于本源的频道；跨源最多只会追加线路。
func TestImportDoesNotTouchForeignSourceFields(t *testing.T) {
	st := openImportStore(t)
	ctx := context.Background()

	srcA := seedSource(t, st, "src-a")
	srcB := seedSource(t, st, "src-b")

	// A 源先导入一个台。
	if _, err := st.ImportChannels(ctx, srcA, []Channel{
		item("CCTV1", "央视", "cctv1.epg", "http://trusted.example/logo.png", "http://origin-a.example/1.ts"),
	}, ImportOptions{Align: true}); err != nil {
		t.Fatalf("A 导入失败: %v", err)
	}
	before := chanByName(t, st, srcA, "CCTV1")

	// B 源提供一个同 tvg_id、同名、但台标是攻击者的台。
	// 注意 URL 不同（不同源站），所以 urlKey 匹配不成立，只能靠跨源兜底。
	if _, err := st.ImportChannels(ctx, srcB, []Channel{
		item("CCTV1", "央视", "cctv1.epg", "http://evil.example/logo.png", "http://origin-b.example/1.ts"),
	}, ImportOptions{Align: true}); err != nil {
		t.Fatalf("B 导入失败: %v", err)
	}

	after := chanByName(t, st, srcA, "CCTV1")
	if after.Logo != before.Logo {
		t.Fatalf("跨源刷新改掉了别的源的台标（内容冒名）：%q -> %q", before.Logo, after.Logo)
	}
	if after.Name != before.Name {
		t.Fatalf("跨源刷新改掉了别的源的台名：%q -> %q", before.Name, after.Name)
	}
	if after.TvgID != before.TvgID {
		t.Fatalf("跨源刷新改掉了别的源的 tvg_id：%q -> %q", before.TvgID, after.TvgID)
	}
	if after.Logo == "http://evil.example/logo.png" {
		t.Fatal("攻击者的台标被写进了信任的频道")
	}
}

// ---- 以下为保护性测试：旧代码也绿，用来证明"没修 A 破 B" ----

// TestImportKeepsUserDisabledChannel 确认刷新**不会**把用户停用的台打开。
//
// 这是上一批修掉的 handler 层 bug（给每条 item 硬写 Enabled:true）的回归。
func TestImportKeepsUserDisabledChannel(t *testing.T) {
	st := openImportStore(t)
	ctx := context.Background()
	srcID := seedSource(t, st, "src-a")

	ch := item("手动关掉的台", "组", "off.epg", "", "http://origin.example/off.ts")
	if _, err := st.ImportChannels(ctx, srcID, []Channel{ch}, ImportOptions{Align: true}); err != nil {
		t.Fatalf("首次导入失败: %v", err)
	}
	id := chanByName(t, st, srcID, "手动关掉的台").ID

	// 用户手动停用
	off := false
	if err := st.PatchChannel(ctx, id, ChannelPatch{Enabled: &off}); err != nil {
		t.Fatalf("停用失败: %v", err)
	}

	// 再刷一次（上游仍然有这个台）
	if _, err := st.ImportChannels(ctx, srcID, []Channel{ch}, ImportOptions{Align: true}); err != nil {
		t.Fatalf("二次导入失败: %v", err)
	}
	if got := chanByName(t, st, srcID, "手动关掉的台"); got.Enabled {
		t.Fatal("刷新把用户手动停用的频道重新打开了")
	}
}

// TestImportPreservesUserFields 确认刷新不动台号/排序/EPG 映射/收藏。
func TestImportPreservesUserFields(t *testing.T) {
	st := openImportStore(t)
	ctx := context.Background()
	srcID := seedSource(t, st, "src-a")

	if _, err := st.ImportChannels(ctx, srcID, []Channel{
		item("台号被用户改过", "组", "keep.epg", "", "http://origin.example/k.ts"),
	}, ImportOptions{Align: true}); err != nil {
		t.Fatalf("首次导入失败: %v", err)
	}
	c := chanByName(t, st, srcID, "台号被用户改过")

	// 用户手工调整：台号、EPG 映射、收藏
	num := 888
	if err := st.PatchChannel(ctx, c.ID, ChannelPatch{Number: &num}); err != nil {
		t.Fatalf("改台号失败: %v", err)
	}
	if err := st.SetChannelEpgID(ctx, c.ID, "user.chosen.epg"); err != nil {
		t.Fatalf("设 EPG 映射失败: %v", err)
	}
	devID, err := st.CreateDevice(ctx, "dev-1", "hash-1", "电视", "android", "TV")
	if err != nil {
		t.Fatalf("建设备失败: %v", err)
	}
	if err := st.SetFavorite(ctx, devID, c.ID, true); err != nil {
		t.Fatalf("收藏失败: %v", err)
	}

	// 再刷（上游改了自己的 number）
	if _, err := st.ImportChannels(ctx, srcID, []Channel{
		{Name: "台号被用户改过", Group: "组", TvgID: "keep.epg", Number: 5, PendingURL: "http://origin.example/k.ts"},
	}, ImportOptions{Align: true}); err != nil {
		t.Fatalf("二次导入失败: %v", err)
	}

	after := chanByName(t, st, srcID, "台号被用户改过")
	if after.Number != 888 {
		t.Errorf("刷新覆盖了用户设置的台号：期望 888，实际 %d", after.Number)
	}
	if after.EpgID != "user.chosen.epg" {
		t.Errorf("刷新覆盖了 EPG 映射：%q", after.EpgID)
	}
	favs, err := st.ListFavorites(ctx, devID)
	if err != nil {
		t.Fatalf("读收藏失败: %v", err)
	}
	if len(favs) != 1 || favs[0] != c.ID {
		t.Errorf("刷新动到了收藏：%v", favs)
	}
	if after.Sort != c.Sort {
		t.Errorf("刷新动到了排序：%d -> %d", c.Sort, after.Sort)
	}
}

// TestImportDoesNotBlockConcurrentWrites 证明「刷新会把 TV 端写死」这件事被修掉。
//
// 背景：SQLite 的写是串行的。WAL 让**读**不被阻塞，所以"大事务阻塞读"不成立；
// 真正的问题是**写**——一次几千条的导入如果是一个事务，
// TV 端的 play_events / watch_progress 写入会一直排到 busy_timeout（8 秒）。
// 用户描述里的「刷新时反而卡死」就是这么被放大的。
//
// 这条测试用 busy_timeout(100ms) 的库，断言并发小写在导入期间**不报锁错**。
// 旧实现（单事务 + per-item 查询）会大面积 "database is locked"。
func TestImportDoesNotBlockConcurrentWrites(t *testing.T) {
	st, err := openWithBusyTimeout(filepath.Join(t.TempDir(), "conc.db"), 100)
	if err != nil {
		t.Fatalf("打开库失败: %v", err)
	}
	t.Cleanup(func() { st.Close() })

	ctx := context.Background()
	srcID := seedSource(t, st, "src-big")

	const n = 600
	items := make([]Channel, 0, n)
	for i := 0; i < n; i++ {
		items = append(items, Channel{
			Name: "台" + itoaTest(i), Group: "大组", TvgID: "tvg" + itoaTest(i),
			Number: i, PendingURL: "http://origin.example/" + itoaTest(i) + ".ts",
		})
	}

	devID, err := st.CreateDevice(ctx, "dev-c", "hash-c", "电视", "android", "TV")
	if err != nil {
		t.Fatalf("建设备失败: %v", err)
	}

	// 并发写：模拟 TV 端在刷新期间上报 play_events。
	stop := make(chan struct{})
	done := make(chan int, 1)
	go func() {
		wrote := 0
		for {
			select {
			case <-stop:
				done <- wrote
				return
			default:
			}
			if err := st.AddPlayEvent(ctx, PlayEvent{DeviceID: devID, Event: "stall", StallMs: 100}); err != nil {
				// 锁超时 → 就是"卡死"的证据
				t.Errorf("导入期间的并发写被锁住了: %v", err)
				done <- wrote
				return
			}
			wrote++
		}
	}()

	// 小 chunk：让出写锁的机会。默认值也是这个量级。
	if _, err := st.ImportChannels(ctx, srcID, items, ImportOptions{Align: true, ChunkSize: 100}); err != nil {
		close(stop)
		<-done
		t.Fatalf("导入失败: %v", err)
	}
	close(stop)
	wrote := <-done

	if wrote == 0 {
		t.Fatal("并发写在导入期间一次都没成功——说明导入一直占着写锁")
	}
}

// itoaTest 是本文件用的小工具（避免引 strconv 噪音）。
func itoaTest(v int) string {
	if v == 0 {
		return "0"
	}
	var b []byte
	for v > 0 {
		b = append([]byte{byte('0' + v%10)}, b...)
		v /= 10
	}
	return string(b)
}
