package store

import "context"

// ProbeTarget 是待探测的线路。
type ProbeTarget struct {
	ID  int64
	URL string
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
