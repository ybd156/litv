package api

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"testing"

	"github.com/litv/litv/server/internal/store"
)

// seedMapping 造一组 EPG 频道 + 直播频道，用于映射测试。
func seedMapping(t *testing.T, a *API) int64 {
	t.Helper()
	ctx := context.Background()

	srcID, err := a.st.CreateEPGSourceEx(ctx, store.EPGSource{
		Name: "映射测试源", URL: "http://x", Enabled: true, Kind: "url",
	})
	if err != nil {
		t.Fatalf("建 EPG 源失败: %v", err)
	}
	for _, e := range []struct{ xmltv, disp string }{
		{"cctv1", "CCTV-1"},
		{"cctv2", "CCTV-2"},
		{"cctv5", "CCTV-5"},
	} {
		if _, err := a.st.UpsertEPGChannel(ctx, srcID, e.xmltv, e.disp, ""); err != nil {
			t.Fatalf("建 EPG 频道失败: %v", err)
		}
	}
	for _, c := range []struct {
		number int
		name   string
	}{
		{1, "CCTV-1 综合"}, // 前缀匹配 cctv1
		{2, "CCTV2"},     // 归一化后精确匹配 cctv2
		{10, "CCTV-10"},  // 不应被 cctv1 吃掉
		{99, "某地方台"},     // 完全匹配不上
	} {
		if _, err := a.st.CreateChannel(ctx, store.Channel{
			Number: c.number, Name: c.name, Enabled: true,
		}); err != nil {
			t.Fatalf("建频道失败: %v", err)
		}
	}
	return srcID
}

// TestAutoMapReport 覆盖 B1（结果预览）与 B2（置信度/未匹配）。
func TestAutoMapReport(t *testing.T) {
	a, _ := newTestAPI(t)
	seedMapping(t, a)

	rec := adminRequest(t, a, http.MethodPost, "/api/v1/epg/automap", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("自动匹配失败：%d %s", rec.Code, rec.Body.String())
	}
	var out struct {
		Data struct {
			Scanned   int `json:"scanned"`
			Matched   int `json:"matched"`
			Unmatched []struct {
				Name      string `json:"name"`
				BestGuess string `json:"best_guess"`
				BestScore int    `json:"best_score"`
			} `json:"unmatched"`
			Conflicts []any `json:"conflicts"`
		} `json:"data"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("解析失败: %v", err)
	}

	if out.Data.Scanned != 4 {
		t.Errorf("扫描数期望 4，实际 %d", out.Data.Scanned)
	}
	if out.Data.Matched != 2 {
		t.Errorf("应匹配上 2 个（CCTV-1 综合 / CCTV2），实际 %d", out.Data.Matched)
	}
	// CCTV-10 与 某地方台 应留在未匹配里
	if len(out.Data.Unmatched) != 2 {
		t.Fatalf("未匹配期望 2 个，实际 %d：%+v", len(out.Data.Unmatched), out.Data.Unmatched)
	}
	// CCTV-10 必须留在未匹配里：cctv1 后面紧跟数字不算前缀匹配，
	// 否则「CCTV-1」会把「CCTV-10/11/12…」全吃掉。
	sawCCTV10 := false
	for _, u := range out.Data.Unmatched {
		if u.Name == "CCTV-10" {
			sawCCTV10 = true
		}
	}
	if !sawCCTV10 {
		t.Error("CCTV-10 应当留在未匹配列表里，却被自动匹配掉了")
	}
	// 未匹配项应给出最佳候选与分数（B2）
	for _, u := range out.Data.Unmatched {
		if u.Name == "CCTV-10" && u.BestGuess == "" {
			t.Logf("提示：CCTV-10 没有候选，符合预期（EPG 里没有 cctv10）")
		}
	}
}

// TestAutoMapDoesNotEatCCTV10 单独钉住「cctv1 不能吃掉 cctv10」这条规则。
func TestAutoMapDoesNotEatCCTV10(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	srcID, _ := a.st.CreateEPGSourceEx(ctx, store.EPGSource{Name: "s", URL: "http://x", Enabled: true})
	if _, err := a.st.UpsertEPGChannel(ctx, srcID, "cctv1", "CCTV-1", ""); err != nil {
		t.Fatalf("建 EPG 频道失败: %v", err)
	}
	if _, err := a.st.CreateChannel(ctx, store.Channel{Number: 10, Name: "CCTV-10", Enabled: true}); err != nil {
		t.Fatalf("建频道失败: %v", err)
	}

	rep, err := a.st.AutoMapChannelsReport(ctx)
	if err != nil {
		t.Fatalf("自动匹配失败: %v", err)
	}
	if rep.Matched != 0 {
		t.Fatalf("CCTV-10 不该匹配到 cctv1，实际匹配了 %d 个", rep.Matched)
	}
}

// TestMappingConflicts 覆盖 B6。
func TestMappingConflicts(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()
	srcID := seedMapping(t, a)
	_ = srcID

	// 手动把两个频道映射到同一个 xmltv_id
	var ids []int64
	rows, err := a.st.DB().QueryContext(ctx, `SELECT id FROM channels ORDER BY id LIMIT 2`)
	if err != nil {
		t.Fatalf("查频道失败: %v", err)
	}
	for rows.Next() {
		var id int64
		_ = rows.Scan(&id)
		ids = append(ids, id)
	}
	rows.Close()
	if len(ids) < 2 {
		t.Fatalf("测试前提不成立：频道不足 2 个")
	}
	if _, err := a.st.BatchSetChannelEpgID(ctx, ids, "cctv1"); err != nil {
		t.Fatalf("批量映射失败: %v", err)
	}

	rec := adminRequest(t, a, http.MethodGet, "/api/v1/epg/mapping", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("读映射状态失败：%d", rec.Code)
	}
	var out struct {
		Data struct {
			Conflicts []struct {
				EpgID    string   `json:"epg_id"`
				Channels []string `json:"channels"`
			} `json:"conflicts"`
		} `json:"data"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	if len(out.Data.Conflicts) == 0 {
		t.Fatal("两个频道映射到同一个 EPG 频道，应当报冲突")
	}
	if out.Data.Conflicts[0].EpgID != "cctv1" {
		t.Errorf("冲突的 epg_id 期望 cctv1，实际 %q", out.Data.Conflicts[0].EpgID)
	}
	if len(out.Data.Conflicts[0].Channels) < 2 {
		t.Errorf("冲突应列出至少 2 个频道，实际 %v", out.Data.Conflicts[0].Channels)
	}
}

// TestMapByNumberOnlyUnique 覆盖 B4：只在数字唯一对应时才映射。
func TestMapByNumberOnlyUnique(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	srcID, _ := a.st.CreateEPGSourceEx(ctx, store.EPGSource{Name: "s", URL: "http://x", Enabled: true})
	// 5 只对应一个 EPG 频道；6 对应两个（有歧义）
	for _, e := range []struct{ xmltv, disp string }{
		{"cctv5", "CCTV-5"},
		{"sixa", "频道 6 甲"},
		{"sixb", "频道 6 乙"},
	} {
		if _, err := a.st.UpsertEPGChannel(ctx, srcID, e.xmltv, e.disp, ""); err != nil {
			t.Fatalf("建 EPG 频道失败: %v", err)
		}
	}
	// 直播频道：名字里带 5 的应被映射；带 6 的有歧义，不该映射
	if _, err := a.st.CreateChannel(ctx, store.Channel{Number: 5, Name: "CCTV5体育", Enabled: true}); err != nil {
		t.Fatalf("建频道失败: %v", err)
	}
	if _, err := a.st.CreateChannel(ctx, store.Channel{Number: 6, Name: "频道6", Enabled: true}); err != nil {
		t.Fatalf("建频道失败: %v", err)
	}

	rep, err := a.st.MapByNumber(ctx)
	if err != nil {
		t.Fatalf("按台号匹配失败: %v", err)
	}
	if rep.Matched != 1 {
		t.Errorf("应只匹配 1 个（5 唯一），实际 %d", rep.Matched)
	}

	// 校验映射结果
	var epgID string
	if err := a.st.DB().QueryRowContext(ctx,
		`SELECT epg_id FROM channels WHERE name='CCTV5体育'`).Scan(&epgID); err != nil {
		t.Fatalf("查频道失败: %v", err)
	}
	if epgID != "cctv5" {
		t.Errorf("CCTV5体育 应映射到 cctv5，实际 %q", epgID)
	}
	if err := a.st.DB().QueryRowContext(ctx,
		`SELECT epg_id FROM channels WHERE name='频道6'`).Scan(&epgID); err != nil {
		t.Fatalf("查频道失败: %v", err)
	}
	if epgID != "" {
		t.Errorf("有歧义的「频道6」不该被映射，实际 %q", epgID)
	}
}

// TestBatchUnmap 覆盖 B3 的解除映射。
func TestBatchUnmap(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	id, err := a.st.CreateChannel(ctx, store.Channel{Number: 1, Name: "台", Enabled: true})
	if err != nil {
		t.Fatalf("建频道失败: %v", err)
	}
	if _, err := a.st.BatchSetChannelEpgID(ctx, []int64{id}, "cctv1"); err != nil {
		t.Fatalf("映射失败: %v", err)
	}
	rec := adminRequest(t, a, http.MethodPost, "/api/v1/epg/map/batch",
		map[string]any{"channel_ids": []int64{id}, "epg_id": ""})
	if rec.Code != http.StatusOK {
		t.Fatalf("解除映射失败：%d %s", rec.Code, rec.Body.String())
	}
	var got string
	if err := a.st.DB().QueryRowContext(ctx, `SELECT epg_id FROM channels WHERE id=?`, id).Scan(&got); err != nil {
		t.Fatalf("查频道失败: %v", err)
	}
	if got != "" {
		t.Errorf("解除映射后 epg_id 应为空，实际 %q", got)
	}
	_ = fmt.Sprint()
}
