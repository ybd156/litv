package timeshift

import (
	"log/slog"
	"path/filepath"
	"sync"
)

// Manager 管理所有频道的时移缓冲。
//
// 生命周期：频道第一次被观看时创建 Ring，之后一直保留（环形覆盖最旧的段）。
// 不做主动回收——录制文件本身有上限（MaxSegments × SegmentBytes），
// 一个频道最多占几百 MB，家庭 NAS 完全承受得起；主动回收反而会让
// "刚切走的台回来看时没有缓冲"，体验更差。
type Manager struct {
	dir     string
	opt     Options
	enabled bool

	mu    sync.Mutex
	rings map[int64]*Ring
}

// NewManager 创建。dir 为空或 enabled=false 时整个功能降级为 no-op。
func NewManager(dir string, enabled bool, opt Options) *Manager {
	return &Manager{
		dir:     dir,
		opt:     opt,
		enabled: enabled,
		rings:   make(map[int64]*Ring),
	}
}

// Enabled 是否开启。
func (m *Manager) Enabled() bool { return m != nil && m.enabled && m.dir != "" }

// Write 把扇出层读到的字节顺手写进时移缓冲。
//
// 这是热路径（每个直播块都会走），所以：
//   - 不加全局锁，只在需要创建 Ring 时短暂加锁
//   - 单频道内部由 Ring 自己的锁保证并发安全
//   - 写失败只记一次日志，不影响播放（时移是锦上添花，不能拖累主链路）
func (m *Manager) Write(channelID int64, p []byte) {
	if !m.Enabled() {
		return
	}
	r := m.ring(channelID)
	if r == nil {
		return
	}
	if _, err := r.Write(p); err != nil {
		slog.Debug("时移写入失败", "channel", channelID, "err", err)
	}
}

func (m *Manager) ring(channelID int64) *Ring {
	m.mu.Lock()
	if r, ok := m.rings[channelID]; ok {
		m.mu.Unlock()
		return r
	}
	m.mu.Unlock()

	// 创建是慢操作，放锁外做，避免阻塞其它频道的热路径
	dir := filepath.Join(m.dir, channelDirName(channelID))
	r, err := NewRing(channelID, dir, m.opt)
	if err != nil {
		slog.Warn("创建时移缓冲失败", "channel", channelID, "err", err)
		return nil
	}

	m.mu.Lock()
	if exist, ok := m.rings[channelID]; ok {
		m.mu.Unlock()
		r.Close()
		return exist
	}
	m.rings[channelID] = r
	m.mu.Unlock()
	return r
}

// Open 打开某频道「距今 back 秒」的回看流。
func (m *Manager) Open(channelID int64, backSeconds int) (*Reader, error) {
	r := m.ring(channelID)
	if r == nil {
		return nil, errNotAvailable
	}
	return r.Open(backSeconds)
}

// SpanSeconds 返回某频道当前可回看多久。
func (m *Manager) SpanSeconds(channelID int64) int {
	if !m.Enabled() {
		return 0
	}
	m.mu.Lock()
	r, ok := m.rings[channelID]
	m.mu.Unlock()
	if !ok {
		return 0
	}
	return r.SpanSeconds()
}

// Close 关闭全部缓冲（服务退出时）。
func (m *Manager) Close() {
	if m == nil {
		return
	}
	m.mu.Lock()
	rings := make([]*Ring, 0, len(m.rings))
	for _, r := range m.rings {
		rings = append(rings, r)
	}
	m.rings = map[int64]*Ring{}
	m.mu.Unlock()
	for _, r := range rings {
		r.Close()
	}
}

// errNotAvailable 表示该频道还没有时移缓冲。
var errNotAvailable = errString("该频道暂无可回看内容（需要先观看一会儿）")

type errString string

func (e errString) Error() string { return string(e) }

// channelDirName 把频道 ID 变成目录名。
func channelDirName(id int64) string {
	// 直接用十进制 ID 就够，避免引入哈希依赖
	return "ch" + itoa(id)
}

func itoa(v int64) string {
	if v == 0 {
		return "0"
	}
	neg := v < 0
	if neg {
		v = -v
	}
	var b [20]byte
	i := len(b)
	for v > 0 {
		i--
		b[i] = byte('0' + v%10)
		v /= 10
	}
	if neg {
		i--
		b[i] = '-'
	}
	return string(b[i:])
}
