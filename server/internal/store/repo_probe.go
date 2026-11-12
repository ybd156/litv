package store

import (
	"context"
	"strings"
)

// ProbeTarget 是待探测的线路。
type ProbeTarget struct {
	ID  int64
	URL string
}

// DeadChannel 是一个"所有线路都挂了"的频道。
type DeadChannel struct {
	ID    int64  `json:"id"`
	Name  string `json:"name"`
	Group string `json:"group"`
	Total int    `json:"total"`
}

// ChannelsWithNoHealthyStream 返回有线路但没有任何健康线路的频道。
// 这是告警的判定依据：只要还有一个线路能播，就不该打扰用户。
func (s *Store) ChannelsWithNoHealthyStream(ctx context.Context) ([]DeadChannel, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT c.id, c.name, c.group_name, COUNT(st.id) AS total
		FROM channels c
		JOIN streams st ON st.channel_id = c.id
		WHERE c.enabled = 1
		GROUP BY c.id
		HAVING SUM(CASE WHEN st.health = 1 THEN 1 ELSE 0 END) = 0
		   AND SUM(CASE WHEN st.health = -1 THEN 1 ELSE 0 END) = 0
		ORDER BY c.name`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []DeadChannel{}
	for rows.Next() {
		var v DeadChannel
		if err := rows.Scan(&v.ID, &v.Name, &v.Group, &v.Total); err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

// ListStreamsForProbe 取一批线路用于健康探测。
//
// 排序策略：**先探最久没探过的**，这样轮转均匀；
// 已经确认失效的也照探（源可能恢复），但排在后面。
func (s *Store) ListStreamsForProbe(ctx context.Context, limit int) ([]ProbeTarget, error) {
	if limit <= 0 || limit > 2000 {
		limit = 500
	}
	rows, err := s.db.QueryContext(ctx, `
		SELECT id, url FROM streams
		WHERE url <> ''
		ORDER BY CASE health WHEN -1 THEN 0 WHEN 1 THEN 1 ELSE 2 END, last_check ASC
		LIMIT ?`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []ProbeTarget{}
	for rows.Next() {
		var t ProbeTarget
		if err := rows.Scan(&t.ID, &t.URL); err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

// AutoMapChannels 按频道名把频道关联到 EPG 频道，返回本次匹配上的数量。
//
// 具体规则见 AutoMapChannelsReport（那里有完整报告与置信度）。
// 这里保留薄封装，是为了让调用方不必关心报告结构。
func (s *Store) AutoMapChannels(ctx context.Context) (int, error) {
	rep, err := s.AutoMapChannelsReport(ctx)
	return rep.Matched, err
}

// normChannelName 归一化频道名用于匹配：去空格/横线/下划线，转小写，
// 去掉「高清」「HD」这类后缀——源里和 EPG 里的写法经常差这几个字。
func normChannelName(s string) string {
	s = strings.ToLower(strings.TrimSpace(s))
	for _, cut := range []string{"高清", "标清", "超清", "hd", "sd", " ", "-", "_", "·"} {
		s = strings.ReplaceAll(s, cut, "")
	}
	return s
}
