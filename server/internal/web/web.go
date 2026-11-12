// Package web 提供管理台静态文件服务。
//
// 静态文件通过 go:embed 打进二进制，这样 fpk 里只需要一个可执行文件，
// 不依赖任何外部目录结构（NAS 上少一个出错点）。
//
// 部署要点：
//  1. 管理台在飞牛网关下挂在 /app/litv/ 子路径，请求要剥前缀；
//  2. SPA 路由要回落到 index.html；
//  3. ⚠️ 绝对不要把 "/index.html" 交给 http.FileServer——
//     标准库会对以 index.html 结尾的路径做 localRedirect("./")，
//     结果是 /app/litv/ → /index.html → 301 ./ → /app/litv/ 无限重定向，
//     浏览器报「重定向次数过多」，管理台直接打不开。
//     所以 index.html 一律自己读出来写，不经过 FileServer。
package web

import (
	"embed"
	"io/fs"
	"net/http"
	"path"
	"strings"
)

//go:embed all:www
var embedded embed.FS

// Handler 返回静态资源处理器。prefix 形如 "/app/litv"，可为空。
func Handler(prefix string) http.Handler {
	sub, err := fs.Sub(embedded, "www")
	if err != nil {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			http.Error(w, "管理台资源缺失", http.StatusInternalServerError)
		})
	}
	fileServer := http.FileServer(http.FS(sub))

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p := r.URL.Path
		if prefix != "" && strings.HasPrefix(p, prefix) {
			p = strings.TrimPrefix(p, prefix)
		}
		if p == "" {
			p = "/"
		}
		// 目录形式统一按 index 处理
		if strings.HasSuffix(p, "/") {
			p += "index.html"
		}

		clean := strings.TrimPrefix(path.Clean(p), "/")
		if clean == "" {
			clean = "index.html"
		}

		// index.html 永远自己写，绝不交给 FileServer（避免 301 死循环）
		if clean == "index.html" {
			serveIndex(w, sub)
			return
		}

		// 其它静态资源：存在就走 FileServer
		if f, err := sub.Open(clean); err == nil {
			st, statErr := f.Stat()
			_ = f.Close()
			if statErr == nil && !st.IsDir() {
				r2 := r.Clone(r.Context())
				r2.URL.Path = "/" + clean
				fileServer.ServeHTTP(w, r2)
				return
			}
		}

		// SPA 兜底：未知路径交给前端路由
		serveIndex(w, sub)
	})
}

func serveIndex(w http.ResponseWriter, sub fs.FS) {
	index, err := fs.ReadFile(sub, "index.html")
	if err != nil {
		http.Error(w, "管理台未构建：请先执行 npm run build 并把产物放入 server/internal/web/www", http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	_, _ = w.Write(index)
}
