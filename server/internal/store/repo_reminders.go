package store

import (
	"context"
	"database/sql"
	"errors"
)

// Reminder 是一条节目开播提醒（C5）。
//
// 按设备存：同一档节目，电视和手机可以各自设置提醒，
// 推送时也各推各的——共用一份会互相干扰。
type Reminder struct {
	ID          int64  `json:"id"`
	DeviceID    int64  `json:"device_id"`
	ChannelID   int64  `json:"channel_id"`
	ChannelName string `json:"channel_name"`
	Title       string `json:"title"`
	StartAt     int64  `json:"start_at"`
	Fired       bool   `json:"fired"`
	CreatedAt   int64  `json:"created_at"`
}

// CreateReminder 新建提醒。同一设备同一频道同一时刻只保留一条，
// 重复设置直接返回已有 id（避免用户连点两下出现两条推送）。
func (s *Store) CreateReminder(ctx context.Context, deviceID, channelID int64, channelName, title string, startAt int64) (int64, error) {
	var existing int64
	err := s.db.QueryRowContext(ctx,
		`SELECT id FROM epg_reminders WHERE device_id=? AND channel_id=? AND start_at=?`,
		deviceID, channelID, startAt).Scan(&existing)
	if err == nil {
		return existing, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return 0, err
	}
	res, err := s.db.ExecContext(ctx,
		`INSERT INTO epg_reminders(device_id,channel_id,channel_name,title,start_at,created_at)
		 VALUES(?,?,?,?,?,?)`,
		deviceID, channelID, channelName, title, startAt, Now())
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

// ListReminders 返回某设备**未来**的提醒（已过期的顺手清掉）。
func (s *Store) ListReminders(ctx context.Context, deviceID int64) ([]Reminder, error) {
	now := Now()
	// 过期的提醒留着没意义，顺手清理，避免列表越攒越多
	_, _ = s.db.ExecContext(ctx,
		`DELETE FROM epg_reminders WHERE device_id=? AND start_at < ?`, deviceID, now-3600)

	rows, err := s.db.QueryContext(ctx,
		`SELECT id,device_id,channel_id,channel_name,title,start_at,fired,created_at
		 FROM epg_reminders WHERE device_id=? ORDER BY start_at`, deviceID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Reminder{}
	for rows.Next() {
		var r Reminder
		var fired int
		if err := rows.Scan(&r.ID, &r.DeviceID, &r.ChannelID, &r.ChannelName,
			&r.Title, &r.StartAt, &fired, &r.CreatedAt); err != nil {
			return nil, err
		}
		r.Fired = fired != 0
		out = append(out, r)
	}
	return out, rows.Err()
}

// DeleteReminder 删除某设备的提醒（带 device_id 条件，不能删别人的）。
func (s *Store) DeleteReminder(ctx context.Context, deviceID, id int64) error {
	res, err := s.db.ExecContext(ctx,
		`DELETE FROM epg_reminders WHERE id=? AND device_id=?`, id, deviceID)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// DueReminders 取出到点该推的提醒。
//
// 只取最近 10 分钟内到点的：服务停过一段时间再起来时，
// 不应该把几天前的提醒全部补推一遍——那对用户是骚扰。
func (s *Store) DueReminders(ctx context.Context, now int64) ([]Reminder, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT id,device_id,channel_id,channel_name,title,start_at,fired,created_at
		 FROM epg_reminders
		 WHERE fired=0 AND start_at <= ? AND start_at >= ?
		 ORDER BY start_at`, now, now-600)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Reminder{}
	for rows.Next() {
		var r Reminder
		var fired int
		if err := rows.Scan(&r.ID, &r.DeviceID, &r.ChannelID, &r.ChannelName,
			&r.Title, &r.StartAt, &fired, &r.CreatedAt); err != nil {
			return nil, err
		}
		r.Fired = fired != 0
		out = append(out, r)
	}
	return out, rows.Err()
}

// MarkReminderFired 标记已推送。
func (s *Store) MarkReminderFired(ctx context.Context, id int64) error {
	_, err := s.db.ExecContext(ctx, `UPDATE epg_reminders SET fired=1 WHERE id=?`, id)
	return err
}
