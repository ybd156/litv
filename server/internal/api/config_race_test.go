package api

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"

	"github.com/litv/litv/server/internal/auth"
)

// noFatalRequest 与 adminRequest 等价，但**不碰 *testing.T**：
// 并发测试里从 goroutine 调 t.Fatalf 是禁止的（会 Goexit 掉那个 goroutine）。
func noFatalRequest(a *API, method, path string, body []byte) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, path, bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set(auth.HeaderUserID, "42")
	req.Header.Set(auth.HeaderUsername, "admin")
	req.Header.Set(auth.HeaderIsAdmin, "true")
	rec := httptest.NewRecorder()
	a.Handler(true).ServeHTTP(rec, req)
	return rec
}

// TestConcurrentConfigUpdateAndRead 覆盖配置热更新的数据竞争。
//
// 以前 `a.cfg` 是裸指针：保存设置时 `a.cfg = next` 直接换指针，
// 而全包 70 处读点完全不加锁。管理台点保存的同时任何请求在播，
// 就是一次读写竞争——普通测试发现不了，只有 `-race` 会报。
//
// 改成 atomic.Pointer 后，这个测试在 `-race` 下必须干净。
// 注意：本机没有 gcc，`-race` 跑不了，竞态检测由 CI 的
// `go test -race ./...`（build-check 的 server job）负责；这里至少验证
// 「并发读写不会死锁、也不会返回 5xx」。
func TestConcurrentConfigUpdateAndRead(t *testing.T) {
	a, _ := newTestAPI(t)

	const workers = 8
	const rounds = 10
	bad := make(chan string, workers*rounds)

	var wg sync.WaitGroup
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			for j := 0; j < rounds; j++ {
				if i%2 == 0 {
					rec := noFatalRequest(a, http.MethodPut, "/api/v1/system/config",
						[]byte(`{"log_level":"info"}`))
					if rec.Code != http.StatusOK {
						bad <- "PUT /system/config = " + rec.Result().Status
					}
				} else {
					rec := noFatalRequest(a, http.MethodGet, "/api/v1/system/config", nil)
					if rec.Code != http.StatusOK {
						bad <- "GET /system/config = " + rec.Result().Status
					}
				}
			}
		}(i)
	}
	wg.Wait()
	close(bad)

	for msg := range bad {
		t.Error(msg)
	}
}
