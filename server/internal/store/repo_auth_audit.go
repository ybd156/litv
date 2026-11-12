package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"net/netip"
)

type AuthAuditAction string
type AuthAuditReason string

const (
	AuthLoginSuccess   AuthAuditAction = "auth.login_success"
	AuthLoginFailure   AuthAuditAction = "auth.login_failure"
	AuthLoginLimited   AuthAuditAction = "auth.login_limited"
	AuthSessionExpired AuthAuditAction = "auth.session_expired"
	AuthSessionRevoked AuthAuditAction = "auth.session_revoked"
	AuthLegacyRevoked  AuthAuditAction = "auth.legacy_revoked"

	AuditCredentials    AuthAuditReason = "credentials"
	AuditInvalidRequest AuthAuditReason = "invalid_request"
	AuditDisabled       AuthAuditReason = "disabled"
	AuditStorageError   AuthAuditReason = "storage_error"
	AuditIPWindow       AuthAuditReason = "ip_window"
	AuditIPLockout      AuthAuditReason = "ip_lockout"
	AuditGlobalWindow   AuthAuditReason = "global_window"
	AuditCapacity       AuthAuditReason = "capacity"
	AuditTTL            AuthAuditReason = "ttl"
	AuditCleanup        AuthAuditReason = "cleanup"
	AuditLogout         AuthAuditReason = "logout"
	AuditRevoke         AuthAuditReason = "revoke"
	AuditMigration      AuthAuditReason = "migration"

	MaxAuthAuditRows          = 10000
	authAuditRetentionSeconds = 30 * 24 * 60 * 60
)

// AuditAuth accepts only closed enums, a numeric session id and a canonical IP.
// There is deliberately no free-form detail/token/password/error parameter.
// Retention applies to auth.* rows only, leaving unrelated audit logs intact.
func (s *Store) AuditAuth(ctx context.Context, action AuthAuditAction, reason AuthAuditReason, sessionID int64, ip string) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err := auditAuthTx(ctx, tx, action, reason, sessionID, ip, Now()); err != nil {
		return err
	}
	return tx.Commit()
}

func auditAuthTx(ctx context.Context, tx *sql.Tx, action AuthAuditAction, reason AuthAuditReason, sessionID int64, ip string, now int64) error {
	switch action {
	case AuthLoginSuccess, AuthLoginFailure, AuthLoginLimited, AuthSessionExpired, AuthSessionRevoked, AuthLegacyRevoked:
	default:
		return errors.New("unsupported authentication audit action")
	}
	switch reason {
	case AuditCredentials, AuditInvalidRequest, AuditDisabled, AuditStorageError,
		AuditIPWindow, AuditIPLockout, AuditGlobalWindow, AuditCapacity, AuditTTL,
		AuditCleanup, AuditLogout, AuditRevoke, AuditMigration:
	default:
		return errors.New("unsupported authentication audit reason")
	}
	if sessionID < 0 {
		return errors.New("invalid authentication audit session id")
	}
	target := "admin_sessions"
	if sessionID > 0 {
		target = fmt.Sprint(sessionID)
	}
	if addr, err := netip.ParseAddr(ip); err == nil {
		ip = addr.Unmap().WithZone("").String()
	} else if ip != "gateway" {
		ip = "unknown"
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO audit_logs(user_id,action,target,detail,ip,created_at) VALUES(0,?,?,?,?,?)`,
		string(action), target, string(reason), ip, now); err != nil {
		return err
	}
	_, err := tx.ExecContext(ctx, `DELETE FROM audit_logs WHERE action LIKE 'auth.%' AND
		(created_at < ? OR id <= COALESCE((SELECT id FROM audit_logs WHERE action LIKE 'auth.%'
		ORDER BY id DESC LIMIT 1 OFFSET ?), -1))`, now-authAuditRetentionSeconds, MaxAuthAuditRows)
	return err
}
