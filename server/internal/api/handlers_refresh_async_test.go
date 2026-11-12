package api

import (
	"context"
	"encoding/json"
	"net/http"
	"sync/atomic"
	"testing"
	"time"

	"github.com/litv/litv/server/internal/store"
)

// 本文件覆盖 A2：刷新异步化 + 单飞 + 全局并发上限。
//
// 全部通过注入的 importSource 驱动，不连网络、不等真实抓取——
// 否则这些行为在 CI 上根本测不了。

// newRefreshTestAPI 造一个带源的 API，并把 importer 换成可控的假实现。
func newRefreshTestAPI(t *testing.T, fn sourceImporter) (*API, int64) {
	t.Helper()
	a, _ := newTestAPI(t)
	srcID, err := a.st.CreateSource(context.Background(), store.Source{
		Name: "测试源", Type: "m3u", URL: "https://example.invalid/list.m3u", Enabled: true,
	})
	if err != nil {
		t.Fatalf("建源失败: %v", err)
	}
	a.importSource = fn
	return a, srcID
}

// TestRefreshSourceReturns202 是契约变更的核心回归。
//
// 旧代码必然失败：刷先是同步跑完再 200 + {parsed, inserted}。
// 同步 handler 会让管理台一直转圈，用户自然连点——每次一个完整写事务，
// 于是 TV 端的小写操作全排在写锁上（这就是"刷新时反而卡死"）。
func TestRefreshSourceReturns202(t *testing.T) {
	a, srcID := newRefreshTestAPI(t, func(ctx context.Context, src store.Source) (store.ImportStats, error) {
		return store.ImportStats{Parsed: 3, Inserted: 1}, nil
	})

	rec := adminRequest(t, a, http.MethodPost, "/api/v1/sources/"+itoa(srcID)+"/refresh", nil)
	if rec.Code != http.StatusAccepted {
		t.Fatalf("期望 202，实际 %d %s", rec.Code, rec.Body.String())
	}

	var out struct {
		Code int `json:"code"`
		Data struct {
			Status string `json:"status"`
		} `json:"data"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("解析失败: %v body=%s", err, rec.Body.String())
	}
	if out.Code != 0 {
		t.Errorf("202 的信封 code 应为 0，实际 %d", out.Code)
	}
	if out.Data.Status != "running" {
		t.Errorf("data.status 应为 running，实际 %q", out.Data.Status)
	}
}

// TestRefreshSourceSingleFlight 确认同一个源并发刷新只跑一次。
//
// 没有单飞时，管理员连点 N 次就是 N 个并发的写事务，
// 全都抢同一把 SQLite 写锁——"卡死"的来源之一。
func TestRefreshSourceSingleFlight(t *testing.T) {
	var calls atomic.Int32
	release := make(chan struct{})

	a, srcID := newRefreshTestAPI(t, func(ctx context.Context, src store.Source) (store.ImportStats, error) {
		calls.Add(1)
		<-release // 卡住，模拟"正在刷"
		return store.ImportStats{Parsed: 1}, nil
	})

	// 第一次：启动
	rec1 := adminRequest(t, a, http.MethodPost, "/api/v1/sources/"+itoa(srcID)+"/refresh", nil)
	if rec1.Code != http.StatusAccepted {
		t.Fatalf("第一次刷新应 202，实际 %d %s", rec1.Code, rec1.Body.String())
	}
	// 等 worker 真的进到 importer 里，否则第二次可能在注册前就跑了
	deadline := 0
	for calls.Load() == 0 && deadline < 2000 {
		time.Sleep(time.Millisecond)
		deadline++
	}
	if calls.Load() == 0 {
		close(release)
		t.Fatal("importer 没有被调用")
	}

	// 第二次：同一个源，应该直接 202 且**不**再启动一个
	rec2 := adminRequest(t, a, http.MethodPost, "/api/v1/sources/"+itoa(srcID)+"/refresh", nil)
	if rec2.Code != http.StatusAccepted {
		t.Fatalf("同一源在刷时的第二次请求应 202（不排队不重跑），实际 %d %s",
			rec2.Code, rec2.Body.String())
	}
	if got := calls.Load(); got != 1 {
		close(release)
		t.Fatalf("importer 被调用了 %d 次，单飞没有生效", got)
	}

	close(release)
	// 等收尾，避免 goroutine 泄漏到下一个测试
	for a.isRefreshing(srcID) {
		time.Sleep(time.Millisecond)
	}
}

// TestRefreshSourceRejectsWhenGlobalBusy 覆盖全局并发上限。
//
// 为什么必须限：ImportChannels 是写事务，SQLite 写串行。
// 两个源同时导入只会互相等锁，把 TV 端的写一起堵住。
func TestRefreshSourceRejectsWhenGlobalBusy(t *testing.T) {
	release := make(chan struct{})
	a, src1 := newRefreshTestAPI(t, func(ctx context.Context, src store.Source) (store.ImportStats, error) {
		<-release
		return store.ImportStats{Parsed: 1}, nil
	})
	src2, err := a.st.CreateSource(context.Background(), store.Source{
		Name: "第二个源", Type: "m3u", URL: "https://example.invalid/b.m3u", Enabled: true,
	})
	if err != nil {
		t.Fatalf("建源失败: %v", err)
	}

	if rec := adminRequest(t, a, http.MethodPost, "/api/v1/sources/"+itoa(src1)+"/refresh", nil); rec.Code != http.StatusAccepted {
		close(release)
		t.Fatalf("第一个源应 202，实际 %d", rec.Code)
	}
	for !a.isRefreshing(src1) {
		time.Sleep(time.Millisecond)
	}

	// 第二个源：全局槽被占，必须明确告诉客户端等一会儿，而不是排队。
	rec := adminRequest(t, a, http.MethodPost, "/api/v1/sources/"+itoa(src2)+"/refresh", nil)
	close(release)
	if rec.Code != http.StatusTooManyRequests {
		t.Fatalf("全局忙时应 429，实际 %d %s", rec.Code, rec.Body.String())
	}
	if rec.Header().Get("Retry-After") == "" {
		t.Error("429 缺少 Retry-After")
	}
	for a.isRefreshing(src1) {
		time.Sleep(time.Millisecond)
	}
}

// TestListSourcesCarriesRefreshState 确认结果挂在行上（K-2）。
//
// 202 立刻返回时结果还不存在，所以"上游更新了吗"的答案必须来自
// GET /sources 每行的 refreshing + last_result，且刷新页面之后仍在。
func TestListSourcesCarriesRefreshState(t *testing.T) {
	a, srcID := newRefreshTestAPI(t, func(ctx context.Context, src store.Source) (store.ImportStats, error) {
		return store.ImportStats{Parsed: 10, Inserted: 2, Updated: 3, Missing: 1, Unchanged: 4}, nil
	})

	// 先让一次刷新跑完
	if rec := adminRequest(t, a, http.MethodPost, "/api/v1/sources/"+itoa(srcID)+"/refresh", nil); rec.Code != http.StatusAccepted {
		t.Fatalf("刷新应 202，实际 %d", rec.Code)
	}
	deadline := 0
	for a.isRefreshing(srcID) && deadline < 3000 {
		time.Sleep(time.Millisecond)
		deadline++
	}

	rec := adminRequest(t, a, http.MethodGet, "/api/v1/sources", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("列源失败：%d %s", rec.Code, rec.Body.String())
	}
	var out struct {
		Data []map[string]any `json:"data"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	if len(out.Data) != 1 {
		t.Fatalf("期望 1 条源，实际 %d", len(out.Data))
	}
	row := out.Data[0]

	// refreshing 是"正在刷新"的唯一表示（行级布尔）。
	if v, ok := row["refreshing"]; !ok {
		t.Error("缺少 refreshing 字段")
	} else if b, _ := v.(bool); b {
		t.Error("刷新已结束，refreshing 应为 false")
	}
	// status 仍是三值域，不许出现 refreshing。
	if st, _ := row["status"].(string); st == "refreshing" {
		t.Error("status 里不该出现 refreshing（会与行级布尔并存，前端只会命中一个）")
	}

	lr, ok := row["last_result"].(map[string]any)
	if !ok {
		t.Fatalf("缺少 last_result（刷新结果必须挂在行上）：%v", row)
	}
	// K-2：字段名是 missing，不是 disabled。
	if _, has := lr["missing"]; !has {
		t.Errorf("last_result 缺少 missing 键：%v", lr)
	}
	if _, has := lr["disabled"]; has {
		t.Errorf("last_result 不该有 disabled 键（与频道 enabled 撞词）：%v", lr)
	}
	for _, k := range []string{"parsed", "inserted", "updated", "unchanged"} {
		if _, has := lr[k]; !has {
			t.Errorf("last_result 缺少 %s 键：%v", k, lr)
		}
	}
	if got, _ := lr["inserted"].(float64); got != 2 {
		t.Errorf("inserted 应为 2，实际 %v", lr["inserted"])
	}

	// K-6：channel_count 是库内真数，parsed_count 是解析条数，二者必须分开。
	if _, has := row["parsed_count"]; !has {
		t.Error("缺少 parsed_count（解析条数）")
	}
	if _, has := row["channel_count"]; !has {
		t.Error("缺少 channel_count（库内真数，破坏性确认文案要用它）")
	}
}

// TestShouldAlign 用表驱动锁住 A1-2 的护栏阈值。
func TestShouldAlign(t *testing.T) {
	cases := []struct {
		parsed, last int
		want         bool
		why          string
	}{
		{50, 50, true, "持平"},
		{30, 50, true, "掉到 60%，仍在正常波动范围"},
		{25, 50, true, "恰好一半，边界允许"},
		{5, 100, false, "骤降到 5%，典型的残缺页"},
		{1, 10, false, "只剩 1 条，绝对下限挡住"},
		// 没有基线一律不对齐：老库升级后 parsed_count=0，
		// 此时"上游这次是不是残缺页"无从判断。一次刷新换不来
		// "把用户几百个台一次标成已移除"的风险。
		{50, 0, false, "首次刷新没有基线，刻意不对齐"},
		{3000, 0, false, "即使条数很多，没有基线也不对齐"},
		{3, 0, false, "首次就只解析到 3 条，也不对齐"},
		{8, 20, false, "低于绝对下限"},
	}
	for _, c := range cases {
		if got := shouldAlign(c.parsed, c.last); got != c.want {
			t.Errorf("shouldAlign(%d,%d)=%v，期望 %v（%s）", c.parsed, c.last, got, c.want, c.why)
		}
	}
}
