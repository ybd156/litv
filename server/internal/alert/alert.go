// Package alert 做源失效告警。
//
// 场景：家里电视突然某个台放不出来，用户不会去看管理台。
// 与其让人自己发现，不如服务端主动推一条消息。
//
// 支持两种出口：
//   - 通用 webhook：POST JSON 到任意地址（自建机器人、企业微信中转等）
//   - Telegram：webhook_url 里带 bot token 时自动用 Telegram 格式
package alert

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"
)

// Options 告警参数。
type Options struct {
	Enabled bool
	// WebhookURL 通用 webhook 或 Telegram 的 https://api.telegram.org/bot<token>/sendMessage
	WebhookURL string
	// ChatID Telegram 用
	ChatID string
	// MinInterval 同类告警最小间隔，防刷屏（默认 30 分钟）
	MinInterval time.Duration
}

// Notifier 发告警。
type Notifier struct {
	opt Options
	cli *http.Client

	mu   sync.Mutex
	last map[string]time.Time // key -> 上次发送时间
}

// New 创建。
func New(opt Options) *Notifier {
	if opt.MinInterval <= 0 {
		opt.MinInterval = 30 * time.Minute
	}
	return &Notifier{
		opt:  opt,
		cli:  &http.Client{Timeout: 10 * time.Second},
		last: make(map[string]time.Time),
	}
}

// Enabled 是否启用。
func (n *Notifier) Enabled() bool {
	return n != nil && n.opt.Enabled && n.opt.WebhookURL != ""
}

// Send 发一条告警。key 用于去重（同类告警在 MinInterval 内只发一次）。
func (n *Notifier) Send(key, title, body string) error {
	if !n.Enabled() {
		return nil
	}
	n.mu.Lock()
	if t, ok := n.last[key]; ok && time.Since(t) < n.opt.MinInterval {
		n.mu.Unlock()
		return nil // 静默去重
	}
	n.last[key] = time.Now()
	n.mu.Unlock()

	payload := n.buildPayload(title, body)
	b, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	req, err := http.NewRequest(http.MethodPost, n.opt.WebhookURL, bytes.NewReader(b))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := n.cli.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		return fmt.Errorf("webhook 返回 HTTP %d", resp.StatusCode)
	}
	return nil
}

// buildPayload 按 webhook 类型构造请求体。
func (n *Notifier) buildPayload(title, body string) map[string]any {
	if strings.Contains(n.opt.WebhookURL, "api.telegram.org") {
		text := fmt.Sprintf("*%s*\n%s", title, body)
		p := map[string]any{
			"text":       text,
			"parse_mode": "Markdown",
		}
		if n.opt.ChatID != "" {
			p["chat_id"] = n.opt.ChatID
		}
		return p
	}
	// 通用格式：字段名尽量直白，方便任何中转服务解析
	return map[string]any{
		"source": "litv",
		"title":  title,
		"text":   body,
		"ts":     time.Now().Unix(),
	}
}
