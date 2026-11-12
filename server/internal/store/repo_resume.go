package store

import (
	"context"
	"database/sql"
	"errors"
)

// ResumePoint 是一条"接着看"记录。
type ResumePoint struct {
	ChannelID   int64  `json:"channel_id"`
	ChannelName string `json:"channel_name"`
	ChannelLogo string `json:"channel_logo"`
	Group       string `json:"group"`
	// 直播场景一般是 0；用了时移的话是"距直播多少秒"
	PositionSec int    `json:"position_sec"`
	DeviceID    int64  `json:"device_id"`
	DeviceName  string `json:"device_name"`
	UpdatedAt   int64  `json:"updated_at"`
}

// UpsertProgress 记录某设备在某频道看到的位置。
func (s *Store) UpsertProgress(ctx context.Context, deviceID, channelID int64, posSec int) error {
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO watch_progress(device_id, channel_id, position_sec, updated_at)
		VALUES(?,?,?,?)
		ON CONFLICT(device_id, channel_id) DO UPDATE SET
			position_sec = excluded.position_sec,
			updated_at   = excluded.updated_at`,
		deviceID, channelID, posSec, Now())
	return err
}

// LatestResume 返回"最近在**别的设备**上看过的频道"，用于跨端续播。
//
// 排除自己：同一台设备打开时不需要提示"接着看"，直接进上次的台就行
// （那个用 device 表的 lastChannelId 语义已经覆盖）。
func (s *Store) LatestResume(ctx context.Context, excludeDeviceID int64) (*ResumePoint, error) {
	var r ResumePoint
	err := s.db.QueryRowContext(ctx, `
		SELECT w.channel_id,
		       COALESCE(c.name,'已删除频道'),
		       COALESCE(c.logo,''),
		       COALESCE(c.group_name,''),
		       w.position_sec,
		       w.device_id,
		       COALESCE(d.name,'未知设备'),
		       w.updated_at
		FROM watch_progress w
		LEFT JOIN channels c ON c.id = w.channel_id
		LEFT JOIN devices  d ON d.id = w.device_id
		WHERE w.device_id <> ?
		ORDER BY w.updated_at DESC
		LIMIT 1`, excludeDeviceID).
		Scan(&r.ChannelID, &r.ChannelName, &r.ChannelLogo, &r.Group,
			&r.PositionSec, &r.DeviceID, &r.DeviceName, &r.UpdatedAt)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &r, nil
}

// PurgeOldProgress 清理过旧的续播记录（默认保留 7 天）。
func (s *Store) PurgeOldProgress(ctx context.Context, before int64) (int64, error) {
	res, err := s.db.ExecContext(ctx, `DELETE FROM watch_progress WHERE updated_at < ?`, before)
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}
