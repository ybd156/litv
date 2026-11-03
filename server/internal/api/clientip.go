package api

import (
	"net"
	"net/http"
	"strings"

	"github.com/litv/litv/server/internal/config"
)

// clientIP 取请求的来源地址，用于「记录并显示设备 IP」。
//
// 安全约定（重要）：
//
//	这个函数的返回值**绝不能**用于任何鉴权判断。
//	X-Forwarded-For 是客户端可以随意伪造的头，一旦拿它做权限决策，
//	伪造一个头就能冒充别人。鉴权只认设备 token / 网关注入的身份。
//
// 取法：
//  1. 先拿 TCP 层的对端地址（RemoteAddr），这是唯一可信的。
//  2. 只有当 trust_proxy 打开、且对端落在 trusted_proxies 网段里时，
//     才采信 X-Real-IP / X-Forwarded-For 的第一段。
//     反向代理通常和 litvd 在同一台机器（127.0.0.1），
//     所以默认受信范围就是回环地址。
func clientIP(r *http.Request, cfg *config.Config) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	host = strings.TrimSpace(host)
	if cfg == nil || !cfg.Server.TrustProxy || !peerTrusted(host, cfg.Server.TrustedProxies) {
		return host
	}

	// X-Real-IP 优先（nginx 常见配置），没有再取 X-Forwarded-For 的第一段
	if v := strings.TrimSpace(r.Header.Get("X-Real-IP")); v != "" {
		if ip := net.ParseIP(v); ip != nil {
			return v
		}
	}
	if v := r.Header.Get("X-Forwarded-For"); v != "" {
		// 格式：client, proxy1, proxy2 —— 取最左边的原始客户端
		if first := strings.TrimSpace(strings.Split(v, ",")[0]); first != "" {
			if ip := net.ParseIP(first); ip != nil {
				return first
			}
		}
	}
	return host
}

// peerTrusted 判断直连对端是否落在受信代理网段里。
func peerTrusted(host string, cidrs []string) bool {
	ip := net.ParseIP(host)
	if ip == nil {
		return false
	}
	for _, c := range cidrs {
		c = strings.TrimSpace(c)
		if c == "" {
			continue
		}
		// 允许只写 IP（等价于 /32 或 /128）
		if !strings.Contains(c, "/") {
			if strings.Contains(c, ":") {
				c += "/128"
			} else {
				c += "/32"
			}
		}
		if _, n, err := net.ParseCIDR(c); err == nil && n.Contains(ip) {
			return true
		}
	}
	return false
}
