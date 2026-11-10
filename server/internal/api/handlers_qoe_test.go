package api

import (
	"context"
	"encoding/json"
	"net/http"
	"testing"

	"github.com/litv/litv/server/internal/store"
)

// 本文件覆盖 A3（QoE 埋点）。

// statsRow 读回最后一条 play_events，供断言字段是否入库。
func statsRow(t *testing.T, a *API) store.PlayEvent {
	t.Helper()
	var (
		ev                    store.PlayEvent
		stall, watch          int
		path, engine, detail string
		startup               int
	)
	err := a.st.DB().QueryRowContext(context.Background(),
		`SELECT id,device_id,channel_id,event,startup_ms,detail,created_at,stall_ms,watch_ms,path,engine
		 FROM play_events ORDER BY id DESC LIMIT 1`).
		Scan(&ev.ID, &ev.DeviceID, &ev.ChannelID, &ev.Event, &startup, &detail, &ev.CreatedAt,
			&stall, &watch, &path, &engine)
	if err != nil {
		t.Fatalf("读 play_events 失败: %v", err)
	}
	ev.StartupMs, ev.Detail, ev.StallMs, ev.WatchMs, ev.Path, ev.Engine = startup, detail, stall, watch, path, engine
	return ev
}

// TestReportStatsPersistsQoEFields 覆盖新字段落库。
//
// 旧代码必然失败：表里根本没有 stall_ms / watch_ms / path / engine 四列。
func TestReportStatsPersistsQoEFields(t *testing.T) {
	a, _ := newTestAPI(t)
	tok := boundDeviceToken(t, a, "qoe-device")

	payload := map[string]any{
		"channel_id": 7,
		"event":      "stall",
		"startup_ms": 1200,
		"detail":     "缓冲不足",
		"stall_ms":   3200,
		"watch_ms":   60000,
		"path":       "remux",
		"engine":     "vlc",
	}
	rec := devicePOST(t, a, "/api/v1/tv/stats", tok, payload)
	if rec.Code != http.StatusOK {
		t.Fatalf("上报失败：%d %s", rec.Code, rec.Body.String())
	}
	got := statsRow(t, a)
	if got.StallMs != 3200 || got.WatchMs != 60000 {
		t.Errorf("时长字段没入库：stall=%d watch=%d", got.StallMs, got.WatchMs)
	}
	if got.Path != "remux" || got.Engine != "vlc" {
		t.Errorf("path/engine 没入库：%q %q", got.Path, got.Engine)
	}
}

// TestReportStatsAcceptsUnknownFieldsAndEngineValues 覆盖两条兼容性要求。
//
// 1) J-2：path/engine 是**自由文本**，服务端不校验枚举。
//    真实值是 exo|mpv|vlc|avs3；客户端以后加一个 "whatever" 也不该被拒。
// 2) 新 APK + 旧 NAS：多带一个未知字段不能让整条上报链路 400。
//    旧代码必然失败（全局 decode 开了 DisallowUnknownFields）。
func TestReportStatsAcceptsUnknownFieldsAndEngineValues(t *testing.T) {
	a, _ := newTestAPI(t)
	tok := boundDeviceToken(t, a, "qoe-device")

	cases := []map[string]any{
		{"channel_id": 1, "event": "start", "startup_ms": 800, "engine": "avs3", "path": "ts"},
		{"channel_id": 1, "event": "start", "startup_ms": 800, "engine": "whatever", "path": "unknown-path"},
		// 未知字段：模拟"新客户端 + 旧服务端"的多余字段
		{"channel_id": 1, "event": "start", "startup_ms": 800, "brand_new_field": "x"},
	}
	for i, p := range cases {
		rec := devicePOST(t, a, "/api/v1/tv/stats", tok, p)
		if rec.Code != http.StatusOK {
			t.Fatalf("第 %d 个 payload 被拒：%d %s", i+1, rec.Code, rec.Body.String())
		}
	}
	// 最后一个 case 落库的应该是那个未知字段被忽略、其余字段正常。
	got := statsRow(t, a)
	if got.Event != "start" {
		t.Errorf("event 没入库：%q", got.Event)
	}
}

// TestReportStatsOldClientStillOK 是老客户端兼容。
//
// 老 APK 只发 channel_id/event/startup_ms/detail，四个新字段一个都没有。
func TestReportStatsOldClientStillOK(t *testing.T) {
	a, _ := newTestAPI(t)
	tok := boundDeviceToken(t, a, "old-device")

	rec := devicePOST(t, a, "/api/v1/tv/stats", tok, map[string]any{
		"channel_id": 3,
		"event":      "start",
		"startup_ms": 900,
		"detail":     "",
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("老客户端上报被拒：%d %s", rec.Code, rec.Body.String())
	}
	got := statsRow(t, a)
	if got.StallMs != 0 || got.WatchMs != 0 || got.Path != "" || got.Engine != "" {
		t.Errorf("缺省字段应保持零值：%+v", got)
	}
}

// TestReportStatsClampsAndTruncates 覆盖 A3-2 的入参边界。
//
// 以前 detail 没有长度上限、数值没有范围校验：任一已绑定设备
// 都能反复写接近 1MB 的行，把 play_events 撑爆。
func TestReportStatsClampsAndTruncates(t *testing.T) {
	a, _ := newTestAPI(t)
	tok := boundDeviceToken(t, a, "big-device")

	long := make([]byte, 2000)
	for i := range long {
		long[i] = 'x'
	}
	rec := devicePOST(t, a, "/api/v1/tv/stats", tok, map[string]any{
		"channel_id": 5,
		"event":      "error",
		"detail":     string(long),
		"startup_ms": 999999999, // 越界
		"stall_ms":   -5,        // 负数
		"watch_ms":   999999999, // 越界
		"path":       string(long),
		"engine":     string(long),
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("越界值应被夹住而不是拒收：%d %s", rec.Code, rec.Body.String())
	}
	got := statsRow(t, a)
	if len(got.Detail) > maxStatDetailBytes {
		t.Errorf("detail 没有被截断：%d 字节", len(got.Detail))
	}
	if len(got.Path) > maxStatTagBytes || len(got.Engine) > maxStatTagBytes {
		t.Errorf("path/engine 没有被截断：%d/%d", len(got.Path), len(got.Engine))
	}
	if got.StartupMs > maxStartupMs {
		t.Errorf("startup_ms 越界未被夹住：%d", got.StartupMs)
	}
	if got.StallMs < 0 || got.WatchMs > maxDurationMs {
		t.Errorf("时长没有被夹到合法区间：stall=%d watch=%d", got.StallMs, got.WatchMs)
	}
}

// TestStatsOverviewRebufferRatio 覆盖 rebuffer ratio 的计算。
//
// 旧代码必然失败：StatsOverview 没有 RebufferRatio 字段。
//
// 手算：stall 共 3000ms，watch 共 60000ms → 0.05。
func TestStatsOverviewRebufferRatio(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	devID, err := a.st.CreateDevice(ctx, "dev-q", "hash-q", "电视", "android", "TV")
	if err != nil {
		t.Fatalf("建设备失败: %v", err)
	}
	// 一次观看 60s
	if err := a.st.AddPlayEvent(ctx, store.PlayEvent{
		DeviceID: devID, ChannelID: 1, Event: "stop", WatchMs: 60000, Path: "ts", Engine: "exo",
	}); err != nil {
		t.Fatalf("写 stop 失败: %v", err)
	}
	// 两次卡顿共 3s
	for _, ms := range []int{1000, 2000} {
		if err := a.st.AddPlayEvent(ctx, store.PlayEvent{
			DeviceID: devID, ChannelID: 1, Event: "stall", StallMs: ms, Path: "ts", Engine: "exo",
		}); err != nil {
			t.Fatalf("写 stall 失败: %v", err)
		}
	}
	// 一次起播（分组 p90 要有数据）
	if err := a.st.AddPlayEvent(ctx, store.PlayEvent{
		DeviceID: devID, ChannelID: 1, Event: "start", StartupMs: 1500, Path: "ts", Engine: "exo",
	}); err != nil {
		t.Fatalf("写 start 失败: %v", err)
	}

	ov, err := a.st.StatsOverview(ctx, 7)
	if err != nil {
		t.Fatalf("统计失败: %v", err)
	}
	if want := 3000.0 / 60000.0; ov.RebufferRatio != want {
		t.Errorf("rebuffer_ratio = %v，期望 %v", ov.RebufferRatio, want)
	}
	if len(ov.ByPath) != 1 || ov.ByPath[0].Key != "ts" {
		t.Errorf("by_path 分组不对：%+v", ov.ByPath)
	}
	if len(ov.ByEngine) != 1 || ov.ByEngine[0].Key != "exo" {
		t.Errorf("by_engine 分组不对：%+v", ov.ByEngine)
	}
}

// TestStatsOverviewNoDivideByZero 是 A3-1 的除零回归。
//
// 修复前必然失败（而且是整页坏掉，不是数字难看）：
// Σwatch_ms 为 0 时 Σstall_ms/Σwatch_ms = +Inf，而 encoding/json
// 对 +Inf 直接返回错误 → /stats/overview 整体 500。
//
// 触发条件在内网很现实：历史行 watch_ms 全是 0（老库/老客户端），
// 只要有一条新上报带了 stall_ms 就会踩到。
func TestStatsOverviewNoDivideByZero(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	devID, err := a.st.CreateDevice(ctx, "dev-z", "hash-z", "电视", "android", "TV")
	if err != nil {
		t.Fatalf("建设备失败: %v", err)
	}
	// 只有 stall，没有任何 watch_ms
	if err := a.st.AddPlayEvent(ctx, store.PlayEvent{
		DeviceID: devID, ChannelID: 1, Event: "stall", StallMs: 5000, Path: "ts",
	}); err != nil {
		t.Fatalf("写 stall 失败: %v", err)
	}

	ov, err := a.st.StatsOverview(ctx, 7)
	if err != nil {
		t.Fatalf("统计失败（除零会让整个 /stats/overview 500）: %v", err)
	}
	if ov.RebufferRatio != 0 {
		t.Errorf("分母为 0 时 rebuffer_ratio 应为 0，实际 %v", ov.RebufferRatio)
	}

	// 真正的判据：能被序列化成 JSON（+Inf/NaN 在这里会报错）。
	if _, jerr := json.Marshal(ov); jerr != nil {
		t.Fatalf("统计结果无法序列化（说明有 NaN/Inf）：%v", jerr)
	}

	// 走一遍真实 handler，确认是 200 而不是 500。
	rec := adminRequest(t, a, http.MethodGet, "/api/v1/stats/overview?days=7", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("/stats/overview 返回 %d：%s", rec.Code, rec.Body.String())
	}
}

// TestReportStatsRateLimited 覆盖按设备限流（A3-2）。
func TestReportStatsRateLimited(t *testing.T) {
	a, _ := newTestAPI(t)
	tok := boundDeviceToken(t, a, "flood-device")

	limited := false
	for i := 0; i < statsEventsPerMinute+5; i++ {
		rec := devicePOST(t, a, "/api/v1/tv/stats", tok, map[string]any{
			"channel_id": 1, "event": "stall", "stall_ms": 10,
		})
		if rec.Code == http.StatusTooManyRequests {
			limited = true
			break
		}
		if rec.Code != http.StatusOK {
			t.Fatalf("第 %d 次上报异常：%d %s", i+1, rec.Code, rec.Body.String())
		}
	}
	if !limited {
		t.Fatalf("连续上报 %d 次都没有被限流", statsEventsPerMinute+5)
	}
}
