package store

import (
	"context"
	"database/sql"
	"errors"
)

// KVAdminPasswordHash 是「管理台登录密码」的哈希在 kv 表里的键。
//
// 为什么放进数据库而不是只认配置文件：首次部署时用户要自己设密码
// （见 /api/v1/auth/setup），设完之后这个口令就归用户所有——
// 再被配置文件里的明文覆盖，或者重装时被安装脚本随机生成的值顶掉，
// 都会让用户「明明设过密码却登不进」。配置文件里的 admin_password
// 只在数据库里还没有哈希时作为初始值使用。
const KVAdminPasswordHash = "auth.admin_password_hash"

// KVGet 读一个配置键。不存在时返回 ("", false, nil)——
// 「没配过」与「配成空串」必须区分得开。
func (s *Store) KVGet(ctx context.Context, key string) (string, bool, error) {
	var value string
	err := s.db.QueryRowContext(ctx, `SELECT v FROM kv WHERE k = ?`, key).Scan(&value)
	if errors.Is(err, sql.ErrNoRows) {
		return "", false, nil
	}
	if err != nil {
		return "", false, err
	}
	return value, true, nil
}

// KVSet 写入或覆盖一个配置键。
func (s *Store) KVSet(ctx context.Context, key, value string) error {
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO kv(k, v) VALUES(?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v`,
		key, value)
	return err
}
