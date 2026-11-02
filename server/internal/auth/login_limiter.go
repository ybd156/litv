package auth

import (
	"net"
	"net/netip"
	"sync"
	"time"
)

const (
	LoginIPLimit = 5
	LoginGlobalLimit = 30
	LoginWindow = time.Minute
	LoginLockout = 15 * time.Minute
	loginMaxIPs = 4096
	loginIdleTTL = 15 * time.Minute
)

type LoginLimitReason string

const (
	LimitIPWindow LoginLimitReason = "ip_window"
	LimitIPLockout LoginLimitReason = "ip_lockout"
	LimitGlobalWindow LoginLimitReason = "global_window"
	LimitCapacity LoginLimitReason = "capacity"
)

// PeerIP canonicalizes a socket peer address, never a forwarded HTTP header.
// Unparseable peers share one fail-closed bucket. IPv4-mapped IPv6 addresses
// and scoped IPv6 addresses cannot gain additional buckets by spelling.
func PeerIP(remoteAddr string) string {
	host, _, err := net.SplitHostPort(remoteAddr)
	if err != nil {
		host = remoteAddr
	}
	ip, err := netip.ParseAddr(host)
	if err != nil {
		return "unknown"
	}
	return ip.Unmap().WithZone("").String()
}

type loginBucket struct {
	attempts []time.Time
	failures int
	lockedUntil time.Time
	lastAttempt time.Time
	inFlight int
}

// LoginLimiter is shared by every listener of one API instance. Sliding-window
// counters and completion of credential checks are protected by the same lock.
// Capacity exhaustion fails closed; it never evicts live lockouts. Idle failure
// streaks expire after 15 minutes, while denied requests cannot extend a lockout.
// Cleanup is lazy and bounded, with no goroutine or per-request timer.
type LoginLimiter struct {
	mu sync.Mutex
	ips map[string]*loginBucket
	global []time.Time
	lastSweep time.Time
	maxIPs int
}

func NewLoginLimiter() *LoginLimiter {
	return &LoginLimiter{
		ips: make(map[string]*loginBucket),
		global: make([]time.Time, 0, LoginGlobalLimit),
		maxIPs: loginMaxIPs,
	}
}

// LoginAttempt is a lease on a bucket so cleanup cannot forget an in-flight
// credential failure. Finish is idempotent and is safe to call from a defer.
type LoginAttempt struct {
	limiter *LoginLimiter
	bucket *loginBucket
	finished bool
}

// Begin accounts for all requests (including malformed/disabled login), before
// body decoding, password hashing, random-token generation or database writes.
// retry is the maximum wait required by every currently applicable limit.
func (l *LoginLimiter) Begin(ip string, now time.Time) (*LoginAttempt, time.Duration, LoginLimitReason) {
	l.mu.Lock()
	defer l.mu.Unlock()

	l.global = pruneAttempts(l.global, now.Add(-LoginWindow))
	if l.lastSweep.IsZero() || !now.Before(l.lastSweep.Add(LoginWindow)) {
		for key, bucket := range l.ips {
			if bucket.inFlight == 0 && !now.Before(bucket.lockedUntil) &&
				!now.Before(bucket.lastAttempt.Add(loginIdleTTL)) {
				delete(l.ips, key)
			}
		}
		l.lastSweep = now
	}

	bucket := l.ips[ip]
	var retry time.Duration
	var reason LoginLimitReason
	if len(l.global) >= LoginGlobalLimit {
		retry = l.global[0].Add(LoginWindow).Sub(now)
		reason = LimitGlobalWindow
	}
	if bucket != nil {
		bucket.attempts = pruneAttempts(bucket.attempts, now.Add(-LoginWindow))
		if !bucket.lockedUntil.IsZero() && !now.Before(bucket.lockedUntil) {
			bucket.lockedUntil = time.Time{}
			bucket.failures = 0
		}
		if len(bucket.attempts) >= LoginIPLimit {
			wait := bucket.attempts[0].Add(LoginWindow).Sub(now)
			if wait > retry {
				retry, reason = wait, LimitIPWindow
			}
		}
		if now.Before(bucket.lockedUntil) {
			wait := bucket.lockedUntil.Sub(now)
			if wait >= retry {
				retry, reason = wait, LimitIPLockout
			}
		}
	}
	if retry > 0 {
		return nil, retry, reason
	}
	if bucket == nil {
		if len(l.ips) >= l.maxIPs {
			return nil, LoginWindow, LimitCapacity
		}
		bucket = &loginBucket{attempts: make([]time.Time, 0, LoginIPLimit)}
		l.ips[ip] = bucket
	}
	bucket.attempts = append(bucket.attempts, now)
	bucket.lastAttempt = now
	bucket.inFlight++
	l.global = append(l.global, now)
	return &LoginAttempt{limiter: l, bucket: bucket}, 0, ""
}

// Finish uses credential outcomes only: correct credentials reset the streak,
// malformed/incorrect credentials count as failures, infrastructure errors do
// neither. Limit rejections never call Finish and never prolong lockouts.
func (a *LoginAttempt) Finish(success, credentialFailure bool, now time.Time) {
	if a == nil {
		return
	}
	l := a.limiter
	l.mu.Lock()
	defer l.mu.Unlock()
	if a.finished {
		return
	}
	a.finished = true
	a.bucket.inFlight--
	if success {
		a.bucket.failures = 0
		return
	}
	if credentialFailure && !now.Before(a.bucket.lockedUntil) {
		a.bucket.failures++
		if a.bucket.failures >= LoginIPLimit {
			a.bucket.lockedUntil = now.Add(LoginLockout)
		}
	}
}

func pruneAttempts(attempts []time.Time, cutoff time.Time) []time.Time {
	first := 0
	for first < len(attempts) && !attempts[first].After(cutoff) {
		first++
	}
	if first > 0 {
		copy(attempts, attempts[first:])
		attempts = attempts[:len(attempts)-first]
	}
	return attempts
}
