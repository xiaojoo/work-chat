package connection

import (
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
	mu           sync.Mutex
}

type Message struct {
	Type      string          `json:"type"`
	RequestId string          `json:"requestId,omitempty"`
	Data      json.RawMessage `json:"data,omitempty"`
}

func NewClient(userID int64, deviceID, connectionID string, conn *websocket.Conn) *Client {
	return &Client{
		UserID:       userID,
		DeviceID:     deviceID,
		ConnectionID: connectionID,
		Conn:         conn,
		Send:         make(chan []byte, 256),
		LastActive:   time.Now(),
	}
}

// enqueue 非阻塞入队。阻塞发送会让 ReadPump 停摆，这条连接连 PING 都读不到，
// 256 帧的缓冲就变成永久死锁；缓冲满说明对端已经不健康，断开让它重连后
// 用 LOAD_MESSAGES 补齐，比静默丢帧更可查证。
func (c *Client) enqueue(data []byte) bool {
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
	c.Conn.SetReadDeadline(time.Now().Add(60 * time.Second))
	c.Conn.SetPongHandler(func(string) error {
		c.mu.Lock()
		c.LastActive = time.Now()
		c.mu.Unlock()
		c.Conn.SetReadDeadline(time.Now().Add(60 * time.Second))
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
	ticker := time.NewTicker(30 * time.Second)
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
