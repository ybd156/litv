// Package remux 把裸 MPEG-TS 直播流转封装成 HLS。
//
// 为什么需要：iOS 的 AVPlayer 只认 HLS，播不了裸 TS；
// 部分老旧播放器也只认 HLS。而国内大量 IPTV 源是裸 TS。
//
// 关键点：**只转封装，不转码**（`-c copy`）。码流原样搬，
// 所以 N100 上几乎不吃 CPU，也不损失画质。
//
// 实现方式：每个频道按需拉起一个 ffmpeg 子进程写 HLS 切片，
// 空闲一段时间后自动回收。
//
// # 出站地址策略（safedial）管不到这里
//
// 上游 URL 是直接交给 ffmpeg 的，由它自己建连、自己跟随重定向，
// 我们既没有 DialContext 也没有 Control 可以挂钩。
// 所以转封装这条路径**不经过** safedial 的判段；能拦住"打 NAS 自己"
// 与云元数据端点的只有其它入口（播放代理 /seg、fetch、probe、logo）。
//
// 真要收口，得给 ffmpeg 传 `-protocol_whitelist` / 走本地代理转发，
// 那属于播放链路的行为变更（会影响 4K 高码率源的 CPU 与延迟），
// 需要和播放域一起评估，本轮刻意不动。
package remux

import (
	"bufio"
	"context"
	"fmt"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// Options 转封装参数。
type Options struct {
	// FFmpeg 可执行文件路径。空表示不可用。
	FFmpeg string
	// Dir 切片输出根目录。
	Dir string
	// SegmentSeconds 单个切片时长，默认 2 秒（直播要短，换台才快）。
	SegmentSeconds int
	// PlaylistSize 播放列表里保留几个切片，默认 6。
	PlaylistSize int
	// IdleTimeout 无请求多久后杀掉 ffmpeg，默认 60 秒。
	IdleTimeout time.Duration
}

// Job 是一个频道正在跑的转封装任务。
type Job struct {
	channelID int64
	dir       string
	cmd       *exec.Cmd
	cancel    context.CancelFunc

	mu       sync.Mutex
	lastUsed time.Time
	exited   bool
}

// Manager 管理所有转封装任务。
type Manager struct {
	opt Options

	mu   sync.Mutex
	jobs map[int64]*Job
}

// NewManager 创建。
func NewManager(opt Options) *Manager {
	if opt.SegmentSeconds <= 0 {
		opt.SegmentSeconds = 2
	}
	if opt.PlaylistSize <= 0 {
		opt.PlaylistSize = 6
	}
	if opt.IdleTimeout <= 0 {
		opt.IdleTimeout = 60 * time.Second
	}
	return &Manager{opt: opt, jobs: make(map[int64]*Job)}
}

// Available 判断 ffmpeg 是否可用。
//
// 注意：配置里可能只写 "ffmpeg"（靠 PATH 找），也可能是绝对路径。
// 只 os.Stat 会漏掉前者——飞牛系统自带 ffmpeg 就属于这种。
func (m *Manager) Available() bool {
	if m == nil || m.opt.FFmpeg == "" || m.opt.Dir == "" {
		return false
	}
	return resolveFFmpeg(m.opt.FFmpeg) != ""
}

// FFmpegPath 返回实际解析到的 ffmpeg 路径（找不到返回空）。
// 日志里用它，用户就知道我们到底用了哪个 ffmpeg，而不是只看到配置里的原始值。
func (m *Manager) FFmpegPath() string {
	if m == nil || m.opt.FFmpeg == "" {
		return ""
	}
	return resolveFFmpeg(m.opt.FFmpeg)
}

// resolveFFmpeg 返回可执行的 ffmpeg 路径，找不到返回空。
//
// 为什么要找这么多地方：NAS 上 ffmpeg 经常**不在 PATH 里**。
// 用户看到的现象是「转封装已配置但 ffmpeg 不可用」，然后 iOS / 浏览器
// 播不了裸 TS 源——但他其实装了 ffmpeg，只是我们没找到。
//
// 查找顺序（先精确后模糊）：
//  1. 配的是绝对路径 → 直接用
//  2. 随 App 打包的 ffmpeg（和 litvd 同目录）→ 最优先，保证自包含
//  3. PATH 里的
//  4. 常见安装位置
func resolveFFmpeg(name string) string {
	// 1. 绝对路径
	if strings.ContainsRune(name, os.PathSeparator) {
		if st, err := os.Stat(name); err == nil && !st.IsDir() {
			return name
		}
		return ""
	}

	// 2. 随 App 打包的（fpk 里 bin/ffmpeg）
	if exe, err := os.Executable(); err == nil {
		cand := filepath.Join(filepath.Dir(exe), name)
		if st, err := os.Stat(cand); err == nil && !st.IsDir() {
			return cand
		}
	}

	// 3. PATH
	if p, err := exec.LookPath(name); err == nil {
		return p
	}

	// 4. 常见位置（NAS / 各种 Linux 发行版）
	for _, dir := range []string{
		"/usr/bin",
		"/usr/local/bin",
		"/opt/ffmpeg/bin",
		"/opt/bin",
		"/usr/trim/bin", // 飞牛 fnOS 自带的组件目录
		"/vol1/@appstore",
		"/volume1/@appstore",
	} {
		cand := filepath.Join(dir, name)
		if st, err := os.Stat(cand); err == nil && !st.IsDir() {
			return cand
		}
	}
	return ""
}

// Playlist 返回某频道的 m3u8 内容（必要时先拉起 ffmpeg）。
// segPrefix 用于把切片 URI 改写成我们自己的出口路径。
func (m *Manager) Playlist(channelID int64, upstream string, segPrefix string) ([]byte, error) {
	return m.PlaylistWithHeaders(channelID, upstream, segPrefix, nil)
}

// PlaylistWithHeaders 与 Playlist 相同，但把线路请求头交给 ffmpeg。
//
// R09：源站可能要求特定 UA / Referer 才给流。ffmpeg 默认带自己的 UA，
// 于是裸 TS 源在 NAS 转封装这一步就被 403，用户看到的是
// "iOS 播不了、但电视能播"——因为电视走的是另一条链路。
// 这里把与 relay/分片完全相同的一份头喂给 ffmpeg。
func (m *Manager) PlaylistWithHeaders(channelID int64, upstream string, segPrefix string, headers map[string]string) ([]byte, error) {
	if !m.Available() {
		return nil, fmt.Errorf("服务端未配置 ffmpeg，无法转封装（可在 config.yaml 的 transcode.ffmpeg 指定路径）")
	}
	j, err := m.ensure(channelID, upstream, headers)
	if err != nil {
		return nil, err
	}
	j.touch()

	path := filepath.Join(j.dir, "index.m3u8")
	// 等 ffmpeg 吐出第一个播放列表（最多 10 秒）
	deadline := time.Now().Add(10 * time.Second)
	for {
		if data, err := os.ReadFile(path); err == nil && len(data) > 0 {
			return rewritePlaylist(data, segPrefix), nil
		}
		if time.Now().After(deadline) {
			return nil, fmt.Errorf("转封装启动超时（上游可能连不上，或编码不受支持）")
		}
		time.Sleep(200 * time.Millisecond)
	}
}

// Segment 返回一个切片文件的内容。
func (m *Manager) Segment(channelID int64, name string) ([]byte, error) {
	m.mu.Lock()
	j, ok := m.jobs[channelID]
	m.mu.Unlock()
	if !ok {
		return nil, fmt.Errorf("转封装未启动")
	}
	j.touch()

	// 防路径穿越：只允许纯文件名
	if strings.ContainsAny(name, "/\\") || strings.Contains(name, "..") {
		return nil, fmt.Errorf("非法切片名")
	}
	return os.ReadFile(filepath.Join(j.dir, name))
}

// Stop 停掉某频道。
func (m *Manager) Stop(channelID int64) {
	m.mu.Lock()
	j, ok := m.jobs[channelID]
	if ok {
		delete(m.jobs, channelID)
	}
	m.mu.Unlock()
	if ok {
		j.kill()
	}
}

// Close 停掉全部。
func (m *Manager) Close() {
	m.mu.Lock()
	jobs := make([]*Job, 0, len(m.jobs))
	for _, j := range m.jobs {
		jobs = append(jobs, j)
	}
	m.jobs = map[int64]*Job{}
	m.mu.Unlock()
	for _, j := range jobs {
		j.kill()
	}
}

// Stats 返回正在转封装的频道。
func (m *Manager) Stats() []map[string]any {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]map[string]any, 0, len(m.jobs))
	for id, j := range m.jobs {
		j.mu.Lock()
		out = append(out, map[string]any{
			"channel_id": id,
			"dir":        j.dir,
			"idle_s":     int(time.Since(j.lastUsed).Seconds()),
		})
		j.mu.Unlock()
	}
	return out
}

// ---------------------------------------------------------------- 内部

// buildFFmpegHeaders 把 map 拼成 ffmpeg 的 -headers 参数值。
//
// ffmpeg 要求的格式是每条 "Key: value\r\n"，整体作为一个参数字符串。
// 键必须稳定排序，否则同样的头每次拼出不同顺序，会让 ffmpeg 的缓存
// 和日志出现无意义的抖动。
//
// 含 CRLF 的值直接丢弃：用户导入的源文件是不可信输入，
// 放行就等于允许往 ffmpeg 的请求里注入额外头。
func buildFFmpegHeaders(headers map[string]string) string {
	if len(headers) == 0 {
		return ""
	}
	keys := make([]string, 0, len(headers))
	for k, v := range headers {
		if k == "" || strings.ContainsAny(k, ":\r\n") || strings.ContainsAny(v, "\r\n\x00") {
			continue
		}
		keys = append(keys, k)
	}
	if len(keys) == 0 {
		return ""
	}
	sort.Strings(keys)
	var b strings.Builder
	for _, k := range keys {
		b.WriteString(k)
		b.WriteString(": ")
		b.WriteString(headers[k])
		b.WriteString("\r\n")
	}
	return b.String()
}

func (m *Manager) ensure(channelID int64, upstream string, headers map[string]string) (*Job, error) {
	m.mu.Lock()
	if j, ok := m.jobs[channelID]; ok && !j.exited {
		m.mu.Unlock()
		return j, nil
	}
	m.mu.Unlock()

	dir := filepath.Join(m.opt.Dir, fmt.Sprintf("ch%d", channelID))
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, fmt.Errorf("创建转封装目录失败: %w", err)
	}
	// 清掉上次残留的切片，避免播放器拿到过期的
	cleanDir(dir)

	ctx, cancel := context.WithCancel(context.Background())
	args := []string{
		"-hide_banner", "-loglevel", "warning",
		"-fflags", "nobuffer",
		"-flags", "low_delay",
	}
	// R09：ffmpeg 的 -headers 必须写在 -i 之前，否则对输入不生效。
	// 值里不能有 CRLF：那是请求头注入。
	if h := buildFFmpegHeaders(headers); h != "" {
		args = append(args, "-headers", h)
	}
	args = append(args,
		"-i", upstream,
		"-c", "copy", // 关键：只转封装不转码
		"-f", "hls",
		"-hls_time", fmt.Sprint(m.opt.SegmentSeconds),
		"-hls_list_size", fmt.Sprint(m.opt.PlaylistSize),
		"-hls_flags", "delete_segments+omit_endlist+independent_segments",
		"-hls_segment_filename", filepath.Join(dir, "seg%05d.ts"),
		filepath.Join(dir, "index.m3u8"),
	)
	cmd := exec.CommandContext(ctx, resolveFFmpeg(m.opt.FFmpeg), args...)
	cmd.Dir = dir
	if err := cmd.Start(); err != nil {
		cancel()
		return nil, fmt.Errorf("启动 ffmpeg 失败: %w", err)
	}

	j := &Job{
		channelID: channelID,
		dir:       dir,
		cmd:       cmd,
		cancel:    cancel,
		lastUsed:  time.Now(),
	}

	m.mu.Lock()
	if old, ok := m.jobs[channelID]; ok {
		m.mu.Unlock()
		old.kill()
		m.mu.Lock()
	}
	m.jobs[channelID] = j
	m.mu.Unlock()

	// 等 ffmpeg 退出，标记状态
	go func() {
		err := cmd.Wait()
		j.mu.Lock()
		j.exited = true
		j.mu.Unlock()
		if err != nil {
			slog.Warn("转封装进程退出", "channel", channelID, "err", err)
		} else {
			slog.Info("转封装进程正常结束", "channel", channelID)
		}
	}()

	// 空闲回收
	go m.reaper(channelID, j)
	slog.Info("转封装已启动", "channel", channelID, "dir", dir)
	return j, nil
}

// reaper 定期检查空闲时间，超时就杀进程并清理。
func (m *Manager) reaper(channelID int64, j *Job) {
	t := time.NewTicker(10 * time.Second)
	defer t.Stop()
	for range t.C {
		j.mu.Lock()
		idle := time.Since(j.lastUsed)
		exited := j.exited
		j.mu.Unlock()

		if exited {
			return
		}
		if idle > m.opt.IdleTimeout {
			slog.Info("转封装空闲回收", "channel", channelID, "idle_s", int(idle.Seconds()))
			m.mu.Lock()
			if cur, ok := m.jobs[channelID]; ok && cur == j {
				delete(m.jobs, channelID)
			}
			m.mu.Unlock()
			j.kill()
			cleanDir(j.dir)
			return
		}
	}
}

func (j *Job) touch() {
	j.mu.Lock()
	j.lastUsed = time.Now()
	j.mu.Unlock()
}

func (j *Job) kill() {
	j.cancel()
	j.mu.Lock()
	j.exited = true
	j.mu.Unlock()
}

func cleanDir(dir string) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	// 只删我们自己的切片和播放列表，不碰别的
	var names []string
	for _, e := range entries {
		n := e.Name()
		if strings.HasSuffix(n, ".ts") || strings.HasSuffix(n, ".m3u8") {
			names = append(names, n)
		}
	}
	sort.Strings(names)
	for _, n := range names {
		_ = os.Remove(filepath.Join(dir, n))
	}
}

// rewritePlaylist 把切片 URI 改写成经过我们出口的路径。
//
// 为什么要改写：切片由服务端产生，但客户端拿到的是
// /live/{id}/remux/{seg} 这样的地址，这样才能复用同一套签名鉴权，
// 也避免客户端直接摸到磁盘路径。
func rewritePlaylist(data []byte, segPrefix string) []byte {
	var out strings.Builder
	sc := bufio.NewScanner(strings.NewReader(string(data)))
	sc.Buffer(make([]byte, 64*1024), 1024*1024)
	for sc.Scan() {
		line := sc.Text()
		trimmed := strings.TrimSpace(line)
		switch {
		case trimmed == "" || strings.HasPrefix(trimmed, "#"):
			out.WriteString(line)
		default:
			// 非注释行 = 切片 URI
			out.WriteString(segPrefix)
			out.WriteString(trimmed)
		}
		out.WriteString("\n")
	}
	return []byte(out.String())
}
