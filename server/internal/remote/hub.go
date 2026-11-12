// Package remote 实现「手机当遥控器」的服务端中转。
//
// 架构：电视端用 WebSocket 连到服务端待命，手机端发 HTTP 指令，
// 服务端把指令转发给指定电视。这样：
//   - 手机和电视不需要在同一网段（都连服务端即可）
//   - 电视不用开端口、不用做局域网发现
//   - 服务端天然是鉴权点（双方都要 device token）
package remote

import (
	"encoding/json"
	"log/slog"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// Command 是一条遥控指令。
type Command struct {
	// 指令类型：key（按键）/ play（直接播某台）/ text（文字输入）
	Type string `json:"type"`
	// 按键名：up/down/left/right/ok/back/menu/home/volume_up/volume_down/数字 0-9
	Key string `json:"key,omitempty"`
	// play 类型时的频道 ID
	ChannelID int64 `json:"channel_id,omitempty"`
	// 发起方设备名，电视端可以用来提示"来自 张三的手机"
	From string `json:"from,omitempty"`
}

// Conn 是一台电视的连接。
//
// done 关闭表示这条连接已经收尾。修复前是直接 close(send)：Send 在拿到
// Conn 之后、真正写入之前，连接可能刚好被 close，于是往已关闭的 channel
// 发送 → panic。现在 send 永不关闭，收尾只关 done，所有发送都 select 到
// done 退出。
type Conn struct {
	DeviceID int64
	ws       *websocket.Conn
	send     chan []byte
	done     chan struct{}
	hub      *Hub
	once     sync.Once
}

// Hub 管理所有在线电视。
type Hub struct {
	mu    sync.RWMutex
	conns map[int64]*Conn
}

// NewHub 创建。
func NewHub() *Hub { return &Hub{conns: make(map[int64]*Conn)} }

// Register 把一台电视登记为在线，并启动收发协程。
// 同一设备重复连接时，踢掉旧连接（重装/重连场景）。
func (h *Hub) Register(deviceID int64, ws *websocket.Conn) *Conn {
	c := &Conn{
		DeviceID: deviceID,
		ws:       ws,
		send:     make(chan []byte, 16),
		done:     make(chan struct{}),
		hub:      h,
	}

	h.mu.Lock()
	if old, ok := h.conns[deviceID]; ok {
		old.close()
	}
	h.conns[deviceID] = c
	h.mu.Unlock()

	go c.writePump()
	go c.readPump()
	slog.Info("遥控通道已连接", "device", deviceID)
	return c
}

// Send 给指定电视发一条指令。返回 false 表示该电视不在线。
func (h *Hub) Send(deviceID int64, cmd Command) bool {
	b, err := json.Marshal(cmd)
	if err != nil {
		return false
	}
	h.mu.RLock()
	c, ok := h.conns[deviceID]
	h.mu.RUnlock()
	if !ok {
		return false
	}
	// 已收尾的连接直接失败。先做一次非阻塞判断，保证 close 之后
	// Send 的结果是确定的（select 多路就绪时是随机选）。
	select {
	case <-c.done:
		return false
	default:
	}
	select {
	case c.send <- b:
		return true
	case <-c.done:
		return false
	case <-time.After(2 * time.Second):
		// 电视端卡住了，别把服务端拖死
		slog.Warn("遥控指令发送超时", "device", deviceID)
		return false
	}
}

// Online 返回在线设备 ID 列表。
func (h *Hub) Online() []int64 {
	h.mu.RLock()
	defer h.mu.RUnlock()
	out := make([]int64, 0, len(h.conns))
	for id := range h.conns {
		out = append(out, id)
	}
	return out
}

// IsOnline 判断某设备是否在线。
func (h *Hub) IsOnline(deviceID int64) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	_, ok := h.conns[deviceID]
	return ok
}

func (c *Conn) close() {
	c.once.Do(func() {
		close(c.done)
		// 测试里会构造没有 ws 的 Conn；生产路径永远有。
		if c.ws != nil {
			_ = c.ws.Close()
		}
	})
}

// writePump 负责把队列里的指令写出去，并定时发 ping 保活。
func (c *Conn) writePump() {
	ticker := time.NewTicker(30 * time.Second)
	defer func() {
		ticker.Stop()
		c.hub.remove(c)
		c.close()
	}()

	for {
		select {
		case msg := <-c.send:
			_ = c.ws.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if err := c.ws.WriteMessage(websocket.TextMessage, msg); err != nil {
				return
			}
		case <-c.done:
			_ = c.ws.SetWriteDeadline(time.Now().Add(time.Second))
			_ = c.ws.WriteMessage(websocket.CloseMessage, []byte{})
			return
		case <-ticker.C:
			_ = c.ws.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if err := c.ws.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}

// readPump 读电视端的上行消息（目前只用于保活和状态回报）。
func (c *Conn) readPump() {
	defer func() {
		c.hub.remove(c)
		c.close()
	}()
	c.ws.SetReadLimit(4096)
	_ = c.ws.SetReadDeadline(time.Now().Add(90 * time.Second))
	c.ws.SetPongHandler(func(string) error {
		return c.ws.SetReadDeadline(time.Now().Add(90 * time.Second))
	})
	for {
		_, _, err := c.ws.ReadMessage()
		if err != nil {
			return
		}
		_ = c.ws.SetReadDeadline(time.Now().Add(90 * time.Second))
	}
}

func (h *Hub) remove(c *Conn) {
	h.mu.Lock()
	if cur, ok := h.conns[c.DeviceID]; ok && cur == c {
		delete(h.conns, c.DeviceID)
	}
	h.mu.Unlock()
}
