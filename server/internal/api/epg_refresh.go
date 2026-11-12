package api

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"strings"
	"time"

	"github.com/litv/litv/server/internal/epg"
	"github.com/litv/litv/server/internal/fetch"
	"github.com/litv/litv/server/internal/parse"
	"github.com/litv/litv/server/internal/safedial"
	"github.com/litv/litv/server/internal/store"
)

// EPGRefreshResult 是单个源的刷新结果（A3：明细）。
type EPGRefreshResult struct {
	ID        int64  `json:"id"`
	Name      string `json:"name"`
	OK        bool   `json:"ok"`
	Channels  int    `json:"channels"`
	Programs  int    `json:"programs"`
	ElapsedMs int64  `json:"elapsed_ms"`
	Error     string `json:"error,omitempty"`
}

// RefreshAllEPG 刷新所有启用的源，并做一次频道自动匹配。
//
// 调度器（A1）和「立即刷新」按钮共用这一个入口，保证两条路径行为一致。
func (a *API) RefreshAllEPG(ctx context.Context) []EPGRefreshResult {
	srcs, err := a.st.ListEPGSources(ctx)
	if err != nil {
		return []EPGRefreshResult{{Error: err.Error()}}
	}
	out := make([]EPGRefreshResult, 0, len(srcs))
	for _, src := range srcs {
		if !src.Enabled {
			continue
		}
		out = append(out, a.refreshOneEPGSource(ctx, src))
	}
	// 同步完立刻按频道名自动匹配一次：源 M3U 大多没有 tvg-id，
	// 不自动匹配的话 channels.epg_id 全空，电视端节目单永远是空的。
	_, _ = a.st.AutoMapChannels(ctx)
	return out
}

// RefreshOneEPG 刷新指定源。
func (a *API) RefreshOneEPG(ctx context.Context, id int64) (EPGRefreshResult, error) {
	src, err := a.st.GetEPGSource(ctx, id)
	if err != nil {
		return EPGRefreshResult{}, err
	}
	res := a.refreshOneEPGSource(ctx, *src)
	// 单源刷新也要重跑自动匹配：新频道可能正好补上之前匹配不上的台
	_, _ = a.st.AutoMapChannels(ctx)
	return res, nil
}

// refreshOneEPGSource 拉取、解析并写库。任何失败都记进 last_error，不中断其他源。
func (a *API) refreshOneEPGSource(ctx context.Context, src store.EPGSource) EPGRefreshResult {
	start := time.Now()
	res := EPGRefreshResult{ID: src.ID, Name: src.Name}

	body, err := a.fetchEPGBody(ctx, src)
	if err != nil {
		res.Error = err.Error()
		res.ElapsedMs = time.Since(start).Milliseconds()
		streak, _ := a.st.MarkEPGAttempt(ctx, src.ID, res.Error)
		slog.Warn("EPG 拉取失败", "source", src.Name, "err", err, "streak", streak)
		a.alertEPGFailure(src, streak, res.Error)
		return res
	}

	parsed, perr := epg.Parse(strings.NewReader(string(parse.ToUTF8(body))), a.cfg.EPG.MaxProgrammes)
	if perr != nil && (parsed == nil || len(parsed.Programmes) == 0) {
		res.Error = perr.Error()
		res.ElapsedMs = time.Since(start).Milliseconds()
		streak, _ := a.st.MarkEPGAttempt(ctx, src.ID, res.Error)
		slog.Warn("EPG 解析失败", "source", src.Name, "err", perr, "streak", streak)
		a.alertEPGFailure(src, streak, res.Error)
		return res
	}
	if perr != nil {
		// 部分损坏但仍有内容：继续写库，但把原因记进日志
		slog.Warn("EPG 解析有告警", "source", src.Name, "err", perr)
	}

	idMap := map[string]int64{}
	for _, ch := range parsed.Channels {
		cid, e := a.st.UpsertEPGChannel(ctx, src.ID, ch.ID, ch.DisplayName, ch.Icon)
		if e == nil {
			idMap[ch.ID] = cid
		}
	}
	byChannel := map[int64][]store.EPGProgram{}
	for _, p := range parsed.Programmes {
		cid, ok := idMap[p.ChannelID]
		if !ok {
			continue
		}
		byChannel[cid] = append(byChannel[cid], store.EPGProgram{
			StartAt: p.Start, EndAt: p.Stop, Title: p.Title, Description: p.Description, Category: p.Category,
		})
	}
	for cid, progs := range byChannel {
		if err := a.st.ReplacePrograms(ctx, cid, progs); err != nil {
			slog.Warn("EPG 写节目失败", "epg_channel", cid, "err", err)
			continue
		}
		res.Programs += len(progs)
	}
	res.Channels = len(parsed.Channels)
	res.OK = true
	res.ElapsedMs = time.Since(start).Milliseconds()
	_, _ = a.st.MarkEPGAttempt(ctx, src.ID, "")
	slog.Info("EPG 同步完成",
		"source", src.Name, "channels", res.Channels, "programs", res.Programs, "ms", res.ElapsedMs)
	return res
}

// fetchEPGBody 取回 XMLTV 原始字节。
//
// A6：网络抖动重试（最多 3 次，指数退避）。
// A7：按源配置带 UA / Referer（部分 EPG 站有防盗链）。
// A8：kind=file 的源直接读本地文件（管理台上传的 XMLTV）。
// A9：体积上限，防止一个巨无霸文件把内存吃光。
func (a *API) fetchEPGBody(ctx context.Context, src store.EPGSource) ([]byte, error) {
	if src.Kind == "file" {
		b, err := os.ReadFile(src.URL)
		if err != nil {
			return nil, fmt.Errorf("读取本地 XMLTV 失败: %w", err)
		}
		if max := a.cfg.EPG.MaxBytes(); max > 0 && int64(len(b)) > max {
			return nil, fmt.Errorf("文件超过上限 %d MB", a.cfg.EPG.MaxBytesMB)
		}
		return b, nil
	}

	opt := fetch.DefaultOptions()
	if src.UserAgent != "" {
		opt.UserAgent = src.UserAgent
	}
	if src.Referer != "" {
		if opt.Headers == nil {
			opt.Headers = map[string]string{}
		}
		opt.Headers["Referer"] = src.Referer
	}
	if max := a.cfg.EPG.MaxBytes(); max > 0 {
		opt.MaxBytes = max
	}

	var lastErr error
	backoff := 500 * time.Millisecond
	const attempts = 3
	for i := 0; i < attempts; i++ {
		res, err := fetch.Get(ctx, src.URL, opt)
		if err == nil {
			return res.Body, nil
		}
		lastErr = err
		// 被出站策略拒绝的地址不值得重试：三次重试只会让管理台多等 1.5 秒，
		// 最后还是同一个结论，而且会把 fail_streak 推高触发误告警。
		if safedial.IsBlockedError(err) {
			break
		}
		if i == attempts-1 {
			break
		}
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(backoff):
		}
		backoff *= 2
	}
	return nil, fmt.Errorf("拉取失败（已重试 %d 次）: %w", attempts, lastErr)
}

// errEPGNotFound 便于 handler 区分「源不存在」。
var errEPGNotFound = errors.New("EPG 源不存在")

// epgAlertStreakThreshold 连续失败多少次才告警。
//
// 取 3 而不是 1：EPG 源偶发超时很常见，一次失败就推消息会变成骚扰，
// 用户很快就会把通知关掉，真正出问题时反而收不到。
const epgAlertStreakThreshold = 3

// alertEPGFailure 在源连续失败到阈值时发一条告警（D4）。
//
// 复用 alert.Notifier：它自带 30 分钟去重，所以即使定时任务每小时都失败，
// 也不会把用户刷屏。a.alert 可能为 nil（未配置告警），此时静默跳过。
func (a *API) alertEPGFailure(src store.EPGSource, streak int, msg string) {
	if a.alert == nil || streak < epgAlertStreakThreshold {
		return
	}
	_ = a.alert.Send(
		"epg-source-"+fmt.Sprint(src.ID),
		fmt.Sprintf("LITV：EPG 源「%s」连续 %d 次刷新失败", src.Name, streak),
		fmt.Sprintf("最近一次错误：%s\n地址：%s\n请在管理台「EPG 源」页检查地址或网络。", msg, src.URL),
	)
}
