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
	"net/http"
	"strconv"
	"strings"
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
	"github.com/litv/litv/server/internal/store"
	"github.com/litv/litv/server/internal/stream"
	"github.com/litv/litv/server/internal/timeshift"
)

// API 聚合所有依赖。
type API struct {
	cfg     *config.Config
	st      *store.Store
	hub     *stream.Hub
	remote  *remote.Hub
	ts      *timeshift.Manager
	rx      *remux.Manager
	rec     *record.Manager
	alert   *alert.Notifier
	dlna    *dlna.Server
	logos   *logo.Store
	signer  *auth.Signer
	loginLimiter *auth.LoginLimiter
	authAudit *authAuditGate
	now func() time.Time

	// 运行时事实（由 main 注入）
	actualPort int
	ffmpegPath string
	startAt time.Time
	version string

	// cfgPath 是配置文件的真实路径（由 main 注入）。
	//
	// 管理台保存设置时要写回这个文件。路径不能靠 DiscoverPath 反推：
	// 启动时可能是用 -config 显式指定的，反推会写到别的文件去，
	// 表现就是"保存成功但重启后设置没了"。
	cfgPath string
}

// New 创建 API。
func New(cfg *config.Config, st *store.Store, hub *stream.Hub, version string) *API {
	return &API{
		cfg:     cfg,
		st:      st,
		hub:     hub,
		remote:  remote.NewHub(),
		signer:  auth.NewSigner(cfg.Auth.Secret),
		loginLimiter: auth.NewLoginLimiter(),
		authAudit: &authAuditGate{},
		now: time.Now,
		startAt: time.Now(),
		version: version,
	}
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
