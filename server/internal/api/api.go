// Package api 提供 HTTP 接口层。
//
// 两套入口共用同一个路由：
//   - 网关入口（viaGateway=true）：请求带 /app/litv 前缀，身份来自 X-Trim-* 头
//   - TCP 入口（viaGateway=false）：终端 App 直连，身份来自 device token
//
// 关键安全设计：X-Trim-* 头只在 viaGateway=true 时才被信任。
// 否则任何能直连 TCP 端口的人都能伪造管理员身份。
package api

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"

	"github.com/litv/litv/server/internal/alert"
	"github.com/litv/litv/server/internal/auth"
	"github.com/litv/litv/server/internal/config"
	"github.com/litv/litv/server/internal/dlna"
	"github.com/litv/litv/server/internal/logo"
	"github.com/litv/litv/server/internal/record"
	"github.com/litv/litv/server/internal/remote"
	"github.com/litv/litv/server/internal/remux"
	"github.com/litv/litv/server/internal/safedial"
	"github.com/litv/litv/server/internal/store"
	"github.com/litv/litv/server/internal/stream"
	"github.com/litv/litv/server/internal/timeshift"
)

// API 聚合所有依赖。
type API struct {
	cfg          *config.Config
	st           *store.Store
	hub          *stream.Hub
	remote       *remote.Hub
	ts           *timeshift.Manager
	rx           *remux.Manager
	rec          *record.Manager
	alert        *alert.Notifier
	dlna         *dlna.Server
	logos        *logo.Store
	signer       *auth.Signer
	loginLimiter *auth.LoginLimiter
	authAudit    *authAuditGate
	now          func() time.Time

	// deviceLimiter 设备注册/绑定用的独立限流器。
	//
	// 绝不能和 loginLimiter 共用桶（见 New 里的注释）。
	deviceLimiter *auth.LoginLimiter

	// 运行时事实（由 main 注入）
	actualPort int
	ffmpegPath string
	startAt    time.Time
	version    string

	// cfgPath 是配置文件的真实路径（由 main 注入）。
	//
	// 管理台保存设置时要写回这个文件。路径不能靠 DiscoverPath 反推：
	// 启动时可能是用 -config 显式指定的，反推会写到别的文件去，
	// 表现就是"保存成功但重启后设置没了"。
	cfgPath string

	// recordDir 是录制目录的**规范化**路径（由 main 注入）。
	//
	// 为什么不能直接读 cfg.Record.Dir：main 会在配置为空时兜底成
	// <cache>/recordings，而那个兜底值从没回写进 cfg。
	// 于是 deleteRecording 的"路径必须在录制目录内"校验在默认配置下
	// 恒为假（Clean("") == "."），结果是 DB 行删了、文件永久残留。
	recordDir string

	// dialPolicy 是出站地址策略（SSRF 防线）。
	//
	// 生产永远是 safedial.DefaultPolicy()：放行 RFC1918/ULA（LAN 源是产品功能），
	// 拒绝 loopback / 链路本地 / CGNAT / 本机网卡地址（挡住"打 NAS 自己"与云元数据）。
	//
	// 刻意是未导出字段、也没有 Setter：生产代码不存在任何放宽它的入口
	// （不读 config、不读环境变量）。测试在同包内直接赋值成
	// Policy{AllowLoopback:true}——httptest 服务器都监听回环。
	dialPolicy safedial.Policy

	// segClient 是分片代理专用的出站客户端。
	//
	// 单独一个 client 而不是复用 fetch 的：分片是**持续流式**传输，
	// 和"抓一个小清单"的超时语义完全不同（见 liveSegment 的注释）。
	segClient *http.Client

	// segAuditAt 记录"上次为某个频道记过私网分片审计"的时间。
	// 每个分片都写一条会把 audit_logs 刷爆，所以按频道 + 时间窗去重。
	segAuditMu sync.Mutex
	segAuditAt map[int64]time.Time

	// 投屏设备发现缓存。
	//
	// tvDLNAcast / tvDLNAstop 要用它做白名单比对（control_url 必须是
	// 本进程真的发现过的设备），而 SSDP 扫描一次要 ~3 秒——
	// 放进 cast 路径会把投屏起播从"几乎瞬间"拉到"等三秒"。
	rendererMu   sync.Mutex
	rendererAt   time.Time
	rendererList []dlna.Renderer
}

// New 创建 API。
func New(cfg *config.Config, st *store.Store, hub *stream.Hub, version string) *API {
	a := &API{
		cfg:          cfg,
		st:           st,
		hub:          hub,
		remote:       remote.NewHub(),
		signer:       auth.NewSigner(cfg.Auth.Secret),
		loginLimiter: auth.NewLoginLimiter(),
		// 设备注册用**独立**的限流器实例。
		//
		// 绝不能复用 loginLimiter：那是"IP 5/分 + 全局 30/分"的共享桶，
		// 复用等于让攻击者用注册请求把全局额度吃光，
		// 顺手把应急管理员登录也一起饿死（那是网关挂掉时唯一的后路）。
		deviceLimiter: auth.NewLoginLimiter(),
		authAudit:     &authAuditGate{},
		now:           time.Now,
		startAt:       time.Now(),
		version:       version,
		dialPolicy:    safedial.DefaultPolicy(),
	}
	a.segClient = &http.Client{Transport: a.segTransport()}
	return a
}

// SetTimeshift 注入时移管理器（main 里创建，因为 Hub 也要用它做 tee）。
func (a *API) SetTimeshift(m *timeshift.Manager) { a.ts = m }

// SetRemux 注入转封装管理器。
func (a *API) SetRemux(m *remux.Manager) { a.rx = m }

// SetRecord 注入录制管理器。
func (a *API) SetRecord(m *record.Manager) { a.rec = m }

// SetAlert 注入告警通知器。
func (a *API) SetAlert(n *alert.Notifier) { a.alert = n }

// SetDLNA 注入 DLNA 服务（同时把路由挂到 /dlna/*）。
func (a *API) SetDLNA(s *dlna.Server) { a.dlna = s }

// SetLogoStore 注入台标存储（同时启用 /logo/{id} 出图）。
func (a *API) SetLogoStore(s *logo.Store) { a.logos = s }

// SetRuntime 注入运行时事实：实际监听端口、解析到的 ffmpeg 路径。
//
// 为什么要单独注入：配置里的端口可能被占用后自动顺延，
// 配置里的 ffmpeg 可能只是个名字而实际解析到别的路径。
// 管理台要显示"真实情况"，否则用户按配置里的端口去填 TV 会连不上。
func (a *API) SetRuntime(actualPort int, ffmpegPath string) {
	a.actualPort = actualPort
	a.ffmpegPath = ffmpegPath
}

// SetConfigPath 注入真实配置文件路径，供管理台保存设置时写回。
func (a *API) SetConfigPath(path string) { a.cfgPath = path }

// SetRecordDir 注入录制目录的规范化路径。
//
// main 会在 cfg.Record.Dir 为空时兜底成 <cache>/recordings，
// 但那个兜底值不回写 cfg。所以这里必须显式注入，
// 否则 deleteRecording 的"路径属于录制目录"校验拿不到真实根目录。
func (a *API) SetRecordDir(dir string) { a.recordDir = dir }

// segTransport 构造分片代理专用的 Transport。
//
// 关键点：DialContext 用闭包读 a.dialPolicy，而不是在构造时把策略值拷进去。
// 测试会在 New() 之后注入 Policy{AllowLoopback:true}，闭包能读到新值；
// 生产永远读到 DefaultPolicy()。
//
// 超时按"流式传输"配，不设总超时（http.Client.Timeout 会算上读完响应体的时间，
// 一个 2MB 的分片在 1Mbps 的源上要 16 秒，设 15 秒必然超时 —— 弱网必卡）。
// 真正的护栏是：拨号 10 秒、响应头 15 秒（都在 Policy.Transport 里），
// 加上请求透传 r.Context()——客户端断开时上游读取会立刻跟着取消。
func (a *API) segTransport() *http.Transport {
	t := a.dialPolicy.Transport()
	t.DialContext = func(ctx context.Context, network, address string) (net.Conn, error) {
		return a.dialPolicy.DialContext(ctx, network, address)
	}
	return t
}

// notePrivateSegmentTarget 为"分片打到了 LAN"写一条审计。
//
// 为什么需要：放行 RFC1918 是产品功能（LAN 源），但"服务端替终端去取内网地址"
// 是需要留下痕迹的行为——真出问题时这是唯一的线索。
//
// 为什么按频道 + 10 分钟窗口去重：HLS 每秒一个分片，每个都写一条
// 会把 audit_logs 刷爆，反而把真正的审计线索淹掉。
//
// 只写 channel_id 与"落在私网"这个标记，**不写 URL**：
// 分片地址里通常带源站签名，落到库里就是泄露凭据。
func (a *API) notePrivateSegmentTarget(ctx context.Context, channelID int64, addr netip.Addr) {
	if !safedial.IsPrivateAddr(addr) {
		return
	}
	a.segAuditMu.Lock()
	if a.segAuditAt == nil || len(a.segAuditAt) > 4096 {
		// 4096 远超任何家庭场景的频道数；超过就整体重置，
		// 宁可多记几条也不要让这张表无界增长。
		a.segAuditAt = map[int64]time.Time{}
	}
	if t, ok := a.segAuditAt[channelID]; ok && time.Since(t) < 10*time.Minute {
		a.segAuditMu.Unlock()
		return
	}
	a.segAuditAt[channelID] = time.Now()
	a.segAuditMu.Unlock()

	a.st.Audit(ctx, 0, "seg.private_target", fmt.Sprint(channelID), "分片目标位于私网/LAN", "")
}

// DLNASource 返回 DLNA 需要的数据源适配器。
func (a *API) DLNASource() dlna.ChannelSource { return dlnaSource{a: a} }

// ctxKey 避免 context key 冲突。
type ctxKey int

const (
	ctxIdentity ctxKey = iota
	ctxViaGateway
	ctxDevice
	ctxPeerIP
	ctxAdminSession
)

func identityFrom(ctx context.Context) (auth.Identity, bool) {
	v, ok := ctx.Value(ctxIdentity).(auth.Identity)
	return v, ok
}

func deviceFrom(ctx context.Context) (*store.Device, bool) {
	v, ok := ctx.Value(ctxDevice).(*store.Device)
	return v, ok
}

func viaGatewayFrom(ctx context.Context) bool {
	v, _ := ctx.Value(ctxViaGateway).(bool)
	return v
}

// Handler 构造完整路由。viaGateway 决定是否信任网关头。
func (a *API) Handler(viaGateway bool) http.Handler {
	r := chi.NewRouter()
	r.Use(middleware.Recoverer)
	// 必须在 RealIP 之前捕获原始 socket 对端；TCP 入口绝不能信任转发头。
	r.Use(a.peerIPMiddleware(viaGateway))
	r.Use(middleware.RealIP)
	r.Use(a.accessLog)
	r.Use(a.identityMiddleware(viaGateway))

	r.Get("/api/v1/health", a.health)
	r.Post("/api/v1/device/register", a.deviceRegister)
	r.Post("/api/v1/auth/login", a.passwordLogin)

	// ---- 管理台（需管理员） ----
	r.Group(func(pr chi.Router) {
		pr.Use(a.requireAdmin)

		pr.Get("/api/v1/system/me", a.systemMe)
		pr.Get("/api/v1/system/info", a.systemInfo)
		pr.Get("/api/v1/system/logs", a.systemLogs)
		pr.Get("/api/v1/system/config", a.getConfig)
		pr.Put("/api/v1/system/config", a.putConfig)
		pr.Get("/api/v1/system/streams", a.activeStreams)
		pr.Post("/api/v1/system/probe", a.runProbe)
		// 播放质量统计
		pr.Get("/api/v1/stats/overview", a.statsOverview)
		pr.Get("/api/v1/stats/channels", a.statsChannels)
		// 告警：整台挂掉的频道
		pr.Get("/api/v1/alerts/dead-channels", a.deadChannels)
		// Web 端直接看：给管理台发一个短期签名播放地址
		pr.Get("/api/v1/web/play/{id}", a.webPlay)

		// 录制中心
		pr.Get("/api/v1/recordings", a.listRecordings)
		pr.Post("/api/v1/recordings", a.createRecording)
		pr.Delete("/api/v1/recordings/{id}", a.deleteRecording)
		pr.Post("/api/v1/recordings/{id}/stop", a.stopRecording)
		pr.Get("/api/v1/recordings/{id}/file", a.recordingFile)

		pr.Get("/api/v1/sources", a.listSources)
		pr.Post("/api/v1/sources", a.createSource)
		pr.Put("/api/v1/sources/{id}", a.updateSource)
		pr.Delete("/api/v1/sources/{id}", a.deleteSource)
		pr.Post("/api/v1/sources/{id}/refresh", a.refreshSource)
		pr.Get("/api/v1/sources/{id}/preview", a.previewSource)

		pr.Get("/api/v1/channels", a.listChannels)
		pr.Get("/api/v1/groups", a.listGroups)
		pr.Post("/api/v1/channels", a.createChannel)
		pr.Put("/api/v1/channels/{id}", a.updateChannel)
		pr.Delete("/api/v1/channels/{id}", a.deleteChannel)
		pr.Post("/api/v1/channels/reorder", a.reorderChannels)
		pr.Post("/api/v1/channels/batch", a.batchChannels)
		pr.Get("/api/v1/channels/{id}/streams", a.listChannelStreams)
		pr.Post("/api/v1/channels/{id}/streams", a.addChannelStream)
		pr.Delete("/api/v1/channels/{id}/streams/{sid}", a.deleteChannelStream)
		pr.Put("/api/v1/channels/{id}/epg", a.mapChannelEPG)

		pr.Get("/api/v1/epg/sources", a.listEPGSources)
		pr.Post("/api/v1/epg/sources", a.createEPGSource)
		// A4：编辑源（名称/地址/启用/优先级/UA/Referer）
		pr.Put("/api/v1/epg/sources/{id}", a.updateEPGSource)
		pr.Delete("/api/v1/epg/sources/{id}", a.deleteEPGSource)
		// A2：单源刷新
		pr.Post("/api/v1/epg/sources/{id}/refresh", a.refreshEPGSource)
		pr.Post("/api/v1/epg/refresh", a.refreshEPG)
		// A8：导入本地 XMLTV
		pr.Post("/api/v1/epg/import", a.importEPG)
		pr.Get("/api/v1/epg/channels", a.listEPGChannels)
		// D1–D5：EPG 运维。
		// 这些是静态路径，必须注册在 /epg/{id} 之前，
		// 否则 chi 会把 "stats" 当成频道 id 匹配掉。
		pr.Get("/api/v1/epg/stats", a.epgStats)
		pr.Post("/api/v1/epg/cleanup", a.epgCleanup)
		pr.Get("/api/v1/epg/refresh-log", a.epgRefreshLog)
		pr.Get("/api/v1/epg/search", a.epgSearch)
		// B1–B6：频道 ↔ EPG 映射
		pr.Post("/api/v1/epg/automap", a.epgAutomap)
		pr.Get("/api/v1/epg/mapping", a.epgMapping)
		pr.Post("/api/v1/epg/map/batch", a.epgMapBatch)
		pr.Post("/api/v1/epg/map/by-number", a.epgMapByNumber)
		// 某个频道某天的节目单（管理台频道详情页用）。
		// 复用 TV 端那个 handler：它只依赖频道 id 和 day，跟设备无关。
		pr.Get("/api/v1/epg/{id}", a.tvEPG)

		pr.Get("/api/v1/devices", a.listDevices)
		pr.Post("/api/v1/devices/bindcode", a.createBindCode)
		pr.Post("/api/v1/devices/{id}/approve", a.approveDevice)
		pr.Post("/api/v1/devices/{id}/reject", a.rejectDevice)
		pr.Delete("/api/v1/devices/{id}", a.revokeDevice)
		pr.Put("/api/v1/devices/{id}/config", a.setDeviceConfig)

		// 台标管理：抓外链入库 / 上传自己的图 / 清除
		pr.Post("/api/v1/channels/{id}/logo", a.uploadLogo)
		pr.Delete("/api/v1/channels/{id}/logo", a.deleteLogo)
	})

	// ---- 终端（需 device token） ----
	r.Group(func(pr chi.Router) {
		pr.Use(a.requireDevice)
		pr.Get("/api/v1/tv/bootstrap", a.tvBootstrap)
		pr.Get("/api/v1/tv/channels", a.tvChannels)
		pr.Get("/api/v1/tv/epg/{id}", a.tvEPG)
		pr.Post("/api/v1/tv/favorite", a.tvFavorite)
		pr.Post("/api/v1/tv/heartbeat", a.tvHeartbeat)
		pr.Get("/api/v1/tv/play/{id}", a.tvPlay)
		pr.Get("/api/v1/tv/streams/{id}", a.tvStreams)
		// 遥控：电视端连 WS 待命，手机端发指令
		pr.Get("/api/v1/tv/ws", a.tvWS)
		pr.Post("/api/v1/tv/remote/{id}", a.tvRemote)
		pr.Get("/api/v1/tv/remote/targets", a.remoteTargets)
		// 时移回看
		pr.Get("/api/v1/tv/timeshift/{id}", a.tvTimeshift)
		// 播放质量上报（起播耗时/卡顿/失败）
		pr.Post("/api/v1/tv/stats", a.tvReportStats)
		// 跨端续播
		pr.Post("/api/v1/tv/progress", a.tvSaveProgress)
		pr.Get("/api/v1/tv/resume", a.tvResume)
		// C5：节目开播提醒
		pr.Post("/api/v1/tv/reminder", a.tvCreateReminder)
		pr.Get("/api/v1/tv/reminders", a.tvListReminders)
		pr.Delete("/api/v1/tv/reminder/{id}", a.tvDeleteReminder)
		// C6：从节目单预约录制
		pr.Post("/api/v1/tv/record", a.tvCreateRecording)
		// DLNA 投屏：发现由服务端做（iOS 拿不到多播权限），手机只调接口
		pr.Get("/api/v1/tv/dlna/renderers", a.tvDLNARenderers)
		pr.Post("/api/v1/tv/dlna/cast", a.tvDLNACast)
		pr.Post("/api/v1/tv/dlna/stop", a.tvDLNAStop)
	})

	// ---- 流出口（签名 URL，不走身份中间件） ----
	r.Get("/live/{id}/play", a.livePlay)
	r.Get("/live/{id}/index.m3u8", a.liveManifest)
	r.Get("/live/{id}/stream.ts", a.liveTS)
	r.Get("/live/{id}/timeshift.ts", a.liveTimeshift)
	// 裸 TS → HLS 转封装（给 iOS 和只认 HLS 的播放器用）
	r.Get("/live/{id}/remux.m3u8", a.liveRemuxPlaylist)
	r.Get("/live/{id}/remux/{seg}", a.liveRemuxSegment)
	r.Get("/live/{id}/seg", a.liveSegment)

	// 台标出图：公开访问，终端/浏览器/DLNA 都要能直接拉
	r.Get("/logo/{id}", a.serveLogo)

	// ---- DLNA（局域网设备发现与浏览，不需要我们的 App）----
	//
	// 注意：chi 的 Mount 不会剥掉挂载前缀，子路由拿到的还是 /dlna/xxx。
	// 所以这里必须显式 StripPrefix，否则内部按 /desc.xml 注册的处理器永远匹配不上
	// （表现是所有 /dlna/* 返回 404）。
	if a.dlna != nil {
		r.Mount("/dlna", http.StripPrefix("/dlna", a.dlna.Handler()))
	}

	return r
}

// ---------- 中间件 ----------

func (a *API) accessLog(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		ww := middleware.NewWrapResponseWriter(w, r.ProtoMajor)
		next.ServeHTTP(ww, r)
		// 流接口会长时间挂着，只在结束时记录一行
		slog.Debug("http",
			"method", r.Method, "path", r.URL.Path, "status", ww.Status(),
			"bytes", ww.BytesWritten(), "ms", time.Since(start).Milliseconds())
	})
}

func (a *API) identityMiddleware(viaGateway bool) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			ctx := context.WithValue(r.Context(), ctxViaGateway, viaGateway)

			// 1) 网关身份
			if viaGateway {
				if id, ok := auth.FromGateway(r.Header, true); ok {
					u, err := a.st.UpsertUser(ctx, id.UserID, id.Username, id.IsAdmin)
					if err == nil && u != nil {
						slog.Debug("网关用户", "uid", u.FnosUID, "admin", u.IsAdmin)
					}
					ctx = context.WithValue(ctx, ctxIdentity, id)
				} else if a.cfg.Auth.DevMode {
					// 开发模式：网关没配好时也能进管理台
					ctx = context.WithValue(ctx, ctxIdentity, auth.Identity{
						UserID: "dev", Username: "dev", IsAdmin: true, ViaGateway: true,
					})
				}
			}

			// 1.5) 应急管理员会话（网关不可用时用密码登录换来的 token）
			//
			// 关键：管理员身份**只能**来自 admin_sessions 表里的会话记录，
			// 不能从设备展示字段派生。以前的实现是
			// `if d.Platform == "admin" { IsAdmin = true }`——
			// 而 platform 是客户端注册时可以自报的字段，
			// 等于让设备自己声明"我是管理员"就真的成了管理员。
			// 现在一律只认 admin_sessions 里带到期时间的那条记录。
			if tok := auth.BearerToken(r); tok != "" {
				if sess, err := a.st.GetAdminSessionByToken(ctx, auth.HashToken(tok), a.now().Unix()); err == nil && sess != nil {
					ctx = context.WithValue(ctx, ctxAdminSession, sess)
					ctx = context.WithValue(ctx, ctxIdentity, auth.Identity{
						UserID:     "admin-session",
						Username:   "管理员",
						IsAdmin:    true,
						ViaGateway: false,
					})
				}
			}

			// 2) 终端 token
			if tok := auth.BearerToken(r); tok != "" {
				// R15：token_hours 现在真正生效。<=0 表示不施加 TTL（长期有效，靠吊销）。
				// >0 时是滑动续期：正常使用的设备不会被迫重绑，
				// 只有长期不用的设备才自然过期。
				if d, err := a.st.GetDeviceByToken(ctx, auth.HashToken(tok), a.cfg.Auth.TokenHours); err == nil && d != nil {
					ctx = context.WithValue(ctx, ctxDevice, d)
					_ = a.st.TouchDevice(ctx, d.ID)
					// 这里**没有**任何基于 d.Platform 的提权。
					// 设备永远是设备；管理员身份只来自网关头或 admin_sessions。
				}
			}

			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

func (a *API) requireAdmin(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		id, ok := identityFrom(r.Context())
		if !ok {
			fail(w, http.StatusUnauthorized, 2001, "未认证：请从飞牛桌面打开本应用")
			return
		}
		if !id.IsAdmin {
			// 非管理员只允许读
			if r.Method != http.MethodGet {
				fail(w, http.StatusForbidden, 2002, "需要管理员权限")
				return
			}
		}
		next.ServeHTTP(w, r)
	})
}

func (a *API) requireDevice(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if _, ok := deviceFrom(r.Context()); !ok {
			fail(w, http.StatusUnauthorized, 2003, "设备未绑定或 token 已失效")
			return
		}
		next.ServeHTTP(w, r)
	})
}

// ---------- 响应helper ----------

type resp struct {
	Code int    `json:"code"`
	Msg  string `json:"msg"`
	Data any    `json:"data,omitempty"`
}

func ok(w http.ResponseWriter, data any) {
	writeJSON(w, http.StatusOK, resp{Code: 0, Msg: "ok", Data: data})
}

func fail(w http.ResponseWriter, status, code int, msg string) {
	writeJSON(w, status, resp{Code: code, Msg: msg})
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func decode(r *http.Request, v any) error {
	defer r.Body.Close()
	body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if err != nil {
		return err
	}
	if len(bytes.TrimSpace(body)) == 0 {
		// 空 body 视为「无参数」，不报错（很多 POST 只是触发动作）
		return nil
	}
	dec := json.NewDecoder(bytes.NewReader(body))
	dec.DisallowUnknownFields()
	return dec.Decode(v)
}

func pathInt64(r *http.Request, name string) int64 {
	n, _ := strconv.ParseInt(chi.URLParam(r, name), 10, 64)
	return n
}

func queryInt(r *http.Request, name string, def int) int {
	v := r.URL.Query().Get(name)
	if v == "" {
		return def
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return def
	}
	return n
}

func queryBool(r *http.Request, name string) *bool {
	v := r.URL.Query().Get(name)
	if v == "" {
		return nil
	}
	b := strings.EqualFold(v, "true") || v == "1"
	return &b
}

func errMsg(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}

func formatErr(format string, args ...any) string { return fmt.Sprintf(format, args...) }
