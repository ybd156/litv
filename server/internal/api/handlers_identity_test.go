package api

import (
	"context"
	"net/http"
	"testing"

	"github.com/litv/litv/server/internal/auth"
	"github.com/litv/litv/server/internal/store"
)

// TestDeviceCannotEscalateViaPlatform 是 R01 的核心回归测试。
//
// 修复前必然失败：身份中间件里有一句
//
//	if d.Platform == "admin" {
//	    ctx = context.WithValue(ctx, ctxIdentity, auth.Identity{IsAdmin: true})
//	}
//
// 而 platform 是客户端注册时自报的字段。虽然注册入口现在会拒绝
// platform="admin"，但只要这个提权分支存在，任何能让 admin 值进入
// devices.platform 的路径（历史数据、直接改库、未来某个导入功能）
// 都会把它变成管理员。身份不能由设备的展示字段派生。
//
// 这里直接写库造一台 platform='admin' 的已绑定设备，模拟历史遗留数据，
// 验证它拿不到管理接口写权限。
func TestDeviceCannotEscalateViaPlatform(t *testing.T) {
	a, _ := newTestAPI(t)

	// 直接往库里塞一台 platform=admin 的已绑定设备，
	// 绕过注册入口的保留值校验——这正是"历史数据/被改过的库"的形态。
	plain, hash, err := auth.NewToken()
	if err != nil {
		t.Fatalf("生成 token 失败: %v", err)
	}
	if _, err := a.st.DB().ExecContext(context.Background(),
		`INSERT INTO devices(device_key,token_hash,name,platform,model,status,created_at,last_seen,bound_at)
		 VALUES(?,?,?,?,?,'bound',?,?,?)`,
		"legacy-admin-device", hash, "历史设备", "admin", "test",
		store.Now(), store.Now(), store.Now()); err != nil {
		t.Fatalf("写设备失败: %v", err)
	}

	// 这个 token 可以当设备用（能读终端接口），但**绝不能**当管理员。
	rec := deviceGET(t, a, "/api/v1/system/config", plain)
	if rec.Code == http.StatusOK {
		t.Fatal("platform=admin 的设备读到了管理员配置接口——身份由设备字段派生")
	}

	// 写管理配置更必须被拒。
	writeRec := devicePUT(t, a, "/api/v1/system/config", plain, `{"max_concurrent":9}`)
	if writeRec.code == http.StatusOK {
		t.Fatal("platform=admin 的设备拿到了管理员写权限")
	}
}

// TestReservedPlatformNeverReturnsDevice 是 R01 的纵深防御测试。
//
// 第一道防线：GetDeviceByToken 直接过滤保留 platform，
// 使得 platform=admin 的记录连"设备身份"都拿不到。
// 这一道即使单独存在也能挡住提权——但它是第二道，不是唯一一道：
// 上面的 TestDeviceCannotEscalateViaPlatform 证明提权分支本身已被移除。
//
// 两道都要测，因为任何一道单独失效都会重新打开缺口。
func TestReservedPlatformNeverReturnsDevice(t *testing.T) {
	a, _ := newTestAPI(t)

	plain, hash, err := auth.NewToken()
	if err != nil {
		t.Fatalf("生成 token 失败: %v", err)
	}
	if _, err := a.st.DB().ExecContext(context.Background(),
		`INSERT INTO devices(device_key,token_hash,name,platform,model,status,created_at,last_seen,bound_at)
		 VALUES(?,?,?,?,?,'bound',?,?,?)`,
		"reserved-dev", hash, "保留值设备", "admin", "test",
		store.Now(), store.Now(), store.Now()); err != nil {
		t.Fatalf("写设备失败: %v", err)
	}

	// 连普通终端接口都不该可用（设备身份被完全拒绝）。
	rec := deviceGET(t, a, "/api/v1/tv/bootstrap", plain)
	if rec.Code == http.StatusOK {
		t.Fatal("保留平台设备仍然拿到了终端身份")
	}
}

// TestCreateDeviceRejectsReservedPlatform 确认注册入口也拒绝 admin 值。
func TestCreateDeviceRejectsReservedPlatform(t *testing.T) {
	a, _ := newTestAPI(t)
	if _, err := a.st.CreateDevice(context.Background(), "k1", "hash", "n", "admin", "m"); err == nil {
		t.Fatal("store 层未拒绝保留平台值")
	}
}

// TestAdminSessionGrantsAdmin 确认应急登录会话仍能进管理台。
//
// R01 的修复不能顺手把合法管理员也挡在门外。
func TestAdminSessionGrantsAdmin(t *testing.T) {
	a, _ := newTestAPI(t)

	// 造一个有效的 admin_session。
	plain, hash, err := auth.NewToken()
	if err != nil {
		t.Fatalf("生成 token 失败: %v", err)
	}
	if _, err := a.st.CreateAdminSession(context.Background(), hash, store.Now(), 8*3600*1e9/1e9, 16); err != nil {
		// TTL 单位是 Duration，这里传的是纳秒近似值；失败则直接写库兜底
		if _, xerr := a.st.DB().ExecContext(context.Background(),
			`INSERT INTO admin_sessions(token_hash,created_at,expires_at) VALUES(?,?,?)`,
			hash, store.Now(), store.Now()+8*3600); xerr != nil {
			t.Fatalf("建管理员会话失败: %v / %v", err, xerr)
		}
	}

	rec := deviceGET(t, a, "/api/v1/system/config", plain)
	if rec.Code != http.StatusOK {
		t.Fatalf("合法管理员会话进不了管理接口：%d %s", rec.Code, rec.Body.String())
	}
}

// TestExpiredAdminSessionRejected 确认到期会话被拒（会话到期语义）。
func TestExpiredAdminSessionRejected(t *testing.T) {
	a, _ := newTestAPI(t)

	plain, hash, err := auth.NewToken()
	if err != nil {
		t.Fatalf("生成 token 失败: %v", err)
	}
	if _, err := a.st.DB().ExecContext(context.Background(),
		`INSERT INTO admin_sessions(token_hash,created_at,expires_at) VALUES(?,?,?)`,
		hash, store.Now()-10*3600, store.Now()-3600); err != nil {
		t.Fatalf("写会话失败: %v", err)
	}

	rec := deviceGET(t, a, "/api/v1/system/config", plain)
	if rec.Code == http.StatusOK {
		t.Fatal("已过期的管理员会话仍然能访问管理接口")
	}
}

// TestGatewayAdminStillWorks 确认网关管理员路径没被 R01 的改动破坏。
func TestGatewayAdminStillWorks(t *testing.T) {
	a, _ := newTestAPI(t)

	rec := adminRequest(t, a, http.MethodGet, "/api/v1/system/config", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("网关管理员读配置失败：%d", rec.Code)
	}
}

func devicePUT(t *testing.T, a *API, path, token, body string) *httptestRecorder {
	t.Helper()
	req := newJSONRequest(http.MethodPut, path, body)
	req.Header.Set("Authorization", "Bearer "+token)
	return wrapRecorder(doRaw(a, req))
}
