// Package main 是 LITV 服务端（litvd）的**占位实现**，仅用于让 .fpk 能真正装起来、
// 起得来、网关通得了，从而验证打包链路与 cmd/main 的启动/就绪/停止逻辑。
//
// 真正的业务实现（直播源管理、EPG、转码、设备令牌）在仓库的 server/ 模块里。
// build.sh 的优先顺序是：
//   1) server/ 模块里已经有 Go 代码  → 编译真实二进制
//   2) 否则                          → 编译本文件作为占位二进制
//
// 但**运行时契约与真实服务端一致**，这样替换成真二进制时 cmd/main 不需要改：
//   LITV_DATA_DIR        $TRIM_PKGVAR
//   LITV_ETC_DIR         $TRIM_PKGETC
//   LITV_CONFIG          $TRIM_PKGETC/config.yaml
//   LITV_WWW_DIR         $TRIM_APPDEST/www
//   LITV_LISTEN_SOCKET   $TRIM_APPDEST/app.sock   （统一网关）
//   LITV_URL_PREFIX      /app/litv                （必须与 gatewayPrefix 一致）
//   LITV_LISTEN_ADDR     0.0.0.0:28027             （原生客户端直连端口，可选）
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"
)

var (
	version = "0.0.0-stub"
	startAt = time.Now()
)

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

// writeJSON 统一 JSON 输出（占位实现，不做鉴权；真实现必须校验 X-Trim-* 或设备令牌）。
func writeJSON(w http.ResponseWriter, code int, payload any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(payload)
}

func main() {
	log.SetFlags(log.LstdFlags | log.Lmicroseconds)

	sockPath := envOr("LITV_LISTEN_SOCKET", "")
	tcpAddr := envOr("LITV_LISTEN_ADDR", "")
	prefix := envOr("LITV_URL_PREFIX", "/app/litv")
	wwwDir := envOr("LITV_WWW_DIR", "")
	dataDir := envOr("LITV_DATA_DIR", "")
	etcDir := envOr("LITV_ETC_DIR", "")

	if sockPath == "" && tcpAddr == "" {
		log.Fatal("LITV_LISTEN_SOCKET 与 LITV_LISTEN_ADDR 至少需要配置一个")
	}

	// 路由统一挂在 prefix 下：网关转发过来的路径是 /app/litv/... 原样进来，
	// 所以不能只注册 "/"，否则网关能找到 socket 但路由 404。
	mux := http.NewServeMux()
	p := prefix
	if len(p) > 0 && p[len(p)-1] == '/' {
		p = p[:len(p)-1]
	}

	mux.HandleFunc(p+"/api/health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{
			"status":  "ok",
			"version": version,
			"prefix":  p,
			"uptime":  int(time.Since(startAt).Seconds()),
			"stub":    true,
		})
	})

	// 回显网关注入的身份 Header：用来验证"网关确实把登录态传进来了"
	mux.HandleFunc(p+"/api/whoami", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{
			"userid":   r.Header.Get("X-Trim-Userid"),
			"username": r.Header.Get("X-Trim-Username"),
			"isadmin":  r.Header.Get("X-Trim-Isadmin"),
		})
	})

	mux.HandleFunc(p+"/", func(w http.ResponseWriter, r *http.Request) {
		if wwwDir == "" {
			fmt.Fprintf(w, "LITV stub %s\nprefix=%s\ndata=%s\netc=%s\n", version, p, dataDir, etcDir)
			return
		}
		// SPA 兜底：找不到文件就回 index.html，交给前端路由
		clean := filepath.Clean(r.URL.Path)
		rel := clean[len(p):]
		if rel == "" || rel == "/" {
			rel = "/index.html"
		}
		full := filepath.Join(wwwDir, filepath.FromSlash(rel))
		if fi, err := os.Stat(full); err != nil || fi.IsDir() {
			full = filepath.Join(wwwDir, "index.html")
		}
		if _, err := os.Stat(full); err != nil {
			fmt.Fprintf(w, "LITV stub %s: no static file at %s (put your admin UI in app/www)\n", version, rel)
			return
		}
		http.ServeFile(w, r, full)
	})

	// 访问根路径时跳到网关前缀，方便"手动 curl 一下看看活没活"
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/" {
			http.Redirect(w, r, p+"/", http.StatusFound)
			return
		}
		http.NotFound(w, r)
	})

	srv := &http.Server{
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
	}

	var listeners []net.Listener

	if sockPath != "" {
		// 双保险：cmd/main 已经 rm 过，这里再删一次，防止手工直跑时残留
		_ = os.Remove(sockPath)
		if dir := filepath.Dir(sockPath); dir != "" {
			_ = os.MkdirAll(dir, 0o755)
		}
		l, err := net.Listen("unix", sockPath)
		if err != nil {
			log.Fatalf("监听 unix socket 失败 %s: %v", sockPath, err)
		}
		// 0660：网关进程（不同用户/组）需要能连进来；同时避免同机其他用户乱连
		if err := os.Chmod(sockPath, 0o660); err != nil {
			log.Printf("警告: chmod socket 失败: %v", err)
		}
		listeners = append(listeners, l)
		log.Printf("统一网关 socket 就绪: %s (prefix=%s)", sockPath, p)
	}

	if tcpAddr != "" {
		l, err := net.Listen("tcp", tcpAddr)
		if err != nil {
			// TCP 端口被占用不应导致整体失败：管理台走网关仍然可用
			log.Printf("警告: 监听 TCP %s 失败（原生客户端将无法直连）: %v", tcpAddr, err)
		} else {
			listeners = append(listeners, l)
			log.Printf("原生客户端端口就绪: %s", tcpAddr)
		}
	}

	if len(listeners) == 0 {
		log.Fatal("没有任何监听器可用")
	}

	// 优雅退出：fnOS 没有 systemd，stop 时 cmd/main 发 SIGTERM
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()

	errCh := make(chan error, len(listeners))
	for _, l := range listeners {
		l := l
		go func() {
			if err := srv.Serve(l); err != nil && !errors.Is(err, http.ErrServerClosed) {
				errCh <- err
			}
		}()
	}

	log.Printf("litvd %s 已启动 (data=%s etc=%s)", version, dataDir, etcDir)

	select {
	case err := <-errCh:
		log.Printf("服务异常退出: %v", err)
		_ = srv.Close()
		os.Exit(1)
	case <-ctx.Done():
		log.Printf("收到退出信号，开始优雅关闭")
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		log.Printf("优雅关闭超时，强制退出: %v", err)
	}
	if sockPath != "" {
		_ = os.Remove(sockPath)
	}
	log.Printf("已退出")
}
