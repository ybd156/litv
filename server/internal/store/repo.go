package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
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
	rows, err := s.db.QueryContext(ctx,
		`SELECT id,name,type,url,enabled,last_fetch_at,last_error,channel_count,created_at,user_agent,
		        parsed_count,last_result_json
		 FROM sources ORDER BY id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Source{}
	for rows.Next() {
		var v Source
		var en int
		if err := rows.Scan(&v.ID, &v.Name, &v.Type, &v.URL, &en, &v.LastFetchAt, &v.LastError,
			&v.ChannelCount, &v.CreatedAt, &v.UserAgent, &v.ParsedCount, &v.LastResultJSON); err != nil {
			return nil, err
		}
		v.Enabled = en != 0
		out = append(out, v)
	}
	return out, rows.Err()
}

// GetSource 取单个源。
//
// 以前所有需要"一个源"的地方都 ListSources 再线性找，除了浪费一次全表读，
// 还容易在 handler 里漏判 not found。
func (s *Store) GetSource(ctx context.Context, id int64) (*Source, error) {
	var v Source
	var en int
	err := s.db.QueryRowContext(ctx,
		`SELECT id,name,type,url,enabled,last_fetch_at,last_error,channel_count,created_at,user_agent,
		        parsed_count,last_result_json
		 FROM sources WHERE id=?`, id).
		Scan(&v.ID, &v.Name, &v.Type, &v.URL, &en, &v.LastFetchAt, &v.LastError,
			&v.ChannelCount, &v.CreatedAt, &v.UserAgent, &v.ParsedCount, &v.LastResultJSON)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	v.Enabled = en != 0
	return &v, nil
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
//
// **已知问题（本轮未修，不要以为这里是安全的）**：
// ImportChannels 的判重键是 (name, group_name)，**不含 source_id**，
// 而 channels.source_id 只记"首个导入者"。所以当两个源都含同名同分组频道时，
// 删 A 源会把 B 源正在用的那条频道一起删掉（B 的线路挂在 A 的频道上，
// 级联一起没了），B 下次刷新会重新建一条新频道——
// 收藏、跨端续播、开播提醒全部指向已经不存在的 id。
//
// 为什么本轮不修：正确的语义是「删本源的 streams，然后只删已无残留线路的频道」，
// 但 streams 表**没有 source_id** 列（建表语句、store.Stream 结构体、
// ListStreams 的 SELECT 三处互证），当前 schema 无法表达"这条线路属于哪个源"。
// 先删后判等于没改，先判后删则一个都不删——硬做只会得到一段
// 能编译但永远不生效的 SQL，比不修更危险。
//
// 后续方案（expand-and-contract）：
//  1. additive 迁移：`ALTER TABLE streams ADD COLUMN source_id INTEGER NOT NULL DEFAULT 0`
//     （db.go 里已有同形态的增量列迁移先例）；
//  2. ImportChannels 写入来源；历史行按 channel.source_id 回填；
//  3. DeleteSource 改成「删本源的 streams → 只删已无残留线路的频道」；
//  4. 双写观察一个版本后再切换到新语义。
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

// CountChannelsBySource 数一个源名下有多少频道。
//
// 给管理台的"删除源"确认文案用：文案必须说得出"会一并删除 N 个频道"，
// 否则用户只能看到一个不带数字的"确定吗"。
func (s *Store) CountChannelsBySource(ctx context.Context, sourceID int64) (int, error) {
	var n int
	err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM channels WHERE source_id=?`, sourceID).Scan(&n)
	return n, err
}

// MarkSourceFetched 记录拉取结果。
func (s *Store) MarkSourceFetched(ctx context.Context, id int64, parsedCount int, errMsg string) error {
	// channel_count 必须写"库里真实存在、会被一并删除的频道数"，
	// 而不是本次解析条数——管理台的删源确认文案要拿它报影响范围（AGENTS §6）。
	//
	// 以前两者是同一个字段，于是"解析 50 条、库里只有 40 个"时，
	// 破坏性确认里显示的数字是错的。
	_, err := s.db.ExecContext(ctx,
		`UPDATE sources SET last_fetch_at=?, parsed_count=?, last_error=?,
		        channel_count=(SELECT COUNT(*) FROM channels WHERE source_id=sources.id)
		 WHERE id=?`,
		Now(), parsedCount, errMsg, id)
	return err
}

// MarkSourceError 记录一次刷新失败的原因。
//
// **不动** last_fetch_at / parsed_count / last_result_json：
// 那几个字段描述的是"上一次成功"，失败不该把它们抹掉。
// 这与 EPG 侧 MarkEPGAttempt 的教训是同一条——只写一个 last_* 字段，
// 会让界面在失败后照样显示"刚刷新过"。
func (s *Store) MarkSourceError(ctx context.Context, id int64, errMsg string) error {
	_, err := s.db.ExecContext(ctx, `UPDATE sources SET last_error=? WHERE id=?`, errMsg, id)
	return err
}

// MarkSourceRefreshed 记录一次刷新的结果（五键 JSON）与成功时间。
//
// 与 MarkSourceFetched 分开：那个只管"拉取到了什么"，
// 这个记"对齐做了什么"，失败路径不该覆盖上一次的成功结果。
func (s *Store) MarkSourceRefreshed(ctx context.Context, id int64, parsedCount int, resultJSON string) error {
	_, err := s.db.ExecContext(ctx,
		`UPDATE sources SET last_fetch_at=?, last_error='', parsed_count=?, last_result_json=?,
		        channel_count=(SELECT COUNT(*) FROM channels WHERE source_id=sources.id)
		 WHERE id=?`,
		Now(), parsedCount, resultJSON, id)
	return err
}

// ---------- channels ----------

// ChannelQuery 是频道列表查询条件。
type ChannelQuery struct {
	Group   string
	Keyword string
	Enabled *bool
	// SourceID > 0 时只看这个源导入的频道（管理台「按源分组」用）
	SourceID int64

	// IncludeMissing 是否包含「上游已移除」的频道（src_missing=1）。
	//
	// 默认 false：终端与电视端绝不能看到已移除的台（点进去必然播不了）。
	// 管理台要显示"该线路已下线（上游已移除）"这类标记，所以它显式传 true。
	IncludeMissing bool

	Offset int
	Limit  int
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
	if q.SourceID > 0 {
		where = append(where, "source_id = ?")
		args = append(args, q.SourceID)
	}
	// 上游已移除的频道默认不出现在任何列表里。
	// 它们不是"用户停用"，所以不能靠 enabled 过滤——那是两个独立状态。
	if !q.IncludeMissing {
		where = append(where, "src_missing = 0")
	}
	clause := ""
	if len(where) > 0 {
		clause = " WHERE " + strings.Join(where, " AND ")
	}

	var total int
	if err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM channels`+clause, args...).Scan(&total); err != nil {
		return nil, 0, err
	}

	// 上限截断而不是回退到 50：调用方明确要 500 条时应该给 500 条，
	// 静默改成 50 会让「取全部频道」的页面只看到 50 个。
	if q.Limit <= 0 {
		q.Limit = 50
	}
	if q.Limit > 500 {
		q.Limit = 500
	}
	sqlStr := `SELECT c.id,c.number,c.name,c.group_name,c.logo,c.tvg_id,c.epg_id,c.enabled,c.sort,c.source_id,c.created_at,c.missing_since,
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
		if err := rows.Scan(&c.ID, &c.Number, &c.Name, &c.Group, &c.Logo, &c.TvgID, &c.EpgID, &en, &c.Sort, &c.SourceID, &c.CreatedAt, &c.MissingSince, &c.StreamCnt); err != nil {
			return nil, 0, err
		}
		c.Enabled = en != 0
		out = append(out, c)
	}
	return out, total, rows.Err()
}

// ListAllEnabledChannels 返回全部启用的频道（不分页）。
//
// tvBootstrap 需要完整列表。以前它复用管理台分页接口、写死 Limit=500，
// 频道超过 500 的部署在电视端只能看到前 500 个，后面的台"凭空消失"。
func (s *Store) ListAllEnabledChannels(ctx context.Context) ([]Channel, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT c.id,c.number,c.name,c.group_name,c.logo,c.tvg_id,c.epg_id,c.enabled,c.sort,c.source_id,c.created_at,c.missing_since,
		(SELECT COUNT(*) FROM streams st WHERE st.channel_id = c.id)
		FROM channels c WHERE c.enabled = 1 AND c.src_missing = 0 ORDER BY c.sort, c.id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := []Channel{}
	for rows.Next() {
		var c Channel
		var en int
		if err := rows.Scan(&c.ID, &c.Number, &c.Name, &c.Group, &c.Logo, &c.TvgID, &c.EpgID, &en, &c.Sort, &c.SourceID, &c.CreatedAt, &c.MissingSince, &c.StreamCnt); err != nil {
			return nil, err
		}
		c.Enabled = en != 0
		out = append(out, c)
	}
	return out, rows.Err()
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
	var en, dbm int
	err := s.db.QueryRowContext(ctx,
		`SELECT id,number,name,group_name,logo,tvg_id,epg_id,enabled,sort,source_id,created_at,missing_since,disabled_by_missing
		 FROM channels WHERE id=?`, id).
		Scan(&c.ID, &c.Number, &c.Name, &c.Group, &c.Logo, &c.TvgID, &c.EpgID, &en, &c.Sort, &c.SourceID, &c.CreatedAt, &c.MissingSince, &dbm)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	c.Enabled = en != 0
	c.DisabledByMissing = dbm != 0
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

// UpdateChannel 更新频道的**可编辑字段**（全量覆盖这 6 列）。
//
// 硬约束：这条 SQL **不含 epg_id**。
//
// 以前 SET 里有 epg_id=?，而所有调用方都没设过 Channel.EpgID
// （handler 的请求体里根本没这个字段），于是每次「成功保存频道」
// 都会把 EPG 映射写成空串——节目单跟着消失，用户在另一个页面
// 辛苦对好的映射每次编辑都被清掉一次，且没有任何提示。
//
// EPG 映射只能走 SetChannelEpgID（PUT /channels/{id}/epg）。
// 不要为了「少一个接口」把它加回来。
func (s *Store) UpdateChannel(ctx context.Context, c Channel) error {
	_, err := s.db.ExecContext(ctx,
		`UPDATE channels SET number=?,name=?,group_name=?,logo=?,tvg_id=?,enabled=? WHERE id=?`,
		c.Number, c.Name, c.Group, c.Logo, c.TvgID, boolInt(c.Enabled), c.ID)
	return err
}

// ChannelPatch 是频道的**部分**更新参数：nil 表示「这一列不动」。
//
// 为什么需要它：UpdateChannel 是全量覆盖，而 HTTP 请求体曾经是非指针字段，
// 于是 PUT /channels/{id} 只发 {"enabled":false} 时，其余列全部被写成零值——
// 名称、分组、台标、tvg_id、台号被静默清空。
// 用户在频道列表里点一下「停用」，台名就没了，而且没有任何提示。
//
// 三个必须保住的行为（都容易在实现时被"顺手防呆"掉）：
//   - 显式清空必须生效：Group 传 "" 就是要清空分组，
//     不能写成 NULLIF(?, 空串) 让用户永远清不空；
//   - 数值 0 是合法输入：Number 传 0 就是要把台号设成 0，
//     不能加 `> 0` 的判断把它当"没传"；
//   - 用指针区分「没传」和「传了零值」，不要用非指针中间结构，
//     否则 false/0/"" 会退化成"保持不变"，那是反向 bug。
//
// 硬约束：不含 epg_id。EPG 映射只走 SetChannelEpgID。
type ChannelPatch struct {
	Number  *int
	Name    *string
	Group   *string
	Logo    *string
	TvgID   *string
	Enabled *bool
}

// PatchChannel 只写传入的列。
//
// 没有任何字段时只做存在性检查（返回 ErrNotFound 或 nil），
// 保证 PUT /channels/999 不会静默成功。
func (s *Store) PatchChannel(ctx context.Context, id int64, p ChannelPatch) error {
	// 先判存在：SQLite 的 changes() 在「值没实际变化」时是否计数，
	// 不同驱动/版本上语义不够直观，而频道更新是低频的管理操作，
	// 多一条 SELECT 换确定的 404 语义是划算的。
	var exists int
	if err := s.db.QueryRowContext(ctx, `SELECT 1 FROM channels WHERE id=?`, id).Scan(&exists); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return ErrNotFound
		}
		return err
	}

	// 列名是代码里的字面量，值全部走占位符——不存在拼接不可信值。
	var sets []string
	var args []any
	add := func(col string, v any) {
		sets = append(sets, col+"=?")
		args = append(args, v)
	}
	if p.Number != nil {
		add("number", *p.Number)
	}
	if p.Name != nil {
		add("name", *p.Name)
	}
	if p.Group != nil {
		add("group_name", *p.Group)
	}
	if p.Logo != nil {
		add("logo", *p.Logo)
	}
	if p.TvgID != nil {
		add("tvg_id", *p.TvgID)
	}
	if p.Enabled != nil {
		add("enabled", boolInt(*p.Enabled))
	}
	if len(sets) == 0 {
		return nil
	}

	args = append(args, id)
	_, err := s.db.ExecContext(ctx,
		`UPDATE channels SET `+strings.Join(sets, ",")+` WHERE id=?`, args...)
	return err
}

// DeleteChannel 删除一个频道及其依赖数据（事务）。
func (s *Store) DeleteChannel(ctx context.Context, id int64) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err := deleteChannelsTx(ctx, tx, []int64{id}); err != nil {
		return err
	}
	return tx.Commit()
}

// deleteChannelsTx 在一个事务里删除频道及其所有引用数据。调用方提供事务。
//
// 为什么必须收拾干净：favorites / watch_progress / epg_reminders / play_events /
// recordings 的 channel_id **都没有外键**（建表时故意没加，避免历史库迁移失败），
// 所以删频道不会级联。留下的孤儿行不会报错，只会安静地坏掉：
// TV 端「我的收藏」里点一个已删的台永远播不了，跨端续播提示一个不存在的频道，
// 播放质量统计把事件记到一个没人认识的 channel_id 上。
//
// 参考 DeleteDevice（本文件下面）——它早就把这套做对了，这里只是补齐同款。
//
// recordings 刻意**不删行**：它是用户数据，不是派生状态。
//   - finished：行在 → 文件可达，用户能在录制中心看到并自己删；
//     盲删行会把 .mp4 永久留在磁盘上，而且再没有任何入口能发现它。
//   - scheduled / recording：置 canceled。不能留 scheduled——
//     调度器（record.Manager.tick）每 5 秒扫一次 status='scheduled'，
//     频道已经不存在，那行会无限失败重试刷日志。
//   - 绝不在事务里 os.Remove 文件：文件删除必须走 deleteRecording
//     那套「规范化路径属于录制目录」的校验（见 api.deleteRecording）。
func deleteChannelsTx(ctx context.Context, tx *sql.Tx, ids []int64) error {
	if len(ids) == 0 {
		return nil
	}
	ph := strings.Repeat("?,", len(ids))
	ph = ph[:len(ph)-1]
	args := make([]any, 0, len(ids))
	for _, id := range ids {
		args = append(args, id)
	}

	// 顺序：先把"引用方"清掉，再删主表。
	for _, stmt := range []string{
		`DELETE FROM favorites      WHERE channel_id IN (` + ph + `)`,
		`DELETE FROM watch_progress WHERE channel_id IN (` + ph + `)`,
		`DELETE FROM epg_reminders  WHERE channel_id IN (` + ph + `)`,
		`DELETE FROM play_events    WHERE channel_id IN (` + ph + `)`,
	} {
		if _, err := tx.ExecContext(ctx, stmt, args...); err != nil {
			return err
		}
	}

	// recordings：见上面注释，只取消未完成的，保留已完成的行。
	if _, err := tx.ExecContext(ctx,
		`UPDATE recordings SET status='canceled'
		 WHERE channel_id IN (`+ph+`) AND status IN ('scheduled','recording')`,
		args...); err != nil {
		return err
	}

	_, err := tx.ExecContext(ctx, `DELETE FROM channels WHERE id IN (`+ph+`)`, args...)
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
	case "set_group":
		sqlStr = `UPDATE channels SET group_name=? WHERE id IN (` + ph + `)`
		args = append(args, value)
	case "delete":
		// 批量删除是多记录破坏性操作，必须走事务，并且要收拾引用表——
		// 见 deleteChannelsTx 的注释（favorites / watch_progress / epg_reminders /
		// play_events / recordings 的 channel_id 都没有外键，不会自动级联）。
		return s.batchDeleteChannels(ctx, ids)
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

// batchDeleteChannels 在事务里批量删除频道及其依赖数据，返回实际删掉的频道数。
func (s *Store) batchDeleteChannels(ctx context.Context, ids []int64) (int64, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()

	// 先数再删：本函数的返回值是「删掉了几个频道」。
	// 删完再 COUNT 只会得到 0 —— CI 第一次跑就抓到了这个 bug。
	var n int64
	ph := strings.Repeat("?,", len(ids))
	ph = ph[:len(ph)-1]
	args := make([]any, 0, len(ids))
	for _, id := range ids {
		args = append(args, id)
	}
	if err := tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM channels WHERE id IN (`+ph+`)`, args...).Scan(&n); err != nil {
		return 0, err
	}

	if err := deleteChannelsTx(ctx, tx, ids); err != nil {
		return 0, err
	}
	if err := tx.Commit(); err != nil {
		return 0, err
	}
	return n, nil
}

// ImportChannels 批量导入频道（导入时按 name+group 去重，避免重复刷新源导致爆炸）。
// ImportStats 是一次「对齐上游」的结果。
//
// 字段名是契约：管理台按这些数字告诉用户"上游更新了什么"。
// 注意用 `Missing` 而不是 `Disabled`：`enabled` 已专指"用户手动停用"，
// 同一个词表达两件事，必然在下一个改动里出 bug。
type ImportStats struct {
	Parsed    int `json:"parsed"`    // 本次解析条数（已经过条数上限截断）
	Inserted  int `json:"inserted"`  // 上游新增，本地新建
	Updated   int `json:"updated"`   // 已有频道，可变字段或线路变了并已更新
	Missing   int `json:"missing"`   // 上游已移除，本次标记缺失
	Restored  int `json:"restored"`  // 之前缺失，本次回到上游
	Unchanged int `json:"unchanged"` // 已有频道，什么都没变

	// AlignmentSkipped 表示这次**没有**执行"标记缺失"这一步。
	// 触发条件见 ImportOptions.Align（解析条数骤降护栏）。
	AlignmentSkipped bool `json:"alignment_skipped"`
	// Capped 表示解析结果被条数上限截断过（上游给了异常多条目）。
	Capped bool `json:"capped"`
}

// ImportOptions 控制一次导入的行为。
type ImportOptions struct {
	// Align 是否执行「上游已移除 → 标记缺失」。
	//
	// 调用方**必须**先做骤降护栏再传 true：上游返回残缺页/防盗链页/半截 gzip 时
	// Parsed 会很小，此时无脑对齐会把用户整个频道表批量标记缺失——
	// 那比"没自动更新"劣化一个数量级。
	// 判据见 api.shouldAlign。
	Align bool

	// Capped 表示 items 已经被调用方按条数上限截断过。
	//
	// 上游内容是外部输入，一个 5 万条的源会让整次刷新无限拉长，
	// 所以调用方在**开事务之前**截断，并把结果带进来记进统计。
	Capped bool

	// ChunkSize 每个写事务处理多少条 item。<=0 用默认值。
	//
	// 为什么不是一个事务做到底：SQLite 的**写是串行的**。
	// WAL 让读不被阻塞，但 NAS 上 TV 端的 play_events / watch_progress 写入
	// 和这次导入抢同一把写锁；一个几千条的大事务会让那些小写操作排队到
	// busy_timeout（8 秒）——用户感受到的就是"刷新时电视卡死"。
	// 分块之后，其它写最多等一个块的时间。
	ChunkSize int
}

const defaultImportChunk = 200

// importExisting 是「本源已存在频道」在内存里的快照。
type importExisting struct {
	id      int64
	name    string
	group   string
	logo    string
	tvgID   string
	missing bool
}

// importStream 是线路的最小快照（判重只需要 id + url）。
type importStream struct {
	id   int64
	url  string
	chID int64
}

// importSnapshot 是一次导入开始时读进内存的**全部现状**。
//
// 这个类型存在的意义：把它传进 applyImportChunk 之后，函数体里
// **没有任何 per-item 查询**——签名本身就是"O(n) 化"的编译期证明，
// 不靠"跑得快"这种在 CI 上没法断言的证据。
type importSnapshot struct {
	existing []importExisting
	streams  map[int64][]importStream

	// 索引（loadImportSnapshot 建好）
	byID    map[int64]*importExisting
	byTvg   map[string]int64
	byName  map[string]int64
	byURL   map[string][]int64
	maxSort int

	// crossName 是「别的源（或手工建的）同名同分组频道」的索引。
	//
	// 只用于保持旧语义：同一个台被两个源同时提供时，第二份线路追加到
	// 已存在的那个频道上，而不是新建一个重名频道。
	// **绝不用于改字段**——那等于允许一个源冒名另一个源的频道。
	crossName map[string]int64

	// matchedIDs 记录本轮命中过的本源频道（用于"缺失"判定，
	// 也用于同一个台被两条 item 命中时只改一次字段）。
	matchedIDs map[int64]bool
}

// match 按四级优先级找这条 item 应该落到哪个频道。
//
// 返回 (频道 id, 是否属于本源)。0 表示本地还没有这个台。
//
// 前三级都带 source_id（这是"改字段"的作用域）；第四级是跨源兜底，
// 只用于追加线路。
func (snap *importSnapshot) match(c Channel, nu string) (int64, bool) {
	// 1) 本源 + tvg_id：上游给的稳定标识，改名也跟着它走。
	if c.TvgID != "" {
		if id, ok := snap.byTvg[c.TvgID]; ok {
			return id, true
		}
	}
	// 2) 本源 + urlKey：源站地址是频道的物理身份。
	//    只在唯一命中时采用——命中多条说明地址被复用，判给谁都是猜。
	if nu != "" {
		if ids := snap.byURL[urlKey(nu)]; len(ids) == 1 {
			return ids[0], true
		}
	}
	// 3) 本源 + name+group：老库兼容兜底（历史数据可能从未写过 tvg_id）。
	if id, ok := snap.byName[groupKey(c.Name, c.Group)]; ok {
		return id, true
	}
	// 4) 跨源：保持今天的语义——只往那个频道追加线路。
	if id, ok := snap.crossName[groupKey(c.Name, c.Group)]; ok {
		return id, false
	}
	return 0, false
}

// matched 报这个本源频道本轮是否被命中过。
func (snap *importSnapshot) matched(id int64) bool { return snap.matchedIDs[id] }

// markMatched 返回 true 表示这是本轮第一次命中它（字段由这条 item 来改）。
func (snap *importSnapshot) markMatched(id int64) bool {
	if snap.matchedIDs[id] {
		return false
	}
	snap.matchedIDs[id] = true
	return true
}

// ImportChannels 把一次解析结果「对齐」到库里，返回各类计数。
//
// # 为什么必须是对齐而不是只追加
//
// 旧实现只做两件事：按 (name, group) 命中已有频道、按 urlKey 更新线路。
// 于是上游做的这些事在本地完全看不到：
//   - 改名 → 匹配不上 → 插一条新频道，旧频道留在库里变成死台
//     （用户原话就是「上游更新，里面没有自动更新」）；
//   - 下线 → 本地永远不删不隐藏，用户点了播不出来；
//   - 换台标 → 本地永远用第一次导入的那个。
//
// # 匹配优先级，以及为什么每一级都带 source_id
//
//  1. `source_id=本源 AND tvg_id`：上游给的稳定标识。
//  2. `source_id=本源 AND urlKey`（唯一命中）：没有 tvg-id 的源改名只能靠它。
//  3. `source_id=本源 AND name+group`：老库兜底。
//  4. 跨源 name+group：**只追加线路**，绝不改它的 name/logo/tvg_id。
//
// 第 4 条的安全含义：如果允许跨源改字段，一个恶意/被劫持的源只要写
// `tvg_id=CCTV1` 就能命中别的源的频道行、把台名台标换成自己想要的，
// 再把自家线路塞进去——用户看到的是信任的台名+台标，实际播的是攻击者的源。
// 那是内容冒名，不是功能缺陷。
//
// # 绝不触碰的用户数据
//
//   - `enabled`：刷新**永远不**改它。用户手动停用的台不会被刷新重新打开
//     （旧代码在 handler 里给每条 item 硬写 Enabled:true，正是这个 bug 来源）。
//   - `number` / `sort`：用户的台号与排序。
//   - `group_name`：用户可能重新组织过分组，上游改分组不跟。
//   - `epg_id`：EPG 映射只走 SetChannelEpgID。
//   - 收藏 / 提醒 / 续播：靠频道 id 存活，所以缺失只标记、不删行。
func (s *Store) ImportChannels(ctx context.Context, sourceID int64, items []Channel, opts ImportOptions) (ImportStats, error) {
	stats := ImportStats{Parsed: len(items), Capped: opts.Capped}

	// ---------- 1) 预取（只读，不占写锁） ----------
	snap, err := s.loadImportSnapshot(ctx, sourceID)
	if err != nil {
		return stats, err
	}

	// ---------- 2) 分块写入 ----------
	chunk := opts.ChunkSize
	if chunk <= 0 {
		chunk = defaultImportChunk
	}
	for start := 0; start < len(items); start += chunk {
		stop := start + chunk
		if stop > len(items) {
			stop = len(items)
		}
		if err := s.applyImportChunk(ctx, sourceID, items[start:stop], snap, &stats); err != nil {
			return stats, err
		}
	}

	// ---------- 3) 上游已移除 → 标记缺失（不删行、不动 enabled） ----------
	var gone []int64
	for _, e := range snap.existing {
		if !snap.matched(e.id) && !e.missing {
			gone = append(gone, e.id)
		}
	}
	if opts.Align {
		stats.Missing = s.markMissing(ctx, gone)
	} else if len(gone) > 0 {
		stats.AlignmentSkipped = true
	}

	// ---------- 4) 本源的线路去重 ----------
	//
	// 同一频道下「主机+路径」相同的多条线路只保留 id 最大（最新）的那条。
	// 旧实现每次导入都跑一次 `SELECT id,channel_id,url FROM streams`（全表！），
	// 这里只处理本源自己的频道，数据量从"全库"降到"本源"。
	if derr := s.dedupeSourceStreams(ctx, snap.streams); derr != nil {
		// 去重失败不影响导入本身
		_ = derr
	}

	return stats, nil
}

// loadImportSnapshot 一次读回本源现状（频道 + 线路）+ 跨源同名索引。
func (s *Store) loadImportSnapshot(ctx context.Context, sourceID int64) (*importSnapshot, error) {
	snap := &importSnapshot{
		streams:    map[int64][]importStream{},
		byID:       map[int64]*importExisting{},
		byTvg:      map[string]int64{},
		byName:     map[string]int64{},
		byURL:      map[string][]int64{},
		crossName:  map[string]int64{},
		matchedIDs: map[int64]bool{},
	}

	rows, err := s.db.QueryContext(ctx,
		`SELECT id,name,group_name,logo,tvg_id,src_missing FROM channels WHERE source_id=?`, sourceID)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var e importExisting
		var miss int
		if err := rows.Scan(&e.id, &e.name, &e.group, &e.logo, &e.tvgID, &miss); err != nil {
			rows.Close()
			return nil, err
		}
		e.missing = miss != 0
		snap.existing = append(snap.existing, e)
	}
	rerr := rows.Err()
	rows.Close()
	if rerr != nil {
		return nil, rerr
	}

	// 线路：分批 IN，避免超过 SQLite 的变量个数上限。
	ids := make([]int64, 0, len(snap.existing))
	for i := range snap.existing {
		ids = append(ids, snap.existing[i].id)
	}
	for _, part := range chunkIDs(ids, 400) {
		ph := placeholders(len(part))
		args := make([]any, 0, len(part))
		for _, id := range part {
			args = append(args, id)
		}
		srows, qerr := s.db.QueryContext(ctx,
			`SELECT id,channel_id,url FROM streams WHERE channel_id IN (`+ph+`) ORDER BY id`, args...)
		if qerr != nil {
			return nil, qerr
		}
		for srows.Next() {
			var st importStream
			if serr := srows.Scan(&st.id, &st.chID, &st.url); serr != nil {
				srows.Close()
				return nil, serr
			}
			snap.streams[st.chID] = append(snap.streams[st.chID], st)
		}
		serr := srows.Err()
		srows.Close()
		if serr != nil {
			return nil, serr
		}
	}

	// 本源的索引
	for i := range snap.existing {
		e := &snap.existing[i]
		snap.byID[e.id] = e
		if e.tvgID != "" {
			if _, dup := snap.byTvg[e.tvgID]; !dup {
				snap.byTvg[e.tvgID] = e.id
			}
		}
		snap.byName[groupKey(e.name, e.group)] = e.id
		for _, st := range snap.streams[e.id] {
			k := urlKey(st.url)
			snap.byURL[k] = append(snap.byURL[k], e.id)
		}
	}

	// 跨源同名索引（一次查询，不是 per-item）。
	crows, cerr := s.db.QueryContext(ctx,
		`SELECT id,name,group_name FROM channels WHERE source_id<>?`, sourceID)
	if cerr != nil {
		return nil, cerr
	}
	for crows.Next() {
		var id int64
		var name, group string
		if serr := crows.Scan(&id, &name, &group); serr != nil {
			crows.Close()
			return nil, serr
		}
		k := groupKey(name, group)
		if _, dup := snap.crossName[k]; !dup {
			snap.crossName[k] = id
		}
	}
	cerr = crows.Err()
	crows.Close()
	if cerr != nil {
		return nil, cerr
	}

	if err := s.db.QueryRowContext(ctx, `SELECT COALESCE(MAX(sort),0) FROM channels`).Scan(&snap.maxSort); err != nil {
		return nil, err
	}
	return snap, nil
}

// applyImportChunk 在一个写事务里处理一批 item。
//
// 它只读 snap（内存），**不查库**——每个 item 零查询。
func (s *Store) applyImportChunk(ctx context.Context, sourceID int64, items []Channel, snap *importSnapshot, stats *ImportStats) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	// 这个源专属的 UA，导入时写进每条线路的 headers_json。
	var srcUA string
	_ = tx.QueryRowContext(ctx, `SELECT COALESCE(user_agent,'') FROM sources WHERE id=?`, sourceID).Scan(&srcUA)
	headersJSON := "{}"
	if ua := strings.TrimSpace(srcUA); ua != "" {
		if b, jerr := json.Marshal(map[string]string{"User-Agent": ua}); jerr == nil {
			headersJSON = string(b)
		}
	}

	for _, c := range items {
		nu := c.firstURL()
		hitID, sameSource := snap.match(c, nu)

		if hitID == 0 {
			// 本地没有这个台 → 新建。
			// enabled 固定为 1：新建即启用；之后用户怎么开关，刷新一律不碰。
			snap.maxSort++
			res, ierr := tx.ExecContext(ctx,
				`INSERT INTO channels(number,name,group_name,logo,tvg_id,enabled,sort,source_id,created_at)
				 VALUES(?,?,?,?,?,?,?,?,?)`,
				c.Number, c.Name, c.Group, c.Logo, c.TvgID, 1, snap.maxSort, sourceID, Now())
			if ierr != nil {
				return ierr
			}
			cid, _ := res.LastInsertId()
			if nu != "" {
				if _, ierr := tx.ExecContext(ctx,
					`INSERT INTO streams(channel_id,url,priority,health,headers_json) VALUES(?,?,?,?,?)`,
					cid, nu, 0, -1, headersJSON); ierr != nil {
					return ierr
				}
				snap.streams[cid] = append(snap.streams[cid], importStream{url: nu, chID: cid})
			}
			stats.Inserted++
			continue
		}

		if !sameSource {
			// 跨源共享的台：只追加线路（旧语义），绝不改字段。
			//
			// 这里是整条导入路径上**唯一**的 per-item 查询，而且只发生在
			// "同一个台被两个源同时提供"这种少见情况；为了它去把全库线路
			// 预取进内存不值得（那正是我们要去掉的全表扫描）。
			added, aerr := appendStreamToForeignChannel(ctx, tx, hitID, nu, headersJSON)
			if aerr != nil {
				return aerr
			}
			if added {
				stats.Updated++
			} else {
				stats.Unchanged++
			}
			continue
		}

		e := snap.byID[hitID]
		if e == nil {
			continue
		}
		first := snap.markMatched(hitID)
		changed := false

		if first {
			if e.missing {
				// 回到上游：清缺失。注意**不动 enabled**——
				// 用户关掉的台保持关闭。
				if _, uerr := tx.ExecContext(ctx,
					`UPDATE channels SET src_missing=0, missing_since=0 WHERE id=?`, e.id); uerr != nil {
					return uerr
				}
				e.missing = false
				stats.Restored++
				changed = true
			}
			// 名称：上游给了非空名字才更新（空名字多半是解析残缺，别把台名清空）。
			if c.Name != "" && c.Name != e.name {
				if _, uerr := tx.ExecContext(ctx, `UPDATE channels SET name=? WHERE id=?`, c.Name, e.id); uerr != nil {
					return uerr
				}
				e.name = c.Name
				changed = true
			}
			// tvg_id：同上，非空才覆盖。
			if c.TvgID != "" && c.TvgID != e.tvgID {
				if _, uerr := tx.ExecContext(ctx, `UPDATE channels SET tvg_id=? WHERE id=?`, c.TvgID, e.id); uerr != nil {
					return uerr
				}
				e.tvgID = c.TvgID
				changed = true
			}
			// 台标：见 shouldReplaceLogo（不覆盖用户上传的站内台标）。
			if shouldReplaceLogo(e.logo, c.Logo) {
				if _, uerr := tx.ExecContext(ctx, `UPDATE channels SET logo=? WHERE id=?`, c.Logo, e.id); uerr != nil {
					return uerr
				}
				e.logo = c.Logo
				changed = true
			}
		}

		// 线路：按「主机+路径」判重。
		//
		// 不能拿完整 URL 判重：源站地址带签名，每次刷新签名都变，
		// 用完整 URL 比会一直不命中，于是每刷一次就多一条线路，
		// 旧的那条带着过期签名留在库里变成死线路。
		streamChanged := false
		if nu != "" {
			wantKey := urlKey(nu)
			var existStreamID int64
			var existURL string
			for _, st := range snap.streams[e.id] {
				if urlKey(st.url) == wantKey {
					existStreamID, existURL = st.id, st.url
					break
				}
			}
			if existStreamID > 0 {
				if existURL != nu {
					// 签名变了：更新地址，并把健康状态重置为未知等下次探测
					if _, uerr := tx.ExecContext(ctx,
						`UPDATE streams SET url=?, headers_json=?, health=0 WHERE id=?`,
						nu, headersJSON, existStreamID); uerr != nil {
						return uerr
					}
					streamChanged = true
				}
			} else {
				if _, uerr := tx.ExecContext(ctx,
					`INSERT INTO streams(channel_id,url,priority,health,headers_json) VALUES(?,?,?,?,?)`,
					e.id, nu, 0, -1, headersJSON); uerr != nil {
					return uerr
				}
				snap.streams[e.id] = append(snap.streams[e.id], importStream{url: nu, chID: e.id})
				streamChanged = true
			}
		}

		if changed || streamChanged {
			stats.Updated++
		} else {
			stats.Unchanged++
		}
	}

	return tx.Commit()
}

// appendStreamToForeignChannel 往「别的源」的频道追加一条线路。
//
// 返回是否真的新增/更新了线路。只做线路，不碰频道字段。
func appendStreamToForeignChannel(ctx context.Context, tx *sql.Tx, channelID int64, rawURL, headersJSON string) (bool, error) {
	if rawURL == "" {
		return false, nil
	}
	wantKey := urlKey(rawURL)
	rows, err := tx.QueryContext(ctx, `SELECT id,url FROM streams WHERE channel_id=?`, channelID)
	if err != nil {
		return false, err
	}
	var existID int64
	var existURL string
	for rows.Next() {
		var id int64
		var u string
		if serr := rows.Scan(&id, &u); serr != nil {
			rows.Close()
			return false, serr
		}
		if urlKey(u) == wantKey {
			existID, existURL = id, u
			break
		}
	}
	rerr := rows.Err()
	rows.Close()
	if rerr != nil {
		return false, rerr
	}

	if existID > 0 {
		if existURL == rawURL {
			return false, nil
		}
		_, uerr := tx.ExecContext(ctx,
			`UPDATE streams SET url=?, headers_json=?, health=0 WHERE id=?`, rawURL, headersJSON, existID)
		return uerr == nil, uerr
	}
	_, ierr := tx.ExecContext(ctx,
		`INSERT INTO streams(channel_id,url,priority,health,headers_json) VALUES(?,?,?,?,?)`,
		channelID, rawURL, 0, -1, headersJSON)
	return ierr == nil, ierr
}

// markMissing 把「上游已移除」的频道标记为缺失，返回本次新标记的条数。
//
// 只标记、不删行、**不动 enabled**：
//   - 不删行：收藏、开播提醒、跨端续播都引用频道 id，删了会留下一堆悬空引用；
//   - 不动 enabled：那是"用户手动停用"的表达，两者共用 0 就永久不可区分，
//     而用户手动启用一个已被上游移除的台会把它重新激活。
//
// 分批执行，理由同 chunkIDs。
func (s *Store) markMissing(ctx context.Context, ids []int64) int {
	n := 0
	for _, part := range chunkIDs(ids, 400) {
		tx, err := s.db.BeginTx(ctx, nil)
		if err != nil {
			continue
		}
		ph := placeholders(len(part))
		args := make([]any, 0, len(part)+1)
		args = append(args, Now())
		for _, id := range part {
			args = append(args, id)
		}
		res, uerr := tx.ExecContext(ctx,
			`UPDATE channels SET src_missing=1, missing_since=?
			 WHERE id IN (`+ph+`) AND src_missing=0`, args...)
		if uerr == nil {
			if affected, rerr := res.RowsAffected(); rerr == nil {
				n += int(affected)
			}
			_ = tx.Commit()
			continue
		}
		_ = tx.Rollback()
	}
	return n
}

// dedupeSourceStreams 清掉本源频道下「主机+路径」重复的线路，每组只留 id 最大的。
func (s *Store) dedupeSourceStreams(ctx context.Context, streamsByCh map[int64][]importStream) error {
	var drop []int64
	for _, list := range streamsByCh {
		keep := make(map[string]int64, len(list))
		for _, st := range list {
			if st.id == 0 {
				continue // 本轮刚插进去的（还没拿到 id），不可能重复
			}
			k := urlKey(st.url)
			if prev, ok := keep[k]; ok {
				if st.id > prev {
					drop = append(drop, prev)
					keep[k] = st.id
				} else {
					drop = append(drop, st.id)
				}
				continue
			}
			keep[k] = st.id
		}
	}
	for _, part := range chunkIDs(drop, 400) {
		ph := placeholders(len(part))
		args := make([]any, 0, len(part))
		for _, id := range part {
			args = append(args, id)
		}
		if _, err := s.db.ExecContext(ctx, `DELETE FROM streams WHERE id IN (`+ph+`)`, args...); err != nil {
			return err
		}
	}
	return nil
}

// groupKey 是 name+group 的复合键。
//
// 用 \x00 分隔：频道名里可以出现空格、斜杠、竖线，用普通分隔符会撞键。
func groupKey(name, group string) string { return name + "\x00" + group }

// shouldReplaceLogo 判断上游的新台标地址要不要覆盖库里的值。
//
// 三条规则，都是为了不破坏用户的东西：
//  1. 上游没给地址 → 不覆盖。很多源本来就缺 tvg-logo，
//     拿空串覆盖会把用户上传的台标抹掉。
//  2. 库里是站内地址（`/logo/{id}`，用户上传或服务端抓下来的）
//     → 永不覆盖。那是用户明确选过的图。
//  3. 其余情况（上游给了新外链且与旧值不同）→ 更新。
func shouldReplaceLogo(cur, next string) bool {
	if next == "" || next == cur {
		return false
	}
	if strings.HasPrefix(cur, "/logo/") {
		return false
	}
	return true
}

// placeholders 生成 "?,?,?" 。
func placeholders(n int) string {
	if n <= 0 {
		return ""
	}
	return strings.Repeat("?,", n-1) + "?"
}

// chunkIDs 把 id 列表切成多批。
//
// 为什么必须分批：SQLite 的单条语句有变量个数上限，一个上千频道的源
// 直接拼 IN (...) 会报 "too many SQL variables"，整次刷新失败。
func chunkIDs(ids []int64, size int) [][]int64 {
	if size <= 0 {
		size = 400
	}
	var out [][]int64
	for len(ids) > size {
		out = append(out, ids[:size])
		ids = ids[size:]
	}
	if len(ids) > 0 {
		out = append(out, ids)
	}
	return out
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

// ErrReservedDevicePlatform prevents privileged roles entering device storage.
var ErrReservedDevicePlatform = errors.New("reserved device platform")

// ErrDeviceKeyConflict 表示同一个 device_key 出现在不同平台上。
// 这种情况不能直接换发可用 token——否则知道 key 就能顶掉原设备。
var ErrDeviceKeyConflict = errors.New("device_key 已被其他平台占用")

func IsReservedDevicePlatform(platform string) bool {
	return strings.EqualFold(strings.TrimSpace(platform), "admin")
}

// CreateDevice 注册一台待绑定设备。
func (s *Store) CreateDevice(ctx context.Context, key, tokenHash, name, platform, model string) (int64, error) {
	if IsReservedDevicePlatform(platform) {
		return 0, ErrReservedDevicePlatform
	}
	res, err := s.db.ExecContext(ctx, `INSERT INTO devices(device_key,token_hash,name,platform,model,status,created_at,last_seen)
		VALUES(?,?,?,?,?,'pending',?,?)`, key, tokenHash, name, platform, model, Now(), Now())
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

// ListDevices 列出设备。
func (s *Store) ListDevices(ctx context.Context, status string) ([]Device, error) {
	q := `SELECT id,device_key,name,platform,model,status,config_json,config_version,bound_at,last_seen,created_at,ip,app_version FROM devices`
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
		if err := rows.Scan(&d.ID, &d.DeviceKey, &d.Name, &d.Platform, &d.Model, &d.Status, &d.ConfigJSON, &d.ConfigVersion, &d.BoundAt, &d.LastSeen, &d.CreatedAt, &d.IP, &d.AppVersion); err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

// ErrDeviceSessionExpired 表示设备会话已过期，需要重新绑定。
var ErrDeviceSessionExpired = errors.New("设备会话已过期")

// GetDeviceByToken 用 token 哈希查设备（必须是 bound 状态）。
//
// ttlHours <= 0 表示不施加 TTL（保持长期有效，靠显式吊销）。
// ttlHours > 0 时走滑动续期：每次成功使用都把到期时间往后推，
// 只有**长期不用**才会过期。IPTV 场景下设备几乎每次开电视都在用，
// 所以正常用户不会被迫重新绑定，而一台被遗忘/被替换的设备会自然失效。
func (s *Store) GetDeviceByToken(ctx context.Context, tokenHash string, ttlHours int) (*Device, error) {
	var d Device
	var sessionExpires int64
	err := s.db.QueryRowContext(ctx,
		`SELECT id,device_key,name,platform,model,status,config_json,config_version,bound_at,last_seen,created_at,session_expires_at,ip,app_version
		FROM devices WHERE token_hash=? AND status='bound'`, tokenHash).
		Scan(&d.ID, &d.DeviceKey, &d.Name, &d.Platform, &d.Model, &d.Status,
			&d.ConfigJSON, &d.ConfigVersion, &d.BoundAt, &d.LastSeen, &d.CreatedAt, &sessionExpires, &d.IP, &d.AppVersion)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	if IsReservedDevicePlatform(d.Platform) {
		return nil, ErrNotFound
	}

	if ttlHours > 0 {
		now := Now()
		ttl := int64(ttlHours) * 3600
		// 从未设置过到期时间：以 bound_at 为起点**加上 TTL** 得到有效期。
		// 注意不能直接拿 bound_at 当到期时间——那是绑定时刻，
		// 拿它比较会把刚绑好的设备判成"已过期"。
		if sessionExpires == 0 {
			sessionExpires = d.BoundAt + ttl
		}
		if sessionExpires <= 0 {
			sessionExpires = now + ttl
		}
		if now >= sessionExpires {
			return nil, ErrDeviceSessionExpired
		}
		// 滑动续期：把到期时间推到 now+ttl。
		//
		// 只有「剩余有效期不足一半」时才真的写库。以前每个请求都 UPDATE 一次：
		// 直播时设备每秒都在发请求，等于把 devices 表变成了高频写目标
		// （WAL 增长 + fsync），而滑动续期的语义并不要求每次都写。
		// 用 MAX 避免并发下把到期时间往回拨。
		if sessionExpires-now < ttl/2 {
			if _, uerr := s.db.ExecContext(ctx,
				`UPDATE devices SET session_expires_at=MAX(session_expires_at, ?) WHERE id=?`,
				now+ttl, d.ID); uerr != nil {
				// 续期失败不该让请求失败：本次已经通过了到期校验。
				_ = uerr
			}
		}
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

// DeleteDevice 真正删除一台设备及其关联数据。
//
// 管理台的「删除」按钮走的是 DELETE /devices/{id}，但之前那个 handler 只调
// RevokeDevice（把状态改成 revoked），**行还在**——用户点完删除发现设备
// 依然在列表里，看起来就是「删不掉」。
//
// 这里改成真删，并在同一事务里清掉所有引用 device_id 的表，
// 否则外键约束会让删除失败、或者留下孤儿记录。
func (s *Store) DeleteDevice(ctx context.Context, id int64) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	for _, stmt := range []string{
		`DELETE FROM favorites WHERE device_id=?`,
		`DELETE FROM watch_progress WHERE device_id=?`,
		`DELETE FROM play_events WHERE device_id=?`,
		`DELETE FROM epg_reminders WHERE device_id=?`,
		// 绑定码只清掉尚未使用的，已用过的保留审计痕迹
		`DELETE FROM bind_codes WHERE device_id=? AND used=0`,
	} {
		if _, e := tx.ExecContext(ctx, stmt, id); e != nil {
			// 表可能不存在（老库升级），跳过但继续
			continue
		}
	}

	res, err := tx.ExecContext(ctx, `DELETE FROM devices WHERE id=?`, id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return tx.Commit()
}

// touchIntervalSec 是心跳写库的最小间隔。
//
// last_seen 只需要分钟级精度（管理台显示「最后在线」）。以前每个请求都
// UPDATE 一次 devices 表：一台设备在播直播时每秒都有请求，SQLite 上就是
// 持续的 WAL 写入 + fsync，纯属放大写。
const touchIntervalSec = 60

// TouchDevice 更新心跳（节流）。
func (s *Store) TouchDevice(ctx context.Context, id int64) error {
	now := Now()
	_, err := s.db.ExecContext(ctx,
		`UPDATE devices SET last_seen=? WHERE id=? AND last_seen<?`,
		now, id, now-touchIntervalSec)
	return err
}

// TouchDeviceWithInfo 更新心跳，同时记录客户端 IP 与版本号（同样节流）。
//
// 这两个值只用于管理台展示（"这台设备是谁、装的是哪版"），
// 不参与任何鉴权判断。
func (s *Store) TouchDeviceWithInfo(ctx context.Context, id int64, ip, appVersion string) error {
	now := Now()
	_, err := s.db.ExecContext(ctx,
		`UPDATE devices SET last_seen=?,
			ip=CASE WHEN ?<>'' THEN ? ELSE ip END,
			app_version=CASE WHEN ?<>'' THEN ? ELSE app_version END
		 WHERE id=? AND last_seen<?`,
		now, ip, ip, appVersion, appVersion, id, now-touchIntervalSec)
	return err
}

// UpdateDeviceClientInfo 在注册时补写客户端 IP 与版本号。
//
// 与心跳不同，这里必须立即生效：注册刚插入的行 last_seen=now，
// 如果也走 60 秒节流，管理台会有一分钟看不到这台设备的 IP/版本。
func (s *Store) UpdateDeviceClientInfo(ctx context.Context, id int64, ip, appVersion string) error {
	_, err := s.db.ExecContext(ctx,
		`UPDATE devices SET last_seen=?,
			ip=CASE WHEN ?<>'' THEN ? ELSE ip END,
			app_version=CASE WHEN ?<>'' THEN ? ELSE app_version END
		 WHERE id=?`,
		Now(), ip, ip, appVersion, appVersion, id)
	return err
}

// SetDeviceConfig 下发/覆盖终端配置。
func (s *Store) SetDeviceConfig(ctx context.Context, id int64, cfgJSON string) error {
	_, _, err := s.SetDeviceConfigVersioned(ctx, id, cfgJSON)
	return err
}

// SetDeviceConfigVersioned 写设备配置并把 config_version 单调 +1。
//
// R17：版本号必须在**同一条 UPDATE** 里递增，不能先读再写——
// 两个管理员同时保存会读到同一个旧版本，各自 +1 后版本只涨了 1，
// 终端可能因此错过一次变更。
// 返回新版本号，供管理台立刻回显。
func (s *Store) SetDeviceConfigVersioned(ctx context.Context, id int64, cfgJSON string) (int64, bool, error) {
	res, err := s.db.ExecContext(ctx,
		`UPDATE devices SET config_json=?, config_version=config_version+1 WHERE id=?`,
		cfgJSON, id)
	if err != nil {
		return 0, false, err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return 0, false, err
	}
	if n == 0 {
		return 0, false, ErrNotFound
	}
	var ver int64
	if err := s.db.QueryRowContext(ctx,
		`SELECT config_version FROM devices WHERE id=?`, id).Scan(&ver); err != nil {
		return 0, false, err
	}
	return ver, true, nil
}

// ---------- bind codes ----------

// CreateBindCode 生成绑定码。
func (s *Store) CreateBindCode(ctx context.Context, code string, deviceID, ttlSeconds int64) error {
	_, err := s.db.ExecContext(ctx, `INSERT INTO bind_codes(code,device_id,expires_at) VALUES(?,?,?)`,
		code, deviceID, Now()+ttlSeconds)
	return err
}

// 绑定码相关的错误。调用方要能区分"码错了"和"码是对的但不是给你的"，
// 否则会出现"错设备消费了码、真正该消费的设备反而绑不上"。
var (
	ErrBindCodeNotFound = errors.New("绑定码不存在")
	ErrBindCodeUsed     = errors.New("绑定码已被使用")
	ErrBindCodeExpired  = errors.New("绑定码已过期")
	ErrBindCodeMismatch = errors.New("绑定码不属于这台设备")
)

// BindCodeDeviceID 读出绑定码应绑定的设备 ID（0 表示通用邀请码）。
//
// 只读校验，不消费。注册流程先用它判断"这个码是不是给我的"，
// 确认后再走事务消费——不能像以前那样先标 used 再比对。
func (s *Store) BindCodeDeviceID(ctx context.Context, code string) (int64, error) {
	var deviceID, exp int64
	var used int
	err := s.db.QueryRowContext(ctx, `SELECT device_id,expires_at,used FROM bind_codes WHERE code=?`, code).
		Scan(&deviceID, &exp, &used)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return 0, ErrBindCodeNotFound
		}
		return 0, err
	}
	if used != 0 {
		return 0, ErrBindCodeUsed
	}
	if exp < Now() {
		return 0, ErrBindCodeExpired
	}
	return deviceID, nil
}

// RegisterWithBindCode 在一个事务里完成「建建设备 → 消费码 → 批准」。
//
// 这是绑定链路的唯一正确入口，解决三个原有缺陷：
//  1. 通用邀请码（device_id=0）以前永远匹配不上新设备自增 ID，绑不上；
//  2. 码在比对之前就被标 used，错的设备会把码白白吃掉；
//  3. 建建设备和消费码分两次写，中途失败会留下半成品状态。
//
// code 为空时等价于普通注册（只建设备、状态 pending）。
func (s *Store) RegisterWithBindCode(ctx context.Context, key, tokenHash, name, platform, model, code string) (int64, string, error) {
	if IsReservedDevicePlatform(platform) {
		return 0, "", ErrReservedDevicePlatform
	}

	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return 0, "", err
	}
	defer tx.Rollback()

	now := Now()

	// 设备已经存在时不要当成新设备。
	//
	// 以前这里是裸 INSERT，撞上 device_key 唯一约束就直接报错。
	// 客户端把 device_key 做成稳定值（ANDROID_ID）之后，重装/清数据会带着
	// 同一个 key 重新注册——这时应当**认回原设备**，而不是要求管理员再批准一次。
	//
	// 安全边界：只有「已 bound 且平台一致」才直接换发 token。
	// 平台不一致（同一 key 换了平台）或状态是 pending/rejected 时，
	// 只更新 token 与最后在线时间，**保持原状态**，避免仅凭一个 key 就能提权。
	var existID int64
	var existStatus, existPlatform string
	err = tx.QueryRowContext(ctx,
		`SELECT id,status,platform FROM devices WHERE device_key=?`, key).
		Scan(&existID, &existStatus, &existPlatform)
	if err == nil {
		// 平台不一致时**任何状态**都必须拒绝（bound / pending / revoked 一视同仁）。
		//
		// 以前只在 existStatus == "bound" 时拒绝，于是 pending/revoked 的行会被
		// 直接覆盖 token_hash 并返回成功——知道一台待批准设备的 device_key，
		// 就能把它顶掉，再让管理员"批准"那台其实是攻击者的设备。
		// device_key 不是秘密（ANDROID_ID 派生，日志与截图里都可能出现），
		// 它只能当"寻址"用，不能当"凭据"用。
		//
		// 平台一致时只更新 token 与最后在线时间，**保持原状态**：
		// pending 的仍然 pending，不会因为"重复注册"就被批准。
		if existPlatform != platform {
			return 0, "", ErrDeviceKeyConflict
		}
		// 注意不要动 session_expires_at：置 0 会被 GetDeviceByToken 判成"已过期"，
		// 重装后拿到的 token 立刻不可用。
		if _, uerr := tx.ExecContext(ctx,
			`UPDATE devices SET token_hash=?, name=?, model=?, last_seen=? WHERE id=?`,
			tokenHash, name, model, now, existID); uerr != nil {
			return 0, "", uerr
		}
		if cerr := tx.Commit(); cerr != nil {
			return 0, "", cerr
		}
		return existID, existStatus, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return 0, "", err
	}

	res, err := tx.ExecContext(ctx, `INSERT INTO devices(device_key,token_hash,name,platform,model,status,created_at,last_seen)
		VALUES(?,?,?,?,?,'pending',?,?)`, key, tokenHash, name, platform, model, now, now)
	if err != nil {
		return 0, "", err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return 0, "", err
	}

	status := "pending"
	if code != "" {
		// 条件更新：只有未使用且未过期的码才能被消费，且必须成功一次。
		// 用 UPDATE ... WHERE used=0 AND expires_at>=? 保证并发兑换只有一个赢家。
		var owner int64
		var used int
		err := tx.QueryRowContext(ctx, `SELECT device_id,used FROM bind_codes WHERE code=?`, code).Scan(&owner, &used)
		if err != nil {
			if errors.Is(err, sql.ErrNoRows) {
				return 0, "", ErrBindCodeNotFound
			}
			return 0, "", err
		}
		if used != 0 {
			return 0, "", ErrBindCodeUsed
		}
		// device_id=0 是通用邀请码，谁都能用；非 0 只允许码上指定的设备。
		if owner != 0 {
			// 专有码：码里记录的设备必须是"已存在且属于这台设备 key"的，
			// 或者直接由管理员针对某台存量设备生成、现在由同 key 设备重新兑换。
			var existingKey string
			if qerr := tx.QueryRowContext(ctx, `SELECT device_key FROM devices WHERE id=?`, owner).Scan(&existingKey); qerr == nil {
				if existingKey != key {
					return 0, "", ErrBindCodeMismatch
				}
			}
		}
		upd, uerr := tx.ExecContext(ctx, `UPDATE bind_codes SET used=1 WHERE code=? AND used=0 AND expires_at>=?`, code, now)
		if uerr != nil {
			return 0, "", uerr
		}
		if n, _ := upd.RowsAffected(); n != 1 {
			// 并发下被别人先兑换了：整个事务回滚，不留下设备。
			return 0, "", ErrBindCodeUsed
		}
		if _, aerr := tx.ExecContext(ctx, `UPDATE devices SET status='bound', bound_at=? WHERE id=?`, now, id); aerr != nil {
			return 0, "", aerr
		}
		status = "bound"
	}

	if err := tx.Commit(); err != nil {
		return 0, "", err
	}
	return id, status, nil
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

// urlKey 取 URL 的「主机+路径」，丢掉 query 和 fragment。
//
// 用途：源站地址的签名放在 query 里，每次刷新都会变；
// 判重必须忽略签名，否则同一路信号会被当成新线路反复追加。
func urlKey(raw string) string {
	u, err := url.Parse(raw)
	if err != nil {
		return raw
	}
	return u.Scheme + "://" + u.Host + u.Path
}

// 注意：这里曾经有一个 dedupeStreams(ctx, tx)，每次导入都跑一次
// `SELECT id,channel_id,url FROM streams`（**全表**）来清理历史重复线路。
// 它是"刷新卡死"的放大器之一：源的频道越多，全表扫描越多。
//
// 现在改成 dedupeSourceStreams：数据已经在 loadImportSnapshot 里按源预取过，
// 只处理本源自己的频道，不再碰全库。删除它时特意留下这条注释，
// 避免以后有人"顺手加回来"。
