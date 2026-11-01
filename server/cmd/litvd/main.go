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
	"syscall"
	"time"

	"github.com/litv/litv/server/internal/api"
	"github.com/litv/litv/server/internal/config"
	"github.com/litv/litv/server/internal/probe"
	"github.com/litv/litv/server/internal/store"
	"github.com/litv/litv/server/internal/stream"
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

	cfg, err := config.Load(*cfgPath)
	if err != nil {
		slog.Error("配置加载失败", "err", err)
		os.Exit(1)
	}
	if *checkCfg {
		fmt.Println("配置校验通过")
		return
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

	hub := stream.NewHub(stream.Options{
		RingSize:  cfg.Stream.RingSize,
		ChunkSize: cfg.Stream.BufferSize,
		UserAgent: cfg.Stream.UserAgent,
		Timeout:   time.Duration(cfg.Stream.UpstreamTimeout) * time.Second,
	})
	defer hub.Close()

	a := api.New(cfg, st, hub, version)

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

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	var servers []*http.Server

	// ---- TCP 入口（终端） ----
	tcpAddr := fmt.Sprintf("%s:%d", cfg.Server.Host, cfg.Server.Port)
	tcpSrv := &http.Server{
		Addr:              tcpAddr,
		Handler:           rootHandler(false),
		ReadHeaderTimeout: 10 * time.Second,
		// 直播流会长时间挂着，不设 WriteTimeout
		IdleTimeout: 120 * time.Second,
	}
	ln, err := net.Listen("tcp", tcpAddr)
	if err != nil {
		slog.Error("端口监听失败", "addr", tcpAddr, "err", err)
		os.Exit(1)
	}
	servers = append(servers, tcpSrv)
	go func() {
		slog.Info("TCP 入口已启动", "addr", tcpAddr, "version", version)
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

	// ---- 后台任务 ----
	// 1) 源健康探测：定期探一遍所有线路，供 BestStream 自动择优
	if cfg.Probe.Enabled {
		pr := probe.New(st,
			time.Duration(cfg.Probe.TimeoutSec)*time.Second,
			cfg.Probe.Concurrency,
			cfg.Stream.UserAgent)
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
	return len(p) >= 5 && (p[:5] == "/api/" || p[:6] == "/live/")
}

func setupLogger() {
	level := slog.LevelInfo
	switch os.Getenv("LITV_LOG_LEVEL") {
	case "debug":
		level = slog.LevelDebug
	case "warn":
		level = slog.LevelWarn
	case "error":
		level = slog.LevelError
	}
	h := slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: level})
	slog.SetDefault(slog.New(h))
}
