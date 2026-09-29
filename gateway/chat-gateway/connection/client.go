package connection

import (
	"chat-gateway/config"
	"encoding/json"
	"log"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

type Client struct {
	UserID       int64
	DeviceID     string
	ConnectionID string
	Conn         *websocket.Conn
	Send         chan []byte
	LastActive   time.Time
	pingEvery    time.Duration
	readWindow   time.Duration
	mu           sync.Mutex
	closed       bool
}

type Message struct {
	Type      string          `json:"type"`
	RequestId string          `json:"requestId,omitempty"`
	Data      json.RawMessage `json:"data,omitempty"`
}

func NewClient(cfg *config.Config, userID int64, deviceID, connectionID string, conn *websocket.Conn) *Client {
	interval := time.Duration(cfg.HeartbeatInterval) * time.Second
	return &Client{
		UserID:       userID,
		DeviceID:     deviceID,
		ConnectionID: connectionID,
		Conn:         conn,
		Send:         make(chan []byte, 256),
		LastActive:   time.Now(),
		pingEvery:    interval,
		readWindow:   interval * 2,
	}
}

// shutdown 幂等地关掉发送通道：被同设备新连接顶掉的那一条，稍后还会从自己
// ReadPump 的 defer 里走一遍 Remove，那时再 close 就是 close of closed channel。
func (c *Client) shutdown() {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return
	}
	c.closed = true
	close(c.Send)
}

// enqueue 非阻塞入队。阻塞发送会让 ReadPump 停摆，这条连接连 PING 都读不到，
// 256 帧的缓冲就变成永久死锁；缓冲满说明对端已经不健康，断开让它重连后
// 用 LOAD_MESSAGES 补齐，比静默丢帧更可查证。
//
// 判 closed 和发送必须放在同一把锁里：被同设备的新连接顶掉时，另一个 goroutine 正在
// shutdown() 里 close(Send)（它持的就是这把锁）。分成"先看一眼没关、再发"就会踩中中间
// 那一毫秒 —— 实测 panic: send on closed channel，整个网关进程跟着没了，所有在线的人一起掉线
// （手机每秒重连那一阵子踩中的正是这条）。
func (c *Client) enqueue(data []byte) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return false
	}
	select {
	case c.Send <- data:
		return true
	default:
		log.Printf("Send buffer full, dropping %d bytes and closing conn for user %d device %s",
			len(data), c.UserID, c.DeviceID)
		c.Conn.Close()
		return false
	}
}

func (c *Client) ReadPump(manager *Manager) {
	defer func() {
		manager.Remove(c)
		c.Conn.Close()
	}()

	c.Conn.SetReadLimit(65536)
	c.Conn.SetReadDeadline(time.Now().Add(c.readWindow))
	c.Conn.SetPongHandler(func(string) error {
		c.mu.Lock()
		c.LastActive = time.Now()
		c.mu.Unlock()
		c.Conn.SetReadDeadline(time.Now().Add(c.readWindow))
		return nil
	})

	for {
		_, raw, err := c.Conn.ReadMessage()
		if err != nil {
			break
		}

		c.mu.Lock()
		c.LastActive = time.Now()
		c.mu.Unlock()

		var msg Message
		if err := json.Unmarshal(raw, &msg); err != nil {
			continue
		}

		switch msg.Type {
		case "PING":
			resp, _ := json.Marshal(Message{Type: "PONG"})
			c.enqueue(resp)
		default:
			manager.HandleMessage(c, &msg)
		}
	}
}

func (c *Client) WritePump() {
	ticker := time.NewTicker(c.pingEvery)
	defer func() {
		ticker.Stop()
		c.Conn.Close()
	}()

	for {
		select {
		case message, ok := <-c.Send:
			c.Conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if !ok {
				c.Conn.WriteMessage(websocket.CloseMessage, []byte{})
				return
			}
			c.Conn.WriteMessage(websocket.TextMessage, message)
		case <-ticker.C:
			c.Conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if err := c.Conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}
