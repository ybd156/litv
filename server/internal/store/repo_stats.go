package store

import (
	"context"
	"time"
)

// PlayEvent 是一条播放质量事件。
type PlayEvent struct {
	ID        int64  `json:"id"`
	DeviceID  int64  `json:"device_id"`
	ChannelID int64  `json:"channel_id"`
	Event     string `json:"event"`
	StartupMs int    `json:"startup_ms"`
	Detail    string `json:"detail"`
	CreatedAt int64  `json:"created_at"`

	// A3（第二批）：QoE 埋点。
	//
	// 老客户端不传这些字段 → 全 0/空串；统计侧一律用 "> 0" 过滤，
	// 所以不需要回填，也不会把"没数据"算成"0 秒卡顿"。
	StallMs int    `json:"stall_ms"` // stall 事件：本次卡顿持续多久
	WatchMs int    `json:"watch_ms"` // stop 事件：本次观看多久（rebuffer 的分母）
	Path    string `json:"path"`     // ts | hls | remux | direct（自由文本）
	Engine  string `json:"engine"`   // exo | mpv | vlc | avs3（自由文本）
}

// AddPlayEvent 记录一条播放事件。
//
// 热路径（每次起播/卡顿都会写），所以不做任何额外校验，
// 失败也只返回错误让上层决定要不要记日志。
func (s *Store) AddPlayEvent(ctx context.Context, e PlayEvent) error {
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO play_events(device_id,channel_id,event,startup_ms,detail,created_at,stall_ms,watch_ms,path,engine)
		 VALUES(?,?,?,?,?,?,?,?,?,?)`,
		e.DeviceID, e.ChannelID, e.Event, e.StartupMs, e.Detail, Now(),
		e.StallMs, e.WatchMs, e.Path, e.Engine)
	return err
}

// StatsOverview 是总览指标。
type StatsOverview struct {
	Days           int     `json:"days"`
	TotalPlays     int     `json:"total_plays"`
	UniqueChannels int     `json:"unique_channels"`
	UniqueDevices  int     `json:"unique_devices"`
	AvgStartupMs   int     `json:"avg_startup_ms"`
	P90StartupMs   int     `json:"p90_startup_ms"`
	Stalls         int     `json:"stalls"`
	Errors         int     `json:"errors"`
	// StallRate 是「卡顿**次数** / 起播次数」，它是事件计数比，**不是**
	// rebuffer ratio。想知道"看了多久里卡了多久"要看 RebufferRatio。
	// 保留它是因为管理台已经在用，且两个指标回答的是不同问题。
	StallRate float64 `json:"stall_rate"`
	ErrorRate float64 `json:"error_rate"`

	// RebufferRatio = Σstall_ms / Σwatch_ms（"观看时长里有百分之多少在卡"）。
	//
	// 分母为 0 时返回 0（而不是 null/Inf），见 StatsOverview 里的短路注释。
	RebufferRatio float64 `json:"rebuffer_ratio"`

	// 按传输路径 / 播放器分组的起播 p90。
	// 分不开这两种维度就看不出"是 remux 慢还是 VLC 慢"。
	ByPath   []StartupGroup `json:"by_path"`
	ByEngine []StartupGroup `json:"by_engine"`
}

// StartupGroup 是一个分组（path 或 engine）的起播统计。
type StartupGroup struct {
	Key          string `json:"key"`
	Plays        int    `json:"plays"`
	P90StartupMs int    `json:"p90_startup_ms"`
}

// StatsOverview 统计最近 N 天的播放质量。
func (s *Store) StatsOverview(ctx context.Context, days int) (*StatsOverview, error) {
	if days <= 0 {
		days = 7
	}
	since := time.Now().AddDate(0, 0, -days).Unix()
	out := &StatsOverview{Days: days}

	row := s.db.QueryRowContext(ctx, `
		SELECT
			COALESCE(SUM(CASE WHEN event='start' THEN 1 ELSE 0 END),0),
			COUNT(DISTINCT CASE WHEN event='start' THEN channel_id END),
			COUNT(DISTINCT device_id),
			COALESCE(SUM(CASE WHEN event='stall' THEN 1 ELSE 0 END),0),
			COALESCE(SUM(CASE WHEN event='error' THEN 1 ELSE 0 END),0)
		FROM play_events WHERE created_at >= ?`, since)
	if err := row.Scan(&out.TotalPlays, &out.UniqueChannels, &out.UniqueDevices,
		&out.Stalls, &out.Errors); err != nil {
		return nil, err
	}

	// 平均起播
	_ = s.db.QueryRowContext(ctx, `
		SELECT COALESCE(AVG(startup_ms),0) FROM play_events
		WHERE created_at >= ? AND event='start' AND startup_ms > 0`, since).
		Scan(&out.AvgStartupMs)

	// P90 起播：SQLite 没有百分位函数，取第 90% 位的值
	var total int
	_ = s.db.QueryRowContext(ctx, `
		SELECT COUNT(*) FROM play_events
		WHERE created_at >= ? AND event='start' AND startup_ms > 0`, since).Scan(&total)
	if total > 0 {
		offset := int(float64(total) * 0.9)
		if offset >= total {
			offset = total - 1
		}
		_ = s.db.QueryRowContext(ctx, `
			SELECT startup_ms FROM play_events
			WHERE created_at >= ? AND event='start' AND startup_ms > 0
			ORDER BY startup_ms LIMIT 1 OFFSET ?`, since, offset).
			Scan(&out.P90StartupMs)
	}

	if out.TotalPlays > 0 {
		out.StallRate = float64(out.Stalls) / float64(out.TotalPlays)
		out.ErrorRate = float64(out.Errors) / float64(out.TotalPlays)
	}

	// ---- A3：rebuffer ratio ----
	//
	// 必须短路：历史行 watch_ms 全是 0（老库/老客户端）。
	// 只要有一条新上报带 stall_ms>0，Σwatch_ms 就是 0 → 除零 → +Inf，
	// 而 encoding/json 遇到 +Inf/NaN 会**返回错误**，
	// 于是 /stats/overview 整体 500 —— 不是数字难看，是整页坏掉。
	//
	// 选择返回 0 而不是 null：前端拿 float 直接算百分比，少一个 null 分支；
	// "分母为 0"这类语义由 TotalPlays/WatchMs 是否存在来表达已经够了。
	var sumStall, sumWatch int64
	_ = s.db.QueryRowContext(ctx,
		`SELECT COALESCE(SUM(stall_ms),0), COALESCE(SUM(watch_ms),0)
		 FROM play_events WHERE created_at >= ?`, since).Scan(&sumStall, &sumWatch)
	if sumWatch > 0 {
		out.RebufferRatio = float64(sumStall) / float64(sumWatch)
	}

	// ---- A3：按 path / engine 分组的起播 p90 ----
	out.ByPath = s.startupP90ByGroup(ctx, since, "path")
	out.ByEngine = s.startupP90ByGroup(ctx, since, "engine")

	return out, nil
}

// startupP90ByGroup 按某一列分组算起播 p90。
//
// column 只接受代码里的字面量（"path" / "engine"），不接受外部输入。
// 过滤条件与全局 p90 一致：event='start' 且 startup_ms > 0 ——
// 不排除 0 的话，老客户端那些没上报耗时的行会把每组 p90 拉到 0。
func (s *Store) startupP90ByGroup(ctx context.Context, since int64, column string) []StartupGroup {
	switch column {
	case "path", "engine":
	default:
		return nil
	}
	out := []StartupGroup{}

	rows, err := s.db.QueryContext(ctx,
		`SELECT `+column+` AS k, COUNT(*) AS n
		 FROM play_events
		 WHERE created_at >= ? AND event='start' AND startup_ms > 0 AND `+column+` <> ''
		 GROUP BY k ORDER BY n DESC`, since)
	if err != nil {
		return out
	}
	type grp struct {
		key string
		n   int
	}
	groups := []grp{}
	for rows.Next() {
		var g grp
		if err := rows.Scan(&g.key, &g.n); err != nil {
			rows.Close()
			return out
		}
		groups = append(groups, g)
	}
	rerr := rows.Err()
	rows.Close()
	if rerr != nil {
		return out
	}

	for _, g := range groups {
		offset := int(float64(g.n) * 0.9)
		if offset >= g.n {
			offset = g.n - 1
		}
		var p90 int
		if err := s.db.QueryRowContext(ctx,
			`SELECT startup_ms FROM play_events
			 WHERE created_at >= ? AND event='start' AND startup_ms > 0 AND `+column+` = ?
			 ORDER BY startup_ms LIMIT 1 OFFSET ?`, since, g.key, offset).Scan(&p90); err != nil {
			continue
		}
		out = append(out, StartupGroup{Key: g.key, Plays: g.n, P90StartupMs: p90})
	}
	return out
}

// ChannelStat 是单个频道的质量指标。
type ChannelStat struct {
	ChannelID    int64   `json:"channel_id"`
	Name         string  `json:"name"`
	Plays        int     `json:"plays"`
	AvgStartupMs int     `json:"avg_startup_ms"`
	Stalls       int     `json:"stalls"`
	Errors       int     `json:"errors"`
	StallRate    float64 `json:"stall_rate"`
}

// StatsByChannel 按频道聚合，按播放次数降序。
func (s *Store) StatsByChannel(ctx context.Context, days, limit int) ([]ChannelStat, error) {
	if days <= 0 {
		days = 7
	}
	if limit <= 0 || limit > 200 {
		limit = 20
	}
	since := time.Now().AddDate(0, 0, -days).Unix()

	rows, err := s.db.QueryContext(ctx, `
		SELECT e.channel_id,
		       COALESCE(c.name,'已删除频道'),
		       SUM(CASE WHEN e.event='start' THEN 1 ELSE 0 END),
		       COALESCE(AVG(CASE WHEN e.event='start' AND e.startup_ms>0 THEN e.startup_ms END),0),
		       SUM(CASE WHEN e.event='stall' THEN 1 ELSE 0 END),
		       SUM(CASE WHEN e.event='error' THEN 1 ELSE 0 END)
		FROM play_events e LEFT JOIN channels c ON c.id = e.channel_id
		WHERE e.created_at >= ?
		GROUP BY e.channel_id
		ORDER BY 3 DESC
		LIMIT ?`, since, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := []ChannelStat{}
	for rows.Next() {
		var v ChannelStat
		if err := rows.Scan(&v.ChannelID, &v.Name, &v.Plays, &v.AvgStartupMs, &v.Stalls, &v.Errors); err != nil {
			return nil, err
		}
		if v.Plays > 0 {
			v.StallRate = float64(v.Stalls) / float64(v.Plays)
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

// PurgePlayEvents 清理过旧的事件（默认保留 30 天）。
func (s *Store) PurgePlayEvents(ctx context.Context, before int64) (int64, error) {
	res, err := s.db.ExecContext(ctx, `DELETE FROM play_events WHERE created_at < ?`, before)
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}

// PurgeOldAuditLogs 清理过旧的操作日志。
//
// auth.* 那部分由 auditAuthTx 按行数上限自己维护；这里主要清掉
// 非认证类日志（play、device.*、epg.*、config.* 等）。
// 它们只用于管理台展示和排查，没有长期保留价值，但以前从来没人删——
// 长期运行的 NAS 上 audit_logs 会一直长。
func (s *Store) PurgeOldAuditLogs(ctx context.Context, before int64) (int64, error) {
	res, err := s.db.ExecContext(ctx, `DELETE FROM audit_logs WHERE created_at < ?`, before)
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}
