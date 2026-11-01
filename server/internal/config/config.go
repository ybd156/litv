// Package config 负责加载与校验 LITV 服务端配置。
//
// 配置来源优先级：命令行 -config > $TRIM_PKGETC/config.yaml > ./config.yaml
// 所有字段都有默认值，用户不写也能跑起来（NAS 场景下用户最怕的就是"装完起不来"）。
package config

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"gopkg.in/yaml.v3"
)

// Config 是服务端全部配置。
type Config struct {
	Server    Server    `yaml:"server"`
	Database  Database  `yaml:"database"`
	Auth      Auth      `yaml:"auth"`
	Stream    Stream    `yaml:"stream"`
	Transcode Transcode `yaml:"transcode"`
	EPG       EPG       `yaml:"epg"`
	Probe     Probe     `yaml:"probe"`
	Timeshift Timeshift `yaml:"timeshift"`
	Remux     Remux     `yaml:"remux"`
	Record    Record    `yaml:"record"`
	Alert     Alert     `yaml:"alert"`
	DLNA      DLNA      `yaml:"dlna"`
	Log       Log       `yaml:"log"`
}

// Server 网络监听相关。
//
// 双入口设计：
//   - GatewaySocket: 飞牛统一网关用的 Unix Socket，只由网关转发，天然带 X-Trim-* 身份头
//   - Port:          终端 App（TV/手机/iOS）用的 TCP 端口，自建 device token 鉴权
type Server struct {
	Host          string `yaml:"host"`
	Port          int    `yaml:"port"`
	GatewaySocket string `yaml:"gateway_socket"` // 例如 ${TRIM_APPDEST}/app.sock
	Prefix        string `yaml:"prefix"`         // 例如 /app/litv
	ReadTimeout   int    `yaml:"read_timeout"`   // 秒
	WriteTimeout  int    `yaml:"write_timeout"`  // 秒；流式接口设为 0 表示不限制
}

// Database 数据库配置。当前只支持 SQLite（单机 NAS 最省事）。
type Database struct {
	Path string `yaml:"path"`
}

// Auth 鉴权配置。
type Auth struct {
	Secret        string `yaml:"secret"`         // 流地址签名密钥
	AdminPassword string `yaml:"admin_password"` // 兜底管理密码（网关不可用时应急登录）
	TokenHours    int    `yaml:"token_hours"`    // 终端 token 有效期
	DevMode       bool   `yaml:"dev_mode"`       // 开发模式：无网关头时降级为本地管理员
}

// Stream 流媒体转发配置。
type Stream struct {
	CacheDir      string `yaml:"cache_dir"`
	MaxConcurrent int    `yaml:"max_concurrent"` // 最大并发流
	BufferSize    int    `yaml:"buffer_size"`    // 上游读取块大小（字节）
	RingSize      int    `yaml:"ring_size"`      // 每频道环形缓冲大小（字节），新客户端从这里起播
	UserAgent     string `yaml:"user_agent"`
	UpstreamTimeout int  `yaml:"upstream_timeout"` // 秒

	// DirectEnabled 允许客户端直连上游源地址（不经过服务端中转）。
	//
	// 好处：省掉 NAS 的带宽和 CPU，起播更快，服务端挂了也不影响已开播的客户端。
	// 代价：客户端要能访问到源站（跨网/内网源可能不行），
	//       而且源站的 UA / Referer 校验得由客户端自己带。
	// 所以做成"允许"而不是"强制"：客户端请求时带 direct=1 才直连，
	// 否则仍走服务端中转。
	DirectEnabled bool `yaml:"direct_enabled"`
}

// Transcode 转码配置。默认关闭——N100 上纯转发才省电。
type Transcode struct {
	Enabled   bool   `yaml:"enabled"`
	FFmpeg    string `yaml:"ffmpeg"`
	HWAccel   string `yaml:"hwaccel"` // qsv | vaapi | none
	Preset    string `yaml:"preset"`
}

// EPG 电子节目单配置。
type EPG struct {
	Enabled     bool     `yaml:"enabled"`
	RefreshCron string   `yaml:"refresh_cron"`
	KeepDays    int      `yaml:"keep_days"`
	DefaultURLs []string `yaml:"default_urls"`
}

// Probe 源健康探测配置。
//
// 这是「多源自动择优」的数据来源：定期探一遍所有线路，
// 记下可用性和延迟，BestStream 就能挑出当下最快的那条。
type Probe struct {
	Enabled     bool `yaml:"enabled"`
	IntervalMin int  `yaml:"interval_minutes"`
	TimeoutSec  int  `yaml:"timeout_seconds"`
	Concurrency int  `yaml:"concurrency"`
}

// Timeshift 时移/回看配置。
//
// 实现方式：扇出层（stream.Hub）读到的字节顺手 tee 一份到磁盘，
// 所以只要有人看过这个台，就有回看缓冲——不需要额外开录制任务。
type Timeshift struct {
	Enabled      bool   `yaml:"enabled"`
	Dir          string `yaml:"dir"`
	SegmentMB    int    `yaml:"segment_mb"`    // 单段大小
	MaxSegments  int    `yaml:"max_segments"`  // 最多保留几段
}

// Remux 把裸 TS 转封装成 HLS 的配置。
//
// 为什么需要：iOS 的 AVPlayer 播不了裸 TS。只转封装不转码（-c copy），
// 码流原样搬，N100 上几乎不吃 CPU。
type Remux struct {
	Enabled bool `yaml:"enabled"`
	// FFmpeg 转封装用的 ffmpeg。留空则回落到 transcode.ffmpeg。
	//
	// 单独给 remux 一个入口，是因为「转码」和「转封装」是两件事：
	// 用户只想让 iOS 能播，不需要开转码，却要去 transcode 段里改 ffmpeg，
	// 报错信息还会说"转封装已配置但 ffmpeg 不可用"——非常劝退。
	FFmpeg         string `yaml:"ffmpeg"`
	Dir            string `yaml:"dir"`
	SegmentSeconds int    `yaml:"segment_seconds"`
	PlaylistSize   int    `yaml:"playlist_size"`
	IdleTimeoutSec int    `yaml:"idle_timeout_seconds"`
}

// FFmpegPath 返回转封装该用的 ffmpeg：优先 remux.ffmpeg，其次 transcode.ffmpeg。
func (c *Config) FFmpegPath() string {
	if c.Remux.FFmpeg != "" {
		return c.Remux.FFmpeg
	}
	return c.Transcode.FFmpeg
}

// Record 录制中心配置。
//
// 文件默认落在 cache 目录；想长期保存就指到飞牛的共享目录
// （config/resource 里声明的 data-share，比如 /vol1/litv/recordings）。
type Record struct {
	Enabled bool   `yaml:"enabled"`
	Dir     string `yaml:"dir"`
}

// Alert 源失效告警配置。
//
// 家里电视某个台放不出来，用户不会去看管理台。
// 与其让人自己发现，不如服务端主动推一条。
type Alert struct {
	Enabled     bool   `yaml:"enabled"`
	WebhookURL  string `yaml:"webhook_url"`
	ChatID      string `yaml:"chat_id"`       // Telegram 用
	MinIntervalMin int `yaml:"min_interval_minutes"`
}

// DLNA 局域网发现配置。
//
// 让电视自带的播放器 / Kodi / 音箱不用装我们的 App 就能直接放直播。
type DLNA struct {
	Enabled      bool   `yaml:"enabled"`
	FriendlyName string `yaml:"friendly_name"`
	// AdvertiseIP 手动指定对外 IP；空则自动探测第一个非回环 IPv4
	AdvertiseIP string `yaml:"advertise_ip"`
}

// Log 日志配置。
type Log struct {
	Level string `yaml:"level"`
	File  string `yaml:"file"`
	MaxMB int    `yaml:"max_mb"`
}

// Default 返回一份可直接运行的默认配置。
func Default() *Config {
	return &Config{
		Server: Server{
			Host:         "0.0.0.0",
			Port:         9527,
			Prefix:       "/app/litv",
			ReadTimeout:  30,
			WriteTimeout: 0,
		},
		Database: Database{Path: "./data/litv.db"},
		Auth: Auth{
			TokenHours: 24 * 365,
		},
		Stream: Stream{
			CacheDir:        "./data/cache",
			MaxConcurrent:   100,
			BufferSize:      32 * 1024,
			RingSize:        2 * 1024 * 1024,
			UserAgent:       "LITV/1.0 (fnOS)",
			UpstreamTimeout: 15,
			DirectEnabled:   true,
		},
		Transcode: Transcode{
			Enabled: false,
			FFmpeg:  "ffmpeg",
			HWAccel: "qsv",
			Preset:  "veryfast",
		},
		EPG: EPG{
			Enabled:  true,
			KeepDays: 3,
		},
		Probe: Probe{
			Enabled:     true,
			IntervalMin: 15,
			TimeoutSec:  8,
			Concurrency: 6,
		},
		Timeshift: Timeshift{
			Enabled:     true,
			SegmentMB:   64,
			MaxSegments: 12,
		},
		Remux: Remux{
			Enabled:        true,
			SegmentSeconds: 2,
			PlaylistSize:   6,
			IdleTimeoutSec: 60,
		},
		Record: Record{Enabled: true},
		Alert:  Alert{Enabled: false, MinIntervalMin: 30},
		DLNA:   DLNA{Enabled: false, FriendlyName: "LITV 直播"},
		Log: Log{Level: "info", MaxMB: 10},
	}
}

// Load 读取配置文件；文件不存在时返回默认配置（不报错，方便首次启动）。
func Load(path string) (*Config, error) {
	cfg := Default()
	if path == "" {
		path = DiscoverPath()
	}
	if path == "" {
		return cfg, nil
	}
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return cfg, nil
		}
		return nil, fmt.Errorf("读取配置 %s 失败: %w", path, err)
	}
	// 用默认值打底再反序列化，避免用户只写半个文件就导致零值覆盖默认值。
	if err := yaml.Unmarshal(data, cfg); err != nil {
		return nil, fmt.Errorf("解析配置 %s 失败: %w", path, err)
	}
	cfg.normalize()
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	return cfg, nil
}

// DiscoverPath 按 fnOS 环境变量与当前目录寻找配置文件。
func DiscoverPath() string {
	if etc := os.Getenv("TRIM_PKGETC"); etc != "" {
		p := filepath.Join(etc, "config.yaml")
		if _, err := os.Stat(p); err == nil {
			return p
		}
	}
	for _, p := range []string{"config.yaml", "./etc/config.yaml"} {
		if _, err := os.Stat(p); err == nil {
			return p
		}
	}
	return ""
}

// normalize 处理环境变量与路径归一化。
func (c *Config) normalize() {
	// fnOS 的运行时目录全部走环境变量，避免硬编码 /vol1。
	if c.Database.Path == "" || strings.HasPrefix(c.Database.Path, "./") {
		if v := os.Getenv("TRIM_PKGVAR"); v != "" && strings.HasPrefix(c.Database.Path, "./") {
			c.Database.Path = filepath.Join(v, strings.TrimPrefix(c.Database.Path, "./"))
		}
	}
	if c.Stream.CacheDir == "" {
		c.Stream.CacheDir = filepath.Join(filepath.Dir(c.Database.Path), "cache")
	}
	// 网关 socket 默认放程序目录，和 fnOS 官方示例一致。
	if c.Server.GatewaySocket == "" {
		if dest := os.Getenv("TRIM_APPDEST"); dest != "" {
			c.Server.GatewaySocket = filepath.Join(dest, "app.sock")
		}
	}
	c.Server.Prefix = "/" + strings.Trim(c.Server.Prefix, "/")
	if c.Server.Prefix == "/" {
		c.Server.Prefix = ""
	}
}

// Validate 做最小必要校验，宁可启动失败也不要带病运行。
func (c *Config) Validate() error {
	if c.Server.Port <= 0 || c.Server.Port > 65535 {
		return fmt.Errorf("server.port 非法: %d", c.Server.Port)
	}
	if c.Stream.MaxConcurrent <= 0 {
		c.Stream.MaxConcurrent = 100
	}
	if c.Stream.RingSize < 64*1024 {
		c.Stream.RingSize = 2 * 1024 * 1024
	}
	if c.Auth.Secret == "" {
		// 没配密钥就用一个进程级随机值：能用，但重启后旧签名 URL 失效。
		c.Auth.Secret = randomSecret()
	}
	return nil
}

func randomSecret() string {
	const alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	b := make([]byte, 48)
	seed := os.Getpid()
	for i := range b {
		seed = seed*1103515245 + 12345
		b[i] = alphabet[(seed>>16)&0x7fffffff%len(alphabet)]
	}
	return string(b)
}

// EnsureDirs 创建运行所需目录。
func (c *Config) EnsureDirs() error {
	for _, dir := range []string{
		filepath.Dir(c.Database.Path),
		c.Stream.CacheDir,
	} {
		if dir == "" || dir == "." {
			continue
		}
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return fmt.Errorf("创建目录 %s 失败: %w", dir, err)
		}
	}
	return nil
}
