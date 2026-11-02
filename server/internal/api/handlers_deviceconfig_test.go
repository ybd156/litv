package api

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"testing"

	"github.com/litv/litv/server/internal/auth"
	"github.com/litv/litv/server/internal/store"
)

// TestConfigVersionIncrementsOnChange 是 R17 的核心回归测试。
//
// 修复前必然失败：heartbeat 返回的 config_version 是 dev.BoundAt（绑定时间），
// 而 SetDeviceConfig 只更新 config_json，不动 bound_at。
// 结果管理台改了设备参数，终端心跳拿到的版本号永远不变，
// 永远不知道该重新拉配置——改了等于没改。
func TestConfigVersionIncrementsOnChange(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	devID, err := a.st.DB().ExecContext(ctx,
		`INSERT INTO devices(device_key,token_hash,name,platform,model,status,created_at,last_seen,bound_at)
		 VALUES(?,?,?,?,?,'bound',?,?,?)`,
		"ver-dev", "hash-ver", "版本设备", "android", "test",
		store.Now(), store.Now(), store.Now())
	if err != nil {
		t.Fatalf("建设备失败: %v", err)
	}
	id, _ := devID.LastInsertId()

	// 初始版本
	before := deviceConfigVersion(t, a, id)

	// 改配置
	if rec := adminRequest(t, a, http.MethodPut,
		fmt.Sprintf("/api/v1/devices/%d/config", id),
		map[string]any{"buffer_ms": 5000}); rec.Code != http.StatusOK {
		t.Fatalf("保存设备配置失败：%d %s", rec.Code, rec.Body.String())
	}

	after := deviceConfigVersion(t, a, id)
	if after <= before {
		t.Fatalf("改配置后版本没有递增：%d -> %d", before, after)
	}
}

// TestHeartbeatReturnsRealConfigVersion 确认心跳返回的是 config_version 而非 bound_at。
func TestHeartbeatReturnsRealConfigVersion(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	plain, hash, err := auth.NewToken()
	if err != nil {
		t.Fatalf("生成 token 失败: %v", err)
	}
	if _, err := a.st.DB().ExecContext(ctx,
		`INSERT INTO devices(device_key,token_hash,name,platform,model,status,created_at,last_seen,bound_at)
		 VALUES(?,?,?,?,?,'bound',?,?,?)`,
		"hb-dev", hash, "心跳设备", "android", "test",
		store.Now(), store.Now(), 111111); err != nil { // bound_at 故意设成固定旧值
		t.Fatalf("建设备失败: %v", err)
	}

	req := newJSONRequest(http.MethodPost, "/api/v1/tv/heartbeat", `{"state":"playing"}`)
	req.Header.Set("Authorization", "Bearer "+plain)
	rec := doRaw(a, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("心跳失败：%d %s", rec.Code, rec.Body.String())
	}

	var out struct {
		Data struct {
			ConfigVersion int64 `json:"config_version"`
		} `json:"data"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	// bound_at 是 111111，若 return 的等于它说明还在用旧字段
	if out.Data.ConfigVersion == 111111 {
		t.Fatal("心跳仍返回 bound_at 当 config_version——终端永远发现不了配置变化")
	}
}

// TestConcurrentConfigUpdatesIncrementMonotonically 确认并发保存不会丢版本。
//
// 版本号必须在同一条 UPDATE 里 +1；先读再写会让两个并发保存各自 +1 后
// 版本只涨 1，终端可能错过一次变更。
func TestConcurrentConfigUpdatesIncrementMonotonically(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	res, err := a.st.DB().ExecContext(ctx,
		`INSERT INTO devices(device_key,token_hash,name,platform,model,status,created_at,last_seen,bound_at)
		 VALUES(?,?,?,?,?,'bound',?,?,?)`,
		"cc-dev", "hash-cc", "并发设备", "android", "test",
		store.Now(), store.Now(), store.Now())
	if err != nil {
		t.Fatalf("建设备失败: %v", err)
	}
	id, _ := res.LastInsertId()

	const n = 20
	done := make(chan error, n)
	for i := 0; i < n; i++ {
		go func(i int) {
			_, _, err := a.st.SetDeviceConfigVersioned(ctx, id, fmt.Sprintf(`{"n":%d}`, i))
			done <- err
		}(i)
	}
	for i := 0; i < n; i++ {
		if err := <-done; err != nil {
			t.Fatalf("并发保存失败: %v", err)
		}
	}

	final := deviceConfigVersion(t, a, id)
	if final < n {
		t.Fatalf("并发 %d 次保存后版本只有 %d——存在丢失更新", n, final)
	}
}

// TestSetDeviceConfigUnknownDevice 确认对不存在的设备明确报错，而不是静默成功。
func TestSetDeviceConfigUnknownDevice(t *testing.T) {
	a, _ := newTestAPI(t)

	_, _, err := a.st.SetDeviceConfigVersioned(context.Background(), 99999, `{}`)
	if err == nil {
		t.Fatal("对不存在的设备保存配置应当报错")
	}
}

// TestDeviceConfigSavedValueIsReadable 确认配置内容真的存进去了。
func TestDeviceConfigSavedValueIsReadable(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	res, _ := a.st.DB().ExecContext(ctx,
		`INSERT INTO devices(device_key,token_hash,name,platform,model,status,created_at,last_seen,bound_at)
		 VALUES(?,?,?,?,?,'bound',?,?,?)`,
		"cfg-dev", "hash-cfg", "配置设备", "android", "test",
		store.Now(), store.Now(), store.Now())
	id, _ := res.LastInsertId()

	if _, _, err := a.st.SetDeviceConfigVersioned(ctx, id, `{"buffer_ms":7000}`); err != nil {
		t.Fatalf("保存失败: %v", err)
	}
	var got string
	if err := a.st.DB().QueryRowContext(ctx, `SELECT config_json FROM devices WHERE id=?`, id).Scan(&got); err != nil {
		t.Fatalf("读配置失败: %v", err)
	}
	if got != `{"buffer_ms":7000}` {
		t.Fatalf("配置内容不符：%q", got)
	}
}

// ---------- 辅助 ----------

func deviceConfigVersion(t *testing.T, a *API, id int64) int64 {
	t.Helper()
	var v int64
	if err := a.st.DB().QueryRowContext(context.Background(),
		`SELECT config_version FROM devices WHERE id=?`, id).Scan(&v); err != nil {
		t.Fatalf("读 config_version 失败: %v", err)
	}
	return v
}
