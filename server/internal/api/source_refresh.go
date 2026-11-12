package api

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"time"

	"github.com/litv/litv/server/internal/store"
)

// 源刷新（A2）的几个硬边界。
const (
	// maxImportItems 是一次导入的条目上限。
	//
	// 上游内容是外部输入：一个 5 万条的源会让整次刷新无限拉长，
	// 而 SQLite 的写是串行的——期间电视端的 play_events / watch_progress
	// 写入全都在排队。截断并记录 capped 标记，比"忠实导入全部"更安全。
	// 2 万条已经远超任何真实家庭源（国内大源通常 1~3 千）。
	maxImportItems = 20000

	// sourceRefreshTimeout 是一次刷新的总预算（抓取 + 解析 + 落库）。
	sourceRefreshTimeout = 10 * time.Minute

	// minAlignParsed 是允许执行「标记缺失」的绝对下限。
	//
	// 见 shouldAlign：上游返回残缺页/防盗链页/半截 gzip 时解析条数会很小，
	// 无脑对齐会把用户整个频道表批量标记缺失。
	minAlignParsed = 10
)

// sourceImporter 是「抓取 + 解析 + 对齐 + 落库」这一步的全部工作。
//
// 做成可替换的函数类型的原因：单飞、202、以及"刷新期间别的写不被卡死"
// 这三条必须能在测试里断言，而它们都不该依赖真实网络。
type sourceImporter func(ctx context.Context, src store.Source) (store.ImportStats, error)

// shouldAlign 判断这次解析结果是否"健康到可以做对齐"。
//
// 对齐会**批量标记缺失**，所以必须有两道闸：
//
//  1. **必须有基线**（lastParsed > 0）。第一次刷新、或者老库刚升级上来时
//     sources.parsed_count 是 0，我们根本不知道"这次的条数算不算少"。
//     此时对齐的唯一收益是标出"上游已移除"，但风险是：上游这次恰好返回
//     残缺页/防盗链页/半截 gzip，而用户库里有几百个台 → 一次全标缺失。
//     一次刷新换不来这个风险，代价只是"第一次刷新不显示下线数"。
//     （用户升级后第一次刷新看不到"下线 N"是**刻意**的，见 ImportOptions.Align。）
//
//  2. **不能骤降**：parsed >= max(10, 上次的一半)。
//     用"一半"而不是"90%"：真实源会掉台（源站整理、频道下线），
//     掉一半以上才当成异常。宁可偶尔漏标（下次再标），也不要误标一大片。
func shouldAlign(parsed, lastParsed int) bool {
	// 没有基线 → 不对齐。
	if lastParsed <= 0 {
		return false
	}
	// 绝对下限：条数太少，多半不是一份完整清单。
	if parsed < minAlignParsed {
		return false
	}
	floor := lastParsed / 2
	if floor < minAlignParsed {
		floor = minAlignParsed
	}
	return parsed >= floor
}

// isRefreshing 报某个源此刻是否正在刷新。
//
// 这是"正在刷新"的**唯一**表示（行级布尔）。
// 不要再往 status 里塞一个 refreshing 值：两个表示并存时，
// 前端的 statusOf() 只会命中其中一个，界面上就会出现
// "状态=正常 + 正在刷新"这种自相矛盾的组合。
func (a *API) isRefreshing(sourceID int64) bool {
	a.refreshMu.Lock()
	defer a.refreshMu.Unlock()
	_, ok := a.refreshing[sourceID]
	return ok
}

// startSourceRefresh 尝试启动一次刷新。
//
// 返回 (started, busy)：
//   - started=true           刚启动
//   - started=false, busy=false  这个源已经在刷（单飞：不排队、不重跑）
//   - started=false, busy=true   全局并发槽被别的源占着
func (a *API) startSourceRefresh(reqCtx context.Context, src store.Source) (bool, bool) {
	a.refreshMu.Lock()
	if _, running := a.refreshing[src.ID]; running {
		a.refreshMu.Unlock()
		return false, false
	}
	// 非阻塞抢全局槽：抢不到就是"服务器正在刷别的源"，
	// 让客户端等几秒，而不是排队——排队只会把写锁争用拉得更长。
	select {
	case a.refreshSem <- struct{}{}:
	default:
		a.refreshMu.Unlock()
		return false, true
	}
	a.refreshing[src.ID] = a.now()
	a.refreshMu.Unlock()

	go a.runSourceRefresh(reqCtx, src)
	return true, false
}

// runSourceRefresh 是后台 worker。
func (a *API) runSourceRefresh(reqCtx context.Context, src store.Source) {
	// 关键：worker **不能**继承请求的取消信号。
	//
	// handler 返回 202 之后 r.Context() 立刻被取消，于是事务里每条 SQL 都会
	// 变成 "context canceled" —— 表现是"刷新永远失败，而且日志里没有一条
	// 像样的错误"（因为失败发生在第一条语句上）。
	//
	// WithoutCancel 保留 context 里的值（审计要读 peerIPFrom(ctx)），
	// 只去掉取消；总时长由显式 Timeout 兜住。
	ctx, cancel := context.WithTimeout(context.WithoutCancel(reqCtx), sourceRefreshTimeout)
	defer cancel()

	defer func() {
		a.refreshMu.Lock()
		delete(a.refreshing, src.ID)
		a.refreshMu.Unlock()
		<-a.refreshSem
	}()

	ip := peerIPFrom(ctx)
	start := time.Now()
	stats, err := a.importSource(ctx, src)
	elapsed := time.Since(start)

	if err != nil {
		// 失败只记错误，**不动**上一次成功的结果与计数：
		// 用户刷新管理台后仍应看到"上次成功对齐新增了 3 个台"。
		_ = a.st.MarkSourceError(ctx, src.ID, err.Error())
		slog.Warn("源刷新失败",
			"source", src.ID, "name", src.Name, "err", err, "ms", elapsed.Milliseconds())
		a.st.Audit(ctx, 0, "source.refresh.fail", fmt.Sprint(src.ID),
			truncateRunes(err.Error(), 200), ip)
		return
	}

	// 结果落库：刷新页面、甚至服务重启之后，"上游更新了吗"仍然要有答案。
	if b, jerr := json.Marshal(stats); jerr == nil {
		_ = a.st.MarkSourceRefreshed(ctx, src.ID, stats.Parsed, string(b))
	} else {
		_ = a.st.MarkSourceRefreshed(ctx, src.ID, stats.Parsed, "")
	}

	slog.Info("源刷新完成",
		"source", src.ID, "name", src.Name,
		"parsed", stats.Parsed, "inserted", stats.Inserted, "updated", stats.Updated,
		"missing", stats.Missing, "restored", stats.Restored, "unchanged", stats.Unchanged,
		"align_skipped", stats.AlignmentSkipped, "capped", stats.Capped,
		"ms", elapsed.Milliseconds())

	a.st.Audit(ctx, 0, "source.refresh", fmt.Sprint(src.ID),
		fmt.Sprintf("解析 %d，新增 %d，更新 %d，缺失 %d，恢复 %d，未变 %d，对齐跳过=%v，截断=%v，耗时 %dms",
			stats.Parsed, stats.Inserted, stats.Updated, stats.Missing, stats.Restored,
			stats.Unchanged, stats.AlignmentSkipped, stats.Capped, elapsed.Milliseconds()),
		ip)
}

// defaultImportSource 是生产实现：抓取 → 解析 → 截断 → 对齐判定 → 落库。
func (a *API) defaultImportSource(ctx context.Context, src store.Source) (store.ImportStats, error) {
	var stats store.ImportStats

	items, err := a.fetchAndParse(ctx, src)
	if err != nil {
		return stats, err
	}

	// 注意：这里**不写** Enabled:true。
	//
	// 旧实现给每条 item 硬写 Enabled:true，而 ImportChannels 又照抄这个值，
	// 于是"刷新"会把用户手动停用的频道悄悄重新打开——用户看到的是
	// 自己关掉的台自己又回来了，还以为是故障。
	// 新实现的 ImportChannels 根本不读这个字段（新建固定 enabled=1，
	// 已有频道一律不动 enabled）。
	channels := make([]store.Channel, 0, len(items))
	for _, it := range items {
		channels = append(channels, store.Channel{
			Name: it.Name, Group: it.Group, Logo: it.Logo, TvgID: it.TvgID,
			Number: it.Number, PendingURL: it.URL,
		})
	}

	// 条数上限：开事务之前截断，并把标记带进统计。
	capped := false
	if len(channels) > maxImportItems {
		channels = channels[:maxImportItems]
		capped = true
		slog.Warn("源解析条数超过上限，已截断",
			"source", src.ID, "parsed", len(items), "kept", maxImportItems)
	}

	align := shouldAlign(len(channels), src.ParsedCount)
	if !align {
		if src.ParsedCount <= 0 {
			// 首刷/老库升级：没有基线，刻意不对齐。不是异常，别用 Warn 吓人。
			slog.Info("首次刷新（无历史基线），本次跳过对齐",
				"source", src.ID, "parsed", len(channels))
		} else {
			slog.Warn("解析条数相对上次骤降，本次跳过对齐（不标记缺失）",
				"source", src.ID, "parsed", len(channels), "last_parsed", src.ParsedCount)
		}
	}

	return a.st.ImportChannels(ctx, src.ID, channels, store.ImportOptions{
		Align:  align,
		Capped: capped,
	})
}

// truncateRunes 按字符边界截断，避免把 UTF-8 从中间切断（结论会写进审计）。
func truncateRunes(s string, limit int) string {
	if limit <= 0 || len(s) <= limit {
		return s
	}
	count := 0
	for i := range s {
		if count == limit {
			return s[:i]
		}
		count++
	}
	return s
}
