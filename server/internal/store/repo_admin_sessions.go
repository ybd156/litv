package store

import (
	"context"
	"database/sql"
	"encoding/hex"
	"errors"
	"time"
)

var (
	ErrAdminSessionExpired = errors.New("admin session expired")
	ErrInvalidAdminSession = errors.New("invalid admin session parameters")
)

// AdminSession contains no plaintext token or token hash. Device identity is
// deliberately absent: an emergency session can never become a native device.
type AdminSession struct {
	ID int64 `json:"id"`
	CreatedAt int64 `json:"created_at"`
	ExpiresAt int64 `json:"expires_at"`
}

// CreateAdminSession is serialized by taking SQLite's write lock before any
// read. Cleanup, oldest-session eviction and insertion are one transaction,
// so concurrent logins cannot exceed the cap or leave partially created tokens.
func (s *Store) CreateAdminSession(ctx context.Context, tokenHash string, now int64, ttl time.Duration, maxSessions int) (*AdminSession, error) {
	if !validTokenHash(tokenHash) || now < 0 || ttl < time.Hour || ttl > 168*time.Hour ||
		maxSessions < 1 || maxSessions > 128 {
		return nil, ErrInvalidAdminSession
	}
	expiresAt := now + int64(ttl/time.Second)
	if expiresAt <= now {
		return nil, ErrInvalidAdminSession
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	if err := cleanupAdminSessionsTx(ctx, tx, now); err != nil {
		return nil, err
	}
	res, err := tx.ExecContext(ctx, `DELETE FROM admin_sessions WHERE id IN
		(SELECT id FROM admin_sessions ORDER BY created_at,id LIMIT
		MAX(0,(SELECT COUNT(*) FROM admin_sessions)-?+1))`, maxSessions)
	if err != nil {
		return nil, err
	}
	if n, err := res.RowsAffected(); err != nil {
		return nil, err
	} else if n > 0 {
		if err := auditAuthTx(ctx, tx, AuthSessionRevoked, AuditCapacity, 0, "unknown", now); err != nil {
			return nil, err
		}
	}
	res, err = tx.ExecContext(ctx, `INSERT INTO admin_sessions(token_hash,created_at,expires_at) VALUES(?,?,?)`, tokenHash, now, expiresAt)
	if err != nil {
		return nil, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return nil, err
	}
	if err := tx.Commit(); err != nil {
		return nil, err
	}
	return &AdminSession{ID: id, CreatedAt: now, ExpiresAt: expiresAt}, nil
}

// GetAdminSessionByToken checks absolute expiry on every authentication; use
// never slides or refreshes TTL. At expires_at itself the session is invalid.
// An expired row is removed once. Only its deleting caller receives the expiry
// error, allowing the HTTP layer to audit the actual peer without replay floods.
func (s *Store) GetAdminSessionByToken(ctx context.Context, tokenHash string, now int64) (*AdminSession, error) {
	if !validTokenHash(tokenHash) {
		return nil, ErrNotFound
	}
	var session AdminSession
	err := s.db.QueryRowContext(ctx, `SELECT id,created_at,expires_at FROM admin_sessions WHERE token_hash=?`, tokenHash).
		Scan(&session.ID, &session.CreatedAt, &session.ExpiresAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if session.ExpiresAt <= now {
		res, err := s.db.ExecContext(ctx, `DELETE FROM admin_sessions WHERE id=? AND expires_at<=?`, session.ID, now)
		if err != nil {
			return nil, err
		}
		n, err := res.RowsAffected()
		if err != nil {
			return nil, err
		}
		if n > 0 {
			return &session, ErrAdminSessionExpired
		}
		return nil, ErrNotFound
	}
	return &session, nil
}

func (s *Store) RevokeAdminSession(ctx context.Context, id int64) (bool, error) {
	if id <= 0 {
		return false, ErrInvalidAdminSession
	}
	res, err := s.db.ExecContext(ctx, `DELETE FROM admin_sessions WHERE id=?`, id)
	if err != nil {
		return false, err
	}
	n, err := res.RowsAffected()
	return n > 0, err
}

// RevokeAllAdminSessions affects emergency sessions only, never devices/users.
func (s *Store) RevokeAllAdminSessions(ctx context.Context) (int64, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	res, err := tx.ExecContext(ctx, `DELETE FROM admin_sessions`)
	if err != nil {
		return 0, err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return 0, err
	}
	return n, tx.Commit()
}

// CleanupAdminSessions is used at startup and each login. The active table is
// always bounded by the configured cap; expiry requires no background worker.
func (s *Store) CleanupAdminSessions(ctx context.Context, now int64) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err := cleanupAdminSessionsTx(ctx, tx, now); err != nil {
		return err
	}
	return tx.Commit()
}

func cleanupAdminSessionsTx(ctx context.Context, tx *sql.Tx, now int64) error {
	res, err := tx.ExecContext(ctx, `DELETE FROM admin_sessions WHERE expires_at<=?`, now)
	if err != nil {
		return err
	}
	n, err := res.RowsAffected()
	if err != nil || n == 0 {
		return err
	}
	// One bounded, secret-free batch event, not one row per expired session.
	return auditAuthTx(ctx, tx, AuthSessionExpired, AuditCleanup, 0, "unknown", now)
}

func validTokenHash(hash string) bool {
	if len(hash) != 64 {
		return false
	}
	_, err := hex.DecodeString(hash)
	return err == nil
}

// migrateAdminSessions retires every legacy administrator device token. Old
// rows remain revoked for referential/history compatibility, but are never
// imported into the new privileged namespace, even if still marked bound.
func (s *Store) migrateAdminSessions(ctx context.Context) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	res, err := tx.ExecContext(ctx, `UPDATE devices SET status='revoked',token_hash=''
		WHERE lower(trim(platform))='admin' AND (status<>'revoked' OR token_hash<>'')`)
	if err != nil {
		return err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if n > 0 {
		if err := auditAuthTx(ctx, tx, AuthLegacyRevoked, AuditMigration, 0, "unknown", Now()); err != nil {
			return err
		}
	}
	if err := cleanupAdminSessionsTx(ctx, tx, Now()); err != nil {
		return err
	}
	return tx.Commit()
}
