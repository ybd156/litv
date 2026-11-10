package api

import (
	"context"
	"encoding/json"
	"net/http"
	"testing"

	"github.com/litv/litv/server/internal/store"
)

// TestListSourcesExposesUIStatusFields 是本轮 A2 的回归。
//
// 修复前必然失败：listSources 直接 ok(w, items) 返回 store.Source，
// 字段名是 last_fetch_at / last_error，而管理台 SourcesView 读的是
// error / status。于是"失败原因"永远不显示、概览页"失效源"计数恒为 0——
// 用户看到的是"一切正常"，实际上所有源都拉不到。
//
// 断言锁定**四个键**（Lead 裁决的契约）：
//
//	last_fetch_at（整数秒，保留）、last_error（保留）、error、status
func TestListSourcesExposesUIStatusFields(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	id, err := a.st.CreateSource(ctx, store.Source{
		Name: "会失败的源",
		Type: "m3u",
		URL:  "https://example.invalid/list.m3u",
	})
	if err != nil {
		t.Fatalf("建源失败: %v", err)
	}
	// 记一次失败（last_error 非空）。
	if err := a.st.MarkSourceFetched(ctx, id, 0, "拉取失败: 连接超时"); err != nil {
		t.Fatalf("写失败状态失败: %v", err)
	}

	rec := adminRequest(t, a, http.MethodGet, "/api/v1/sources", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("列源失败：%d %s", rec.Code, rec.Body.String())
	}

	var out struct {
		Code int              `json:"code"`
		Data []map[string]any `json:"data"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("解析失败: %v body=%s", err, rec.Body.String())
	}
	if len(out.Data) != 1 {
		t.Fatalf("期望 1 条源，实际 %d", len(out.Data))
	}
	row := out.Data[0]

	// error：管理台实际读取的字段。
	if got, _ := row["error"].(string); got != "拉取失败: 连接超时" {
		t.Errorf("error 字段不对：%v", row["error"])
	}
	// status：取值域必须是 ok|error|never。
	if got, _ := row["status"].(string); got != "error" {
		t.Errorf("status 应为 error，实际 %v", row["status"])
	}
	// 旧键必须保留（外部脚本可能在用）。
	if _, ok := row["last_error"]; !ok {
		t.Error("last_error 键被删掉了（外部脚本依赖它）")
	}
	// last_fetch_at 必须是数字（整数秒），不能变成字符串或毫秒。
	raw, ok := row["last_fetch_at"]
	if !ok {
		t.Fatal("last_fetch_at 键不存在")
	}
	if _, isNum := raw.(float64); !isNum {
		t.Errorf("last_fetch_at 应为数字（Unix 秒），实际 %T: %v", raw, raw)
	}
}

// TestListSourcesStatusNever 覆盖"从未拉取过"这一档。
func TestListSourcesStatusNever(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	if _, err := a.st.CreateSource(ctx, store.Source{
		Name: "新加的源", Type: "m3u", URL: "https://example.invalid/a.m3u",
	}); err != nil {
		t.Fatalf("建源失败: %v", err)
	}

	rec := adminRequest(t, a, http.MethodGet, "/api/v1/sources", nil)
	var out struct {
		Data []map[string]any `json:"data"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	if len(out.Data) != 1 {
		t.Fatalf("期望 1 条源，实际 %d", len(out.Data))
	}
	if got, _ := out.Data[0]["status"].(string); got != "never" {
		t.Errorf("从未拉取的源 status 应为 never，实际 %v", out.Data[0]["status"])
	}
	if got, _ := out.Data[0]["error"].(string); got != "" {
		t.Errorf("没有错误时 error 应为空串，实际 %q", got)
	}
}
