package api

import (
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"testing"

	"github.com/litv/litv/server/internal/store"
)

// itoa 是本文件用的 id 转字符串小工具（避免各处 fmt.Sprintf 噪音）。
func itoa(v int64) string { return strconv.FormatInt(v, 10) }

// chanRow 读回一个频道，供各处断言用。
func chanRow(t *testing.T, a *API, id int64) store.Channel {
	t.Helper()
	ch, err := a.st.GetChannel(context.Background(), id)
	if err != nil {
		t.Fatalf("读回频道失败: %v", err)
	}
	return *ch
}

// seedFullChannel 造一个"每个可编辑字段都非零"的频道，并且带上 epg_id。
//
// 全字段非零是关键：任何"把缺失字段写成零值"的 bug 都会在断言里露出来。
func seedFullChannel(t *testing.T, a *API) int64 {
	t.Helper()
	ctx := context.Background()
	id, err := a.st.CreateChannel(ctx, store.Channel{
		Number:  42,
		Name:    "CCTV-1 综合",
		Group:   "央视",
		Logo:    "https://example.invalid/logo.png",
		TvgID:   "cctv1.example",
		Enabled: true,
	})
	if err != nil {
		t.Fatalf("建频道失败: %v", err)
	}
	// epg_id 只能走 SetChannelEpgID（这是唯一入口）。
	if err := a.st.SetChannelEpgID(ctx, id, "cctv1.example.epg"); err != nil {
		t.Fatalf("设置 EPG 映射失败: %v", err)
	}
	return id
}

// TestUpdateChannelPartialKeepsFields 是本轮 A1 的核心回归。
//
// 修复前必然失败（而且是静默数据损坏）：
//   - handler 的请求体是非指针字段，缺失即零值；
//   - store.UpdateChannel 又是全量覆盖，SET 里还有 epg_id=?。
//
// 所以管理台只发 {"enabled":false}（频道列表里点一下"停用"）时，
// 名称 / 分组 / 台标 / tvg_id / 台号 全被清空，EPG 映射也被抹成空串。
// 用户看到的是"点了个开关，台名没了，节目单也没了"。
func TestUpdateChannelPartialKeepsFields(t *testing.T) {
	a, _ := newTestAPI(t)
	id := seedFullChannel(t, a)
	before := chanRow(t, a, id)

	rec := adminRequest(t, a, http.MethodPut, "/api/v1/channels/"+itoa(id), map[string]any{
		"enabled": false,
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("部分更新失败：%d %s", rec.Code, rec.Body.String())
	}

	after := chanRow(t, a, id)
	if after.Enabled {
		t.Error("enabled 没有被更新成 false")
	}
	if after.Name != before.Name {
		t.Errorf("名称被清空/改坏了：%q -> %q", before.Name, after.Name)
	}
	if after.Group != before.Group {
		t.Errorf("分组被清空/改坏了：%q -> %q", before.Group, after.Group)
	}
	if after.Logo != before.Logo {
		t.Errorf("台标被清空：%q -> %q", before.Logo, after.Logo)
	}
	if after.TvgID != before.TvgID {
		t.Errorf("tvg_id 被清空：%q -> %q", before.TvgID, after.TvgID)
	}
	if after.Number != before.Number {
		t.Errorf("台号被清空：%d -> %d", before.Number, after.Number)
	}
	// 这一条单独列出来：epg_id 必须完全不受本接口影响。
	// 修复前 store.UpdateChannel 的 SET 里有 epg_id=?，而 handler 从不设置它，
	// 于是每次成功 PUT 都把映射抹掉。
	if after.EpgID != "cctv1.example.epg" {
		t.Errorf("EPG 映射被本接口冲掉了：期望 %q，实际 %q",
			"cctv1.example.epg", after.EpgID)
	}
}

// TestUpdateChannelClearsGroupExplicitly 确认「显式清空」仍然生效。
//
// 反向 bug 的典型写法是 NULLIF(?,'') 或者 `if v != "" { set }`——
// 那样用户永远清不空分组。
func TestUpdateChannelClearsGroupExplicitly(t *testing.T) {
	a, _ := newTestAPI(t)
	id := seedFullChannel(t, a)

	rec := adminRequest(t, a, http.MethodPut, "/api/v1/channels/"+itoa(id), map[string]any{
		"group": "",
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("清空分组失败：%d %s", rec.Code, rec.Body.String())
	}
	if got := chanRow(t, a, id).Group; got != "" {
		t.Errorf("分组没有被清空，实际 %q", got)
	}
}

// TestUpdateChannelSetsNumberZero 确认台号 0 是合法输入。
//
// 反向 bug 的典型写法是 `if *b.Number > 0 { set }`，那会把"设为 0"吞掉。
func TestUpdateChannelSetsNumberZero(t *testing.T) {
	a, _ := newTestAPI(t)
	id := seedFullChannel(t, a)

	rec := adminRequest(t, a, http.MethodPut, "/api/v1/channels/"+itoa(id), map[string]any{
		"number": 0,
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("设置台号 0 失败：%d %s", rec.Code, rec.Body.String())
	}
	if got := chanRow(t, a, id).Number; got != 0 {
		t.Errorf("台号没有变成 0，实际 %d", got)
	}
}

// TestUpdateChannelRejectsUnknownField 保护 DisallowUnknownFields 不被顺手关掉。
//
// 特别注意 epg_id：它现在是**未知字段**（EPG 映射只走 PUT /channels/{id}/epg）。
// 如果有人把它加回请求体，这条测试会红——那是提醒，不是障碍：
// 静默忽略未知字段会让"前端以为改了、后端没改"这种 bug 一直藏着。
func TestUpdateChannelRejectsUnknownField(t *testing.T) {
	a, _ := newTestAPI(t)
	id := seedFullChannel(t, a)

	cases := []map[string]any{
		{"bogus": 1},
		{"epg_id": "should-not-be-here"},
	}
	for _, payload := range cases {
		rec := adminRequest(t, a, http.MethodPut, "/api/v1/channels/"+itoa(id), payload)
		if rec.Code != http.StatusBadRequest {
			t.Errorf("未知字段 %v 未被拒绝：%d %s", payload, rec.Code, rec.Body.String())
		}
	}
	// 被拒绝的请求不能改库。
	if got := chanRow(t, a, id).Group; got != "央视" {
		t.Errorf("被拒绝的请求改动了数据：group=%q", got)
	}
}

// TestUpdateChannelNotFound 确认不存在的 id 是 404 而不是静默成功。
func TestUpdateChannelNotFound(t *testing.T) {
	a, _ := newTestAPI(t)
	rec := adminRequest(t, a, http.MethodPut, "/api/v1/channels/999999", map[string]any{
		"enabled": false,
	})
	if rec.Code != http.StatusNotFound {
		t.Errorf("不存在的频道应返回 404，实际 %d %s", rec.Code, rec.Body.String())
	}
}

// TestMapChannelEPG 覆盖专用映射端点：只改 epg_id，其它字段不动。
func TestMapChannelEPG(t *testing.T) {
	a, _ := newTestAPI(t)
	id := seedFullChannel(t, a)

	rec := adminRequest(t, a, http.MethodPut, "/api/v1/channels/"+itoa(id)+"/epg",
		map[string]any{"epg_id": "new.epg.id"})
	if rec.Code != http.StatusOK {
		t.Fatalf("映射失败：%d %s", rec.Code, rec.Body.String())
	}
	after := chanRow(t, a, id)
	if after.EpgID != "new.epg.id" {
		t.Errorf("epg_id 未更新，实际 %q", after.EpgID)
	}
	if after.Name != "CCTV-1 综合" || after.Group != "央视" || after.Number != 42 {
		t.Errorf("映射接口动到了别的字段：%+v", after)
	}
}

// TestMapChannelEPGNotFound 确认 0 行受影响会变成 404。
//
// 修复前 SetChannelEpgID 直接返回 nil，于是 PUT /channels/999999/epg
// 会回 200 {"mapped":true}，调用方以为映射成功，实际什么都没写。
func TestMapChannelEPGNotFound(t *testing.T) {
	a, _ := newTestAPI(t)
	rec := adminRequest(t, a, http.MethodPut, "/api/v1/channels/999999/epg",
		map[string]any{"epg_id": "x"})
	if rec.Code != http.StatusNotFound {
		t.Fatalf("不存在的频道应返回 404，实际 %d %s", rec.Code, rec.Body.String())
	}
	var out struct {
		Code int `json:"code"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	if out.Code == 0 {
		t.Error("信封 code 不应为 0（这是失败响应）")
	}
}
