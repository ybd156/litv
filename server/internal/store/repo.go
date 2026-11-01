package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"
)

// ErrNotFound 表示记录不存在。
var ErrNotFound = errors.New("记录不存在")

// ---------- users ----------

// UpsertUser 按飞牛 UID 插入或更新用户，返回最新记录。
// 网关每次请求都会带身份头，这里要足够快（单条 UPSERT）。
func (s *Store) UpsertUser(ctx context.Context, uid, username string, isAdmin bool) (*User, error) {
	now := Now()
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO users(fnos_uid, username, is_admin, created_at, last_seen)
		VALUES(?,?,?,?,?)
		ON CONFLICT(fnos_uid) DO UPDATE SET
			username = excluded.username,
			is_admin = excluded.is_admin,
			last_seen = excluded.last_seen`,
		uid, username, boolInt(isAdmin), now, now)
	if err != nil {
		return nil, fmt.Errorf("写入用户失败: %w", err)
	}
	row := s.db.QueryRowContext(ctx, `SELECT id, fnos_uid, username, is_admin, created_at, last_seen FROM users WHERE fnos_uid = ?`, uid)
	return scanUser(row)
}

func scanUser(row *sql.Row) (*User, error) {
	var u User
	var admin int
	if err := row.Scan(&u.ID, &u.FnosUID, &u.Username, &admin, &u.CreatedAt, &u.LastSeen); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	u.IsAdmin = admin != 0
	return &u, nil
}

// ---------- sources ----------

// ListSources 返回全部源。
func (s *Store) ListSources(ctx context.Context) ([]Source, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT id,name,type,url,enabled,last_fetch_at,last_error,channel_count,created_at,user_agent FROM sources ORDER BY id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Source{}
	for rows.Next() {
		var v Source
		var en int
		if err := rows.Scan(&v.ID, &v.Name, &v.Type, &v.URL, &en, &v.LastFetchAt, &v.LastError, &v.ChannelCount, &v.CreatedAt, &v.UserAgent); err != nil {
			return nil, err
		}
		v.Enabled = en != 0
		out = append(out, v)
	}
	return out, rows.Err()
}

// CreateSource 新增源。
func (s *Store) CreateSource(ctx context.Context, v Source) (int64, error) {
	res, err := s.db.ExecContext(ctx, `INSERT INTO sources(name,type,url,enabled,created_at,user_agent) VALUES(?,?,?,?,?,?)`,
		v.Name, v.Type, v.URL, boolInt(v.Enabled), Now(), v.UserAgent)
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

// UpdateSource 更新源。
func (s *Store) UpdateSource(ctx context.Context, v Source) error {
	_, err := s.db.ExecContext(ctx, `UPDATE sources SET name=?,type=?,url=?,enabled=?,user_agent=? WHERE id=?`,
		v.Name, v.Type, v.URL, boolInt(v.Enabled), v.UserAgent, v.ID)
	return err
}

// DeleteSource 删除源，并清理其导入的频道。
func (s *Store) DeleteSource(ctx context.Context, id int64) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, `DELETE FROM channels WHERE source_id = ?`, id); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM sources WHERE id = ?`, id); err != nil {
		return err
	}
	return tx.Commit()
}

// MarkSourceFetched 记录拉取结果。
func (s *Store) MarkSourceFetched(ctx context.Context, id int64, count int, errMsg string) error {
	_, err := s.db.ExecContext(ctx, `UPDATE sources SET last_fetch_at=?, channel_count=?, last_error=? WHERE id=?`,
		Now(), count, errMsg, id)
	return err
}

// ---------- channels ----------

// ChannelQuery 是频道列表查询条件。
type ChannelQuery struct {
	Group   string
	Keyword string
	Enabled *bool
	Offset  int
	Limit   int
}

// ListChannels 分页查询频道。
func (s *Store) ListChannels(ctx context.Context, q ChannelQuery) ([]Channel, int, error) {
	var where []string
	var args []any
	if q.Group != "" {
		where = append(where, "group_name = ?")
		args = append(args, q.Group)
	}
	if q.Keyword != "" {
		where = append(where, "name LIKE ?")
		args = append(args, "%"+q.Keyword+"%")
	}
	if q.Enabled != nil {
		where = append(where, "enabled = ?")
		args = append(args, boolInt(*q.Enabled))
	}
	clause := ""
	if len(where) > 0 {
		clause = " WHERE " + strings.Join(where, " AND ")
	}

	var total int
	if err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM channels`+clause, args...).Scan(&total); err != nil {
		return nil, 0, err
	}

	if q.Limit <= 0 || q.Limit > 500 {
		q.Limit = 50
	}
	sqlStr := `SELECT c.id,c.number,c.name,c.group_name,c.logo,c.tvg_id,c.epg_id,c.enabled,c.sort,c.source_id,c.created_at,
		(SELECT COUNT(*) FROM streams st WHERE st.channel_id = c.id)
		FROM channels c` + clause + ` ORDER BY c.sort, c.id LIMIT ? OFFSET ?`
	rows, err := s.db.QueryContext(ctx, sqlStr, append(args, q.Limit, q.Offset)...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()

	out := []Channel{}
	for rows.Next() {
		var c Channel
		var en int
		if err := rows.Scan(&c.ID, &c.Number, &c.Name, &c.Group, &c.Logo, &c.TvgID, &c.EpgID, &en, &c.Sort, &c.SourceID, &c.CreatedAt, &c.StreamCnt); err != nil {
			return nil, 0, err
		}
		c.Enabled = en != 0
		out = append(out, c)
	}
	return out, total, rows.Err()
}

// ListGroups 返回分组名与计数。
func (s *Store) ListGroups(ctx context.Context) ([]map[string]any, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT group_name, COUNT(*) FROM channels GROUP BY group_name ORDER BY group_name`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		var g string
		var n int
		if err := rows.Scan(&g, &n); err != nil {
			return nil, err
		}
		out = append(out, map[string]any{"name": g, "count": n})
	}
	return out, rows.Err()
}

// GetChannel 取单个频道。
func (s *Store) GetChannel(ctx context.Context, id int64) (*Channel, error) {
	var c Channel
	var en int
	err := s.db.QueryRowContext(ctx, `SELECT id,number,name,group_name,logo,tvg_id,epg_id,enabled,sort,source_id,created_at FROM channels WHERE id=?`, id).
		Scan(&c.ID, &c.Number, &c.Name, &c.Group, &c.Logo, &c.TvgID, &c.EpgID, &en, &c.Sort, &c.SourceID, &c.CreatedAt)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	c.Enabled = en != 0
	return &c, nil
}

// CreateChannel 新增频道。
func (s *Store) CreateChannel(ctx context.Context, c Channel) (int64, error) {
	res, err := s.db.ExecContext(ctx, `INSERT INTO channels(number,name,group_name,logo,tvg_id,epg_id,enabled,sort,source_id,created_at)
		VALUES(?,?,?,?,?,?,?,?,?,?)`,
		c.Number, c.Name, c.Group, c.Logo, c.TvgID, c.EpgID, boolInt(c.Enabled), c.Sort, c.SourceID, Now())
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

// UpdateChannel 更新频道。
func (s *Store) UpdateChannel(ctx context.Context, c Channel) error {
	_, err := s.db.ExecContext(ctx, `UPDATE channels SET number=?,name=?,group_name=?,logo=?,tvg_id=?,epg_id=?,enabled=? WHERE id=?`,
		c.Number, c.Name, c.Group, c.Logo, c.TvgID, c.EpgID, boolInt(c.Enabled), c.ID)
	return err
}

// DeleteChannel 删除频道。
func (s *Store) DeleteChannel(ctx context.Context, id int64) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM channels WHERE id=?`, id)
	return err
}

// ReorderChannels 按给定顺序重排。
//
// 语义：只调整传入 id 之间的相对次序——它们占用「原有这些 id 的 sort 值集合」，
// 其余频道不动。这样前端在「分页 + 分组筛选」下提交当前页的 id 也能正确工作，
// 不需要为了排序把全库 id 都传上来。
func (s *Store) ReorderChannels(ctx context.Context, ids []int64) error {
	if len(ids) == 0 {
		return nil
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	ph := strings.Repeat("?,", len(ids))
	ph = ph[:len(ph)-1]
	args := make([]any, 0, len(ids))
	for _, id := range ids {
		args = append(args, id)
	}

	// 1. 取这些 id 现有的 sort 值（升序），作为"槽位"
	rows, err := tx.QueryContext(ctx, `SELECT sort FROM channels WHERE id IN (`+ph+`) ORDER BY sort`, args...)
	if err != nil {
		return err
	}
	slots := make([]int, 0, len(ids))
	for rows.Next() {
		var v int
		if err := rows.Scan(&v); err != nil {
			rows.Close()
			return err
		}
		slots = append(slots, v)
	}
	rows.Close()
	if len(slots) != len(ids) {
		return fmt.Errorf("部分频道不存在（传入 %d 个，找到 %d 个）", len(ids), len(slots))
	}

	// 2. 把新顺序依次填回这些槽位
	stmt, err := tx.PrepareContext(ctx, `UPDATE channels SET sort=? WHERE id=?`)
	if err != nil {
		return err
	}
	defer stmt.Close()
	for i, id := range ids {
		if _, err := stmt.ExecContext(ctx, slots[i], id); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// Stats 是概览页需要的聚合数字。
type Stats struct {
	ChannelsTotal  int `json:"channels_total"`
	ChannelsOn     int `json:"channels_enabled"`
	DevicesTotal   int `json:"devices_total"`
	DevicesOnline  int `json:"devices_online"`
	DevicesPending int `json:"devices_pending"`
	SourcesTotal   int `json:"sources_total"`
	SourcesFailed  int `json:"sources_failed"`
	PlaysToday     int `json:"plays_today"`
}

// Stats 聚合统计。在线判定：最近 90 秒内有心跳。
func (s *Store) Stats(ctx context.Context) (*Stats, error) {
	out := &Stats{}
	row := s.db.QueryRowContext(ctx, `
		SELECT
			(SELECT COUNT(*) FROM channels),
			(SELECT COUNT(*) FROM channels WHERE enabled=1),
			(SELECT COUNT(*) FROM devices),
			(SELECT COUNT(*) FROM devices WHERE status='bound' AND last_seen >= ?),
			(SELECT COUNT(*) FROM devices WHERE status='pending'),
			(SELECT COUNT(*) FROM sources),
			(SELECT COUNT(*) FROM sources WHERE last_error <> ''),
			(SELECT COUNT(*) FROM audit_logs WHERE action='play' AND created_at >= ?)
	`, Now()-90, startOfToday())
	if err := row.Scan(&out.ChannelsTotal, &out.ChannelsOn, &out.DevicesTotal,
		&out.DevicesOnline, &out.DevicesPending, &out.SourcesTotal,
		&out.SourcesFailed, &out.PlaysToday); err != nil {
		return nil, err
	}
	return out, nil
}

func startOfToday() int64 {
	n := time.Now()
	return time.Date(n.Year(), n.Month(), n.Day(), 0, 0, 0, 0, n.Location()).Unix()
}

// BatchChannels 批量操作：enable/disable/delete/set_group。
func (s *Store) BatchChannels(ctx context.Context, ids []int64, action, value string) (int64, error) {
	if len(ids) == 0 {
		return 0, nil
	}
	ph := strings.Repeat("?,", len(ids))
	ph = ph[:len(ph)-1]
	args := make([]any, 0, len(ids)+1)
	var sqlStr string
	switch action {
	case "enable":
		sqlStr = `UPDATE channels SET enabled=1 WHERE id IN (` + ph + `)`
	case "disable":
		sqlStr = `UPDATE channels SET enabled=0 WHERE id IN (` + ph + `)`
	case "delete":
		sqlStr = `DELETE FROM channels WHERE id IN (` + ph + `)`
	case "set_group":
		sqlStr = `UPDATE channels SET group_name=? WHERE id IN (` + ph + `)`
		args = append(args, value)
	default:
		return 0, fmt.Errorf("不支持的批量操作: %s", action)
	}
	for _, id := range ids {
		args = append(args, id)
	}
	res, err := s.db.ExecContext(ctx, sqlStr, args...)
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}

// ImportChannels 批量导入频道（导入时按 name+group 去重，避免重复刷新源导致爆炸）。
func (s *Store) ImportChannels(ctx context.Context, sourceID int64, items []Channel) (int, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()

	var maxSort int
	_ = tx.QueryRowContext(ctx, `SELECT COALESCE(MAX(sort),0) FROM channels`).Scan(&maxSort)

	// 这个源专属的 UA，导入时写进每条线路的 headers_json。
	// 之后服务端代理、客户端直连都从这里取，不用再回头查源。
	var srcUA string
	_ = tx.QueryRowContext(ctx, `SELECT COALESCE(user_agent,'') FROM sources WHERE id=?`, sourceID).Scan(&srcUA)
	headersJSON := "{}"
	if ua := strings.TrimSpace(srcUA); ua != "" {
		if b, err := json.Marshal(map[string]string{"User-Agent": ua}); err == nil {
			headersJSON = string(b)
		}
	}

	inserted := 0
	for _, c := range items {
		var existID int64
		err := tx.QueryRowContext(ctx, `SELECT id FROM channels WHERE name=? AND group_name=? LIMIT 1`, c.Name, c.Group).Scan(&existID)
		switch {
		case err == nil:
			// 已存在：只补一条线路（同 URL 不重复加）
			var n int
			_ = tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM streams WHERE channel_id=? AND url=?`, existID, c.firstURL()).Scan(&n)
			if n == 0 && c.firstURL() != "" {
				if _, err := tx.ExecContext(ctx, `INSERT INTO streams(channel_id,url,priority,health,headers_json) VALUES(?,?,?,?,?)`,
					existID, c.firstURL(), 0, -1, headersJSON); err != nil {
					return inserted, err
				}
			}
		case errors.Is(err, sql.ErrNoRows):
			maxSort++
			res, err := tx.ExecContext(ctx, `INSERT INTO channels(number,name,group_name,logo,tvg_id,enabled,sort,source_id,created_at)
				VALUES(?,?,?,?,?,?,?,?,?)`,
				c.Number, c.Name, c.Group, c.Logo, c.TvgID, 1, maxSort, sourceID, Now())
			if err != nil {
				return inserted, err
			}
			cid, _ := res.LastInsertId()
			if u := c.firstURL(); u != "" {
				if _, err := tx.ExecContext(ctx, `INSERT INTO streams(channel_id,url,priority,health,headers_json) VALUES(?,?,?,?,?)`,
					cid, u, 0, -1, headersJSON); err != nil {
					return inserted, err
				}
			}
			inserted++
		default:
			return inserted, err
		}
	}
	return inserted, tx.Commit()
}

// firstURL 取导入项的临时 URL 字段（复用 Logo 之外的槽位会混淆，这里单独用 TvgID 之外的约定：
// 解析器把 URL 放进 Streams 的临时字段不方便，因此 ImportChannels 使用 ParseItem 结构，见 parse 包）。
func (c Channel) firstURL() string { return c.PendingURL }

// ---------- streams ----------

// ListStreams 返回某频道所有线路（按优先级）。
func (s *Store) ListStreams(ctx context.Context, channelID int64) ([]Stream, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT id,channel_id,url,quality,priority,headers_json,health,latency_ms,last_check
		FROM streams WHERE channel_id=? ORDER BY priority, id`, channelID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Stream{}
	for rows.Next() {
		var v Stream
		if err := rows.Scan(&v.ID, &v.ChannelID, &v.URL, &v.Quality, &v.Priority, &v.HeadersJSON, &v.Health, &v.LatencyMs, &v.LastCheck); err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

// BestStream 返回该频道当前最该用的一条线路：健康优先、延迟低优先。
func (s *Store) BestStream(ctx context.Context, channelID int64) (*Stream, error) {
	var v Stream
	err := s.db.QueryRowContext(ctx, `SELECT id,channel_id,url,quality,priority,headers_json,health,latency_ms,last_check
		FROM streams WHERE channel_id=?
		ORDER BY CASE health WHEN 1 THEN 0 WHEN -1 THEN 1 ELSE 2 END, priority, latency_ms, id LIMIT 1`, channelID).
		Scan(&v.ID, &v.ChannelID, &v.URL, &v.Quality, &v.Priority, &v.HeadersJSON, &v.Health, &v.LatencyMs, &v.LastCheck)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &v, nil
}

// AddStream 给频道加线路。
func (s *Store) AddStream(ctx context.Context, channelID int64, url, quality string, priority int) (int64, error) {
	res, err := s.db.ExecContext(ctx, `INSERT INTO streams(channel_id,url,quality,priority,health) VALUES(?,?,?,?,-1)`,
		channelID, url, quality, priority)
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

// DeleteStream 删除线路。
func (s *Store) DeleteStream(ctx context.Context, id int64) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM streams WHERE id=?`, id)
	return err
}

// UpdateStreamHealth 更新探测结果。
func (s *Store) UpdateStreamHealth(ctx context.Context, id int64, health, latencyMs int) error {
	_, err := s.db.ExecContext(ctx, `UPDATE streams SET health=?, latency_ms=?, last_check=? WHERE id=?`, health, latencyMs, Now(), id)
	return err
}

// ---------- devices ----------

// CreateDevice 注册一台待绑定设备。
func (s *Store) CreateDevice(ctx context.Context, key, tokenHash, name, platform, model string) (int64, error) {
	res, err := s.db.ExecContext(ctx, `INSERT INTO devices(device_key,token_hash,name,platform,model,status,created_at,last_seen)
		VALUES(?,?,?,?,?,'pending',?,?)`, key, tokenHash, name, platform, model, Now(), Now())
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

// ListDevices 列出设备。
func (s *Store) ListDevices(ctx context.Context, status string) ([]Device, error) {
	q := `SELECT id,device_key,name,platform,model,status,config_json,bound_at,last_seen,created_at FROM devices`
	var args []any
	if status != "" {
		q += ` WHERE status=?`
		args = append(args, status)
	}
	q += ` ORDER BY id DESC`
	rows, err := s.db.QueryContext(ctx, q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Device{}
	for rows.Next() {
		var d Device
		if err := rows.Scan(&d.ID, &d.DeviceKey, &d.Name, &d.Platform, &d.Model, &d.Status, &d.ConfigJSON, &d.BoundAt, &d.LastSeen, &d.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

// GetDeviceByToken 用 token 哈希查设备（必须是 bound 状态）。
func (s *Store) GetDeviceByToken(ctx context.Context, tokenHash string) (*Device, error) {
	var d Device
	err := s.db.QueryRowContext(ctx, `SELECT id,device_key,name,platform,model,status,config_json,bound_at,last_seen,created_at
		FROM devices WHERE token_hash=? AND status='bound'`, tokenHash).
		Scan(&d.ID, &d.DeviceKey, &d.Name, &d.Platform, &d.Model, &d.Status, &d.ConfigJSON, &d.BoundAt, &d.LastSeen, &d.CreatedAt)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &d, nil
}

// ApproveDevice 批准绑定。
func (s *Store) ApproveDevice(ctx context.Context, id int64) error {
	_, err := s.db.ExecContext(ctx, `UPDATE devices SET status='bound', bound_at=? WHERE id=?`, Now(), id)
	return err
}

// RevokeDevice 吊销设备。
func (s *Store) RevokeDevice(ctx context.Context, id int64) error {
	_, err := s.db.ExecContext(ctx, `UPDATE devices SET status='revoked' WHERE id=?`, id)
	return err
}

// TouchDevice 更新心跳。
func (s *Store) TouchDevice(ctx context.Context, id int64) error {
	_, err := s.db.ExecContext(ctx, `UPDATE devices SET last_seen=? WHERE id=?`, Now(), id)
	return err
}

// SetDeviceConfig 下发/覆盖终端配置。
func (s *Store) SetDeviceConfig(ctx context.Context, id int64, cfgJSON string) error {
	_, err := s.db.ExecContext(ctx, `UPDATE devices SET config_json=? WHERE id=?`, cfgJSON, id)
	return err
}

// ---------- bind codes ----------

// CreateBindCode 生成绑定码。
func (s *Store) CreateBindCode(ctx context.Context, code string, deviceID, ttlSeconds int64) error {
	_, err := s.db.ExecContext(ctx, `INSERT INTO bind_codes(code,device_id,expires_at) VALUES(?,?,?)`,
		code, deviceID, Now()+ttlSeconds)
	return err
}

// ConsumeBindCode 消费绑定码，返回对应设备 ID。
func (s *Store) ConsumeBindCode(ctx context.Context, code string) (int64, error) {
	var deviceID, exp int64
	var used int
	err := s.db.QueryRowContext(ctx, `SELECT device_id,expires_at,used FROM bind_codes WHERE code=?`, code).Scan(&deviceID, &exp, &used)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return 0, ErrNotFound
		}
		return 0, err
	}
	if used != 0 || exp < Now() {
		return 0, fmt.Errorf("绑定码已使用或已过期")
	}
	if _, err := s.db.ExecContext(ctx, `UPDATE bind_codes SET used=1 WHERE code=?`, code); err != nil {
		return 0, err
	}
	return deviceID, nil
}

// ---------- favorites ----------

// SetFavorite 设置收藏。
func (s *Store) SetFavorite(ctx context.Context, deviceID, channelID int64, on bool) error {
	if on {
		_, err := s.db.ExecContext(ctx, `INSERT OR IGNORE INTO favorites(device_id,channel_id,created_at) VALUES(?,?,?)`,
			deviceID, channelID, Now())
		return err
	}
	_, err := s.db.ExecContext(ctx, `DELETE FROM favorites WHERE device_id=? AND channel_id=?`, deviceID, channelID)
	return err
}

// ListFavorites 返回收藏频道 ID。
func (s *Store) ListFavorites(ctx context.Context, deviceID int64) ([]int64, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT channel_id FROM favorites WHERE device_id=?`, deviceID)
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

// ---------- audit ----------

// Audit 写审计日志（失败不影响主流程）。
func (s *Store) Audit(ctx context.Context, userID int64, action, target, detail, ip string) {
	_, _ = s.db.ExecContext(ctx, `INSERT INTO audit_logs(user_id,action,target,detail,ip,created_at) VALUES(?,?,?,?,?,?)`,
		userID, action, target, detail, ip, Now())
}

func boolInt(b bool) int {
	if b {
		return 1
	}
	return 0
}
