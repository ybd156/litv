package timeshift

import (
	"log/slog"
	"os"
	"path/filepath"
	"regexp"
	"sync"
	"sync/atomic"
	"time"
)

// Manager 管理所有频道的时移缓冲。
//
// 生命周期：频道第一次被观看时创建 Ring，之后一直保留（环形覆盖最旧的段）。
//
// 空闲回收是必须的：Ring 会一直持有打开的文件句柄（当前段），
// 频道越多占用的 fd 越多；用户把几百个台点一遍就可能在 NAS 上耗尽 fd。
// 所以「超过 idleReapAfter 没有任何读写」的 Ring 会被关掉并删除文件。
// 重新观看时按需重建即可，用户最多失去这个台的旧回看。
type Manager struct {
	dir     string
	opt     Options
	enabled bool

	mu    sync.Mutex
	rings map[int64]*Ring

	stopOnce sync.Once
	stop     chan struct{}
}

// NewManager 创建。dir 为空或 enabled=false 时整个功能降级为 no-op。
func NewManager(dir string, enabled bool, opt Options) *Manager {
	m := &Manager{
		dir:     dir,
		opt:     opt,
		enabled: enabled,
		rings:   make(map[int64]*Ring),
		stop:    make(chan struct{}),
	}
	if m.Enabled() {
		// Ring 的段索引只在内存里，重启后旧文件永远不会再被引用。
		// 不在这里清掉的话，每次重启都会给 NAS 多留一整份"幽灵"录制。
		cleanStaleSegments(dir)
		go m.reapLoop()
	}
	return m
}

// idleReapAfter 是时移缓冲的空闲回收阈值。
//
// 30 分钟：足够覆盖「换台看了一会儿再回来」，又不会让几百个频道
// 的打开文件句柄一直挂着。
const idleReapAfter = 30 * time.Minute

func (m *Manager) reapLoop() {
	t := time.NewTicker(time.Minute)
	defer t.Stop()
	for {
		select {
		case <-m.stop:
			return
		case <-t.C:
			m.reap(time.Now())
		}
	}
}

// reap 关掉并删除空闲超过 idleReapAfter 的 Ring。
func (m *Manager) reap(now time.Time) {
	m.mu.Lock()
	var dead []*Ring
	for id, r := range m.rings {
		if now.Sub(r.LastUsed()) > idleReapAfter {
			delete(m.rings, id)
			dead = append(dead, r)
		}
	}
	m.mu.Unlock()

	for _, r := range dead {
		slog.Info("时移缓冲空闲回收", "channel", r.channelID)
		r.Cleanup()
	}
}

// Enabled 是否开启。
func (m *Manager) Enabled() bool { return m != nil && m.enabled && m.dir != "" }

// Write 把扇出层读到的字节写进时移缓冲。
//
// 调用方是 stream 包里的**每频道 tee 写协程**（见 stream/tee.go），
// 不在上游读取循环里，所以这里的同步磁盘写不会拖慢播放。
//
// 仍然遵守原来的三条：
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
	start := time.Now()
	n, err := r.Write(p)
	// 单次写盘明显变慢：说明磁盘（或 NAS 的共享目录）在拖后腿。
	// 这条日志是「回看有缺口 / tee 丢块」的根因证据，限频 30 秒一条。
	if d := time.Since(start); d > slowWriteThreshold {
		now := time.Now().Unix()
		if last := slowWriteWarnAt.Load(); now-last >= 30 && slowWriteWarnAt.CompareAndSwap(last, now) {
			slog.Warn("时移写盘变慢（不影响直播，但回看会有缺口）",
				"channel", channelID, "ms", d.Milliseconds(), "bytes", n)
		}
	}
	if err != nil {
		slog.Debug("时移写入失败", "channel", channelID, "err", err)
	}
}

// slowWriteThreshold 单次写盘超过这个时长就认为磁盘在拖后腿。
const slowWriteThreshold = 200 * time.Millisecond

// slowWriteWarnAt 上一次打印「写盘变慢」的 Unix 秒（全局限频，不用 map 计数）。
var slowWriteWarnAt atomic.Int64

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
	m.stopOnce.Do(func() { close(m.stop) })
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

// cleanStaleSegments 删除上次运行留下的时移文件。
//
// 只删我们自己命名的 <chN>/chN-<nano>.ts，其它文件一律不碰：
// timeshift.dir 是可配置的，用户可能把它指到已有目录里。
func cleanStaleSegments(root string) {
	entries, err := os.ReadDir(root)
	if err != nil {
		return // 首次启动还没有目录，正常
	}
	dirRe := regexp.MustCompile(`^ch\d+$`)
	fileRe := regexp.MustCompile(`^ch\d+-\d+\.ts$`)
	removed := 0
	for _, e := range entries {
		if !e.IsDir() || !dirRe.MatchString(e.Name()) {
			continue
		}
		dir := filepath.Join(root, e.Name())
		files, err := os.ReadDir(dir)
		if err != nil {
			continue
		}
		for _, f := range files {
			if f.IsDir() || !fileRe.MatchString(f.Name()) {
				continue
			}
			if err := os.Remove(filepath.Join(dir, f.Name())); err == nil {
				removed++
			}
		}
	}
	if removed > 0 {
		slog.Info("清理上次运行遗留的时移文件", "count", removed)
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
