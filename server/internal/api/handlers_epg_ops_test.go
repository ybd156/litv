package api

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"testing"

	"github.com/litv/litv/server/internal/store"
)

const sampleXMLTV = `<?xml version="1.0" encoding="UTF-8"?>
<tv>
  <channel id="cctv1"><display-name>CCTV-1</display-name></channel>
  <channel id="cctv2"><display-name>CCTV-2</display-name></channel>
  <programme start="20261103080000 +0800" stop="20261103090000 +0800" channel="cctv1">
    <title>新闻联播</title><desc>晚间新闻</desc><category>新闻</category>
  </programme>
  <programme start="20261103090000 +0800" stop="20261103100000 +0800" channel="cctv1">
    <title>焦点访谈</title><category>新闻</category>
  </programme>
  <programme start="20261103080000 +0800" stop="20261103093000 +0800" channel="cctv2">
    <title>经济半小时</title><category>财经</category>
  </programme>
</tv>`

// newFileEPGSource 造一个 kind=file 的 EPG 源，内容来自 sampleXMLTV。
//
// 用本地文件而不是远程 URL：测试不依赖网络，而且能完整跑通
// 「读取 → 解析 → 写库 → 统计/搜索」这条链路。
func newFileEPGSource(t *testing.T, a *API) int64 {
	t.Helper()
	dir := t.TempDir()
	p := filepath.Join(dir, "test.xml")
	if err := os.WriteFile(p, []byte(sampleXMLTV), 0o644); err != nil {
		t.Fatalf("写测试 XMLTV 失败: %v", err)
	}
	id, err := a.st.CreateEPGSourceEx(context.Background(), store.EPGSource{
		Name: "本地测试源", URL: p, Enabled: true, Kind: "file",
	})
	if err != nil {
		t.Fatalf("建 EPG 源失败: %v", err)
	}
	return id
}

// TestEPGRefreshFromFileAndStats 覆盖 A8 + D1 + D3 + D5 的端到端链路。
func TestEPGRefreshFromFileAndStats(t *testing.T) {
	a, _ := newTestAPI(t)
	srcID := newFileEPGSource(t, a)

	// 刷新单个源（A2/A8）：走本地文件，不需要网络
	rec := adminRequest(t, a, http.MethodPost,
		fmt.Sprintf("/api/v1/epg/sources/%d/refresh", srcID), nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("刷新失败：%d %s", rec.Code, rec.Body.String())
	}
	var rr struct {
		Data struct {
			OK       bool `json:"ok"`
			Channels int  `json:"channels"`
			Programs int  `json:"programs"`
		} `json:"data"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &rr); err != nil {
		t.Fatalf("解析刷新结果失败: %v", err)
	}
	if !rr.Data.OK {
		t.Fatalf("刷新未成功：%s", rec.Body.String())
	}
	if rr.Data.Channels != 2 {
		t.Errorf("频道数期望 2，实际 %d", rr.Data.Channels)
	}
	if rr.Data.Programs != 3 {
		t.Errorf("节目数期望 3，实际 %d", rr.Data.Programs)
	}

	// D1 统计
	rec = adminRequest(t, a, http.MethodGet, "/api/v1/epg/stats", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("统计失败：%d", rec.Code)
	}
	var st struct {
		Data struct {
			Sources    int   `json:"sources"`
			Channels   int   `json:"channels"`
			Programs   int   `json:"programs"`
			LastSync   int64 `json:"last_sync"`
			CoveredDay int   `json:"covered_days"`
		} `json:"data"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &st); err != nil {
		t.Fatalf("解析统计失败: %v", err)
	}
	if st.Data.Channels != 2 || st.Data.Programs != 3 {
		t.Errorf("统计不符：channels=%d programs=%d", st.Data.Channels, st.Data.Programs)
	}
	if st.Data.LastSync <= 0 {
		t.Error("成功刷新后 last_sync 应大于 0")
	}

	// D5 搜索
	rec = adminRequest(t, a, http.MethodGet, "/api/v1/epg/search?q=%E6%96%B0%E9%97%BB", nil) // 新闻
	if rec.Code != http.StatusOK {
		t.Fatalf("搜索失败：%d", rec.Code)
	}
	var sr struct {
		Data struct {
			Total int `json:"total"`
			Items []struct {
				Title       string `json:"title"`
				ChannelName string `json:"channel_name"`
			} `json:"items"`
		} `json:"data"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &sr); err != nil {
		t.Fatalf("解析搜索失败: %v", err)
	}
	if sr.Data.Total < 1 {
		t.Fatalf("搜索「新闻」应有结果，实际 %d", sr.Data.Total)
	}
	if sr.Data.Items[0].ChannelName == "" {
		t.Error("搜索结果缺少频道名")
	}

	// D3 审计：刷新动作应当留下记录
	rec = adminRequest(t, a, http.MethodGet, "/api/v1/epg/refresh-log?limit=10", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("读审计失败：%d", rec.Code)
	}
	var al struct {
		Data []struct {
			Action string `json:"action"`
		} `json:"data"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &al); err != nil {
		t.Fatalf("解析审计失败: %v", err)
	}
	found := false
	for _, e := range al.Data {
		if e.Action == "epg.refresh.one" {
			found = true
			break
		}
	}
	if !found {
		t.Errorf("审计里没有 epg.refresh.one，实际记录：%+v", al.Data)
	}
}

// TestEPGCleanupRemovesOldPrograms 覆盖 D2。
func TestEPGCleanupRemovesOldPrograms(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()
	srcID := newFileEPGSource(t, a)

	// 先刷出一批节目
	if rec := adminRequest(t, a, http.MethodPost,
		fmt.Sprintf("/api/v1/epg/sources/%d/refresh", srcID), nil); rec.Code != http.StatusOK {
		t.Fatalf("刷新失败：%d", rec.Code)
	}

	// 造一条很老的节目（2020 年），应当被清掉
	chID, err := a.st.UpsertEPGChannel(ctx, srcID, "oldchan", "老频道", "")
	if err != nil {
		t.Fatalf("建频道失败: %v", err)
	}
	if err := a.st.ReplacePrograms(ctx, chID, []store.EPGProgram{
		{StartAt: 1577836800, EndAt: 1577840400, Title: "很久以前的节目"},
	}); err != nil {
		t.Fatalf("写旧节目失败: %v", err)
	}

	// keep_days=3：2020 年的节目必然过期
	rec := adminRequest(t, a, http.MethodPost, "/api/v1/epg/cleanup",
		map[string]any{"keep_days": 3})
	if rec.Code != http.StatusOK {
		t.Fatalf("清理失败：%d %s", rec.Code, rec.Body.String())
	}
	var cr struct {
		Data struct {
			Deleted int `json:"deleted"`
		} `json:"data"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &cr); err != nil {
		t.Fatalf("解析清理结果失败: %v", err)
	}
	if cr.Data.Deleted < 1 {
		t.Errorf("应至少删除 1 条过期节目，实际 %d", cr.Data.Deleted)
	}

	// 确认那条旧节目真没了，而新刷的还在
	var n int
	if err := a.st.DB().QueryRowContext(ctx,
		`SELECT COUNT(*) FROM epg_programs WHERE title='很久以前的节目'`).Scan(&n); err != nil {
		t.Fatalf("查询失败: %v", err)
	}
	if n != 0 {
		t.Errorf("旧节目没有被清理，仍有 %d 条", n)
	}
	if err := a.st.DB().QueryRowContext(ctx,
		`SELECT COUNT(*) FROM epg_programs WHERE title='新闻联播'`).Scan(&n); err != nil {
		t.Fatalf("查询失败: %v", err)
	}
	if n != 1 {
		t.Errorf("未过期的节目被误删：新闻联播剩 %d 条", n)
	}
}
