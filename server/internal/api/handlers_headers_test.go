package api

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"github.com/litv/litv/server/internal/auth"
	"github.com/litv/litv/server/internal/store"
)

// TestSanitizedHeadersRemovesIdentityAndHopByHop 是 R09 的安全回归测试。
//
// headers_json 来自用户导入的源文件，是不可信输入。若不清理：
//   - 源站可能收到我们自己的设备 Bearer token 或管理员身份头（凭据泄露）；
//   - CRLF 注入可以让攻击者往请求里塞任意头。
func TestSanitizedHeadersRemovesIdentityAndHopByHop(t *testing.T) {
	raw := map[string]string{
		"User-Agent":     "TestAgent/1.0",
		"Referer":        "http://example.com/",
		"Authorization":  "Bearer device-token-should-not-leak",
		"Cookie":         "session=secret",
		"X-Trim-Userid":  "42",
		"X-Trim-Isadmin": "true",
		"Host":           "evil.example",
		"Connection":     "keep-alive",
		"Content-Length": "999",
		"X-Litv-Debug":   "1",
		"Injected":       "a\r\nX-Evil: yes",
	}

	got := sanitizedHeaders(raw)

	for _, banned := range []string{
		"Authorization", "Cookie", "X-Trim-Userid", "X-Trim-Isadmin",
		"Host", "Connection", "Content-Length", "X-Litv-Debug", "Injected",
	} {
		if _, ok := got[banned]; ok {
			t.Errorf("敏感/危险头未被剔除：%s", banned)
		}
	}
	// 但正常业务头必须保留，否则会误伤合法源站校验。
	if got["User-Agent"] != "TestAgent/1.0" {
		t.Errorf("User-Agent 被误删：%q", got["User-Agent"])
	}
	if got["Referer"] != "http://example.com/" {
		t.Errorf("Referer 被误删：%q", got["Referer"])
	}
}

// TestSanitizedHeadersRejectsCRLF 单独确认换行注入（CRLF injection）被拦。
func TestSanitizedHeadersRejectsCRLF(t *testing.T) {
	got := sanitizedHeaders(map[string]string{
		"X-Bad": "v\r\nX-Admin: 1",
		"X-OK":  "fine",
	})
	if _, ok := got["X-Bad"]; ok {
		t.Fatal("含 CRLF 的头值未被拒绝——允许请求头注入")
	}
	if got["X-OK"] != "fine" {
		t.Error("正常头被误删")
	}
}

// TestStreamHeadersIncludeReferer 确认完整请求头（不只是 UA）会被下发。
func TestStreamHeadersIncludeReferer(t *testing.T) {
	a, _ := newTestAPI(t)

	st := store.Stream{
		ID:          1,
		HeadersJSON: `{"User-Agent":"UA-X","Referer":"http://ref.example/"}`,
	}
	h := a.streamHeaders(1, &st)

	if h["Referer"] != "http://ref.example/" {
		t.Errorf("Referer 未下发：%v", h)
	}
	if h["User-Agent"] != "UA-X" {
		t.Errorf("User-Agent 未下发：%v", h)
	}
}

// TestStreamHeadersFallBackToGlobalUA 确认线路没配 UA 时用全局 UA，不会出现空 UA。
func TestStreamHeadersFallBackToGlobalUA(t *testing.T) {
	a, _ := newTestAPI(t)

	h := a.streamHeaders(1, &store.Stream{ID: 1, HeadersJSON: "{}"})
	if h["User-Agent"] == "" {
		t.Fatal("没有回落到全局 UA，源站校验 UA 时会 403")
	}
}

// TestPlayResponseCarriesHeaders 端到端：/tv/play 的响应里必须带 headers。
//
// 这是 R09 的契约测试。修复前 relay 分支只给 ua、不给 headers，
// 客户端 DTO 也没有这个字段，于是"要求 Referer 的源"在客户端直连时必然 403。
func TestPlayResponseCarriesHeaders(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	// 造一个频道 + 一条带 Referer 的线路。
	chID, err := a.st.CreateChannel(ctx, store.Channel{
		Number: 1, Name: "测试台", Group: "测试", Enabled: true,
	})
	if err != nil {
		t.Fatalf("建频道失败: %v", err)
	}
	streamID, err := a.st.AddStream(ctx, chID, "http://upstream.example/live.ts", "hd", 1)
	if err != nil {
		t.Fatalf("加线路失败: %v", err)
	}
	// AddStream 不接 headers，直接写库（模拟导入阶段写入的源配置）。
	if _, err := a.st.DB().ExecContext(ctx,
		`UPDATE streams SET headers_json=? WHERE id=?`,
		`{"User-Agent":"UA-Needed","Referer":"http://ref.example/"}`, streamID); err != nil {
		t.Fatalf("写 headers_json 失败: %v", err)
	}

	tok := boundDeviceToken(t, a, "test-device")
	rec := deviceGET(t, a, fmt.Sprintf("/api/v1/tv/play/%d", chID), tok)
	if rec.Code != http.StatusOK {
		t.Fatalf("play 失败：%d %s", rec.Code, rec.Body.String())
	}

	var out struct {
		Data struct {
			Headers map[string]string `json:"headers"`
			UA      string            `json:"ua"`
			Mode    string            `json:"mode"`
		} `json:"data"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("解析失败: %v body=%s", err, rec.Body.String())
	}
	if out.Data.Headers == nil {
		t.Fatalf("relay 响应没有 headers 字段：%s", rec.Body.String())
	}
	if out.Data.Headers["Referer"] != "http://ref.example/" {
		t.Errorf("headers 里没有 Referer：%v", out.Data.Headers)
	}
	// 身份头绝不能出现在给客户端的 headers 里。
	for k := range out.Data.Headers {
		lk := strings.ToLower(k)
		if strings.HasPrefix(lk, "authorization") || strings.HasPrefix(lk, "cookie") {
			t.Errorf("身份头被泄露给客户端：%s", k)
		}
	}
}

// TestSanitizeIsConcurrencySafe 确认清洗函数无共享状态、可并发调用。
func TestSanitizeIsConcurrencySafe(t *testing.T) {
	raw := map[string]string{"User-Agent": "a", "Referer": "b"}
	var wg sync.WaitGroup
	for i := 0; i < 50; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			got := sanitizedHeaders(raw)
			if got["User-Agent"] != "a" {
				t.Error("并发清洗结果不一致")
			}
		}()
	}
	wg.Wait()
}

// ---------- 辅助 ----------

// boundDeviceToken 造一台已绑定设备，返回明文 token。
func boundDeviceToken(t *testing.T, a *API, key string) string {
	t.Helper()
	plain, hash, err := auth.NewToken()
	if err != nil {
		t.Fatalf("生成 token 失败: %v", err)
	}
	if _, err := a.st.DB().ExecContext(context.Background(),
		`INSERT INTO devices(device_key,token_hash,name,platform,model,status,created_at,last_seen,bound_at)
		 VALUES(?,?,?,?,?,'bound',?,?,?)`,
		key, hash, "测试设备", "android", "test", store.Now(), store.Now(), store.Now()); err != nil {
		t.Fatalf("建设备失败: %v", err)
	}
	return plain
}

func deviceGET(t *testing.T, a *API, path, token string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, path, nil)
	req.Header.Set("Authorization", "Bearer "+token)
	rec := httptest.NewRecorder()
	a.Handler(false).ServeHTTP(rec, req)
	return rec
}

// doRaw 用 TCP 入口直接执行一个已构造好的请求（签名 URL 不走设备身份）。
func doRaw(a *API, req *http.Request) *httptest.ResponseRecorder {
	rec := httptest.NewRecorder()
	a.Handler(false).ServeHTTP(rec, req)
	return rec
}

// newJSONRequest 构造一个带 JSON body 的请求。
func newJSONRequest(method, path, body string) *http.Request {
	var r io.Reader
	if body != "" {
		r = strings.NewReader(body)
	}
	req := httptest.NewRequest(method, path, r)
	req.Header.Set("Content-Type", "application/json")
	return req
}

// wrapRecorder 把 httptest 响应包成测试里用的最小快照。
func wrapRecorder(rec *httptest.ResponseRecorder) *httptestRecorder {
	return &httptestRecorder{code: rec.Code, body: rec.Body.Bytes()}
}
