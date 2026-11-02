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
	"net/url"
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
	UserID     string
	Username   string
	IsAdmin    bool
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

// MaxPasswordBytes bounds emergency-login passwords in bytes, not characters.
const MaxPasswordBytes = 256

// PasswordMatches hashes both operands before comparing fixed-size digests.
// Empty/overlong configured passwords disable login; no secret is returned.
func PasswordMatches(candidate, configured string) bool {
	if len(candidate) > MaxPasswordBytes || len(configured) > MaxPasswordBytes {
		return false
	}
	got := sha256.Sum256([]byte(candidate))
	want := sha256.Sum256([]byte(configured))
	matches := subtle.ConstantTimeCompare(got[:], want[:])
	return matches == 1 && configured != "" && candidate != ""
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

// ---------- 分片代理签名 ----------

// segmentSignature 把「用途 + 频道 + 目标地址 + 到期时间 + 设备」全部绑进签名。
//
// 为什么单独一套：原来的 /seg 只校验频道级播放签名，而签名内容里没有目标地址。
// 于是任何持有有效播放签名的设备，只要改查询参数 u，就能让 NAS 去请求
// 任意 http/https 地址——把播放权限放大成"任意 HTTP 代理"，
// 可以拿它探内网服务。签名必须绑定实际目标，改 u 就必须失效。
//
// 用途前缀 "seg" 让分片签名不能被当作播放签名复用，反之亦然。
func segmentSignature(secret []byte, channelID int64, target string, exp int64, deviceKey string) string {
	norm := normalizeSegmentTarget(target)
	payload := fmt.Sprintf("seg|%d|%s|%d|%s", channelID, norm, exp, deviceKey)
	mac := hmac.New(sha256.New, secret)
	mac.Write([]byte(payload))
	return base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}

// normalizeSegmentTarget 归一化目标地址，保证签名与校验用同一形态。
//
// 只做无歧义的处理：大小写统一 scheme/host、去掉 fragment、去掉默认端口。
// 不做路径清洗——路径必须原样参与签名，否则两个不同的地址可能归一到同一串，
// 那是把签名强度换成"差不多能用"。
func normalizeSegmentTarget(raw string) string {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || u == nil {
		return raw
	}
	u.Scheme = strings.ToLower(u.Scheme)
	u.Host = strings.ToLower(u.Host)
	u.Fragment = ""
	// 默认端口省略，避免 http://x:80 与 http://x 被判成不同目标
	if (u.Scheme == "http" && strings.HasSuffix(u.Host, ":80")) ||
		(u.Scheme == "https" && strings.HasSuffix(u.Host, ":443")) {
		if i := strings.LastIndex(u.Host, ":"); i > 0 {
			u.Host = u.Host[:i]
		}
	}
	return u.String()
}

// SignSegment 生成分片签名。
func (s *Signer) SignSegment(channelID int64, target string, exp int64, deviceKey string) string {
	return segmentSignature(s.secret, channelID, target, exp, deviceKey)
}

// VerifySegment 校验分片签名：目标地址、频道、用途、到期时间全部必须一致。
func (s *Signer) VerifySegment(channelID int64, target string, exp int64, deviceKey, sig string) error {
	if exp < time.Now().Unix() {
		return fmt.Errorf("分片地址已过期")
	}
	want := s.SignSegment(channelID, target, exp, deviceKey)
	if subtle.ConstantTimeCompare([]byte(want), []byte(sig)) != 1 {
		return fmt.Errorf("分片地址签名无效")
	}
	return nil
}
