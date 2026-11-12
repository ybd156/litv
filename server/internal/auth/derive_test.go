package auth

import "testing"

// TestDeriveDeviceKey 覆盖 dk 泄露的修复。
//
// device_key 等价于设备凭据（服务端对已 bound 的同 key 重复注册会直接
// 换发可用 token），所以绝不能出现在播放地址里。派生值必须稳定、
// 不可反推，且不同设备之间不能复用。
func TestDeriveDeviceKey(t *testing.T) {
	s := NewSigner("secret-1")

	a := s.DeriveDeviceKey("android-abc")
	if a == "" {
		t.Fatal("派生值为空")
	}
	if a == "android-abc" {
		t.Fatal("派生值就是原文")
	}
	if a != s.DeriveDeviceKey("android-abc") {
		t.Fatal("同一输入两次派生结果不同")
	}
	if a == s.DeriveDeviceKey("android-abd") {
		t.Fatal("不同设备派生出同一个值")
	}

	// 不同密钥不能得到同一个派生值，否则换密钥等于没换
	if a == NewSigner("secret-2").DeriveDeviceKey("android-abc") {
		t.Fatal("派生值与服务端密钥无关")
	}

	if s.DeriveDeviceKey("") != "" {
		t.Fatal("空设备标识应返回空串")
	}

	// 长度固定，避免把 URL 拉长
	if len(a) != 22 {
		t.Fatalf("派生值长度 %d，期望 22", len(a))
	}
}
