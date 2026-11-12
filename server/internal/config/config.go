// Package config 负责加载与校验 LITV 服务端配置。
//
// 配置来源优先级：命令行 -config > $TRIM_PKGETC/config.yaml > ./config.yaml
// 所有字段都有默认值，用户不写也能跑起来（NAS 场景下用户最怕的就是"装完起不来"）。
package config

import (
	"crypto/rand"
	"encoding/base64"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"time"

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

	// 以下字段不写进 YAML，只用于运行期协作。
	//
	// SecretGenerated：本次密钥是运行时生成的，main 要负责写回磁盘。
	// secretPersistPath：写回的目标文件。
	SecretGenerated   bool   `yaml:"-"`
	secretPersistPath string `yaml:"-"`
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
	// Timezone 用户/频道所在地的 IANA 时区（如 Asia/Shanghai）。
	//
	// 必须有显式时区：节目单按"当地日历日"查询，
	// 用 UTC 零点截断会让东八区用户看到错位 8 小时的节目单。
	// 留空按 UTC 处理（行为确定，不猜）。
	Timezone string `yaml:"timezone"`
	// TrustProxy 是否采信反向代理传来的 X-Forwarded-For / X-Real-IP。
	//
	// 只用于「记录并显示客户端 IP」，**绝不参与任何鉴权判断**——
	// 否则伪造一个头就能冒充别人。
	TrustProxy bool `yaml:"trust_proxy"`
	// TrustedProxies 受信代理网段（CIDR）。
	// 只有直接对端落在这里面时，才采信上面的转发头。
	TrustedProxies []string `yaml:"trusted_proxies"`
}

// Database 数据库配置。当前只支持 SQLite（单机 NAS 最省事）。
type Database struct {
	Path string `yaml:"path"`
}

// Emergency sessions are deliberately separate from long-lived device tokens.
const (
	DefaultAdminTokenHours  = 8
	MaxAdminTokenHours      = 168
	DefaultAdminMaxSessions = 16
	MaxAdminSessions        = 128
)

// Auth 鉴权配置。
type Auth struct {
	Secret           string `yaml:"secret"`             // 流地址签名密钥
	AdminPassword    string `yaml:"admin_password"`     // 兜底管理密码（网关不可用时应急登录）
	TokenHours       int    `yaml:"token_hours"`        // 兼容旧配置；普通设备 token 仍需显式吊销，不施加管理员 TTL
	AdminTokenHours  int    `yaml:"admin_token_hours"`  // 应急管理员会话有效期（1..168 小时，默认 8）
	AdminMaxSessions int    `yaml:"admin_max_sessions"` // 有效管理员会话上限（1..128，默认 16）
	DevMode          bool   `yaml:"dev_mode"`           // 开发模式：无网关头时降级为本地管理员
}

// Stream 流媒体转发配置。
type Stream struct {
	CacheDir        string `yaml:"cache_dir"`
	MaxConcurrent   int    `yaml:"max_concurrent"` // 最大并发流
	BufferSize      int    `yaml:"buffer_size"`    // 上游读取块大小（字节）
	RingSize        int    `yaml:"ring_size"`      // 每频道环形缓冲大小（字节），新客户端从这里起播
	UserAgent       string `yaml:"user_agent"`
	UpstreamTimeout int    `yaml:"upstream_timeout"` // 秒

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
	Enabled bool   `yaml:"enabled"`
	FFmpeg  string `yaml:"ffmpeg"`
	HWAccel string `yaml:"hwaccel"` // qsv | vaapi | none
	Preset  string `yaml:"preset"`
}

// EPG 电子节目单配置。
type EPG struct {
	Enabled     bool     `yaml:"enabled"`
	RefreshCron string   `yaml:"refresh_cron"`
	KeepDays    int      `yaml:"keep_days"`
	DefaultURLs []string `yaml:"default_urls"`
	// MaxBytesMB 单个 XMLTV 的体积上限（A9）。
	// 有些源的 xml.gz 解压后能到几百 MB，不设上限会把内存吃光。
	MaxBytesMB int `yaml:"max_mb"`
	// MaxProgrammes 单次解析保留的节目条数上限（A9）。
	// 超出部分丢弃，避免一个巨无霸源拖垮整库。
	MaxProgrammes int `yaml:"max_programmes"`
}

// MaxBytes 返回体积上限（字节）。<=0 表示不限制。
func (e EPG) MaxBytes() int64 {
	if e.MaxBytesMB <= 0 {
		return 0
	}
	return int64(e.MaxBytesMB) << 20
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
	Enabled     bool   `yaml:"enabled"`
	Dir         string `yaml:"dir"`
	SegmentMB   int    `yaml:"segment_mb"`   // 单段大小
	MaxSegments int    `yaml:"max_segments"` // 最多保留几段
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
	Enabled        bool   `yaml:"enabled"`
	WebhookURL     string `yaml:"webhook_url"`
	ChatID         string `yaml:"chat_id"` // Telegram 用
	MinIntervalMin int    `yaml:"min_interval_minutes"`
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
			Port:         28027,
			Prefix:       "/app/litv",
			ReadTimeout:  30,
			WriteTimeout: 0,
			Timezone:     "Asia/Shanghai",
			// 默认只信任本机回环：反向代理通常和 litvd 在同一台机器上。
			// 这个范围很窄，伪造者必须先能连上本机 127.0.0.1 才可能影响显示。
			TrustProxy:     true,
			TrustedProxies: []string{"127.0.0.1/32", "::1/128"},
		},
		Database: Database{Path: "./data/litv.db"},
		Auth: Auth{
			TokenHours:       24 * 365,
			AdminTokenHours:  DefaultAdminTokenHours,
			AdminMaxSessions: DefaultAdminMaxSessions,
		},
		Stream: Stream{
			CacheDir:      "./data/cache",
			MaxConcurrent: 100,
			BufferSize:    32 * 1024,
			// 6MB ≈ 8Mbps 下 6 秒、25Mbps 下 2 秒。为什么要 6MB 而不是 2MB：
			// 新订阅者是从缓冲里起播的，缓冲必须至少覆盖一个完整 GOP（2–4 秒），
			// 否则只能从 GOP 中间开始、等下一个 IDR 才出画 —— 那就是「起播要转很久圈」。
			RingSize:        6 * 1024 * 1024,
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
			// 默认每小时整点同步一次。用户可在「应用设置」里改小时。
			RefreshCron:   "0 3 * * *",
			MaxBytesMB:    64,
			MaxProgrammes: 200000,
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
		Log:    Log{Level: "info", MaxMB: 10},
	}
}

// Load 读取配置文件；文件不存在时返回默认配置（不报错，方便首次启动）。
func Load(path string) (*Config, error) {
	cfg := Default()
	// 关键：**每一条返回路径**都要先 normalize 再 Validate。
	//
	// 以前这里是 `return cfg, nil`，直接返回未校验的默认值，
	// 导致"配置路径写错/文件不存在"这种最常见的情况反而完全绕过校验，
	// -check 也会假通过。默认值本身也要过一遍校验，口径才一致。
	if path == "" {
		path = DiscoverPath()
	}
	if path == "" {
		cfg.normalize()
		if err := cfg.Validate(); err != nil {
			return nil, err
		}
		return cfg, nil
	}
	cfg.BindSecretPersistPath(path)
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			// 首次启动、配置文件尚未生成：可以用默认值跑，
			// 但必须走同样的校验，不能因为"文件不存在"就跳过。
			cfg.normalize()
			if err := cfg.Validate(); err != nil {
				return nil, err
			}
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
	if c.Auth.AdminTokenHours < 1 || c.Auth.AdminTokenHours > MaxAdminTokenHours {
		return fmt.Errorf("auth.admin_token_hours must be between 1 and 168")
	}
	if c.Auth.AdminMaxSessions < 1 || c.Auth.AdminMaxSessions > MaxAdminSessions {
		return fmt.Errorf("auth.admin_max_sessions must be between 1 and 128")
	}
	if len(c.Auth.AdminPassword) > 256 {
		return fmt.Errorf("auth.admin_password must not exceed 256 bytes")
	}
	if c.Server.Port <= 0 || c.Server.Port > 65535 {
		return fmt.Errorf("server.port 非法: %d", c.Server.Port)
	}
	// B01：时区必须是合法的 IANA 名称。写错了不该静默退化成 UTC——
	// 那会让节目单整体错位，而且用户完全看不出原因。
	if c.Server.Timezone != "" {
		if _, err := time.LoadLocation(c.Server.Timezone); err != nil {
			return fmt.Errorf("server.timezone 非法: %q（需 IANA 名称，如 Asia/Shanghai）", c.Server.Timezone)
		}
	}
	if c.Stream.MaxConcurrent <= 0 {
		c.Stream.MaxConcurrent = 100
	}
	if c.Stream.RingSize < 64*1024 {
		// 下限兜底与默认值保持一致：2MB 在 8Mbps 下只有 2 秒，撑不住一个 GOP。
		c.Stream.RingSize = 6 * 1024 * 1024
	}
	if secretIsPlaceholder(c.Auth.Secret) {
		// 占位值（changeme / 过短 / 空）等于没有密钥，必须换掉。
		// 用 crypto/rand 生成，并由调用方持久化，避免每次重启都变
		// （那样所有已发出的播放地址都会立刻失效）。
		c.Auth.Secret = randomSecret()
		if c.Auth.Secret == "" {
			return fmt.Errorf("无法生成签名密钥：crypto/rand 不可用")
		}
		c.SecretGenerated = true
	}
	return nil
}

// SecretGenerated 标记本次密钥是运行时生成的（原配置里没有或不可用）。
//
// main 看到这个标记要把配置写回磁盘，否则重启后密钥又变一遍，
// 用户正在看的播放地址、客户端缓存的地址全部失效。
func (c *Config) NeedsSecretPersist() bool { return c.SecretGenerated && c.secretPersistPath != "" }

// BindSecretPersistPath 告诉配置"密钥需要写回哪个文件"。
func (c *Config) BindSecretPersistPath(path string) { c.secretPersistPath = path }

// PersistSecret 把当前密钥写回配置文件。
func (c *Config) PersistSecret() error {
	if c.secretPersistPath == "" {
		return fmt.Errorf("未绑定配置路径，无法保存密钥")
	}
	if err := c.Save(c.secretPersistPath); err != nil {
		return err
	}
	c.SecretGenerated = false
	return nil
}

// randomSecret 生成流签名密钥。
//
// 必须用 crypto/rand：签名密钥决定流地址不可伪造，
// 用 PID 做种子的线性同余发生器可以被离线推导——
// 攻击者只要知道进程号范围，就能算出密钥并自行签发任意播放地址。
func randomSecret() string {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		// 拿不到密码学随机源时不能悄悄降级成弱密钥：
		// 返回空串，让调用方按"密钥缺失"处理（Validate 会据此报错）。
		return ""
	}
	return base64.RawURLEncoding.EncodeToString(b)
}

// secretIsPlaceholder 判断密钥是否是明显的占位值。
//
// 用户可能从示例配置里复制了 "changeme" / "secret" 之类，
// 这种值等于没有密钥，必须拒绝。
func secretIsPlaceholder(v string) bool {
	s := strings.ToLower(strings.TrimSpace(v))
	if s == "" {
		return true
	}
	for _, bad := range []string{
		"changeme", "change-me", "secret", "password", "your-secret",
		"your_secret", "xxx", "todo", "test", "example", "placeholder",
	} {
		if s == bad {
			return true
		}
	}
	// 长度过短同样视为不安全。
	return len(s) < 16
}

// 日志级别的合法取值。管理台保存设置时按这张表校验。
var allowedLogLevels = map[string]bool{
	"debug": true,
	"info":  true,
	"warn":  true,
	"error": true,
}

// NormalizeLogLevel 把任意大小写的级别归一化；不合法时返回空串。
func NormalizeLogLevel(v string) string {
	s := strings.ToLower(strings.TrimSpace(v))
	if !allowedLogLevels[s] {
		return ""
	}
	return s
}

// SlogLevel 把配置里的日志级别转成 slog.Level。
func SlogLevel(v string) slog.Level {
	switch NormalizeLogLevel(v) {
	case "debug":
		return slog.LevelDebug
	case "warn":
		return slog.LevelWarn
	case "error":
		return slog.LevelError
	default:
		return slog.LevelInfo
	}
}

// 日志级别运行期可调：管理台保存设置后要用新级别继续输出，
// 而 slog 默认 handler 的级别是建的时候就定死的。
// 用 LevelVar 才能在不重建 handler 的前提下切级别。
var logLevelVar slog.LevelVar

// InstallLogger 用当前配置的级别安装全局 slog handler。
//
// 只装一次；之后再调用只会调整级别。这样管理台改 log_level 能立即生效，
// 不需要重启服务（重启才生效的字段会在响应里明确标出来）。
func InstallLogger(level string) {
	logLevelVar.Set(SlogLevel(level))
	h := slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: &logLevelVar})
	slog.SetDefault(slog.New(h))
}

// SetLogLevel 运行期切换日志级别，返回是否接受该值。
func SetLogLevel(level string) bool {
	n := NormalizeLogLevel(level)
	if n == "" {
		return false
	}
	logLevelVar.Set(SlogLevel(n))
	return true
}

// Save 把配置写回 YAML 文件。
//
// 写入前先在目标目录建临时文件再原子 rename，避免写一半断电留下破损配置
// （fnOS 上服务起不来时，用户几乎没有办法自己修）。
// Clone 深拷贝一份配置。
//
// 保存流程要用它：先在副本上改、写盘成功后再发布到 live，
// 这样落盘失败时内存状态和日志级别都保持原样。
// 用 yaml 往返而不是手写字段拷贝——加了新字段不会漏。
func (c *Config) Clone() *Config {
	if c == nil {
		return nil
	}
	data, err := yaml.Marshal(c)
	if err != nil {
		return nil
	}
	var out Config
	if err := yaml.Unmarshal(data, &out); err != nil {
		return nil
	}
	return &out
}

func (c *Config) Save(path string) error {
	if path == "" {
		return fmt.Errorf("配置路径为空，无法保存")
	}
	data, err := yaml.Marshal(c)
	if err != nil {
		return fmt.Errorf("序列化配置失败: %w", err)
	}
	dir := filepath.Dir(path)
	tmp, err := os.CreateTemp(dir, ".config-*.yaml")
	if err != nil {
		return fmt.Errorf("创建临时配置文件失败: %w", err)
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName)

	// 配置里可能有管理密码和签名密钥，权限必须收紧。
	if err := tmp.Chmod(0o600); err != nil {
		tmp.Close()
		return fmt.Errorf("设置配置文件权限失败: %w", err)
	}
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return fmt.Errorf("写入配置失败: %w", err)
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return fmt.Errorf("同步配置失败: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("关闭临时配置文件失败: %w", err)
	}
	if err := os.Rename(tmpName, path); err != nil {
		return fmt.Errorf("替换配置文件失败: %w", err)
	}
	return nil
}

// MustLoadForTest 供测试读取配置；失败直接 panic，避免测试里充斥错误处理。
//
// 放在非 test 文件里是因为要被 api 包的测试跨包调用。
func MustLoadForTest(t interface{ Fatalf(string, ...any) }, path string) *Config {
	c, err := Load(path)
	if err != nil {
		t.Fatalf("加载配置 %s 失败: %v", path, err)
	}
	return c
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
