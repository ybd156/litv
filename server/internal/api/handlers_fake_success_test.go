package api

import (
	"net/http"
	"testing"
)

// TestDestructiveEndpointsRejectBadIDs 覆盖「0 行受影响仍回 200」这一类假成功。
//
// 旧实现：pathInt64 用 `n, _ := strconv.ParseInt(...)` 把错误吞掉返 0，
// 删除 / 审批类接口拿着 0 去执行，影响 0 行也回 {"deleted":true}。
// 前端据此提示「已删除」，用户刷新才发现没变——比报错更难查。
//
// 期望：路径参数非法 → 404；id 合法但记录不存在 → 404。
func TestDestructiveEndpointsRejectBadIDs(t *testing.T) {
	a, _ := newTestAPI(t)

	cases := []struct {
		name   string
		method string
		path   string
	}{
		{"线路 id 非数字", http.MethodDelete, "/api/v1/channels/1/streams/notanint"},
		{"线路 id 不存在", http.MethodDelete, "/api/v1/channels/1/streams/999999"},
		{"EPG 源不存在", http.MethodDelete, "/api/v1/epg/sources/999999"},
		{"待批设备不存在（批准）", http.MethodPost, "/api/v1/devices/999999/approve"},
		{"待批设备不存在（驳回）", http.MethodPost, "/api/v1/devices/999999/reject"},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			rec := adminRequest(t, a, c.method, c.path, nil)
			if rec.Code != http.StatusNotFound {
				t.Fatalf("%s %s = %d，期望 404（0 行受影响不能当成功）", c.method, c.path, rec.Code)
			}
		})
	}
}
