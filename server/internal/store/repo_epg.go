package store

import (
	"context"
	"database/sql"
	"errors"
)

// ---------- EPG sources ----------

// epgSourceCols 是查询 EPG 源的统一列清单，避免各处 SELECT 漂移。
const epgSourceCols = `id,name,url,enabled,priority,user_agent,referer,kind,last_sync,last_attempt,last_error`

func scanEPGSource(sc interface {
	Scan(dest ...any) error
}) (EPGSource, error) {
	var v EPGSource
	var en int
	err := sc.Scan(&v.ID, &v.Name, &v.URL, &en, &v.Priority, &v.UserAgent, &v.Referer,
		&v.Kind, &v.LastSync, &v.LastAttempt, &v.LastError)
	v.Enabled = en != 0
	return v, err
}

// ListEPGSources 列出节目单源。
//
// 按 priority 倒序：优先级高的先同步，配合 UpsertEPGChannel 的
// 「高优先级覆盖低优先级」，多源合并时结果才可预期。
func (s *Store) ListEPGSources(ctx context.Context) ([]EPGSource, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT `+epgSourceCols+` FROM epg_sources ORDER BY priority DESC, id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []EPGSource{}
	for rows.Next() {
		v, err := scanEPGSource(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

// GetEPGSource 取单个源。
func (s *Store) GetEPGSource(ctx context.Context, id int64) (*EPGSource, error) {
	row := s.db.QueryRowContext(ctx, `SELECT `+epgSourceCols+` FROM epg_sources WHERE id=?`, id)
	v, err := scanEPGSource(row)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &v, nil
}

// CreateEPGSource 新增节目单源（兼容旧调用：默认 url 源、优先级 0）。
func (s *Store) CreateEPGSource(ctx context.Context, name, url string) (int64, error) {
	return s.CreateEPGSourceEx(ctx, EPGSource{Name: name, URL: url, Enabled: true, Kind: "url"})
}

// CreateEPGSourceEx 新增节目单源（完整字段）。
func (s *Store) CreateEPGSourceEx(ctx context.Context, v EPGSource) (int64, error) {
	kind := v.Kind
	if kind == "" {
		kind = "url"
	}
	res, err := s.db.ExecContext(ctx,
		`INSERT INTO epg_sources(name,url,enabled,priority,user_agent,referer,kind) VALUES(?,?,?,?,?,?,?)`,
		v.Name, v.URL, boolInt(v.Enabled), v.Priority, v.UserAgent, v.Referer, kind)
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

// UpdateEPGSource 修改节目单源。
func (s *Store) UpdateEPGSource(ctx context.Context, v EPGSource) error {
	kind := v.Kind
	if kind == "" {
		kind = "url"
	}
	res, err := s.db.ExecContext(ctx,
		`UPDATE epg_sources SET name=?, url=?, enabled=?, priority=?, user_agent=?, referer=?, kind=? WHERE id=?`,
		v.Name, v.URL, boolInt(v.Enabled), v.Priority, v.UserAgent, v.Referer, kind, v.ID)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// DeleteEPGSource 删除节目单源及其数据。
func (s *Store) DeleteEPGSource(ctx context.Context, id int64) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, `DELETE FROM epg_programs WHERE epg_channel_id IN (SELECT id FROM epg_channels WHERE source_id=?)`, id); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM epg_channels WHERE source_id=?`, id); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM epg_sources WHERE id=?`, id); err != nil {
		return err
	}
	return tx.Commit()
}

// MarkEPGSynced 记录同步结果（保留旧名字，转调 MarkEPGAttempt）。
func (s *Store) MarkEPGSynced(ctx context.Context, id int64, errMsg string) error {
	return s.MarkEPGAttempt(ctx, id, errMsg)
}

// MarkEPGAttempt 记录一次同步尝试。
//
// 成败必须分开记：
//   - 成功：更新 last_sync 与 last_attempt，清空 last_error
//   - 失败：只更新 last_attempt 与 last_error，**不动 last_sync**
//
// 以前不分成败，一律把 last_sync 写成当前时间，于是界面上「上次刷新」
// 在失败后照样显示成刚刷新过，而错误信息又没被展示出来——
// 用户看到的是「一切正常，但节目单是空的」。
func (s *Store) MarkEPGAttempt(ctx context.Context, id int64, errMsg string) error {
	now := Now()
	if errMsg == "" {
		_, err := s.db.ExecContext(ctx,
			`UPDATE epg_sources SET last_attempt=?, last_sync=?, last_error='' WHERE id=?`,
			now, now, id)
		return err
	}
	_, err := s.db.ExecContext(ctx,
		`UPDATE epg_sources SET last_attempt=?, last_error=? WHERE id=?`,
		now, errMsg, id)
	return err
}

// EPGSourceStat 是一个 EPG 源的规模统计。
type EPGSourceStat struct {
	Channels int
	Programs int
}

// EPGSourceStats 一次查出所有 EPG 源的频道数与节目条数。
//
// 用一条 GROUP BY 而不是每个源查一次：源多时 N+1 会把列表页拖慢。
func (s *Store) EPGSourceStats(ctx context.Context) (map[int64]EPGSourceStat, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT c.source_id, COUNT(DISTINCT c.id), COUNT(p.id)
		FROM epg_channels c
		LEFT JOIN epg_programs p ON p.epg_channel_id = c.id
		GROUP BY c.source_id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[int64]EPGSourceStat{}
	for rows.Next() {
		var sid int64
		var st EPGSourceStat
		if err := rows.Scan(&sid, &st.Channels, &st.Programs); err != nil {
			return nil, err
		}
		out[sid] = st
	}
	return out, rows.Err()
}

// UpsertEPGChannel 写入/更新一个 EPG 频道，返回其 ID。
//
// A5：多源合并时按优先级决定谁说了算。同一 xmltv_id 已存在时，
// 只有**新源优先级 >= 原源优先级**才覆盖（display_name / icon / source_id）。
// 否则保留原记录——这样低优先级的源不会把高优先级源的频道名冲掉。
func (s *Store) UpsertEPGChannel(ctx context.Context, sourceID int64, xmltvID, displayName, icon string) (int64, error) {
	_, err := s.db.ExecContext(ctx, `INSERT INTO epg_channels(source_id,xmltv_id,display_name,icon) VALUES(?,?,?,?)
		ON CONFLICT(xmltv_id) DO UPDATE SET
			display_name=excluded.display_name,
			icon=excluded.icon,
			source_id=excluded.source_id
		WHERE
			COALESCE((SELECT priority FROM epg_sources WHERE id=excluded.source_id),0)
			>= COALESCE((SELECT priority FROM epg_sources WHERE id=epg_channels.source_id),0)`,
		sourceID, xmltvID, displayName, icon)
	if err != nil {
		return 0, err
	}
	var id int64
	err = s.db.QueryRowContext(ctx, `SELECT id FROM epg_channels WHERE xmltv_id=?`, xmltvID).Scan(&id)
	return id, err
}

// ReplacePrograms 替换某 EPG 频道的节目单（同一频道重复同步时避免重复堆积）。
func (s *Store) ReplacePrograms(ctx context.Context, epgChannelID int64, progs []EPGProgram) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, `DELETE FROM epg_programs WHERE epg_channel_id=?`, epgChannelID); err != nil {
		return err
	}
	stmt, err := tx.PrepareContext(ctx, `INSERT INTO epg_programs(epg_channel_id,start_at,end_at,title,description,category) VALUES(?,?,?,?,?,?)`)
	if err != nil {
		return err
	}
	defer stmt.Close()
	for _, p := range progs {
		if _, err := stmt.ExecContext(ctx, epgChannelID, p.StartAt, p.EndAt, p.Title, p.Description, p.Category); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// EPGChannelView 是给「频道↔EPG 映射」界面用的视图结构。
// 前端需要 name / source / program_count 才能画出可拖拽的双列界面。
type EPGChannelView struct {
	ID           int64  `json:"id"`
	XmltvID      string `json:"xmltv_id"`
	Name         string `json:"name"`
	DisplayName  string `json:"display_name"`
	Icon         string `json:"icon"`
	Source       string `json:"source"`
	SourceID     int64  `json:"source_id"`
	ProgramCount int    `json:"program_count"`
}

// ListEPGChannels 列出 EPG 频道（供映射界面），带来源名与节目数量。
func (s *Store) ListEPGChannels(ctx context.Context) ([]EPGChannelView, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT c.id, c.xmltv_id, COALESCE(NULLIF(c.display_name,''), c.xmltv_id), c.display_name, c.icon,
		       COALESCE(s.name,''), c.source_id,
		       (SELECT COUNT(*) FROM epg_programs p WHERE p.epg_channel_id = c.id)
		FROM epg_channels c LEFT JOIN epg_sources s ON s.id = c.source_id
		ORDER BY c.display_name, c.xmltv_id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []EPGChannelView{}
	for rows.Next() {
		var v EPGChannelView
		if err := rows.Scan(&v.ID, &v.XmltvID, &v.Name, &v.DisplayName, &v.Icon,
			&v.Source, &v.SourceID, &v.ProgramCount); err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

// ProgramsByXmltvID 按 xmltv_id 取节目单（频道表里存的是 epg_id 字符串）。
func (s *Store) ProgramsByXmltvID(ctx context.Context, xmltvID string, from, to int64) ([]EPGProgram, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT p.id,p.epg_channel_id,p.start_at,p.end_at,p.title,p.description,p.category
		FROM epg_programs p JOIN epg_channels c ON c.id = p.epg_channel_id
		WHERE c.xmltv_id=? AND p.end_at >= ? AND p.start_at <= ?
		ORDER BY p.start_at`, xmltvID, from, to)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []EPGProgram{}
	for rows.Next() {
		var p EPGProgram
		if err := rows.Scan(&p.ID, &p.EpgChannelID, &p.StartAt, &p.EndAt, &p.Title, &p.Description, &p.Category); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

// PurgeOldPrograms 清理过期节目单。
func (s *Store) PurgeOldPrograms(ctx context.Context, before int64) (int64, error) {
	res, err := s.db.ExecContext(ctx, `DELETE FROM epg_programs WHERE end_at < ?`, before)
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}

// SetChannelEpgID 手动建立频道 → EPG 频道的映射。
func (s *Store) SetChannelEpgID(ctx context.Context, channelID int64, epgID string) error {
	_, err := s.db.ExecContext(ctx, `UPDATE channels SET epg_id=? WHERE id=?`, epgID, channelID)
	return err
}

// ErrNoRows 暴露给上层判断。
var ErrNoRows = sql.ErrNoRows

// IsNotFound 便捷判断。
func IsNotFound(err error) bool { return errors.Is(err, sql.ErrNoRows) || errors.Is(err, ErrNotFound) }
