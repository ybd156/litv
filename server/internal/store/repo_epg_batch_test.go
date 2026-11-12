package store

import (
	"context"
	"fmt"
	"testing"
)

// TestProgramsForEpgIDsBatches 覆盖 tvBootstrap 的批量取节目。
//
// 修复前 TV 首屏对每个频道各查一次 epg_programs；频道上千时首屏要等
// 好几秒。现在一条 IN 查询拿完，返回按 xmltv_id 分组的 map。
func TestProgramsForEpgIDsBatches(t *testing.T) {
	st := openTestStore(t)
	ctx := context.Background()
	now := Now()

	srcID, err := st.CreateEPGSourceEx(ctx, EPGSource{Name: "s", URL: "http://example.com/e.xml", Enabled: true, Priority: 1})
	if err != nil {
		t.Fatalf("建 EPG 源失败: %v", err)
	}
	ch1, err := st.UpsertEPGChannel(ctx, srcID, "cctv1", "CCTV-1", "")
	if err != nil {
		t.Fatalf("写 EPG 频道失败: %v", err)
	}
	ch2, err := st.UpsertEPGChannel(ctx, srcID, "cctv2", "CCTV-2", "")
	if err != nil {
		t.Fatalf("写 EPG 频道失败: %v", err)
	}

	if _, err := st.DB().ExecContext(ctx,
		`INSERT INTO epg_programs(epg_channel_id,start_at,end_at,title) VALUES(?,?,?,?)`,
		ch1, now-60, now+60, "新闻联播"); err != nil {
		t.Fatalf("写节目失败: %v", err)
	}
	// cctv2 的节目在窗口外：不应该出现
	if _, err := st.DB().ExecContext(ctx,
		`INSERT INTO epg_programs(epg_channel_id,start_at,end_at,title) VALUES(?,?,?,?)`,
		ch2, now-7200, now-3601, "很久以前"); err != nil {
		t.Fatalf("写节目失败: %v", err)
	}

	got, err := st.ProgramsForEpgIDs(ctx, []string{"cctv1", "cctv2"}, now-3600, now+3600)
	if err != nil {
		t.Fatalf("批量取节目失败: %v", err)
	}
	if len(got["cctv1"]) != 1 || got["cctv1"][0].Title != "新闻联播" {
		t.Fatalf("cctv1 的节目不对: %+v", got["cctv1"])
	}
	if len(got["cctv2"]) != 0 {
		t.Fatalf("cctv2 不该有窗口内的节目: %+v", got["cctv2"])
	}

	// 空输入不应该查库，也不应该报错
	empty, err := st.ProgramsForEpgIDs(ctx, nil, now-3600, now+3600)
	if err != nil || len(empty) != 0 {
		t.Fatalf("空输入应返回空 map: %v %v", empty, err)
	}
}

// TestProgramsForEpgIDsSpansBatches 覆盖分批实现最容易被写错的地方：
// 每批的结果必须并回同一个 map，不能每批重开一个。
//
// 单批上限 500，这里给 1200 个 id，把两个真频道分别放在第一批和最后一批。
// 旧实现一条 IN 查询传 1200+2 个参数，会撞 SQLite 的变量上限
// （默认 999），错误又被 tvBootstrap 丢掉——电视端首页直接没有当前节目。
func TestProgramsForEpgIDsSpansBatches(t *testing.T) {
	st := openTestStore(t)
	ctx := context.Background()
	now := Now()

	srcID, err := st.CreateEPGSourceEx(ctx, EPGSource{Name: "s", URL: "http://example.com/e.xml", Enabled: true, Priority: 1})
	if err != nil {
		t.Fatalf("建 EPG 源失败: %v", err)
	}
	ch1, err := st.UpsertEPGChannel(ctx, srcID, "cctv1", "CCTV-1", "")
	if err != nil {
		t.Fatalf("写 EPG 频道失败: %v", err)
	}
	ch2, err := st.UpsertEPGChannel(ctx, srcID, "cctv2", "CCTV-2", "")
	if err != nil {
		t.Fatalf("写 EPG 频道失败: %v", err)
	}
	for _, p := range []struct {
		ch    int64
		title string
	}{{ch1, "第一批"}, {ch2, "最后一批"}} {
		if _, err := st.DB().ExecContext(ctx,
			`INSERT INTO epg_programs(epg_channel_id,start_at,end_at,title) VALUES(?,?,?,?)`,
			p.ch, now-60, now+60, p.title); err != nil {
			t.Fatalf("写节目失败: %v", err)
		}
	}

	ids := make([]string, 0, 1200)
	ids = append(ids, "cctv1") // 第一批
	for i := 0; i < 1198; i++ {
		ids = append(ids, fmt.Sprintf("nope-%d", i))
	}
	ids = append(ids, "cctv2") // 第三批（最后）

	got, err := st.ProgramsForEpgIDs(ctx, ids, now-3600, now+3600)
	if err != nil {
		t.Fatalf("跨批次取节目失败: %v", err)
	}
	if len(got["cctv1"]) != 1 || got["cctv1"][0].Title != "第一批" {
		t.Fatalf("第一批的结果丢了: %+v", got["cctv1"])
	}
	if len(got["cctv2"]) != 1 || got["cctv2"][0].Title != "最后一批" {
		t.Fatalf("最后一批的结果丢了: %+v", got["cctv2"])
	}
}

// TestListAllEnabledChannelsHasNoPaginationCap 是「第 501 个台消失」的回归测试。
func TestListAllEnabledChannelsHasNoPaginationCap(t *testing.T) {
	st := openTestStore(t)
	ctx := context.Background()

	const total = 501
	for i := 0; i < total; i++ {
		if _, err := st.CreateChannel(ctx, Channel{
			Number:  i + 1,
			Name:    fmt.Sprintf("频道 %d", i+1),
			Enabled: true,
		}); err != nil {
			t.Fatalf("建频道失败: %v", err)
		}
	}

	chans, err := st.ListAllEnabledChannels(ctx)
	if err != nil {
		t.Fatalf("取全部频道失败: %v", err)
	}
	if len(chans) != total {
		t.Fatalf("只拿到 %d 个频道，期望 %d（说明还有分页上限）", len(chans), total)
	}
}
