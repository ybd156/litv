// Package logo 管理频道台标（logo）。
//
// 为什么要服务端管：
//   - 源里的 tvg-logo 大多是外链，挂了/超时/防盗链很常见，
//     客户端直接去拉就是一片空白或者转半天。
//   - 同一个台标会被 N 个终端重复下载。
//   - 用户可能想换成自己的图。
//
// 做法：下载到本地磁盘，之后统一由服务端 /logo/{id} 出图，
// 频道表的 logo 字段改写成这个站内地址，所有终端自动生效。
package logo

import (
	"context"
	"crypto/sha1"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/litv/litv/server/internal/safedial"
)

// logoClient 是下载外链台标用的客户端。
//
// 不用 http.DefaultClient：DefaultClient 没有超时、也不受任何地址策略约束。
// 台标地址来自源清单（外部输入），所以必须过 safedial（默认档）：
// 放行 LAN（自建图床），拒本机/链路本地/元数据端点。
//
// 做成包级变量复用：每次请求新建 Transport 会漏空闲连接。
var logoClient = safedial.DefaultPolicy().Client(15 * time.Second)

// MaxBytes 单个台标大小上限。台标是缩略图，2MB 已经非常宽松。
const MaxBytes = 2 << 20

// 允许的图片类型 → 扩展名。刻意不支持 SVG：
// SVG 可以内嵌脚本，用 <img> 之外的方式打开会有 XSS 风险。
var allowedTypes = map[string]string{
	"image/png":                ".png",
	"image/jpeg":               ".jpg",
	"image/jpg":                ".jpg",
	"image/webp":               ".webp",
	"image/gif":                ".gif",
	"image/bmp":                ".bmp",
	"image/x-icon":             ".ico",
	"image/vnd.microsoft.icon": ".ico",
}

// Store 台标存储。
type Store struct {
	dir string
}

// New 创建，dir 是存放目录（会被自动创建）。
func New(dir string) *Store {
	_ = os.MkdirAll(dir, 0o755)
	return &Store{dir: dir}
}

// Dir 返回存储目录。
func (s *Store) Dir() string { return s.dir }

// Path 返回某频道已缓存的台标路径；没有返回空串。
func (s *Store) Path(channelID int64) string {
	matches, _ := filepath.Glob(filepath.Join(s.dir, fmt.Sprintf("%d.*", channelID)))
	if len(matches) == 0 {
		return ""
	}
	return matches[0]
}

// Exists 报告是否已有本地台标。
func (s *Store) Exists(channelID int64) bool { return s.Path(channelID) != "" }

// SaveBytes 保存上传的图片字节，返回站内访问路径。
func (s *Store) SaveBytes(channelID int64, data []byte, contentType string) (string, error) {
	ext, ok := allowedTypes[strings.ToLower(strings.TrimSpace(strings.Split(contentType, ";")[0]))]
	if !ok {
		// 有些服务器不给 Content-Type，靠内容嗅探兜底
		ext = sniffExt(data)
		if ext == "" {
			return "", fmt.Errorf("不支持的图片类型：%s", contentType)
		}
	}
	if len(data) == 0 {
		return "", fmt.Errorf("图片内容为空")
	}
	if len(data) > MaxBytes {
		return "", fmt.Errorf("图片超过 %d KB", MaxBytes/1024)
	}

	// 先删旧的（扩展名可能不同，留着会变成两份）
	s.Delete(channelID)

	dst := filepath.Join(s.dir, strconv.FormatInt(channelID, 10)+ext)
	if err := os.WriteFile(dst, data, 0o644); err != nil {
		return "", err
	}
	return "/logo/" + strconv.FormatInt(channelID, 10), nil
}

// SaveFromURL 下载远端图片并保存。
func (s *Store) SaveFromURL(ctx context.Context, channelID int64, rawURL, userAgent string) (string, error) {
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return "", err
	}
	if userAgent == "" {
		userAgent = "LITV/1.0 (logo-fetcher)"
	}
	req.Header.Set("User-Agent", userAgent)
	req.Header.Set("Accept", "image/*,*/*")

	resp, err := logoClient.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("下载失败：HTTP %d", resp.StatusCode)
	}

	// 多读 1 字节用来判断是否超限
	data, err := io.ReadAll(io.LimitReader(resp.Body, MaxBytes+1))
	if err != nil {
		return "", err
	}
	if len(data) > MaxBytes {
		return "", fmt.Errorf("图片超过 %d KB", MaxBytes/1024)
	}
	return s.SaveBytes(channelID, data, resp.Header.Get("Content-Type"))
}

// Delete 删除某频道的本地台标。
func (s *Store) Delete(channelID int64) {
	matches, _ := filepath.Glob(filepath.Join(s.dir, fmt.Sprintf("%d.*", channelID)))
	for _, m := range matches {
		_ = os.Remove(m)
	}
}

// ETag 给 HTTP 缓存用：文件大小 + 修改时间足够区分。
func (s *Store) ETag(channelID int64) string {
	p := s.Path(channelID)
	if p == "" {
		return ""
	}
	st, err := os.Stat(p)
	if err != nil {
		return ""
	}
	h := sha1.Sum([]byte(fmt.Sprintf("%d-%d", st.Size(), st.ModTime().UnixNano())))
	return `"` + hex.EncodeToString(h[:8]) + `"`
}

// sniffExt 按文件头判断类型（Content-Type 缺失时兜底）。
func sniffExt(data []byte) string {
	if len(data) < 12 {
		return ""
	}
	switch {
	case data[0] == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G':
		return ".png"
	case data[0] == 0xFF && data[1] == 0xD8:
		return ".jpg"
	case string(data[0:4]) == "GIF8":
		return ".gif"
	case string(data[0:4]) == "RIFF" && string(data[8:12]) == "WEBP":
		return ".webp"
	case data[0] == 'B' && data[1] == 'M':
		return ".bmp"
	}
	return ""
}
