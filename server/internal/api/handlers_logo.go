package api

import (
	"io"
	"net/http"
	"strconv"
	"strings"

	"github.com/litv/litv/server/internal/logo"
)

// serveLogo 出图：/logo/{id}
//
// 刻意**不校验身份**——台标是公开图片，终端、浏览器、DLNA 客户端都可能直接拉。
// 文件名由频道 id 决定，不暴露任何用户信息。
func (a *API) serveLogo(w http.ResponseWriter, r *http.Request) {
	if a.logos == nil {
		http.NotFound(w, r)
		return
	}
	id := pathInt64(r, "id")
	p := a.logos.Path(id)
	if p == "" {
		http.NotFound(w, r)
		return
	}

	etag := a.logos.ETag(id)
	if etag != "" {
		w.Header().Set("ETag", etag)
		if r.Header.Get("If-None-Match") == etag {
			w.WriteHeader(http.StatusNotModified)
			return
		}
	}
	// 台标基本不变，长缓存 + ETag 校验
	w.Header().Set("Cache-Control", "public, max-age=86400")
	http.ServeFile(w, r, p)
}

// uploadLogo 设置频道台标。
//
// 两种用法：
//   - JSON  {"url": "https://..."}   → 服务端下载并缓存（最常用：把源里的外链收进来）
//   - multipart/form-data  file=@x.png → 直接上传自己的图
//
// 成功后把 channels.logo 改写成 /logo/{id}，所有终端下次刷新自动用新台标。
func (a *API) uploadLogo(w http.ResponseWriter, r *http.Request) {
	if a.logos == nil {
		fail(w, http.StatusServiceUnavailable, 5001, "台标存储未初始化")
		return
	}
	id := pathInt64(r, "id")
	ch, err := a.st.GetChannel(r.Context(), id)
	if err != nil {
		fail(w, http.StatusNotFound, 3001, "频道不存在")
		return
	}

	var local string
	ct := r.Header.Get("Content-Type")

	switch {
	case strings.HasPrefix(ct, "multipart/form-data"):
		if err := r.ParseMultipartForm(logo.MaxBytes + 1024); err != nil {
			fail(w, http.StatusBadRequest, 1001, "解析上传失败: "+err.Error())
			return
		}
		f, hdr, err := r.FormFile("file")
		if err != nil {
			fail(w, http.StatusBadRequest, 1001, "缺少 file 字段")
			return
		}
		defer f.Close()
		data, err := io.ReadAll(io.LimitReader(f, logo.MaxBytes+1))
		if err != nil {
			fail(w, http.StatusBadRequest, 1001, "读取上传内容失败")
			return
		}
		contentType := hdr.Header.Get("Content-Type")
		if contentType == "" {
			contentType = http.DetectContentType(data)
		}
		local, err = a.logos.SaveBytes(id, data, contentType)
		if err != nil {
			fail(w, http.StatusBadRequest, 1001, err.Error())
			return
		}

	default:
		var body struct {
			URL string `json:"url"`
		}
		if err := decode(r, &body); err != nil {
			fail(w, http.StatusBadRequest, 1001, "参数错误: "+err.Error())
			return
		}
		target := strings.TrimSpace(body.URL)
		if target == "" {
			// 没给 url 就抓频道当前那个
			target = ch.Logo
		}
		if target == "" {
			fail(w, http.StatusBadRequest, 1001, "请提供图片地址或上传文件")
			return
		}
		if strings.HasPrefix(target, "/logo/") {
			fail(w, http.StatusBadRequest, 1001, "这已经是本地台标了")
			return
		}
		local, err = a.logos.SaveFromURL(r.Context(), id, target, a.cfg.Stream.UserAgent)
		if err != nil {
			fail(w, http.StatusBadGateway, 4001, "抓取台标失败: "+err.Error())
			return
		}
	}

	// 频道表指向站内地址
	ch.Logo = local
	if err := a.st.UpdateChannel(r.Context(), *ch); err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	a.st.Audit(r.Context(), 0, "channel.logo", strconv.FormatInt(id, 10), local, r.RemoteAddr)
	ok(w, map[string]any{"logo": local})
}

// deleteLogo 清掉本地台标，频道 logo 置空（下次导入会重新带外链）。
func (a *API) deleteLogo(w http.ResponseWriter, r *http.Request) {
	if a.logos == nil {
		fail(w, http.StatusServiceUnavailable, 5001, "台标存储未初始化")
		return
	}
	id := pathInt64(r, "id")
	ch, err := a.st.GetChannel(r.Context(), id)
	if err != nil {
		fail(w, http.StatusNotFound, 3001, "频道不存在")
		return
	}
	a.logos.Delete(id)
	ch.Logo = ""
	if err := a.st.UpdateChannel(r.Context(), *ch); err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, map[string]any{"deleted": true})
}
