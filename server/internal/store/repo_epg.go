package store

import (
	"context"
	"database/sql"
	"errors"
)

// ---------- EPG sources ----------

// ListEPGSources 列出节目单源。
func (s *Store) ListEPGSources(ctx context.Context) ([]EPGSource, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT id,name,url,enabled,last_sync,last_error FROM epg_sources ORDER BY id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []EPGSource{}
	for rows.Next() {
		var v EPGSource
		var en int
		if err := rows.Scan(&v.ID, &v.Name, &v.URL, &en, &v.LastSync, &v.LastError); err != nil {
			return nil, err
		}
		v.Enabled = en != 0
		out = append(out, v)
	}
	return out, rows.Err()
}

// CreateEPGSource 新增节目单源。
func (s *Store) CreateEPGSource(ctx context.Context, name, url string) (int64, error) {
	res, err := s.db.ExecContext(ctx, `INSERT INTO epg_sources(name,url,enabled) VALUES(?,?,1)`, name, url)
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
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

// MarkEPGSynced 记录同步结果。
func (s *Store) MarkEPGSynced(ctx context.Context, id int64, errMsg string) error {
	_, err := s.db.ExecContext(ctx, `UPDATE epg_sources SET last_sync=?, last_error=? WHERE id=?`, Now(), errMsg, id)
	return err
}

// UpsertEPGChannel 写入/更新一个 EPG 频道，返回其 ID。
func (s *Store) UpsertEPGChannel(ctx context.Context, sourceID int64, xmltvID, displayName, icon string) (int64, error) {
	_, err := s.db.ExecContext(ctx, `INSERT INTO epg_channels(source_id,xmltv_id,display_name,icon) VALUES(?,?,?,?)
		ON CONFLICT(xmltv_id) DO UPDATE SET display_name=excluded.display_name, icon=excluded.icon`,
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
