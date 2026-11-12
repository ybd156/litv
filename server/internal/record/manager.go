// Package record 实现录制中心。
//
// 两种用法共用一套机制：
//   - 立即录制：start_at = 现在，duration = N 分钟
//   - 预约录制：start_at = 未来某个时间（通常来自 EPG 的节目开始时间）
//
// 实现：到点拉起一个 ffmpeg 子进程，`-c copy` 原样录制（不转码），
// 录到时长后自动结束。文件落到配置的目录（飞牛上可以指向共享目录）。
package record

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"time"

	"github.com/litv/litv/server/internal/fetch"
	"github.com/litv/litv/server/internal/ffmpeg"
	"github.com/litv/litv/server/internal/store"
)

// Options 录制参数。
type Options struct {
	FFmpeg string
	Dir    string
	// UserAgent 上游 UA（部分源站校验）
	UserAgent string
}

// Manager 管理录制任务。
type Manager struct {
	opt Options
	st  *store.Store

	mu   sync.Mutex
	runs map[int64]*exec.Cmd // recordingID -> 进程
}

// NewManager 创建。
func NewManager(st *store.Store, opt Options) *Manager {
	return &Manager{opt: opt, st: st, runs: make(map[int64]*exec.Cmd)}
}

// Available 判断能否录制。
func (m *Manager) Available() bool {
	if m == nil || m.opt.FFmpeg == "" || m.opt.Dir == "" {
		return false
	}
	if filepath.IsAbs(m.opt.FFmpeg) {
		_, err := os.Stat(m.opt.FFmpeg)
		return err == nil
	}
	_, err := exec.LookPath(m.opt.FFmpeg)
	return err == nil
}

func (m *Manager) ffmpegPath() string {
	if filepath.IsAbs(m.opt.FFmpeg) {
		return m.opt.FFmpeg
	}
	if p, err := exec.LookPath(m.opt.FFmpeg); err == nil {
		return p
	}
	return m.opt.FFmpeg
}

// Run 后台调度：每 5 秒检查一次有没有该开始的、该结束的。
//
// 为什么是 5 秒而不是 20 秒：预约录制常常是"整点开始录 1 分钟"这类短任务，
// tick 间隔一旦大于任务时长，任务会在第一次 tick 时窗口就已过去，直接被跳过。
func (m *Manager) Run(ctx context.Context) {
	if !m.Available() {
		slog.Info("录制中心未启用（ffmpeg 不可用或未配置目录）")
		return
	}
	if err := os.MkdirAll(m.opt.Dir, 0o755); err != nil {
		slog.Error("创建录制目录失败", "err", err, "dir", m.opt.Dir)
		return
	}
	slog.Info("录制中心已启动", "dir", m.opt.Dir)

	t := time.NewTicker(5 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			m.stopAll()
			return
		case <-t.C:
			m.tick(ctx)
		}
	}
}

func (m *Manager) tick(ctx context.Context) {
	jobs, err := m.st.RecordingsByStatus(ctx, "scheduled", 50)
	if err != nil {
		slog.Warn("读取录制任务失败", "err", err)
		return
	}
	now := time.Now().Unix()

	for _, j := range jobs {
		m.mu.Lock()
		_, running := m.runs[j.ID]
		m.mu.Unlock()
		if running {
			continue
		}

		// 窗口已过且从未开录 → 错过了（服务停机、上游不可用等）
		if j.EndAt <= now {
			slog.Warn("录制窗口已过，标记失败", "recording", j.ID, "start", j.StartAt, "end", j.EndAt)
			_ = m.st.UpdateRecording(ctx, j.ID, "failed", "", 0)
			continue
		}

		// 进入窗口 → 立刻开录。
		// 即使晚了也照录：start() 会按"距结束还剩多少"来定时长，
		// 保证结束时间点不变（预约录制就该在节目结束时停）。
		if j.StartAt <= now {
			m.start(j)
		}
	}
}

func (m *Manager) start(j store.Recording) {
	// 拿上游地址
	stream, err := m.st.BestStream(context.Background(), j.ChannelID)
	if err != nil {
		slog.Warn("录制失败：频道无可用线路", "recording", j.ID, "channel", j.ChannelID)
		_ = m.st.UpdateRecording(context.Background(), j.ID, "failed", "", 0)
		return
	}
	// 出站 SSRF 校验：线路地址来自导入的订阅源，不能让它把 ffmpeg 指到
	// 本机管理面或云元数据地址。（代理/局域网私网仍然放行。）
	if err := fetch.ValidateURL(stream.URL); err != nil {
		slog.Warn("录制失败：线路地址不被允许", "recording", j.ID, "err", err)
		_ = m.st.UpdateRecording(context.Background(), j.ID, "failed", "", 0)
		return
	}

	name := fmt.Sprintf("rec%d-ch%d-%s.ts", j.ID, j.ChannelID,
		time.Unix(j.StartAt, 0).Format("20060102-1504"))
	path := filepath.Join(m.opt.Dir, name)
	// 按"距结束还剩多少"定时长：晚开了也不会超时，
	// 保证预约录制在节目结束时准时停。
	duration := j.EndAt - time.Now().Unix()
	if duration <= 0 {
		duration = 5
	}

	cmd := exec.Command(m.ffmpegPath(), m.args(stream, duration, path)...)

	if err := cmd.Start(); err != nil {
		slog.Warn("启动录制进程失败", "recording", j.ID, "err", err)
		_ = m.st.UpdateRecording(context.Background(), j.ID, "failed", "", 0)
		return
	}

	m.mu.Lock()
	m.runs[j.ID] = cmd
	m.mu.Unlock()

	_ = m.st.UpdateRecording(context.Background(), j.ID, "recording", path, 0)
	slog.Info("开始录制", "recording", j.ID, "channel", j.ChannelID, "path", path)

	go func() {
		err := cmd.Wait()
		m.mu.Lock()
		delete(m.runs, j.ID)
		m.mu.Unlock()

		var size int64
		if st, e := os.Stat(path); e == nil {
			size = st.Size()
		}
		status := "finished"
		if err != nil && size < 1024 {
			status = "failed"
		}
		_ = m.st.UpdateRecording(context.Background(), j.ID, status, path, size)
		slog.Info("录制结束", "recording", j.ID, "status", status, "bytes", size)
	}()
}

// args 拼 ffmpeg 的录制参数。
//
// 单独抽出来是为了可测：这里最容易出的错不是"参数拼错导致录不了"，
// 而是"忘了带 Referer/UA，某个源站直接 403"——那种错误只有断言参数
// 才能稳定复现，不需要真的起 ffmpeg。
func (m *Manager) args(stream *store.Stream, duration int64, path string) []string {
	out := []string{
		"-hide_banner", "-loglevel", "warning",
		"-y",
	}
	// 录制和播放走同一条线路，请求头也必须一致。
	//
	// 以前这里只带 -user_agent：要求 Referer（或其它头）的源站会在录制
	// 这一步 403，用户看到的现象是"直播能看，录出来是 0 字节 / 失败"。
	ua, rest := ffmpeg.SplitUserAgent(stream.Headers())
	if ua == "" {
		ua = m.opt.UserAgent
	}
	if ua != "" {
		out = append(out, "-user_agent", ua)
	}
	// -headers 必须写在 -i 之前，否则对输入不生效。
	if h := ffmpeg.HeadersArg(rest); h != "" {
		out = append(out, "-headers", h)
	}
	return append(out,
		"-i", stream.URL,
		"-c", "copy", // 原样录制，不转码
		"-t", fmt.Sprint(duration),
		"-f", "mpegts",
		path,
	)
}

// Stop 手动停止某条录制。
func (m *Manager) Stop(id int64) {
	m.mu.Lock()
	cmd, ok := m.runs[id]
	m.mu.Unlock()
	if ok && cmd.Process != nil {
		_ = cmd.Process.Kill()
	}
}

func (m *Manager) stopAll() {
	m.mu.Lock()
	cmds := make([]*exec.Cmd, 0, len(m.runs))
	for _, c := range m.runs {
		cmds = append(cmds, c)
	}
	m.runs = map[int64]*exec.Cmd{}
	m.mu.Unlock()
	for _, c := range cmds {
		if c.Process != nil {
			_ = c.Process.Kill()
		}
	}
}
