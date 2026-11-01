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
}

// AddPlayEvent 记录一条播放事件。
//
// 热路径（每次起播/卡顿都会写），所以不做任何额外校验，
// 失败也只返回错误让上层决定要不要记日志。
func (s *Store) AddPlayEvent(ctx context.Context, e PlayEvent) error {
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO play_events(device_id,channel_id,event,startup_ms,detail,created_at)
		 VALUES(?,?,?,?,?,?)`,
		e.DeviceID, e.ChannelID, e.Event, e.StartupMs, e.Detail, Now())
	return err
}

// StatsOverview 是总览指标。
type StatsOverview struct {
	Days          int     `json:"days"`
	TotalPlays    int     `json:"total_plays"`
	UniqueChannels int    `json:"unique_channels"`
	UniqueDevices int     `json:"unique_devices"`
	AvgStartupMs  int     `json:"avg_startup_ms"`
	P90StartupMs  int     `json:"p90_startup_ms"`
	Stalls        int     `json:"stalls"`
	Errors        int     `json:"errors"`
	StallRate     float64 `json:"stall_rate"` // 卡顿次数 / 起播次数
	ErrorRate     float64 `json:"error_rate"`
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
	return out, nil
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
