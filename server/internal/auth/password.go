package auth

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"strconv"
	"strings"
)

// 管理台登录密码：用户首次部署时自己设（/api/v1/auth/setup），
// 之后以哈希形式存在 kv 表里，配置文件里的明文只作为「还没设过」时的初始值。
//
// 为什么不用 bcrypt/argon2：本仓库的服务端依赖只有 chi/websocket/x-text/yaml/sqlite，
// 没有 golang.org/x/crypto。为一个管理口令引入新依赖不划算，而标准库
// （crypto/hmac + crypto/sha256）足够实现 PBKDF2-HMAC-SHA256 —— 迭代够多时
// 暴力破解的成本与 bcrypt 同量级，且格式自描述、可随参数升级。
const (
	// AdminPasswordMinLen 是设置新密码时的最小位数。
	// 不强制大小写/符号：手机输入法上那句话只会让用户写在纸上；
	// 位数（≥8）是真正提高搜索空间的那一维。
	AdminPasswordMinLen = 8

	adminPasswordIterations = 120000
	adminPasswordSaltBytes  = 16
	adminPasswordKeyBytes   = 32
	adminPasswordScheme     = "pbkdf2-sha256"
)

// ErrWeakPassword 表示口令不满足最小长度。
var ErrWeakPassword = errors.New("密码太短")

// HashAdminPassword 把明文口令编成可落库的自描述字符串：
//
//	pbkdf2-sha256$<迭代次数>$<盐 base64>$<派生密钥 base64>
//
// 把迭代次数写进去，以后调高强度时旧哈希仍然可验证。
func HashAdminPassword(password string) (string, error) {
	if len([]byte(password)) < AdminPasswordMinLen {
		return "", ErrWeakPassword
	}
	if len(password) > MaxPasswordBytes {
		return "", errors.New("密码过长")
	}
	salt := make([]byte, adminPasswordSaltBytes)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	key := pbkdf2SHA256([]byte(password), salt, adminPasswordIterations, adminPasswordKeyBytes)
	enc := base64.RawStdEncoding
	return strings.Join([]string{
		adminPasswordScheme,
		strconv.Itoa(adminPasswordIterations),
		enc.EncodeToString(salt),
		enc.EncodeToString(key),
	}, "$"), nil
}

// VerifyAdminPassword 校验明文口令与已落的哈希是否匹配。
//
// 任何解析失败都返回 false（而不是报错）：调用方只需要「能不能登录」，
// 而格式坏了等同于口令不可用，不能因此把 500 的细节暴露出去。
// 比较用 subtle.ConstantTimeCompare，避免按字节提前返回泄露前缀信息。
func VerifyAdminPassword(password, encoded string) bool {
	if password == "" || encoded == "" || len(password) > MaxPasswordBytes {
		return false
	}
	parts := strings.Split(encoded, "$")
	if len(parts) != 4 || parts[0] != adminPasswordScheme {
		return false
	}
	iterations, err := strconv.Atoi(parts[1])
	// 上限兜住被篡改成天文数字导致的 DoS；下限保证不会退化成"等于 sha256"。
	if err != nil || iterations < 10000 || iterations > 1000000 {
		return false
	}
	enc := base64.RawStdEncoding
	salt, err := enc.DecodeString(parts[2])
	if err != nil || len(salt) == 0 {
		return false
	}
	want, err := enc.DecodeString(parts[3])
	if err != nil || len(want) == 0 {
		return false
	}
	got := pbkdf2SHA256([]byte(password), salt, iterations, len(want))
	return subtle.ConstantTimeCompare(got, want) == 1
}

// pbkdf2SHA256 是 RFC 8018 的 PBKDF2，PRF 用 HMAC-SHA256。
//
// 只支持调用方需要的一种参数形态（单块输出由 keyLen 决定块数），
// 不追求做成通用库。
func pbkdf2SHA256(password, salt []byte, iterations, keyLen int) []byte {
	if iterations < 1 || keyLen <= 0 {
		return nil
	}
	hashLen := sha256.Size
	blocks := (keyLen + hashLen - 1) / hashLen
	out := make([]byte, 0, blocks*hashLen)
	var buf [4]byte
	for block := 1; block <= blocks; block++ {
		mac := hmac.New(sha256.New, password)
		mac.Write(salt)
		buf[0] = byte(block >> 24)
		buf[1] = byte(block >> 16)
		buf[2] = byte(block >> 8)
		buf[3] = byte(block)
		mac.Write(buf[:])
		u := mac.Sum(nil)
		acc := make([]byte, hashLen)
		copy(acc, u)
		for i := 1; i < iterations; i++ {
			mac = hmac.New(sha256.New, password)
			mac.Write(u)
			u = mac.Sum(nil)
			for j := 0; j < hashLen; j++ {
				acc[j] ^= u[j]
			}
		}
		out = append(out, acc...)
	}
	return out[:keyLen]
}
