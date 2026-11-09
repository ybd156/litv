package store

import (
	"context"
	"path/filepath"
	"testing"
)

func openTestStore(t *testing.T) *Store {
	t.Helper()
	st, err := Open(filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatalf("打开库失败: %v", err)
	}
	t.Cleanup(func() { _ = st.Close() })
	return st
}

func insertBoundDevice(t *testing.T, st *Store, key, hash string, lastSeen, boundAt, expires int64) int64 {
	t.Helper()
	res, err := st.db.ExecContext(context.Background(),
		`INSERT INTO devices(device_key,token_hash,name,platform,model,status,created_at,last_seen,bound_at,session_expires_at)
		 VALUES(?,?,?,?,?,'bound',?,?,?,?)`,
		key, hash, "测试设备", "android", "t", Now(), lastSeen, boundAt, expires)
	if err != nil {
		t.Fatalf("建设备失败: %v", err)
	}
	id, err := res.LastInsertId()
	if err != nil {
		t.Fatalf("取 id 失败: %v", err)
	}
	return id
}

func deviceInt(t *testing.T, st *Store, id int64, column string) int64 {
	t.Helper()
	var v int64
	if err := st.db.QueryRow(`SELECT `+column+` FROM devices WHERE id=?`, id).Scan(&v); err != nil {
		t.Fatalf("读 %s 失败: %v", column, err)
	}
	return v
}

// TestTouchDeviceIsThrottled 覆盖心跳写节流。
//
// 修复前每个请求都 UPDATE devices：直播时一台设备每秒都在发请求，
// SQLite 上就是持续的 WAL 写入。节流后 60 秒内最多写一次，
// 但注册时补写 IP/版本必须立即生效（否则管理台会有一分钟空窗）。
func TestTouchDeviceIsThrottled(t *testing.T) {
	st := openTestStore(t)
	ctx := context.Background()
	now := Now()
	id := insertBoundDevice(t, st, "k1", "h1", now-10, now, 0)

	if err := st.TouchDevice(ctx, id); err != nil {
		t.Fatalf("心跳失败: %v", err)
	}
	if got := deviceInt(t, st, id, "last_seen"); got != now-10 {
		t.Fatalf("60 秒内的心跳不该写库：last_seen %d -> %d", now-10, got)
	}

	// 注册路径：必须立即写入。
	if err := st.UpdateDeviceClientInfo(ctx, id, "1.2.3.4", "1.0.0"); err != nil {
		t.Fatalf("补写客户端信息失败: %v", err)
	}
	var ip, ver string
	if err := st.db.QueryRow(`SELECT ip, app_version FROM devices WHERE id=?`, id).Scan(&ip, &ver); err != nil {
		t.Fatalf("读客户端信息失败: %v", err)
	}
	if ip != "1.2.3.4" || ver != "1.0.0" {
		t.Fatalf("注册时补写的 IP/版本没有立即生效：ip=%q ver=%q", ip, ver)
	}

	// 超过间隔后必须更新。
	if _, err := st.db.ExecContext(ctx, `UPDATE devices SET last_seen=? WHERE id=?`, now-120, id); err != nil {
		t.Fatalf("改 last_seen 失败: %v", err)
	}
	if err := st.TouchDevice(ctx, id); err != nil {
		t.Fatalf("心跳失败: %v", err)
	}
	if got := deviceInt(t, st, id, "last_seen"); got < now-60 {
		t.Fatalf("间隔已过，心跳却没有更新: %d", got)
	}
}

// TestGetDeviceByTokenRenewsOnlyWhenNeeded 覆盖滑动续期写节流。
//
// 语义要求「别让有效期滑到过期」，不要求每个请求都写。
// 只在剩余不足 TTL 一半时续期，等价于最多每 TTL/2 写一次。
func TestGetDeviceByTokenRenewsOnlyWhenNeeded(t *testing.T) {
	st := openTestStore(t)
	ctx := context.Background()
	now := Now()
	id := insertBoundDevice(t, st, "k2", "h2", now, now, now+3600)

	d, err := st.GetDeviceByToken(ctx, "h2", 1)
	if err != nil || d == nil {
		t.Fatalf("按 token 查设备失败: %v", err)
	}
	if got := deviceInt(t, st, id, "session_expires_at"); got != now+3600 {
		t.Fatalf("剩余有效期充足时不该写库: %d", got)
	}

	// 剩余不足一半：必须续期。
	if _, err := st.db.ExecContext(ctx,
		`UPDATE devices SET session_expires_at=? WHERE id=?`, now+60, id); err != nil {
		t.Fatalf("改到期时间失败: %v", err)
	}
	if _, err := st.GetDeviceByToken(ctx, "h2", 1); err != nil {
		t.Fatalf("按 token 查设备失败: %v", err)
	}
	if got := deviceInt(t, st, id, "session_expires_at"); got < now+3500 {
		t.Fatalf("剩余不足一半时应该续期，实际 %d", got)
	}
}
