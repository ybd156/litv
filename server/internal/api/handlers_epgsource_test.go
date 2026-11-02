package api

import (
	"context"
	"encoding/json"
	"net/http"
	"testing"

	"github.com/litv/litv/server/internal/store"
)

// TestListEPGSourcesReturnsUIFields 是 EPG 源列表的契约回归测试。
//
// 修复前必然失败：handler 直接把 store.EPGSource 原样返回，
// 字段名是 last_sync / last_error；而管理台面板读的是
// last_refresh_at / error / channel_count / program_count。
// 结果：刷新过也永远显示「还没拉过」，频道数与节目条数永远是「—」，
// 状态永远是「正常」（错误信息根本没被展示）。
func TestListEPGSourcesReturnsUIFields(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	srcID, err := a.st.CreateEPGSource(ctx, "测试源", "https://example.com/t.xml.gz")
	if err != nil {
		t.Fatalf("建 EPG 源失败: %v", err)
	}
	// 造一个 EPG 频道 + 两条节目，用于验证统计
	chID, err := a.st.UpsertEPGChannel(ctx, srcID, "cctv1", "CCTV-1", "")
	if err != nil {
		t.Fatalf("建 EPG 频道失败: %v", err)
	}
	if err := a.st.ReplacePrograms(ctx, chID, []store.EPGProgram{
		{StartAt: 100, EndAt: 200, Title: "新闻"},
		{StartAt: 200, EndAt: 300, Title: "电视剧"},
	}); err != nil {
		t.Fatalf("写节目失败: %v", err)
	}
	if _, err := a.st.MarkEPGAttempt(ctx, srcID, ""); err != nil {
		t.Fatalf("标记同步失败: %v", err)
	}

	rec := adminRequest(t, a, http.MethodGet, "/api/v1/epg/sources", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("列 EPG 源失败：%d %s", rec.Code, rec.Body.String())
	}

	var out struct {
		Data []map[string]any `json:"data"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	if len(out.Data) != 1 {
		t.Fatalf("期望 1 个源，实际 %d", len(out.Data))
	}
	row := out.Data[0]

	// 面板读取的四个字段必须都在
	for _, key := range []string{"last_refresh_at", "last_attempt_at", "channel_count", "program_count", "error"} {
		if _, ok := row[key]; !ok {
			t.Errorf("缺少管理台需要的字段 %q（面板会显示成「—」或「还没拉过」）", key)
		}
	}

	if got, _ := row["channel_count"].(float64); got != 1 {
		t.Errorf("channel_count 期望 1，实际 %v", row["channel_count"])
	}
	if got, _ := row["program_count"].(float64); got != 2 {
		t.Errorf("program_count 期望 2，实际 %v", row["program_count"])
	}
	if got, _ := row["last_refresh_at"].(float64); got <= 0 {
		t.Errorf("成功同步后 last_refresh_at 应大于 0，实际 %v", row["last_refresh_at"])
	}
}

// TestFailedSyncDoesNotOverwriteLastSuccess 确认失败不覆盖「上次成功」时间。
//
// 否则界面会在失败后照样显示成「刚刷新过」，用户以为一切正常。
func TestFailedSyncDoesNotOverwriteLastSuccess(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	srcID, err := a.st.CreateEPGSource(ctx, "源", "https://example.com/t.xml.gz")
	if err != nil {
		t.Fatalf("建源失败: %v", err)
	}
	// 先成功一次
	if _, err := a.st.MarkEPGAttempt(ctx, srcID, ""); err != nil {
		t.Fatalf("标记成功失败: %v", err)
	}
	before := epgSourceRow(t, a, srcID)
	successAt, _ := before["last_refresh_at"].(float64)
	if successAt <= 0 {
		t.Fatal("成功同步后 last_refresh_at 应大于 0")
	}

	// 再失败一次
	if _, err := a.st.MarkEPGAttempt(ctx, srcID, "拉取超时"); err != nil {
		t.Fatalf("标记失败失败: %v", err)
	}
	after := epgSourceRow(t, a, srcID)

	if got, _ := after["last_refresh_at"].(float64); got != successAt {
		t.Errorf("失败把「上次成功」时间改了：%v -> %v", successAt, got)
	}
	if errMsg, _ := after["error"].(string); errMsg != "拉取超时" {
		t.Errorf("错误信息没有被展示出来：%q", errMsg)
	}
	if got, _ := after["last_attempt_at"].(float64); got <= 0 {
		t.Error("失败也应记录 last_attempt_at")
	}
}

// epgSourceRow 取列表里的第一条（按 id 匹配）。
func epgSourceRow(t *testing.T, a *API, id int64) map[string]any {
	t.Helper()
	rec := adminRequest(t, a, http.MethodGet, "/api/v1/epg/sources", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("列 EPG 源失败：%d", rec.Code)
	}
	var out struct {
		Data []map[string]any `json:"data"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	for _, row := range out.Data {
		if got, _ := row["id"].(float64); int64(got) == id {
			return row
		}
	}
	t.Fatalf("没找到 id=%d 的源", id)
	return nil
}
