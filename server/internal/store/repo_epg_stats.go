package store

import (
	"context"
	"strings"
)

// EPGStats 是 EPG 模块的总览统计（D1）。
type EPGStats struct {
	Sources        int `json:"sources"`
	EnabledSources int `json:"enabled_sources"`
	Channels       int `json:"channels"`
	Programs       int `json:"programs"`
	// EarliestStart / LatestEnd 是节目覆盖的时间范围（unix 秒），0 表示没有节目。
	EarliestStart int64 `json:"earliest_start"`
	LatestEnd     int64 `json:"latest_end"`
	// LastSync 是所有源里最近一次成功同步的时间。
	LastSync int64 `json:"last_sync"`
	// DBBytes 数据库文件占用（页数 × 页大小），用来判断 EPG 是不是吃太多空间。
	DBBytes int64 `json:"db_bytes"`
}

// EPGStats 汇总 EPG 的规模与覆盖范围。
//
// 一次请求把界面要的数字都算好：源数量、频道数、节目条数、
// 覆盖起止时间、最近同步时间、库体积。分开查是为了可读性，
// 这些查询都走索引或全表 COUNT，量级在十万级以内很快。
func (s *Store) EPGStats(ctx context.Context) (EPGStats, error) {
	var st EPGStats

	if err := s.db.QueryRowContext(ctx,
		`SELECT COUNT(*), COALESCE(SUM(CASE WHEN enabled!=0 THEN 1 ELSE 0 END),0), COALESCE(MAX(last_sync),0)
		 FROM epg_sources`).
		Scan(&st.Sources, &st.EnabledSources, &st.LastSync); err != nil {
		return st, err
	}
	if err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM epg_channels`).Scan(&st.Channels); err != nil {
		return st, err
	}
	if err := s.db.QueryRowContext(ctx,
		`SELECT COUNT(*), COALESCE(MIN(start_at),0), COALESCE(MAX(end_at),0) FROM epg_programs`).
		Scan(&st.Programs, &st.EarliestStart, &st.LatestEnd); err != nil {
		return st, err
	}

	// 库体积：page_count × page_size。SQLite 没有直接的"文件大小"查询。
	var pages, pageSize int64
	if err := s.db.QueryRowContext(ctx, `PRAGMA page_count`).Scan(&pages); err == nil {
		if err := s.db.QueryRowContext(ctx, `PRAGMA page_size`).Scan(&pageSize); err == nil {
			st.DBBytes = pages * pageSize
		}
	}
	return st, nil
}

// EPGProgramHit 是节目搜索结果（D5）。
type EPGProgramHit struct {
	EpgChannelID int64  `json:"epg_channel_id"`
	XmltvID      string `json:"xmltv_id"`
	ChannelName  string `json:"channel_name"`
	Title        string `json:"title"`
	StartAt      int64  `json:"start_at"`
	EndAt        int64  `json:"end_at"`
	Category     string `json:"category"`
}

// SearchPrograms 按节目名搜索（D5）。
//
// 用 LIKE 而不是全文索引：节目量级在十万级，LIKE 走全表也就几十毫秒，
// 引入 FTS5 反而多一套迁移和同步负担。
// keyword 为空时返回空，避免把整库拉出来。
func (s *Store) SearchPrograms(ctx context.Context, keyword string, limit int) ([]EPGProgramHit, error) {
	keyword = strings.TrimSpace(keyword)
	if keyword == "" {
		return []EPGProgramHit{}, nil
	}
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	like := "%" + keyword + "%"
	rows, err := s.db.QueryContext(ctx, `
		SELECT p.epg_channel_id,
		       COALESCE(c.xmltv_id,''),
		       COALESCE(NULLIF(c.display_name,''), c.xmltv_id, ''),
		       p.title, p.start_at, p.end_at, p.category
		FROM epg_programs p
		LEFT JOIN epg_channels c ON c.id = p.epg_channel_id
		WHERE p.title LIKE ?
		ORDER BY p.start_at DESC
		LIMIT ?`, like, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []EPGProgramHit{}
	for rows.Next() {
		var h EPGProgramHit
		if err := rows.Scan(&h.EpgChannelID, &h.XmltvID, &h.ChannelName,
			&h.Title, &h.StartAt, &h.EndAt, &h.Category); err != nil {
			return nil, err
		}
		out = append(out, h)
	}
	return out, rows.Err()
}

// AuditEntry 是一条审计记录（D3）。
type AuditEntry struct {
	ID        int64  `json:"id"`
	Action    string `json:"action"`
	Target    string `json:"target"`
	Detail    string `json:"detail"`
	CreatedAt int64  `json:"created_at"`
}

// ListAuditByPrefix 按 action 前缀取最近的审计记录。
//
// EPG 相关动作统一用 "epg." 前缀，这样一条查询就能覆盖
// 刷新/导入/清理/改源，不用为每类动作各开一个接口。
func (s *Store) ListAuditByPrefix(ctx context.Context, prefix string, limit int) ([]AuditEntry, error) {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	rows, err := s.db.QueryContext(ctx, `
		SELECT id, action, target, detail, created_at
		FROM audit_logs
		WHERE action LIKE ?
		ORDER BY id DESC
		LIMIT ?`, prefix+"%", limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []AuditEntry{}
	for rows.Next() {
		var e AuditEntry
		if err := rows.Scan(&e.ID, &e.Action, &e.Target, &e.Detail, &e.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}
