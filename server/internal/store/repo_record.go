package store

import (
	"context"
	"database/sql"
	"errors"
	"strings"
)

// Recording 是一条录制任务/结果。
type Recording struct {
	ID        int64  `json:"id"`
	ChannelID int64  `json:"channel_id"`
	Name      string `json:"name"`
	StartAt   int64  `json:"start_at"`
	EndAt     int64  `json:"end_at"`
	Status    string `json:"status"` // scheduled|recording|finished|failed|canceled
	Path      string `json:"-"`
	Size      int64  `json:"size"`
	CreatedAt int64  `json:"created_at"`
}

// CreateRecording 新建录制任务。
func (s *Store) CreateRecording(ctx context.Context, r Recording) (int64, error) {
	res, err := s.db.ExecContext(ctx, `
		INSERT INTO recordings(channel_id,name,start_at,end_at,status,created_at)
		VALUES(?,?,?,?,?,?)`,
		r.ChannelID, r.Name, r.StartAt, r.EndAt, "scheduled", Now())
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

// ListRecordings 列出录制任务。status 为空表示全部。
func (s *Store) ListRecordings(ctx context.Context, status string, limit int) ([]Recording, error) {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	q := `SELECT id,channel_id,name,start_at,end_at,status,path,size,created_at FROM recordings`
	var args []any
	if status != "" && status != "all" {
		q += ` WHERE status = ?`
		args = append(args, status)
	}
	q += ` ORDER BY start_at DESC LIMIT ?`
	args = append(args, limit)

	rows, err := s.db.QueryContext(ctx, q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Recording{}
	for rows.Next() {
		var v Recording
		if err := rows.Scan(&v.ID, &v.ChannelID, &v.Name, &v.StartAt, &v.EndAt,
			&v.Status, &v.Path, &v.Size, &v.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

// GetRecording 取单条。
func (s *Store) GetRecording(ctx context.Context, id int64) (*Recording, error) {
	var v Recording
	err := s.db.QueryRowContext(ctx, `
		SELECT id,channel_id,name,start_at,end_at,status,path,size,created_at
		FROM recordings WHERE id = ?`, id).
		Scan(&v.ID, &v.ChannelID, &v.Name, &v.StartAt, &v.EndAt,
			&v.Status, &v.Path, &v.Size, &v.CreatedAt)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &v, nil
}

// UpdateRecording 更新状态/路径/大小。
func (s *Store) UpdateRecording(ctx context.Context, id int64, status, path string, size int64) error {
	if path == "" {
		_, err := s.db.ExecContext(ctx, `UPDATE recordings SET status=? WHERE id=?`, status, id)
		return err
	}
	_, err := s.db.ExecContext(ctx, `UPDATE recordings SET status=?,path=?,size=? WHERE id=?`,
		status, path, size, id)
	return err
}

// DeleteRecording 删除记录（不删文件，文件由调用方决定）。
func (s *Store) DeleteRecording(ctx context.Context, id int64) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM recordings WHERE id=?`, id)
	return err
}

// RecordingIDsByChannels 列出这些频道下、状态在给定集合里的录制 id。
//
// 用途：删频道**之前**先把还在跑的录制进程停掉。
// 只改 DB 里的 status 是停不掉 ffmpeg 的——进程会继续往盘上写，
// 而 deleteChannelsTx 已经把那一行改成 canceled 了，
// 结果是一个没人认识、也没人负责收尾的写盘任务。
func (s *Store) RecordingIDsByChannels(ctx context.Context, channelIDs []int64, statuses []string) ([]int64, error) {
	if len(channelIDs) == 0 || len(statuses) == 0 {
		return nil, nil
	}
	chPH := strings.Repeat("?,", len(channelIDs))
	chPH = chPH[:len(chPH)-1]
	stPH := strings.Repeat("?,", len(statuses))
	stPH = stPH[:len(stPH)-1]

	args := make([]any, 0, len(channelIDs)+len(statuses))
	for _, id := range channelIDs {
		args = append(args, id)
	}
	for _, st := range statuses {
		args = append(args, st)
	}

	rows, err := s.db.QueryContext(ctx,
		`SELECT id FROM recordings WHERE channel_id IN (`+chPH+`) AND status IN (`+stPH+`)`,
		args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []int64{}
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, rows.Err()
}

// RecordingsByStatus 给调度器用：取某状态、按开始时间升序的一批。
func (s *Store) RecordingsByStatus(ctx context.Context, status string, limit int) ([]Recording, error) {
	if limit <= 0 {
		limit = 50
	}
	rows, err := s.db.QueryContext(ctx, `
		SELECT id,channel_id,name,start_at,end_at,status,path,size,created_at
		FROM recordings WHERE status = ? ORDER BY start_at ASC LIMIT ?`, status, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Recording{}
	for rows.Next() {
		var v Recording
		if err := rows.Scan(&v.ID, &v.ChannelID, &v.Name, &v.StartAt, &v.EndAt,
			&v.Status, &v.Path, &v.Size, &v.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

// RecordingStats 概览用的录制统计。
func (s *Store) RecordingStats(ctx context.Context) (total int, totalBytes int64, err error) {
	err = s.db.QueryRowContext(ctx, `
		SELECT COUNT(*), COALESCE(SUM(size),0) FROM recordings WHERE status='finished'`).
		Scan(&total, &totalBytes)
	return
}

// SanitizeStatus 把外部传入的状态收敛到允许值。
func SanitizeStatus(v string) string {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "scheduled", "recording", "finished", "failed", "canceled":
		return strings.ToLower(strings.TrimSpace(v))
	}
	return "scheduled"
}
