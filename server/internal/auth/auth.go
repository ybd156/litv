// Package auth 处理两类身份：
//
//  1. 管理台用户 —— 来自飞牛统一网关注入的 X-Trim-Userid / X-Trim-Username / X-Trim-Isadmin。
//     官方明确说明这些头可作为可信身份上下文，但业务鉴权仍由应用自己做。
//     ⚠️ 安全边界：只有经网关（Unix Socket）进来的请求才可信；TCP 端口进来的请求一律不认这些头。
//
//  2. 终端设备 —— 自建 device token（绑定码换取），存 sha256 哈希，不存明文。
package auth

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// 网关注入的身份头（官方字段名）。
const (
	HeaderUserID   = "X-Trim-Userid"
	HeaderUsername = "X-Trim-Username"
	HeaderIsAdmin  = "X-Trim-Isadmin"
)

// Identity 是当前请求的身份。
type Identity struct {
	UserID   string
	Username string
	IsAdmin  bool
	ViaGateway bool // 是否来自网关（可信）
}

// FromGateway 从网关头提取身份。只有 viaGateway=true 时才可信。
func FromGateway(h http.Header, viaGateway bool) (Identity, bool) {
	if !viaGateway {
		return Identity{}, false
	}
	uid := strings.TrimSpace(h.Get(HeaderUserID))
	if uid == "" {
		return Identity{}, false
	}
	return Identity{
		UserID:     uid,
		Username:   strings.TrimSpace(h.Get(HeaderUsername)),
		IsAdmin:    strings.EqualFold(strings.TrimSpace(h.Get(HeaderIsAdmin)), "true"),
		ViaGateway: true,
	}, true
}

// NewToken 生成终端 token（返回明文与哈希，明文只返回一次）。
func NewToken() (plain, hash string, err error) {
	b := make([]byte, 32)
	if _, err = rand.Read(b); err != nil {
		return "", "", err
	}
	plain = base64.RawURLEncoding.EncodeToString(b)
	return plain, HashToken(plain), nil
}

// HashToken 计算 token 的 sha256 十六进制串。
func HashToken(plain string) string {
	sum := sha256.Sum256([]byte(plain))
	return hex.EncodeToString(sum[:])
}

// BearerToken 从 Authorization 头取 token。
func BearerToken(r *http.Request) string {
	h := r.Header.Get("Authorization")
	if h == "" {
		return ""
	}
	if strings.HasPrefix(strings.ToLower(h), "bearer ") {
		return strings.TrimSpace(h[7:])
	}
	return strings.TrimSpace(h)
}

// Signer 负责流地址的签名，防止被直接盗链。
type Signer struct {
	secret []byte
}

// NewSigner 创建签名器。
func NewSigner(secret string) *Signer { return &Signer{secret: []byte(secret)} }

// Sign 生成签名：HMAC-SHA256(channelID|exp|deviceKey)。
func (s *Signer) Sign(channelID int64, exp int64, deviceKey string) string {
	mac := hmac.New(sha256.New, s.secret)
	fmt.Fprintf(mac, "%d|%d|%s", channelID, exp, deviceKey)
	return base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}

// Verify 校验签名与过期时间。
func (s *Signer) Verify(channelID int64, exp int64, deviceKey, sig string) error {
	if exp < time.Now().Unix() {
		return fmt.Errorf("播放地址已过期")
	}
	want := s.Sign(channelID, exp, deviceKey)
	if subtle.ConstantTimeCompare([]byte(want), []byte(sig)) != 1 {
		return fmt.Errorf("播放地址签名无效")
	}
	return nil
}

// BuildSignedURL 拼出带签名的播放地址。
func BuildSignedURL(basePath string, channelID int64, deviceKey string, s *Signer, ttl time.Duration) string {
	exp := time.Now().Add(ttl).Unix()
	sig := s.Sign(channelID, exp, deviceKey)
	return fmt.Sprintf("%s?sig=%s&exp=%s&dk=%s",
		basePath, sig, strconv.FormatInt(exp, 10), deviceKey)
}
