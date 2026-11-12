package api

import (
	"context"
	"testing"

	"github.com/litv/litv/server/internal/auth"
	"github.com/litv/litv/server/internal/store"
)

// TestDeviceSessionExpiresWhenUnused 是 R15 的核心回归测试。
//
// 修复前必然失败：token_hours 只是配置项和管理台显示字段，
// GetDeviceByToken 只查 status='bound'，设备 token 永不过期。
// 一台丢失或已替换的设备，只要 token 没被显式吊销就一直有效。
func TestDeviceSessionExpiresWhenUnused(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	_, hash, err := auth.NewToken()
	if err != nil {
		t.Fatalf("生成 token 失败: %v", err)
	}
	// 造一台"很久没动过"的设备：bound_at 是 2 年前，session_expires_at 也是 2 年前
	longAgo := store.Now() - 2*365*24*3600
	if _, err := a.st.DB().ExecContext(ctx,
		`INSERT INTO devices(device_key,token_hash,name,platform,model,status,config_json,
		  config_version,bound_at,last_seen,created_at,session_expires_at)
		 VALUES(?,?,?,?,?,'bound','{}',0,?,?,?,?)`,
		"stale-dev", hash, "陈旧设备", "android", "test",
		longAgo, longAgo, longAgo, longAgo); err != nil {
		t.Fatalf("建设备失败: %v", err)
	}

	// token_hours 配置生效时，这台设备必须被拒
	if _, err := a.st.GetDeviceByToken(ctx, hash, 24*365); err == nil {
		t.Fatal("两年前绑定且长期未用的设备仍然有效——token_hours 没有生效")
	}
}

// TestActiveDeviceSessionRenews 确认正常使用的设备不会被强制重绑。
//
// 这是 R15 的**关键设计约束**：IPTV 场景下用户几乎每天开电视，
// 必须滑动续期，否则每天都要求重新绑定，体验灾难。
func TestActiveDeviceSessionRenews(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	plain, hash, err := auth.NewToken()
	if err != nil {
		t.Fatalf("生成 token 失败: %v", err)
	}
	// 绑定于 20 天前，但最近一直在用（session_expires_at 已续到未来）
	boundAt := store.Now() - 20*24*3600
	if _, err := a.st.DB().ExecContext(ctx,
		`INSERT INTO devices(device_key,token_hash,name,platform,model,status,config_json,
		  config_version,bound_at,last_seen,created_at,session_expires_at)
		 VALUES(?,?,?,?,?,'bound','{}',0,?,?,?,?)`,
		"active-dev", hash, "活跃设备", "android", "test",
		boundAt, store.Now(), boundAt, store.Now()+48*3600); err != nil {
		t.Fatalf("建设备失败: %v", err)
	}

	d, err := a.st.GetDeviceByToken(ctx, hash, 24*72) // TTL = 72 小时
	if err != nil {
		t.Fatalf("最近使用过的设备不该过期: %v", err)
	}
	if d == nil {
		t.Fatal("没拿到设备")
	}
	_ = plain
}

// TestFreshDeviceNotImmediatelyExpired 确认刚绑定的设备不会秒过期。
//
// 这是一个真实的边界坑：session_expires_at 初始为 0 时，
// 若直接拿 bound_at 当到期时间（而不是 bound_at+TTL），
// 刚绑好的设备会立刻被判过期，所有新设备都用不了。
func TestFreshDeviceNotImmediatelyExpired(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	_, hash, err := auth.NewToken()
	if err != nil {
		t.Fatalf("生成 token 失败: %v", err)
	}
	// 刚绑定，session_expires_at 尚未写入（=0）
	if _, err := a.st.DB().ExecContext(ctx,
		`INSERT INTO devices(device_key,token_hash,name,platform,model,status,config_json,
		  config_version,bound_at,last_seen,created_at,session_expires_at)
		 VALUES(?,?,?,?,?,'bound','{}',0,?,?,?,0)`,
		"fresh-dev", hash, "新设备", "android", "test",
		store.Now(), store.Now(), store.Now()); err != nil {
		t.Fatalf("建设备失败: %v", err)
	}

	if _, err := a.st.GetDeviceByToken(ctx, hash, 24*365); err != nil {
		t.Fatalf("刚绑定的设备被判过期：%v（session_expires_at=0 时必须以 bound_at+TTL 为起点）", err)
	}
}

// TestZeroTokenHoursMeansNoExpiry 确认 token_hours<=0 表示不施加 TTL。
//
// 这是让用户可选的语义：不想管到期就设为 0，靠显式吊销。
func TestZeroTokenHoursMeansNoExpiry(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	_, hash, err := auth.NewToken()
	if err != nil {
		t.Fatalf("生成 token 失败: %v", err)
	}
	longAgo := store.Now() - 5*365*24*3600
	if _, err := a.st.DB().ExecContext(ctx,
		`INSERT INTO devices(device_key,token_hash,name,platform,model,status,config_json,
		  config_version,bound_at,last_seen,created_at,session_expires_at)
		 VALUES(?,?,?,?,?,'bound','{}',0,?,?,?,?)`,
		"noexp-dev", hash, "无到期设备", "android", "test",
		longAgo, longAgo, longAgo, longAgo); err != nil {
		t.Fatalf("建设备失败: %v", err)
	}

	// ttlHours=0：不施加 TTL
	if _, err := a.st.GetDeviceByToken(ctx, hash, 0); err != nil {
		t.Fatalf("token_hours=0 不该施加过期: %v", err)
	}
}

// TestRenewalDoesNotMoveBackwards 确认并发续期不会把到期时间往回拨。
func TestRenewalDoesNotMoveBackwards(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	_, hash, err := auth.NewToken()
	if err != nil {
		t.Fatalf("生成 token 失败: %v", err)
	}
	farFuture := store.Now() + 30*24*3600
	if _, err := a.st.DB().ExecContext(ctx,
		`INSERT INTO devices(device_key,token_hash,name,platform,model,status,config_json,
		  config_version,bound_at,last_seen,created_at,session_expires_at)
		 VALUES(?,?,?,?,?,'bound','{}',0,?,?,?,?)`,
		"fw-dev", hash, "远期设备", "android", "test",
		store.Now(), store.Now(), store.Now(), farFuture); err != nil {
		t.Fatalf("建设备失败: %v", err)
	}

	// 用一个很小的 TTL 续期（now+1h），到期时间不应被拉回到 1 小时后
	if _, err := a.st.GetDeviceByToken(ctx, hash, 1); err != nil {
		t.Fatalf("续期失败: %v", err)
	}
	var got int64
	if err := a.st.DB().QueryRowContext(ctx,
		`SELECT session_expires_at FROM devices WHERE device_key='fw-dev'`).Scan(&got); err != nil {
		t.Fatalf("读到期时间失败: %v", err)
	}
	if got < farFuture {
		t.Fatalf("续期把到期时间往回拨了：%d -> %d（应用 MAX 语义）", farFuture, got)
	}
}
