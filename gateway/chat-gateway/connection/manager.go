package connection

import (
	"chat-gateway/config"
	"chat-gateway/httpclient"
	"chat-gateway/redis"
	"encoding/json"
	"log"
	"strconv"
	"strings"
	"sync"
	"time"
)

type Manager struct {
	mu      sync.RWMutex
	clients map[int64]map[string]*Client
	cfg     *config.Config
}

var manager *Manager

func InitManager(cfg *config.Config) {
	manager = &Manager{
		clients: make(map[int64]map[string]*Client),
		cfg:     cfg,
	}
}

func GetManager() *Manager {
	return manager
}

func (m *Manager) Add(client *Client) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.clients[client.UserID] == nil {
		m.clients[client.UserID] = make(map[string]*Client)
	}
	m.clients[client.UserID][client.DeviceID] = client

	redis.SetUserOnline(client.UserID, client.DeviceID, client.ConnectionID, "ONLINE")
	log.Printf("User %d connected: device=%s", client.UserID, client.DeviceID)
}

func (m *Manager) Remove(client *Client) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if devices, ok := m.clients[client.UserID]; ok {
		delete(devices, client.DeviceID)
		if len(devices) == 0 {
			delete(m.clients, client.UserID)
			redis.SetUserOffline(client.UserID)
		}
	}

	close(client.Send)
	log.Printf("User %d disconnected: device=%s", client.UserID, client.DeviceID)
}

func (m *Manager) SendToUser(userID int64, msg *Message) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	data, _ := json.Marshal(msg)

	if devices, ok := m.clients[userID]; ok {
		for _, client := range devices {
			client.enqueue(data)
		}
	}
}

func (m *Manager) HandleMessage(client *Client, msg *Message) {
	log.Printf("Received from user %d: type=%s", client.UserID, msg.Type)

	switch msg.Type {
	case "MESSAGE_SEND":
		m.handleMessageSend(client, msg)
	case "MESSAGE_DELETE":
		m.handleMessageDelete(client, msg)
	case "MESSAGE_READ":
		m.handleMessageRead(client, msg)
	case "SYNC_CONVERSATIONS":
		m.handleSyncConversations(client, msg)
	case "LOAD_MESSAGES":
		m.handleLoadMessages(client, msg)
	case "PRESENCE_SET":
		m.handlePresenceSet(client, msg)
	default:
		log.Printf("Unknown message type: %s", msg.Type)
	}
}

// ackFrame 组装回给发送方的回执帧
func ackFrame(requestId, msgType string, data map[string]interface{}) []byte {
	payload, _ := json.Marshal(data)
	frame, _ := json.Marshal(Message{Type: msgType, RequestId: requestId, Data: payload})
	return frame
}

func (m *Manager) handleMessageSend(client *Client, msg *Message) {
	var data struct {
		ConversationId string `json:"conversationId"`
		MessageType    string `json:"messageType"`
		Content        string `json:"content"`
		Extra          string `json:"extra,omitempty"`
	}
	if err := json.Unmarshal(msg.Data, &data); err != nil {
		return
	}

	saveReq := &httpclient.SendMessageRequest{
		ConversationId: data.ConversationId,
		SenderId:       client.UserID,
		MessageType:    data.MessageType,
		Content:        data.Content,
		Extra:          data.Extra,
	}

	savedMsg, err := httpclient.SaveMessage(m.cfg.MessageServiceUrl, client.UserID, saveReq)
	if err != nil || savedMsg == nil || savedMsg.MessageId == "" {
		// 落库失败还继续投递，结果是对方屏幕上有了、记录里查不到，发送端还拿到一个
		// 编造的 messageId。宁可回 FAILED 让客户端重发。
		log.Printf("Save message failed: user=%d conv=%s err=%v", client.UserID, data.ConversationId, err)
		client.enqueue(ackFrame(msg.RequestId, "MESSAGE_ACK", map[string]interface{}{
			"messageId": "",
			"status":    "FAILED",
			"error":     "SAVE_FAILED",
		}))
		return
	}

	// ACK 给发送者
	client.enqueue(ackFrame(msg.RequestId, "MESSAGE_ACK", map[string]interface{}{
		"messageId": savedMsg.MessageId,
		"status":    "SENT",
	}))

	// 更新会话最后消息
	realConvId := data.ConversationId
	if strings.HasPrefix(realConvId, "g") {
		realConvId = strings.TrimPrefix(realConvId, "g")
	}
	if err := httpclient.UpdateLastMessage(m.cfg.UserServiceUrl, client.UserID, realConvId, data.Content); err != nil {
		// 消息本身已经存下了，会话列表的最后一条消息属于可后补的派生数据
		log.Printf("Update last message failed: conv=%s err=%v", realConvId, err)
	}

	// 投递消息
	conversationId := data.ConversationId
	if isGroupConversation(conversationId) {
		// 群聊：投递给所有群成员
		m.deliverToGroup(client, conversationId, savedMsg.MessageId, data)
	} else {
		// 一对一：投递给对方
		m.deliverToPrivate(client, conversationId, savedMsg.MessageId, data)
	}
}

// deliverToPrivate 一对一消息投递
func (m *Manager) deliverToPrivate(client *Client, conversationId string, messageId string, data struct {
	ConversationId string `json:"conversationId"`
	MessageType    string `json:"messageType"`
	Content        string `json:"content"`
	Extra          string `json:"extra,omitempty"`
}) {
	convId, err := strconv.ParseInt(conversationId, 10, 64)
	if err != nil {
		log.Printf("Invalid conversation id: %s", conversationId)
		return
	}

	// 通过用户服务获取会话中的用户列表
	userIds, err := httpclient.GetConversationUsers(m.cfg.UserServiceUrl, client.UserID, convId)
	if err != nil {
		log.Printf("Get conversation users error: %v", err)
		return
	}

	for _, uid := range userIds {
		if uid != client.UserID {
			receiveData, _ := json.Marshal(map[string]interface{}{
				"messageId":      messageId,
				"conversationId": conversationId,
				"senderId":       client.UserID,
				"messageType":    data.MessageType,
				"content":        data.Content,
				"extra":          data.Extra,
				"timestamp":      time.Now().UnixMilli(),
			})
			m.SendToUser(uid, &Message{
				Type: "MESSAGE_RECEIVE",
				Data: receiveData,
			})
		}
	}
}

// deliverToGroup 群聊消息投递
func (m *Manager) deliverToGroup(client *Client, conversationId string, messageId string, data struct {
	ConversationId string `json:"conversationId"`
	MessageType    string `json:"messageType"`
	Content        string `json:"content"`
	Extra          string `json:"extra,omitempty"`
}) {
	// conversationId 格式: "g{groupId}"
	groupIdStr := strings.TrimPrefix(conversationId, "g")
	groupId, err := strconv.ParseInt(groupIdStr, 10, 64)
	if err != nil {
		log.Printf("Invalid group conversation id: %s", conversationId)
		return
	}

	// 获取群成员
	members, err := httpclient.GetGroupMembers(m.cfg.GroupServiceUrl, client.UserID, groupId)
	if err != nil {
		log.Printf("Get group members error: %v", err)
		return
	}

	receiveData, _ := json.Marshal(map[string]interface{}{
		"messageId":      messageId,
		"conversationId": conversationId,
		"senderId":       client.UserID,
		"messageType":    data.MessageType,
		"content":        data.Content,
		"extra":          data.Extra,
		"timestamp":      time.Now().UnixMilli(),
	})

	for _, member := range members {
		if member.UserId != client.UserID {
			m.SendToUser(member.UserId, &Message{
				Type: "MESSAGE_RECEIVE",
				Data: receiveData,
			})
		}
	}
}

func (m *Manager) handleMessageDelete(client *Client, msg *Message) {
	var data struct {
		ConversationId string `json:"conversationId"`
		MessageId      string `json:"messageId"`
	}
	if err := json.Unmarshal(msg.Data, &data); err != nil {
		return
	}

	reason, err := httpclient.DeleteMessage(m.cfg.MessageServiceUrl, client.UserID, data.ConversationId, data.MessageId)
	if err != nil {
		log.Printf("Delete message error: user=%d conv=%s err=%v", client.UserID, data.ConversationId, err)
		// 必须回 ACK：前端不等确认就把气泡标成"已撤回"的话，服务端拒绝时界面会一直说谎
		client.enqueue(ackFrame(msg.RequestId, "MESSAGE_DELETE_ACK", map[string]interface{}{
			"status": "FAILED",
			"error":  reason,
		}))
		return
	}

	m.notifyDeleted(client, data.ConversationId, data.MessageId)

	client.enqueue(ackFrame(msg.RequestId, "MESSAGE_DELETE_ACK", map[string]interface{}{"status": "ok"}))
}

// notifyDeleted 通知会话里其他成员这条已撤回。
// 群聊会话 ID 带 g 前缀，解析不成数字 —— 早先只用 ParseInt，所以群里撤回只有发送者自己看到。
func (m *Manager) notifyDeleted(client *Client, conversationId string, messageId string) {
	var userIds []int64

	if isGroupConversation(conversationId) {
		groupId, err := strconv.ParseInt(strings.TrimPrefix(conversationId, "g"), 10, 64)
		if err != nil {
			log.Printf("Invalid group conversation id on delete: %s", conversationId)
			return
		}
		members, err := httpclient.GetGroupMembers(m.cfg.GroupServiceUrl, client.UserID, groupId)
		if err != nil {
			log.Printf("Get group members for delete notify error: %v", err)
			return
		}
		for _, mem := range members {
			userIds = append(userIds, mem.UserId)
		}
	} else {
		convId, err := strconv.ParseInt(conversationId, 10, 64)
		if err != nil {
			log.Printf("Invalid conversation id on delete: %s", conversationId)
			return
		}
		got, err := httpclient.GetConversationUsers(m.cfg.UserServiceUrl, client.UserID, convId)
		if err != nil {
			log.Printf("Get conversation users for delete notify error: %v", err)
			return
		}
		userIds = got
	}

	deleteData, _ := json.Marshal(map[string]interface{}{
		"conversationId": conversationId,
		"messageId":      messageId,
		"senderId":       client.UserID,
	})
	for _, uid := range userIds {
		if uid != client.UserID {
			m.SendToUser(uid, &Message{Type: "MESSAGE_DELETED", Data: deleteData})
		}
	}
}

func (m *Manager) handleMessageRead(client *Client, msg *Message) {
	var data struct {
		ConversationId   string `json:"conversationId"`
		LastReadMessageId string `json:"lastReadMessageId"`
	}
	if err := json.Unmarshal(msg.Data, &data); err != nil {
		return
	}

	err := httpclient.MarkMessageRead(m.cfg.MessageServiceUrl, client.UserID, data.ConversationId, data.LastReadMessageId)
	if err != nil {
		log.Printf("Mark read error: %v", err)
	}

	// 通知对方已读
	convId, err := strconv.ParseInt(data.ConversationId, 10, 64)
	if err == nil {
		userIds, err := httpclient.GetConversationUsers(m.cfg.UserServiceUrl, client.UserID, convId)
		if err == nil {
			for _, uid := range userIds {
				if uid != client.UserID {
					readData, _ := json.Marshal(map[string]interface{}{
						"conversationId":   data.ConversationId,
						"userId":           client.UserID,
						"lastReadMessageId": data.LastReadMessageId,
						"timestamp":        time.Now().UnixMilli(),
					})
					m.SendToUser(uid, &Message{
						Type: "MESSAGE_READ",
						Data: readData,
					})
				}
			}
		}
	}
}

func (m *Manager) handleSyncConversations(client *Client, msg *Message) {
	convs, err := httpclient.GetConversationList(m.cfg.UserServiceUrl, client.UserID)
	if err != nil {
		log.Printf("Get conversations error: %v", err)
		convs = []interface{}{}
	}

	convList, _ := json.Marshal(convs)
	resp, _ := json.Marshal(Message{
		Type:      "SYNC_CONVERSATIONS",
		RequestId: msg.RequestId,
		Data:      convList,
	})
	client.enqueue(resp)
}

// handlePresenceSet 改的是"我"在这台设备上的状态，只重写自己那条哈希字段。
// 忙碌不挡投递：忙碌是给别人看的标签，不是免打扰。离线由前端断连来表达——
// 连接一没了 manager.Remove 就把整个键删掉，别人读 /user/online 就查不到这个人，
// 所以这里不收 "OFFLINE"，否则会出现"标着离线但还收得到消息"的假状态。
func (m *Manager) handlePresenceSet(client *Client, msg *Message) {
	var data struct {
		Status string `json:"status"`
	}
	if err := json.Unmarshal(msg.Data, &data); err != nil {
		return
	}
	if data.Status != "ONLINE" && data.Status != "BUSY" {
		log.Printf("Presence rejected: user=%d status=%q", client.UserID, data.Status)
		client.enqueue(ackFrame(msg.RequestId, "PRESENCE_ACK", map[string]interface{}{
			"status": "FAILED",
			"error":  "BAD_STATUS",
		}))
		return
	}

	redis.SetUserOnline(client.UserID, client.DeviceID, client.ConnectionID, data.Status)
	client.enqueue(ackFrame(msg.RequestId, "PRESENCE_ACK", map[string]interface{}{"status": data.Status}))
}

func (m *Manager) handleLoadMessages(client *Client, msg *Message) {
	var data struct {
		ConversationId string `json:"conversationId"`
		Limit          int    `json:"limit"`
	}
	json.Unmarshal(msg.Data, &data)

	limit := data.Limit
	if limit <= 0 {
		limit = 50
	}

	msgs, err := httpclient.GetMessages(m.cfg.MessageServiceUrl, client.UserID, data.ConversationId, limit)
	if err != nil {
		log.Printf("Get messages error: %v", err)
		msgs = []httpclient.MessageResponse{}
	}

	msgList, _ := json.Marshal(msgs)
	resp, _ := json.Marshal(Message{
		Type:      "LOAD_MESSAGES",
		RequestId: msg.RequestId,
		Data:      msgList,
	})
	client.enqueue(resp)
}

// splitConversationId 解析一对一会话ID
func splitConversationId(convId string) []int64 {
	id, err := strconv.ParseInt(convId, 10, 64)
	if err != nil {
		return []int64{}
	}
	minId := id / 100000
	maxId := id % 100000
	return []int64{minId, maxId}
}

// isGroupConversation 判断是否为群聊会话
func isGroupConversation(convId string) bool {
	return strings.HasPrefix(convId, "g")
}
