package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// TestSecretIsCryptographicallyRandom 是 R02 的核心回归测试。
//
// 修复前必然失败：randomSecret 用进程 PID 做种子的线性同余发生器，
// 整个输出由 PID 决定，可以被离线推导。攻击者一旦能算出密钥，
// 就能自行签发任意播放地址——流签名完全失效。
//
// 这里验证两件事：
//  1. 同一进程内连调两次必须不同（旧实现基于 PID，会得到完全相同的串）；
//  2. 密钥足够长、熵足够（不是可枚举的小空间）。
func TestSecretIsCryptographicallyRandom(t *testing.T) {
	a := randomSecret()
	b := randomSecret()

	if a == "" || b == "" {
		t.Fatal("crypto/rand 不可用，密钥生成失败")
	}
	// 旧实现是确定性的，两次调用必然相同。
	if a == b {
		t.Fatal("两次生成的密钥完全相同——密钥是确定性/可预测的")
	}
	if len(a) < 32 {
		t.Errorf("密钥过短：%d 字节，至少 32", len(a))
	}
	// 粗略熵检查：字符种类不应被限制在很小的字母表里。
	distinct := map[rune]bool{}
	for _, r := range a + b {
		distinct[r] = true
	}
	if len(distinct) < 20 {
		t.Errorf("密钥字符种类过少（%d），可能是弱随机源", len(distinct))
	}
}

// TestSecretNotDerivedFromPID 确认密钥不随进程号可推导。
func TestSecretNotDerivedFromPID(t *testing.T) {
	// 旧实现种子就是 PID；如果密钥含 PID 的十进制串，说明仍可推导。
	pid := itoa(os.Getpid())
	s := randomSecret()
	if strings.Contains(s, pid) {
		t.Errorf("密钥包含进程号 %s，仍可从 PID 推导", pid)
	}
}

// TestPlaceholderSecretIsRejected 确认占位密钥不会被当成有效密钥。
func TestPlaceholderSecretIsRejected(t *testing.T) {
	cases := []string{"", "changeme", "CHANGEME", "secret", "password", "short", "your-secret"}
	for _, bad := range cases {
		if !secretIsPlaceholder(bad) {
			t.Errorf("占位值 %q 未被识别为不安全", bad)
		}
	}
	// 正常随机值不应被误判。
	good := randomSecret()
	if secretIsPlaceholder(good) {
		t.Errorf("合法密钥被判为占位值：%q", good)
	}
}

// TestValidateReplacesPlaceholderSecret 确认 Validate 会把占位密钥换掉。
func TestValidateReplacesPlaceholderSecret(t *testing.T) {
	c := Default()
	c.Auth.Secret = "changeme"
	if err := c.Validate(); err != nil {
		t.Fatalf("校验失败: %v", err)
	}
	if secretIsPlaceholder(c.Auth.Secret) {
		t.Fatal("占位密钥没有被替换")
	}
	if !c.SecretGenerated {
		t.Error("生成密钥后应标记 SecretGenerated，main 才会写回磁盘")
	}
}

// TestSecretPersistsAcrossLoad 确认密钥能持久化，重启后不失效。
//
// 这是 R02 的第二个缺陷：以前密钥只在进程里随机生成，从不落盘，
// 每次重启都换一把，所有已发出的播放地址立刻 403。
func TestSecretPersistsAcrossLoad(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.yaml")

	// 首次：配置里没有密钥。
	c := Default()
	c.Auth.Secret = ""
	c.BindSecretPersistPath(path)
	if err := c.Validate(); err != nil {
		t.Fatalf("首次校验失败: %v", err)
	}
	if !c.NeedsSecretPersist() {
		t.Fatal("应提示需要持久化密钥")
	}
	first := c.Auth.Secret
	if err := c.PersistSecret(); err != nil {
		t.Fatalf("持久化密钥失败: %v", err)
	}

	// 第二次加载：必须拿到同一把密钥。
	c2, err := Load(path)
	if err != nil {
		t.Fatalf("重新加载失败: %v", err)
	}
	if c2.Auth.Secret != first {
		t.Fatalf("密钥跨重启变化：%q -> %q（已发出的播放地址会全部失效）", first, c2.Auth.Secret)
	}
	if c2.NeedsSecretPersist() {
		t.Error("密钥已存在时不应再标记需要持久化")
	}
}

// TestLoadValidatesEveryPath 确认 Load 的每条返回路径都做了校验。
//
// 旧实现里 `path == ""` 和"文件不存在"两处直接 return cfg, nil，
// 完全跳过 normalize/Validate，-check 会假通过。
func TestLoadValidatesEveryPath(t *testing.T) {
	dir := t.TempDir()

	// 1) 文件不存在：仍要得到一把可用密钥（说明走了 Validate）
	missing := filepath.Join(dir, "nope.yaml")
	c, err := Load(missing)
	if err != nil {
		t.Fatalf("文件不存在时不应报错（首次启动场景）: %v", err)
	}
	if secretIsPlaceholder(c.Auth.Secret) {
		t.Fatal("不存在配置文件时没有生成有效密钥——跳过了 Validate")
	}

	// 2) 非法内容必须报错，不能被静默接受
	badPath := filepath.Join(dir, "bad.yaml")
	if err := os.WriteFile(badPath, []byte("server:\n  port: -5\n"), 0o600); err != nil {
		t.Fatalf("写测试配置失败: %v", err)
	}
	if _, err := Load(badPath); err == nil {
		t.Fatal("非法端口未被 Validate 拒绝——-check 会假通过")
	}
}

// TestSaveKeepsSecretPermissionTight 确认落盘的密钥文件权限收紧。
func TestSaveKeepsSecretPermissionTight(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.yaml")

	c := Default()
	c.Auth.Secret = ""
	if err := c.Validate(); err != nil {
		t.Fatalf("校验失败: %v", err)
	}
	if err := c.Save(path); err != nil {
		t.Fatalf("保存失败: %v", err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("配置文件不存在: %v", err)
	}
	if perm := info.Mode().Perm(); perm&0o077 != 0 {
		t.Errorf("含密钥的配置文件权限过宽：%o", perm)
	}
}

// itoa 避免为测试引入 strconv 额外依赖时的可读性损失。
func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var b []byte
	for n > 0 {
		b = append([]byte{byte('0' + n%10)}, b...)
		n /= 10
	}
	return string(b)
}
