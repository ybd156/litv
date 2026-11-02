// Command litvd 是 LITV 的服务端主程序。
//
// 它同时提供两个 HTTP 入口：
//   - Unix Socket（飞牛统一网关）：管理台，身份由网关注入
//   - TCP 端口：终端 App（Android TV / 手机 / iOS）直连
//
// 单进程、单二进制、无外部依赖，符合 fpk 原生应用的分发要求。
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"github.com/litv/litv/server/internal/alert"
	"github.com/litv/litv/server/internal/api"
	"github.com/litv/litv/server/internal/config"
	"github.com/litv/litv/server/internal/dlna"
	"github.com/litv/litv/server/internal/logo"
	"github.com/litv/litv/server/internal/probe"
	"github.com/litv/litv/server/internal/record"
	"github.com/litv/litv/server/internal/remux"
	"github.com/litv/litv/server/internal/store"
	"github.com/litv/litv/server/internal/stream"
	"github.com/litv/litv/server/internal/timeshift"
	"github.com/litv/litv/server/internal/web"
)

// version 由 -ldflags "-X main.version=x.y.z" 注入。
var version = "0.1.0-dev"

func main() {
	var (
		cfgPath  = flag.String("config", "", "配置文件路径（默认自动查找）")
		showVer  = flag.Bool("version", false, "打印版本后退出")
		checkCfg = flag.Bool("check", false, "只校验配置后退出")
	)
	flag.Parse()

	if *showVer {
		fmt.Printf("litvd %s\n", version)
		return
	}

	setupLogger()

	// 配置路径要先确定下来，管理台保存设置时要写回这个文件。
	// 没有显式 -config 时用 DiscoverPath，仍找不到就落到默认位置，
	// 保证"首次安装、配置文件还不存在"时保存也能写出一个文件。
	resolvedCfgPath := *cfgPath
	if resolvedCfgPath == "" {
		resolvedCfgPath = config.DiscoverPath()
	}
	if resolvedCfgPath == "" {
		resolvedCfgPath = "config.yaml"
	}

	cfg, err := config.Load(resolvedCfgPath)
	if err != nil {
		slog.Error("配置加载失败", "err", err)
		os.Exit(1)
	}
	if *checkCfg {
		fmt.Println("配置校验通过")
		return
	}
	// 密钥是本次运行时生成的（配置里没有或不可用），必须立刻写回磁盘。
	//
	// 不写回的话，每次重启都会换一把新密钥，所有已发出的播放地址立刻失效：
	// 用户正在看的流会断，客户端缓存的地址会 403，看起来像"服务不稳定"。
	// 这里失败不致命（还能继续跑），但要让用户知道原因。
	if cfg.NeedsSecretPersist() {
		if perr := cfg.PersistSecret(); perr != nil {
			slog.Warn("签名密钥未能写回配置，重启后已发出的播放地址会失效",
				"path", resolvedCfgPath, "err", perr)
		} else {
			slog.Info("已生成并保存签名密钥", "path", resolvedCfgPath)
		}
	}
	if err := cfg.EnsureDirs(); err != nil {
		slog.Error("目录初始化失败", "err", err)
		os.Exit(1)
	}

	st, err := store.Open(cfg.Database.Path)
	if err != nil {
		slog.Error("数据库打开失败", "err", err, "path", cfg.Database.Path)
		os.Exit(1)
	}
	defer st.Close()

	// 时移缓冲：扇出层每读到一块就 tee 一份到磁盘，
	// 所以"看过就有回看"，不需要额外开录制任务。
	tsDir := cfg.Timeshift.Dir
	if tsDir == "" {
		tsDir = filepath.Join(cfg.Stream.CacheDir, "timeshift")
	}
	tsMgr := timeshift.NewManager(tsDir, cfg.Timeshift.Enabled, timeshift.Options{
		SegmentBytes: int64(cfg.Timeshift.SegmentMB) << 20,
		MaxSegments:  cfg.Timeshift.MaxSegments,
	})
	defer tsMgr.Close()

	hub := stream.NewHub(stream.Options{
		RingSize:  cfg.Stream.RingSize,
		ChunkSize: cfg.Stream.BufferSize,
		UserAgent: cfg.Stream.UserAgent,
		Timeout:   time.Duration(cfg.Stream.UpstreamTimeout) * time.Second,
		OnData: func(channelID int64, data []byte) {
			tsMgr.Write(channelID, data)
		},
	})
	defer hub.Close()

	a := api.New(cfg, st, hub, version)
	a.SetTimeshift(tsMgr)
	// 管理台保存设置要写回真实配置文件，不能靠猜。
	a.SetConfigPath(resolvedCfgPath)

	// 裸 TS → HLS 转封装（给 iOS 和只认 HLS 的播放器）
	rxDir := cfg.Remux.Dir
	if rxDir == "" {
		rxDir = filepath.Join(cfg.Stream.CacheDir, "remux")
	}
	var rxMgr *remux.Manager
	if cfg.Remux.Enabled {
		rxMgr = remux.NewManager(remux.Options{
			FFmpeg:         cfg.FFmpegPath(),
			Dir:            rxDir,
			SegmentSeconds: cfg.Remux.SegmentSeconds,
			PlaylistSize:   cfg.Remux.PlaylistSize,
			IdleTimeout:    time.Duration(cfg.Remux.IdleTimeoutSec) * time.Second,
		})
		defer rxMgr.Close()
		a.SetRemux(rxMgr)
		if rxMgr.Available() {
			slog.Info("转封装已启用", "ffmpeg", rxMgr.FFmpegPath(), "dir", rxDir)
		} else {
			// 注意：这个警告不影响 TV/手机 App——它们直接吃裸 TS。
			// 只有 iOS 和网页（只认 HLS）需要服务端转封装。
			slog.Warn("未找到 ffmpeg，转封装不可用",
				"影响", "iOS 和网页播不了裸 TS 源；TV/手机不受影响",
				"配置值", cfg.Transcode.FFmpeg,
				"已查找", "随包目录、PATH、/usr/bin、/usr/local/bin、/opt/ffmpeg/bin、/usr/trim/bin 等",
				"解决", "安装 ffmpeg 后在「应用设置」里填绝对路径，或放到 litvd 同目录")
		}
	}

	// 台标存储：下载到本地，统一由 /logo/{id} 出图，
	// 免得每个终端都去拉外链（外链经常挂/防盗链）
	a.SetLogoStore(logo.New(filepath.Join(cfg.Stream.CacheDir, "logos")))

	// DLNA 的创建要放在 rootHandler 之前：
	// rootHandler 在服务器构造时就把路由建好了，之后再 SetDLNA 是挂不上去的
	// （表现就是 /dlna/* 全部 404）。
	var dlnaSrv *dlna.Server
	if cfg.DLNA.Enabled {
		dlnaSrv = dlna.New(dlna.Options{
			HTTPPort:     cfg.Server.Port,
			FriendlyName: cfg.DLNA.FriendlyName,
			AdvertiseIP:  cfg.DLNA.AdvertiseIP,
		}, a.DLNASource())
		a.SetDLNA(dlnaSrv)
	}

	// 静态资源（管理台）挂在同一路由下：先走 API，未命中再落静态文件。
	rootHandler := func(viaGateway bool) http.Handler {
		apiHandler := a.Handler(viaGateway)
		static := web.Handler(cfg.Server.Prefix)
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if isAPIPath(r.URL.Path) {
				apiHandler.ServeHTTP(w, r)
				return
			}
			static.ServeHTTP(w, r)
		})
	}

	// TCP 入口也要能接受带前缀的路径。
	//
	// 为什么：服务端下发给终端的播放地址里带 /app/litv 前缀（因为管理台在网关下），
	// 但终端是直连 TCP 端口的。如果这里不剥前缀，终端拿到的
	// http://ip:9527/app/litv/live/1/stream.ts 会落到静态处理器 → 返回首页 HTML，
	// 表现就是"能播但一直黑屏/无数据"。e2e 测试抓到的就是这个。
	stripIfPrefixed := func(prefix string, h http.Handler) http.Handler {
		if prefix == "" {
			return h
		}
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if strings.HasPrefix(r.URL.Path, prefix+"/") {
				r2 := r.Clone(r.Context())
				r2.URL.Path = strings.TrimPrefix(r.URL.Path, prefix)
				h.ServeHTTP(w, r2)
				return
			}
			h.ServeHTTP(w, r)
		})
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	var servers []*http.Server

	// ---- TCP 入口（终端） ----
	tcpAddr := fmt.Sprintf("%s:%d", cfg.Server.Host, cfg.Server.Port)
	tcpSrv := &http.Server{
		Addr:              tcpAddr,
		Handler:           stripIfPrefixed(cfg.Server.Prefix, rootHandler(false)),
		ReadHeaderTimeout: 10 * time.Second,
		// 直播流会长时间挂着，不设 WriteTimeout
		IdleTimeout: 120 * time.Second,
	}
	ln, actualPort, err := listenWithFallback(cfg.Server.Host, cfg.Server.Port, 20)
	if err != nil {
		slog.Error("端口监听失败", "addr", tcpAddr, "err", err)
		os.Exit(1)
	}
	if actualPort != cfg.Server.Port {
		// 端口被占用时自动顺延，并大声说出来——
		// 否则用户只看到"启动失败"，不知道换个端口就好。
		slog.Warn("配置的端口被占用，已自动改用下一个可用端口",
			"配置端口", cfg.Server.Port, "实际端口", actualPort,
			"提示", "如需固定端口，请在「应用设置」里换一个没被占用的")
	}
	// 把实际端口写出来：管理台和命令行都能读到，避免用户猜
	writePortFile(*cfgPath, actualPort)
	// 让管理台能显示"真实情况"（实际端口 + 实际用的 ffmpeg）
	a.SetRuntime(actualPort, rxMgr.FFmpegPath())

	servers = append(servers, tcpSrv)
	go func() {
		slog.Info("TCP 入口已启动", "addr", ln.Addr().String(), "version", version)
		if err := tcpSrv.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
			slog.Error("TCP 服务异常退出", "err", err)
		}
	}()

	// ---- Unix Socket 入口（飞牛网关） ----
	if cfg.Server.GatewaySocket != "" {
		sockPath := cfg.Server.GatewaySocket
		// 关键：上一次异常退出会残留 socket 文件，必须先删，否则 bind 失败
		_ = os.Remove(sockPath)
		if err := os.MkdirAll(filepath.Dir(sockPath), 0o755); err != nil {
			slog.Warn("创建 socket 目录失败", "err", err)
		}
		unixLn, err := net.Listen("unix", sockPath)
		if err != nil {
			slog.Error("网关 socket 监听失败", "path", sockPath, "err", err)
		} else {
			// 0660：只有网关（同组）能访问，这是安全边界
			if err := os.Chmod(sockPath, 0o660); err != nil {
				slog.Warn("设置 socket 权限失败", "err", err)
			}
			prefix := cfg.Server.Prefix
			gwSrv := &http.Server{
				Handler:           http.StripPrefix(prefix, rootHandler(true)),
				ReadHeaderTimeout: 10 * time.Second,
				IdleTimeout:       120 * time.Second,
			}
			servers = append(servers, gwSrv)
			go func() {
				slog.Info("网关入口已启动", "socket", sockPath, "prefix", prefix)
				if err := gwSrv.Serve(unixLn); err != nil && !errors.Is(err, http.ErrServerClosed) {
					slog.Error("网关服务异常退出", "err", err)
				}
			}()
		}
	}

	// 录制中心
	if cfg.Record.Enabled {
		recDir := cfg.Record.Dir
		if recDir == "" {
			recDir = filepath.Join(cfg.Stream.CacheDir, "recordings")
		}
		recMgr := record.NewManager(st, record.Options{
			FFmpeg:    cfg.FFmpegPath(),
			Dir:       recDir,
			UserAgent: cfg.Stream.UserAgent,
		})
		a.SetRecord(recMgr)
		go recMgr.Run(ctx)
	}

	// DLNA 的 SSDP 应答需要 ctx，放到这里启动
	if dlnaSrv != nil {
		go dlnaSrv.Run(ctx)
	}

	// ---- 后台任务 ----
	// 0) 告警通知（源失效时主动推）
	notifier := alert.New(alert.Options{
		Enabled:     cfg.Alert.Enabled,
		WebhookURL:  cfg.Alert.WebhookURL,
		ChatID:      cfg.Alert.ChatID,
		MinInterval: time.Duration(cfg.Alert.MinIntervalMin) * time.Minute,
	})
	if notifier.Enabled() {
		slog.Info("告警通知已启用")
	}
	a.SetAlert(notifier)

	// 1) 源健康探测：定期探一遍所有线路，供 BestStream 自动择优
	if cfg.Probe.Enabled {
		pr := probe.New(st,
			time.Duration(cfg.Probe.TimeoutSec)*time.Second,
			cfg.Probe.Concurrency,
			cfg.Stream.UserAgent)

		// 探测结束后检查"整台挂掉"的频道并告警
		pr.OnDone = func(ctx context.Context, res *probe.Result) {
			if !notifier.Enabled() {
				return
			}
			dead, err := st.ChannelsWithNoHealthyStream(ctx)
			if err != nil || len(dead) == 0 {
				return
			}
			var sb strings.Builder
			for i, d := range dead {
				if i >= 10 {
					sb.WriteString(fmt.Sprintf("\n…还有 %d 个", len(dead)-10))
					break
				}
				sb.WriteString(fmt.Sprintf("\n· %s（%s）%d 条线路全失效", d.Name, d.Group, d.Total))
			}
			_ = notifier.Send(
				"dead-channels",
				fmt.Sprintf("LITV：%d 个频道没有可用线路", len(dead)),
				fmt.Sprintf("本轮探测 %d 条线路，可用 %d，失效 %d。%s",
					res.Checked, res.Healthy, res.Failed, sb.String()),
			)
		}

		go pr.Run(ctx, time.Duration(cfg.Probe.IntervalMin)*time.Minute)
		slog.Info("源探测已启用", "interval_min", cfg.Probe.IntervalMin, "concurrency", cfg.Probe.Concurrency)
	}

	// 2) 过期节目单清理
	go func() {
		t := time.NewTicker(10 * time.Minute)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				if n, err := st.PurgeOldPrograms(ctx, time.Now().AddDate(0, 0, -cfg.EPG.KeepDays).Unix()); err == nil && n > 0 {
					slog.Info("清理过期节目单", "rows", n)
				}
			}
		}
	}()

	// 通知 cmd/main：服务已就绪（它靠这个判断 start 是否成功）
	slog.Info("LITV 启动完成", "version", version, "port", cfg.Server.Port)

	<-ctx.Done()
	slog.Info("收到退出信号，正在关闭…")

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	for _, s := range servers {
		_ = s.Shutdown(shutdownCtx)
	}
	hub.Close()
	if cfg.Server.GatewaySocket != "" {
		_ = os.Remove(cfg.Server.GatewaySocket)
	}
	slog.Info("已退出")
}

func isAPIPath(p string) bool {
	if len(p) >= 5 && p[:5] == "/api/" {
		return true
	}
	if len(p) >= 6 && p[:6] == "/live/" {
		return true
	}
	// DLNA 的 desc.xml / cds/control 等
	if len(p) >= 6 && p[:6] == "/dlna/" {
		return true
	}
	// 台标出图
	if len(p) >= 6 && p[:6] == "/logo/" {
		return true
	}
	return false
}

// listenWithFallback 先试配置端口，被占用就顺延，最多试 tries 次。
//
// 为什么不在端口被占时直接退出：
//
//	NAS 上端口冲突太常见（用户自己装过别的东西、装了两份 LITV、
//	系统服务恰好用了 9527……）。直接退出的话用户只看到"启动失败"，
//	根本不知道换个端口就好。
//
// 只有「端口被占用」才顺延；权限不足之类的错误立即返回，别掩盖真正的问题。
func listenWithFallback(host string, port, tries int) (net.Listener, int, error) {
	var lastErr error
	for i := 0; i <= tries; i++ {
		p := port + i
		if p < 1 || p > 65535 {
			break
		}
		addr := fmt.Sprintf("%s:%d", host, p)
		ln, err := net.Listen("tcp", addr)
		if err == nil {
			return ln, p, nil
		}
		lastErr = err
		if !isAddrInUse(err) {
			return nil, 0, err
		}
		slog.Warn("端口已被占用，尝试下一个", "port", p)
	}
	return nil, 0, lastErr
}

func isAddrInUse(err error) bool {
	return errors.Is(err, syscall.EADDRINUSE) ||
		strings.Contains(strings.ToLower(err.Error()), "address already in use")
}

// writePortFile 把实际监听端口写到配置文件旁边的 litv.port。
//
// 用途：用户/脚本想知道"到底起在哪个端口"，不用去翻日志。
func writePortFile(cfgPath string, port int) {
	dir := filepath.Dir(cfgPath)
	if dir == "" || dir == "." {
		return
	}
	f := filepath.Join(dir, "litv.port")
	if err := os.WriteFile(f, []byte(fmt.Sprintf("%d\n", port)), 0o644); err != nil {
		slog.Debug("写端口文件失败（不影响运行）", "path", f, "err", err)
	}
}

func setupLogger() {
	// 级别用 LevelVar 持有：管理台改 log_level 后能立即生效，不必重启。
	config.InstallLogger(os.Getenv("LITV_LOG_LEVEL"))
}
