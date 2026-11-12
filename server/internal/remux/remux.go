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
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/litv/litv/server/internal/fetch"
	"github.com/litv/litv/server/internal/ffmpeg"
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

	// upstream 是这条任务正在拉的上游地址（只用于诊断，不含请求头）。
	upstream string

	// fingerprint 是「上游 + 规范化请求头」的指纹，换线时据此判断要不要重建。
	fingerprint string

	// started 是任务启动时刻，看门狗用它区分「刚启动」与「真的卡住了」。
	started time.Time
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
	return m.PlaylistContext(context.Background(), channelID, upstream, segPrefix, headers)
}

// PlaylistContext 带 ctx 的版本。
//
// 为什么要 ctx：等 ffmpeg 出第一个播放列表最多要 10 秒，客户端在这期间
// 关掉页面/切台时请求就没了，但原来的实现还会把 goroutine 挂满 10 秒。
// 用 ctx 可以在客户端离开时立刻放弃。
func (m *Manager) PlaylistContext(ctx context.Context, channelID int64, upstream string, segPrefix string, headers map[string]string) ([]byte, error) {
	if !m.Available() {
		return nil, fmt.Errorf("服务端未配置 ffmpeg，无法转封装（可在 config.yaml 的 transcode.ffmpeg 指定路径）")
	}
	j, err := m.ensure(channelID, upstream, headers)
	if err != nil {
		return nil, err
	}
	j.touch()

	path := filepath.Join(j.dir, "index.m3u8")

	// 看门狗：ffmpeg 进程还活着，但播放列表已经好几秒没更新。
	//
	// 这种情况上游多半是「半死」的（TCP 还连着、数据不来了）：ffmpeg 既不退出
	// 也不产出新切片。不减列表就会一直停在旧片段上——播放器每 2 秒拿到同一份
	// 列表，去取已经被 delete_segments 删掉的分片，于是 404 循环、画面黑掉。
	// 处理方式是杀掉重建一次（只一次，避免在这里打转）。
	if m.playlistStale(j, path) {
		slog.Warn("转封装播放列表停止更新，重建任务", "channel", channelID,
			"idle_s", playlistIdleSeconds(path))
		m.dropJob(channelID, j)
		j2, err2 := m.ensure(channelID, upstream, headers)
		if err2 != nil {
			return nil, err2
		}
		j = j2
		j.touch()
	}

	// 等 ffmpeg 吐出第一个播放列表（最多 10 秒）
	deadline := time.Now().Add(10 * time.Second)
	timer := time.NewTimer(200 * time.Millisecond)
	defer timer.Stop()
	for {
		if data, err := os.ReadFile(path); err == nil && len(data) > 0 {
			return rewritePlaylist(data, segPrefix), nil
		}
		if time.Now().After(deadline) {
			return nil, fmt.Errorf("转封装启动超时（上游可能连不上，或编码不受支持）")
		}
		timer.Reset(200 * time.Millisecond)
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-timer.C:
		}
	}
}

// Segment 返回一个切片文件的内容。
func (m *Manager) Segment(channelID int64, name string) ([]byte, error) {
	f, _, err := m.OpenSegment(channelID, name)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	return io.ReadAll(f)
}

// OpenSegment 打开一个切片文件，交给 http.ServeContent 直接回给客户端。
//
// 为什么不在 handler 里先 ReadFile 再 Write：
//   - 切片是完整文件，ServeContent 能直接处理 Range（播放器 seek/断点续传
//     会发 Range；以前一律回 200 全量，等于每次 seek 都重传整片）；
//   - 能带上 Last-Modified/Content-Length，播放器可以自己判断是否需要重取；
//   - 大切片不必整个读进内存再拷贝一次。
func (m *Manager) OpenSegment(channelID int64, name string) (*os.File, time.Time, error) {
	m.mu.Lock()
	j, ok := m.jobs[channelID]
	m.mu.Unlock()
	if !ok {
		return nil, time.Time{}, fmt.Errorf("转封装未启动")
	}
	j.touch()

	// 防路径穿越：只允许纯文件名
	if strings.ContainsAny(name, "/\\") || strings.Contains(name, "..") {
		return nil, time.Time{}, fmt.Errorf("非法切片名")
	}
	path := filepath.Join(j.dir, name)
	f, err := os.Open(path)
	if err != nil {
		return nil, time.Time{}, err
	}
	st, err := f.Stat()
	if err != nil {
		_ = f.Close()
		return nil, time.Time{}, err
	}
	return f, st.ModTime(), nil
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

			// 诊断「换线没反应 / 画面黑掉」：正在拉哪条上游、
			// 播放列表已经多久没更新（-1 = 还没写出来）。
			"upstream":       j.upstream,
			"playlist_age_s": playlistIdleSeconds(filepath.Join(j.dir, "index.m3u8")),
		})
		j.mu.Unlock()
	}
	return out
}

// ---------------------------------------------------------------- 内部

// buildFFmpegHeaders 把 map 拼成 ffmpeg 的 -headers 参数值。
//
// 具体格式与 CRLF 过滤见 internal/ffmpeg.HeadersArg——录制也用同一份，
// 避免两处安全逻辑各写一遍后慢慢漂移。
func buildFFmpegHeaders(headers map[string]string) string {
	return ffmpeg.HeadersArg(headers)
}

// upstreamFingerprint 返回「上游地址 + 规范化请求头」的指纹。
//
// 为什么要带请求头：同一台源的不同线路经常只有 Referer / UA 不同，
// 只比 URL 会漏掉「切到另一条要求不同头的线路」，用户点了线路仍然没反应。
//
// 为什么不把头值直接拼进去：指纹会进日志与诊断输出，而头里可能带 Cookie、
// Referer（可能含 token）。这里只保留 SHA-256 的前 8 字节，够比较、不泄露。
func upstreamFingerprint(upstream string, headers map[string]string) string {
	sum := sha256.Sum256([]byte(ffmpeg.HeadersArg(headers)))
	return upstream + "\x00" + hex.EncodeToString(sum[:8])
}

// shortFP 只取指纹尾部（= 头摘要），日志里足够区分且不会把整条 URL 打出去。
func shortFP(fp string) string {
	if len(fp) <= 16 {
		return fp
	}
	return fp[len(fp)-16:]
}

func (m *Manager) ensure(channelID int64, upstream string, headers map[string]string) (*Job, error) {
	// 整个「检查 → 建目录 → 清残留 → 启动 → 登记」必须在一把锁里完成。
	//
	// 修复前这里先 Unlock、再 MkdirAll/cleanDir/cmd.Start、最后重新加锁。
	// 两台设备同时换到同一个台时，两路请求都会看到「没有任务」，于是各自
	// 起一个 ffmpeg 写同一个目录：cleanDir 会删掉另一个刚写出的切片，
	// 两个进程还会互相覆盖 index.m3u8，表现为画面反复中断、花屏。
	// 许可证/进程数也是双倍。
	m.mu.Lock()
	defer m.mu.Unlock()

	// 出站 SSRF 校验：上游地址来自导入的订阅源。ffmpeg 自己不会挡本机地址，
	// 所以必须在交给它之前拦一次（私网/代理仍然放行）。
	if err := fetch.ValidateURL(upstream); err != nil {
		return nil, fmt.Errorf("上游地址不被允许: %w", err)
	}

	// 上游指纹：URL + 规范化请求头的摘要。换线/换头都要重建 ffmpeg，
	// 否则它会继续拉旧地址（用户看到「点了线路没反应」）。
	fp := upstreamFingerprint(upstream, headers)

	if j, ok := m.jobs[channelID]; ok {
		switch {
		case j.isExited():
			// 进程已经退出（上游断开、编码不支持……），旧任务没有价值了，
			// 清掉后重建一个。
			delete(m.jobs, channelID)
		case j.fingerprint == fp:
			return j, nil
		default:
			// 上游换了一条线路（或请求头变了），而旧进程还在拉**旧线路**。
			// 直接复用等于「用户切了线路、画面没变」——这就是 iOS 侧
			// 「换线路没反应」的根因。杀掉重建。
			slog.Info("转封装上游变化，重建任务", "channel", channelID,
				"old_fp", shortFP(j.fingerprint), "new_fp", shortFP(fp))
			delete(m.jobs, channelID)
			j.kill()
		}
	}

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
	j.upstream = upstream
	j.fingerprint = fp
	j.started = time.Now()
	m.jobs[channelID] = j

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

// maxPlaylistIdle 返回「播放列表多久不更新就算死」。
//
// 取 3 × 分片时长（下限 6 秒）：ffmpeg 每写完一个分片更新一次列表，
// 2 秒分片就是每 2 秒一次；给 3 倍余量吸收源站抖动和磁盘慢。
func (m *Manager) maxPlaylistIdle() time.Duration {
	d := time.Duration(m.opt.SegmentSeconds) * time.Second * 3
	if d < 6*time.Second {
		d = 6 * time.Second
	}
	return d
}

// playlistStale 判断「进程活着但播放列表不再更新」。
func (m *Manager) playlistStale(j *Job, path string) bool {
	if j.isExited() {
		return false // 进程已退出由 ensure 处理，这里不重复动手
	}
	j.mu.Lock()
	started := j.started
	j.mu.Unlock()

	idle := m.maxPlaylistIdle()
	// 刚启动的任务还在探测上游、等第一个切片，这段不算卡死。
	if time.Since(started) < idle+5*time.Second {
		return false
	}
	st, err := os.Stat(path)
	if err != nil {
		return false
	}
	return time.Since(st.ModTime()) > idle
}

// playlistIdleSeconds 给日志/统计用：播放列表已经多少秒没更新，-1 表示还没写出来。
func playlistIdleSeconds(path string) int {
	st, err := os.Stat(path)
	if err != nil {
		return -1
	}
	return int(time.Since(st.ModTime()).Seconds())
}

// dropJob 把任务从表里摘掉并杀掉（只有它还是当前任务时才摘）。
//
// 摘表必须持 m.mu，杀进程放在放锁之后：kill 只取消 ctx 并置标志，
// 但它的 Wait 收尾协程要拿 j.mu，别在持锁时把它拖进来。
func (m *Manager) dropJob(channelID int64, j *Job) {
	m.mu.Lock()
	if cur, ok := m.jobs[channelID]; ok && cur == j {
		delete(m.jobs, channelID)
	}
	m.mu.Unlock()
	j.kill()
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

// isExited 判断进程是否已退出。
// exited 由 cmd.Wait 的收尾协程写、由 ensure/reaper 读，必须走 j.mu。
func (j *Job) isExited() bool {
	j.mu.Lock()
	defer j.mu.Unlock()
	return j.exited
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
