package store

import (
	"context"
	"errors"
	"path/filepath"
	"testing"
)

// TestRegisterExistingDeviceKeepsStatus 是「重装就要重新授权」的回归测试。
//
// 客户端把 device_key 做成稳定值（ANDROID_ID）之后，重装/清数据会带着
// 同一个 key 重新注册。以前这里是裸 INSERT，撞唯一约束直接报错；
// 现在必须认回原设备。
func TestRegisterExistingDeviceKeepsStatus(t *testing.T) {
	dir := t.TempDir()
	st, err := Open(filepath.Join(dir, "t.db"))
	if err != nil {
		t.Fatalf("打开库失败: %v", err)
	}
	defer st.Close()
	ctx := context.Background()

	// 首次注册：pending
	id1, status1, err := st.RegisterWithBindCode(ctx, "and-abc123", "hash1", "客厅电视", "android", "TV", "")
	if err != nil {
		t.Fatalf("首次注册失败: %v", err)
	}
	if status1 != "pending" {
		t.Fatalf("首次注册应为 pending，实际 %q", status1)
	}

	// 同 key 再次注册（模拟重装）：不应报错，且 id 不变、状态仍是 pending
	id2, status2, err := st.RegisterWithBindCode(ctx, "and-abc123", "hash2", "客厅电视", "android", "TV", "")
	if err != nil {
		t.Fatalf("重复注册不该报错，实际: %v", err)
	}
	if id2 != id1 {
		t.Errorf("同一 device_key 应复用同一条记录：%d vs %d", id1, id2)
	}
	if status2 != "pending" {
		t.Errorf("未批准前状态应保持 pending，实际 %q", status2)
	}

	// 管理员批准
	// 批准：必须同时写 bound_at——GetDeviceByToken 用 bound_at+TTL 算有效期，
	// 只改 status 会让设备立刻被判"会话已过期"。
	if _, err := st.DB().ExecContext(ctx,
		`UPDATE devices SET status='bound', bound_at=? WHERE id=?`, Now(), id1); err != nil {
		t.Fatalf("批准失败: %v", err)
	}

	// 批准后重装注册：应直接拿到 bound，不需要再批准
	id3, status3, err := st.RegisterWithBindCode(ctx, "and-abc123", "hash3", "客厅电视", "android", "TV", "")
	if err != nil {
		t.Fatalf("批准后注册失败: %v", err)
	}
	if id3 != id1 {
		t.Errorf("应复用原设备 id：%d vs %d", id1, id3)
	}
	if status3 != "bound" {
		t.Errorf("已 bound 的设备重装后应仍是 bound（免重新授权），实际 %q", status3)
	}

	// 新 token 必须可用：用 hash3 能查到设备
	dev, err := st.GetDeviceByToken(ctx, "hash3", 24)
	if err != nil {
		t.Fatalf("新 token 不可用: %v", err)
	}
	if dev.ID != id1 {
		t.Errorf("token 指向的设备不对：%d vs %d", dev.ID, id1)
	}
}

// TestRegisterSameKeyDifferentPlatformStaysPending 确认不能仅凭 device_key 提权。
//
// 同一个 key 换了平台（例如从 android 变成 ios）时，
// 只更新 token，**不**把状态提升成 bound。
func TestRegisterSameKeyDifferentPlatformStaysPending(t *testing.T) {
	dir := t.TempDir()
	st, err := Open(filepath.Join(dir, "t.db"))
	if err != nil {
		t.Fatalf("打开库失败: %v", err)
	}
	defer st.Close()
	ctx := context.Background()

	id, _, err := st.RegisterWithBindCode(ctx, "and-xyz", "h1", "设备", "android", "TV", "")
	if err != nil {
		t.Fatalf("注册失败: %v", err)
	}
	if _, err := st.DB().ExecContext(ctx,
		`UPDATE devices SET status='bound', bound_at=? WHERE id=?`, Now(), id); err != nil {
		t.Fatalf("批准失败: %v", err)
	}

	// 同 key 但平台变成 ios：必须被拒绝，不能换发可用 token
	_, _, err = st.RegisterWithBindCode(ctx, "and-xyz", "h2", "设备", "ios", "iPhone", "")
	if !errors.Is(err, ErrDeviceKeyConflict) {
		t.Fatalf("平台不一致时应返回 ErrDeviceKeyConflict，实际: %v", err)
	}
}
