package store

import (
	"context"
	"errors"
	"testing"
)

// TestRegisterWithBindCodeOnPendingDevice 是本轮 O02 的核心回归。
//
// 修复前必然失败：RegisterWithBindCode 的 exist 分支只更新 token 就 return，
// code 参数一次都没用过。于是「首次无码注册成 pending → 用户拿到管理员签发的
// 有效码再注册」这条路走不通：设备仍然 pending，码也没被消费。
func TestRegisterWithBindCodeOnPendingDevice(t *testing.T) {
	st := openTestStore(t)
	ctx := context.Background()
	const key = "key-o02"

	// 1) 首次无码注册 → pending
	id1, status1, err := st.RegisterWithBindCode(ctx, key, "hash-1", "电视", "android", "TV", "")
	if err != nil {
		t.Fatalf("首次注册失败: %v", err)
	}
	if status1 != "pending" {
		t.Fatalf("无码注册应为 pending，实际 %q", status1)
	}

	// 2) 管理员签发一个通用邀请码（device_id=0）
	if err := st.CreateBindCode(ctx, "CODE-OK", 0, 600); err != nil {
		t.Fatalf("建码失败: %v", err)
	}

	// 3) 同一 key 带有效码再注册 → 同一个设备 id、状态 bound
	id2, status2, err := st.RegisterWithBindCode(ctx, key, "hash-2", "电视", "android", "TV", "CODE-OK")
	if err != nil {
		t.Fatalf("带码重注册失败: %v", err)
	}
	if id2 != id1 {
		t.Errorf("应认回原设备（id=%d），实际拿到 id=%d", id1, id2)
	}
	if status2 != "bound" {
		t.Fatalf("带有效码重注册后应为 bound，实际 %q —— 码路径被跳过了", status2)
	}

	// 4) 码必须被消费掉（used=1）
	var used int
	if err := st.DB().QueryRowContext(ctx,
		`SELECT used FROM bind_codes WHERE code=?`, "CODE-OK").Scan(&used); err != nil {
		t.Fatalf("查码失败: %v", err)
	}
	if used != 1 {
		t.Errorf("码没有被消费（used=%d），会被重复使用", used)
	}

	// 5) 新凭据可用
	if _, err := st.GetDeviceByToken(ctx, "hash-2", 720); err != nil {
		t.Errorf("新 token 不可用: %v", err)
	}
}

// TestRegisterWithBindCodeNoSideEffectsOnBadCode 覆盖「错误码不能有副作用」。
//
// 关键点：码错了要回滚整个事务——设备保持 pending、码保持未使用。
// 不能因为"试过一次"就把码吃掉，也不能顺手把设备状态改了。
func TestRegisterWithBindCodeNoSideEffectsOnBadCode(t *testing.T) {
	st := openTestStore(t)
	ctx := context.Background()
	const key = "key-o02-bad"

	if _, _, err := st.RegisterWithBindCode(ctx, key, "hash-a", "电视", "android", "TV", ""); err != nil {
		t.Fatalf("首次注册失败: %v", err)
	}

	// 不存在的码
	_, _, err := st.RegisterWithBindCode(ctx, key, "hash-b", "电视", "android", "TV", "NOPE")
	if !errors.Is(err, ErrBindCodeNotFound) {
		t.Fatalf("应返回 ErrBindCodeNotFound，实际 %v", err)
	}

	// 设备仍然 pending
	var status string
	if err := st.DB().QueryRowContext(ctx,
		`SELECT status FROM devices WHERE device_key=?`, key).Scan(&status); err != nil {
		t.Fatalf("查设备失败: %v", err)
	}
	if status != "pending" {
		t.Errorf("错误码把设备状态改成了 %q，应为 pending", status)
	}

	// 已过期的码：同样不能有副作用
	if _, err := st.DB().ExecContext(ctx,
		`INSERT INTO bind_codes(code,device_id,expires_at,used) VALUES(?,?,?,0)`,
		"CODE-OLD", 0, Now()-10); err != nil {
		t.Fatalf("写过期码失败: %v", err)
	}
	_, _, err = st.RegisterWithBindCode(ctx, key, "hash-c", "电视", "android", "TV", "CODE-OLD")
	if !errors.Is(err, ErrBindCodeExpired) {
		t.Fatalf("应返回 ErrBindCodeExpired，实际 %v", err)
	}
	var used int
	if err := st.DB().QueryRowContext(ctx,
		`SELECT used FROM bind_codes WHERE code=?`, "CODE-OLD").Scan(&used); err != nil {
		t.Fatalf("查过期码失败: %v", err)
	}
	if used != 0 {
		t.Errorf("过期码被标记成已使用（used=%d）", used)
	}
}

// TestRegisterWithBindCodeRejectsCrossPlatform 守住跨平台边界：
// 知道一台待批准设备的 device_key 不能用来把它顶掉。
func TestRegisterWithBindCodeRejectsCrossPlatform(t *testing.T) {
	st := openTestStore(t)
	ctx := context.Background()
	const key = "key-o02-x"

	if _, _, err := st.RegisterWithBindCode(ctx, key, "hash-1", "电视", "android", "TV", ""); err != nil {
		t.Fatalf("首次注册失败: %v", err)
	}
	if err := st.CreateBindCode(ctx, "CODE-X", 0, 600); err != nil {
		t.Fatalf("建码失败: %v", err)
	}

	// 同一个 key 换平台：任何状态都必须拒绝
	_, _, err := st.RegisterWithBindCode(ctx, key, "hash-2", "手机", "ios", "iPhone", "CODE-X")
	if !errors.Is(err, ErrDeviceKeyConflict) {
		t.Fatalf("跨平台应返回 ErrDeviceKeyConflict，实际 %v", err)
	}

	// 码不能被这次失败的尝试消费掉
	var used int
	if err := st.DB().QueryRowContext(ctx,
		`SELECT used FROM bind_codes WHERE code=?`, "CODE-X").Scan(&used); err != nil {
		t.Fatalf("查码失败: %v", err)
	}
	if used != 0 {
		t.Errorf("跨平台失败把码消费掉了（used=%d）", used)
	}
}

// TestReRegisterRefreshesExpiredSession 是本轮 O03 的核心回归。
//
// 修复前必然失败：已 bound 会话过期后重注册，返回状态是 bound、也给了新 token，
// 但 session_expires_at 还是旧的过期时刻 —— GetDeviceByToken 立刻判已过期，
// 用户表现为「重装/重新注册后仍然连不上，必须去管理台重新批准」。
func TestReRegisterRefreshesExpiredSession(t *testing.T) {
	st := openTestStore(t)
	ctx := context.Background()
	const key = "key-o03"
	const ttlHours = 24

	// 1) 注册并绑定
	if _, _, err := st.RegisterWithBindCode(ctx, key, "hash-a", "电视", "android", "TV", "", ttlHours); err != nil {
		t.Fatalf("首次注册失败: %v", err)
	}
	if err := st.CreateBindCode(ctx, "CODE-TTL", 0, 600); err != nil {
		t.Fatalf("建码失败: %v", err)
	}
	id, status, err := st.RegisterWithBindCode(ctx, key, "hash-b", "电视", "android", "TV", "CODE-TTL", ttlHours)
	if err != nil {
		t.Fatalf("带码注册失败: %v", err)
	}
	if status != "bound" {
		t.Fatalf("应为 bound，实际 %q", status)
	}

	// 2) 人为把会话置成过期
	if _, err := st.DB().ExecContext(ctx,
		`UPDATE devices SET session_expires_at=? WHERE id=?`, Now()-100, id); err != nil {
		t.Fatalf("置过期失败: %v", err)
	}
	if _, err := st.GetDeviceByToken(ctx, "hash-b", ttlHours); err == nil {
		t.Fatal("前置条件不成立：过期的旧凭据居然可用")
	}

	// 3) 不带码重注册（重装客户端的典型路径）→ 新凭据必须立刻可用
	id2, status2, err := st.RegisterWithBindCode(ctx, key, "hash-c", "电视", "android", "TV", "", ttlHours)
	if err != nil {
		t.Fatalf("重注册失败: %v", err)
	}
	if id2 != id || status2 != "bound" {
		t.Fatalf("应认回原设备且保持 bound，实际 id=%d status=%q", id2, status2)
	}
	if _, err := st.GetDeviceByToken(ctx, "hash-c", ttlHours); err != nil {
		t.Fatalf("新凭据不可用（有效期没续上）: %v", err)
	}
	// 旧 token 已被换掉，不能再认
	if _, err := st.GetDeviceByToken(ctx, "hash-b", ttlHours); err == nil {
		t.Error("旧凭据仍然可用，token 没有真正换发")
	}
}

// TestReRegisterDoesNotRenewPending 守住边界：续期只能发生在**已经 bound** 的设备上。
// pending 设备重注册不能顺手拿到新时效（更不能被批准）。
func TestReRegisterDoesNotRenewPending(t *testing.T) {
	st := openTestStore(t)
	ctx := context.Background()
	const key = "key-o03-pending"

	if _, status, err := st.RegisterWithBindCode(ctx, key, "hash-1", "电视", "android", "TV", "", 24); err != nil {
		t.Fatalf("注册失败: %v", err)
	} else if status != "pending" {
		t.Fatalf("无码注册应为 pending，实际 %q", status)
	}

	var expires int64
	if err := st.DB().QueryRowContext(ctx,
		`SELECT session_expires_at FROM devices WHERE device_key=?`, key).Scan(&expires); err != nil {
		t.Fatalf("查设备失败: %v", err)
	}
	if expires != 0 {
		t.Errorf("pending 设备被写了会话有效期（%d），续期只该发生在 bound 设备上", expires)
	}
}
