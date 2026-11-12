// Package dlna 提供一个最小的 UPnP/DLNA MediaServer。
//
// 目的：让电视自带的播放器、Kodi、音箱等**不用装我们的 App** 就能直接放直播。
// 场景是"客人来了想看个台，但他手机上没装 LITV"。
//
// 实现范围（刻意做小）：
//   - SSDP 发现：应答 M-SEARCH，让设备能搜到我们
//   - Device Description：描述自己是 MediaServer:1
//   - ContentDirectory:1：Browse 返回"分组 → 频道"两级目录
//   - ConnectionManager:1：最小实现（不少客户端会先调它）
//
// 不做的：转码协商、DLNA 的 ProfileID 细分、Seek 能力声明。
// 直播场景这些用不上，做了反而增加不兼容面。
package dlna

import (
	"context"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

// ChannelSource 是 DLNA 需要的数据来源，由上层注入，避免 dlna 包依赖 store。
type ChannelSource interface {
	// Groups 返回分组名与频道数
	Groups(ctx context.Context) ([]GroupInfo, error)
	// ChannelsInGroup 返回某分组的频道
	ChannelsInGroup(ctx context.Context, group string) ([]Item, error)
	// StreamURL 返回某频道的可播放地址（HLS 优先）
	StreamURL(ctx context.Context, channelID int64) (string, error)
}

// GroupInfo 是一个分组。
type GroupInfo struct {
	Name  string
	Count int
}

// Item 是一个频道。
type Item struct {
	ID   int64
	Name string
	Logo string
}

// Options DLNA 参数。
type Options struct {
	// HTTPPort 是服务端对外端口（电视要能访问到）
	HTTPPort int
	// UDN 设备唯一标识
	UDN string
	// FriendlyName 显示名
	FriendlyName string
	// AdvertiseIP 手动指定对外 IP；空则自动探测
	AdvertiseIP string
}

// Server 是 DLNA 服务。
type Server struct {
	opt  Options
	src  ChannelSource
	base string // http://ip:port

	mu   sync.Mutex
	conn *net.UDPConn
}

// New 创建。
func New(opt Options, src ChannelSource) *Server {
	if opt.FriendlyName == "" {
		opt.FriendlyName = "LITV 直播"
	}
	if opt.UDN == "" {
		opt.UDN = "uuid:6c1f7d2a-4b3e-4f5a-9c8d-1e2f3a4b5c6d"
	}
	return &Server{opt: opt, src: src}
}

// BaseURL 返回设备描述的根地址。
func (s *Server) BaseURL() string {
	if s.base != "" {
		return s.base
	}
	ip := s.opt.AdvertiseIP
	if ip == "" {
		ip = localIPv4()
	}
	s.base = fmt.Sprintf("http://%s:%d", ip, s.opt.HTTPPort)
	return s.base
}

// Handler 返回挂到 HTTP 路由上的处理器。
// 注意：路径是相对的，调用方用 r.Mount("/dlna", s.Handler()) 挂上去。
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/desc.xml", s.handleDesc)
	mux.HandleFunc("/cds.xml", s.handleCDSScpd)
	mux.HandleFunc("/cds/control", s.handleCDSControl)
	mux.HandleFunc("/cds/event", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	mux.HandleFunc("/cms.xml", s.handleCMSScpd)
	mux.HandleFunc("/cms/control", s.handleCMSControl)
	mux.HandleFunc("/cms/event", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	return mux
}

// Run 启动 SSDP 应答。ctx 取消时退出。
//
// 注意：组播在某些容器/虚拟网络里不可用，失败只记日志，不影响 HTTP 部分。
func (s *Server) Run(ctx context.Context) {
	addr := &net.UDPAddr{IP: net.ParseIP("239.255.255.250"), Port: 1900}
	conn, err := net.ListenMulticastUDP("udp4", nil, addr)
	if err != nil {
		slog.Warn("DLNA: 无法监听 SSDP 组播，局域网可能搜不到（HTTP 部分仍可用）", "err", err)
		return
	}
	if err := conn.SetReadBuffer(64 * 1024); err != nil {
		slog.Debug("DLNA: 设置读缓冲失败", "err", err)
	}
	s.mu.Lock()
	s.conn = conn
	s.mu.Unlock()

	slog.Info("DLNA 已启动", "base", s.BaseURL(), "name", s.opt.FriendlyName)

	go func() {
		<-ctx.Done()
		s.mu.Lock()
		if s.conn != nil {
			_ = s.conn.Close()
		}
		s.mu.Unlock()
	}()

	buf := make([]byte, 4096)
	for {
		n, src, err := conn.ReadFromUDP(buf)
		if err != nil {
			select {
			case <-ctx.Done():
				return
			default:
			}
			continue
		}
		msg := string(buf[:n])
		if !strings.HasPrefix(msg, "M-SEARCH") {
			continue
		}
		st := headerValue(msg, "ST")
		if st == "" {
			continue
		}
		// 只应答 MediaServer 与 ssdp:all，别乱回包
		if !(strings.Contains(st, "MediaServer") || st == "ssdp:all" || strings.Contains(st, "upnp:rootdevice")) {
			continue
		}
		resp := s.buildSearchResponse(st)
		_, _ = conn.WriteToUDP([]byte(resp), src)
	}
}

func (s *Server) buildSearchResponse(st string) string {
	usn := s.opt.UDN
	if st != "ssdp:all" {
		usn = s.opt.UDN + "::" + st
	}
	lines := []string{
		"HTTP/1.1 200 OK",
		"CACHE-CONTROL: max-age=1800",
		"DATE: " + time.Now().UTC().Format(http.TimeFormat),
		"EXT:",
		"LOCATION: " + s.BaseURL() + "/dlna/desc.xml",
		"SERVER: Linux/1.0 UPnP/1.0 LITV/1.0",
		"ST: " + st,
		"USN: " + usn,
		"",
		"",
	}
	return strings.Join(lines, "\r\n")
}

// headerValue 从 SSDP 报文里取某个头（大小写不敏感）。
func headerValue(msg, name string) string {
	up := strings.ToUpper(name) + ":"
	for _, line := range strings.Split(msg, "\n") {
		l := strings.TrimRight(line, "\r")
		if strings.HasPrefix(strings.ToUpper(l), up) {
			return strings.TrimSpace(l[len(up):])
		}
	}
	return ""
}

// localIPv4 找一个非回环 IPv4 作为对外地址。
func localIPv4() string {
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return "127.0.0.1"
	}
	for _, a := range addrs {
		if ipnet, ok := a.(*net.IPNet); ok && !ipnet.IP.IsLoopback() {
			if v4 := ipnet.IP.To4(); v4 != nil {
				return v4.String()
			}
		}
	}
	return "127.0.0.1"
}
