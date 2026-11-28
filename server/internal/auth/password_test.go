package auth

import (
	"strings"
	"testing"
)

func TestHashAdminPasswordRoundTrip(t *testing.T) {
	const pw = "correct horse battery"
	encoded, err := HashAdminPassword(pw)
	if err != nil {
		t.Fatalf("哈希失败：%v", err)
	}
	if !strings.HasPrefix(encoded, adminPasswordScheme+"$") {
		t.Fatalf("哈希应以 %s$ 开头，实际 %q", adminPasswordScheme, encoded)
	}
	if strings.Contains(encoded, pw) {
		t.Fatal("哈希里不应出现明文口令")
	}
	if !VerifyAdminPassword(pw, encoded) {
		t.Fatal("正确口令应通过校验")
	}
	if VerifyAdminPassword("correct horse batterY", encoded) {
		t.Fatal("大小写不同的口令不应通过")
	}
	if VerifyAdminPassword("", encoded) {
		t.Fatal("空口令不应通过")
	}
	if VerifyAdminPassword(pw, "") {
		t.Fatal("空哈希不应通过")
	}
}

func TestHashAdminPasswordRejectsWeak(t *testing.T) {
	if _, err := HashAdminPassword("1234567"); err == nil {
		t.Fatal("7 位口令应被拒绝")
	}
	if _, err := HashAdminPassword("12345678"); err != nil {
		t.Fatalf("8 位口令应通过：%v", err)
	}
	// 长度按字节算，不是字符数。
	if _, err := HashAdminPassword(strings.Repeat("密", 20)); err != nil {
		t.Fatalf("多字节口令只要字节数够就该通过：%v", err)
	}
}

func TestHashAdminPasswordUsesRandomSalt(t *testing.T) {
	a, err := HashAdminPassword("samepassword")
	if err != nil {
		t.Fatalf("哈希失败：%v", err)
	}
	b, err := HashAdminPassword("samepassword")
	if err != nil {
		t.Fatalf("哈希失败：%v", err)
	}
	if a == b {
		t.Fatal("同一口令两次哈希不应相同（盐要随机）")
	}
	if !VerifyAdminPassword("samepassword", a) || !VerifyAdminPassword("samepassword", b) {
		t.Fatal("两个哈希都应能验证通过")
	}
}

func TestVerifyAdminPasswordRejectsMalformed(t *testing.T) {
	cases := []string{
		"",
		"pbkdf2-sha256$abc$c2FsdA$a2V5",       // 迭代次数不是数字
		"bcrypt$120000$c2FsdA$a2V5",           // 方案不对
		"pbkdf2-sha256$120000$$a2V5",          // 盐为空
		"pbkdf2-sha256$120000$c2FsdA$",        // 密钥为空
		"pbkdf2-sha256$999$c2FsdA$a2V5",       // 迭代次数过低
		"pbkdf2-sha256$999999999$c2FsdA$a2V5", // 迭代次数过高
		"pbkdf2-sha256$120000$c2FsdA",         // 段数不足
	}
	for _, encoded := range cases {
		if VerifyAdminPassword("password", encoded) {
			t.Fatalf("畸形哈希不应通过：%q", encoded)
		}
	}
}
