package store

import (
	"context"
	"sort"
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

// AutoMapChannels 按频道名把频道关联到 EPG 频道。
//
// 为什么需要：源 M3U 里常常没有 tvg-id，导入后 channels.epg_id 全空，
// 电视端的节目单和「当前节目」就永远是空的。管理台虽能手动映射，
// 但几百个频道一个个点不现实。
//
// 归一化规则：去掉空格、横线、下划线，转小写，再去掉常见的「高清/HD」后缀。
// 只填空值，不覆盖用户手动设过的映射。
func (s *Store) AutoMapChannels(ctx context.Context) (int, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT id, name FROM channels WHERE epg_id=''`)
	if err != nil {
		return 0, err
	}
	type ch struct {
		id   int64
		name string
	}
	var pending []ch
	for rows.Next() {
		var c ch
		if rows.Scan(&c.id, &c.name) == nil {
			pending = append(pending, c)
		}
	}
	rows.Close()
	if len(pending) == 0 {
		return 0, nil
	}

	// EPG 侧：归一化名 -> xmltv_id
	erows, err := s.db.QueryContext(ctx, `SELECT xmltv_id, display_name FROM epg_channels`)
	if err != nil {
		return 0, err
	}
	index := map[string]string{}
	for erows.Next() {
		var xmltv, disp string
		if erows.Scan(&xmltv, &disp) == nil {
			k := normChannelName(disp)
			if k != "" {
				index[k] = xmltv
			}
		}
	}
	erows.Close()

	// 前缀匹配用：按长度从长到短排，优先匹配更具体的名字
	keys := make([]string, 0, len(index))
	for k := range index {
		keys = append(keys, k)
	}
	sort.Slice(keys, func(i, j int) bool { return len(keys[i]) > len(keys[j]) })

	updated := 0
	for _, c := range pending {
		n := normChannelName(c.name)
		xmltv, ok := index[n]
		if !ok {
			// 前缀兜底：「cctv1综合」应该匹配到 EPG 的「cctv1」
			for _, k := range keys {
				if k == "" || !strings.HasPrefix(n, k) {
					continue
				}
				// 前缀后面紧跟数字就不算（cctv1 不能吃掉 cctv10）
				rest := n[len(k):]
				if rest != "" && rest[0] >= '0' && rest[0] <= '9' {
					continue
				}
				xmltv, ok = index[k], true
				break
			}
		}
		if ok {
			if _, err := s.db.ExecContext(ctx,
				`UPDATE channels SET epg_id=? WHERE id=? AND epg_id=''`, xmltv, c.id); err == nil {
				updated++
			}
		}
	}
	return updated, nil
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
