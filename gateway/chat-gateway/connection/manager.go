package connection

import (
	"chat-gateway/call"
	"chat-gateway/config"
	"chat-gateway/control"
	"chat-gateway/httpclient"
	"chat-gateway/redis"
	"chat-gateway/safego"
	"encoding/json"
	"fmt"
	"log"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

type Manager struct {
	mu        sync.RWMutex
	clients   map[int64]map[string]*Client
	cfg       *config.Config
	calls     *call.Manager
	controls  *control.Manager
	gatewayID string
}

// controlProvider 是 V3.0 那根可拔插的缝的唯一注入点。一期只有 none：
// 它不给出任何"怎么连过去"的信息，但发起/同意/票据/停止这一整条控制链路照跑。
// 名字写错不掐人 —— 按没接通道处理，并打一行说明为什么 handoff 是空的
func controlProvider(name string) control.Provider {
	switch name {
	case "", "none":
		return control.NullProvider{}
	}
	log.Printf("CONTROL_PROVIDER=%q 这里没有这个实现，按没接远程控制通道处理", name)
	return control.NullProvider{}
}

var manager *Manager

func InitManager(cfg *config.Config) {
	manager = &Manager{
		clients:   make(map[int64]map[string]*Client),
		cfg:       cfg,
		calls:     call.NewManager(cfg.LivekitUrl, cfg.LivekitApiKey, cfg.LivekitApiSecret),
		controls:  control.NewManager([]byte(cfg.ControlSecret), controlProvider(cfg.ControlProvider)),
		gatewayID: cfg.GatewayId,
	}
}

func GetManager() *Manager {
	return manager
}

func (m *Manager) Add(client *Client) {
	m.mu.Lock()
	if m.clients[client.UserID] == nil {
		m.clients[client.UserID] = make(map[string]*Client)
	}
	// 同一 (user, device) 上已有连接就先顶掉再登记。原先是直接覆盖：旧连接的
	// goroutine 还在跑、Send 通道永不关、Redis 那条字段还指着它，于是同一个设备
	// 槽位上留下两个都以为自己在收消息的僵尸。
	replaced := m.clients[client.UserID][client.DeviceID]
	m.clients[client.UserID][client.DeviceID] = client
	m.mu.Unlock()

	if replaced != nil {
		replaced.shutdown()
		replaced.Conn.Close()
		log.Printf("User %d device %s reconnected, previous connection closed", client.UserID, client.DeviceID)
	}

	redis.SetUserOnline(client.UserID, client.DeviceID, client.ConnectionID, "ONLINE")
	log.Printf("User %d connected: device=%s", client.UserID, client.DeviceID)
}

func (m *Manager) Remove(client *Client) {
	m.mu.Lock()
	replaced := false
	goneOffline := false
	if devices, ok := m.clients[client.UserID]; ok {
		// 只摘掉仍然指向自己这一条：被顶掉的旧连接走到这里时新连接已经占着这个 key，
		// 无条件 delete 会把刚上线的那一端从表里删没。
		if devices[client.DeviceID] == client {
			delete(devices, client.DeviceID)
			if len(devices) == 0 {
				delete(m.clients, client.UserID)
				goneOffline = true
			}
		} else {
			replaced = true
		}
	}
	m.mu.Unlock()

	client.shutdown()

	if replaced {
		log.Printf("User %d disconnected: device=%s (已被新连接取代)", client.UserID, client.DeviceID)
		return
	}
	if goneOffline {
		redis.SetUserOffline(client.UserID)
		// 这个人的网关连接全没了，还挂在通话里就把这通收掉。浏览器直接关掉是不会发
		// CALL_HANGUP 的，状态留在表里这个人就永远"通话中"，后面谁都打不进来。
		// 同设备顶号不算断（goneOffline 为假）：LiveKit 那条是另一条连接，不该被网关的重连带走。
		if s := m.calls.ByUser(client.UserID); s != nil {
			if _, ok := m.calls.End(s.Room, client.UserID); ok {
				other := s.CallerId
				if other == client.UserID {
					other = s.CalleeId
				}
				m.push(other, "CALL_HANGUP", map[string]interface{}{
					"room": s.Room, "peerId": client.UserID, "reason": "DISCONNECTED",
				})
				safego.Run("delete-room-on-disconnect", func() { m.calls.DeleteRoom(s.Room) })
				m.endCall(s.Room, client.UserID, "DISCONNECTED", s.ConvId, otherOf(s, client.UserID))
				log.Printf("Call ended by disconnect: room=%s by=%d", s.Room, client.UserID)
			}
		}
		// 远程控制更是不能留：人一走，那一屏要么已经在别人手里、要么马上就要断。
		// 网关这边收的是信令这一侧（票据立刻作废 + 通知对面），
		// 真正掐掉像素是 Provider.Release 的事 —— 空实现下它什么都不做
		if cs := m.controls.ByUser(client.UserID); cs != nil {
			if ex := m.controls.ForceStop(cs.ID); ex != nil {
				m.endControl(ex, 0, "远程控制断开了", "远程控制请求断了")
				log.Printf("Control ended by disconnect: id=%s by=%d", ex.ID, client.UserID)
			}
		}
	}
	log.Printf("User %d disconnected: device=%s", client.UserID, client.DeviceID)
}

// SendToUser 一帧投给某个人的所有设备：本实例上的直接入队，别的实例走 fanout 总线。
func (m *Manager) SendToUser(userID int64, msg *Message) {
	m.SendToUsers([]int64{userID}, msg)
}

// fanout 是实例之间传的信封。Frame 已经是给客户端的最终帧，收到的实例只管往自己
// 手里的设备入队，不再解释业务含义 —— 加新帧类型时这里不用跟着改。
type fanout struct {
	Gw    string  `json:"gw"`
	Uids  []int64 `json:"uids"`
	Frame []byte  `json:"frame"`
}

// SendToUsers 群聊一次投一批：本地该投的投完，把这批人整个广播出去。
// 广播里带上是谁发的，收端跳过自己那条 —— 否则发送方本地入过一次、
// 又从总线再入一次，一条消息在屏幕上会出现两遍。
// 一个人两端分挂两个实例时也正好各收一次。
func (m *Manager) SendToUsers(userIDs []int64, msg *Message) {
	if len(userIDs) == 0 {
		return
	}
	data, _ := json.Marshal(msg)

	m.mu.RLock()
	for _, uid := range userIDs {
		for _, client := range m.clients[uid] {
			client.enqueue(data)
		}
	}
	m.mu.RUnlock()

	env, err := json.Marshal(fanout{Gw: m.gatewayID, Uids: userIDs, Frame: data})
	if err != nil {
		log.Printf("fanout encode failed: users=%d err=%v", len(userIDs), err)
		return
	}
	if err := redis.PublishFanout(env); err != nil {
		// 本地那一份已经投出去了，广播失败只能喊出来：
		// 静默失败就退回到"发送方以为对方收到了"
		log.Printf("fanout publish failed: users=%d err=%v", len(userIDs), err)
	}
}

// ServeFanout 收别的实例投来的帧并投给自己手里的设备。
func (m *Manager) ServeFanout() {
	ps := redis.SubscribeFanout()
	ch := ps.Channel()
	log.Printf("fanout subscribed: channel=%s id=%s", redis.FanoutChannel, m.gatewayID)
	for pm := range ch {
		var f fanout
		if err := json.Unmarshal([]byte(pm.Payload), &f); err != nil {
			log.Printf("fanout payload bad: %v", err)
			continue
		}
		if f.Gw == m.gatewayID {
			continue
		}
		m.deliverLocal(f.Uids, f.Frame)
	}
}

func (m *Manager) deliverLocal(userIDs []int64, data []byte) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	for _, uid := range userIDs {
		for _, client := range m.clients[uid] {
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
	case "MESSAGE_DELIVERED":
		m.handleMessageDelivered(client, msg)
	case "SYNC_CONVERSATIONS":
		m.handleSyncConversations(client, msg)
	case "LOAD_MESSAGES":
		m.handleLoadMessages(client, msg)
	case "SYNC_MISSING":
		m.handleSyncMissing(client, msg)
	case "SEARCH_MESSAGES":
		m.handleSearchMessages(client, msg)
	case "LOAD_OLDER":
		m.handleLoadOlder(client, msg)
	case "SEARCH_GLOBAL":
		m.handleSearchGlobal(client, msg)
	case "SEARCH_SUMMARY":
		m.handleSearchSummary(client, msg)
	case "PRESENCE_SET":
		m.handlePresenceSet(client, msg)
	case "CALL_INVITE":
		m.handleCallInvite(client, msg)
	case "CALL_ACCEPT":
		m.handleCallAccept(client, msg)
	case "CALL_REJECT":
		m.handleCallReject(client, msg)
	case "CALL_HANGUP":
		m.handleCallHangup(client, msg)
	case "CALL_SHARE":
		m.handleCallShare(client, msg)
	case "CONTROL_REQUEST":
		m.handleControlRequest(client, msg)
	case "CONTROL_ACCEPT":
		m.handleControlAccept(client, msg)
	case "CONTROL_REJECT":
		m.handleControlReject(client, msg)
	case "CONTROL_STOP":
		m.handleControlStop(client, msg)
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

// sendPayload 是 MESSAGE_SEND 的 data 部分
type sendPayload struct {
	ConversationId string `json:"conversationId"`
	MessageType    string `json:"messageType"`
	Content        string `json:"content"`
	Extra          string `json:"extra,omitempty"`
}

func (m *Manager) handleMessageSend(client *Client, msg *Message) {
	var data sendPayload
	if err := json.Unmarshal(msg.Data, &data); err != nil {
		return
	}

	// 幂等窗口。requestId 是客户端本来就为一条消息生成并沿用到重试的，
	// 所以弱网重试 / 连点两次不会在库里多出第二条（Web 端那条 3 秒自动重试就是这种）。
	idemKey := ""
	if msg.RequestId != "" {
		idemKey = fmt.Sprintf("%d:%s", client.UserID, msg.RequestId)
		if !redis.IdemClaim(idemKey, 10*time.Minute) {
			prev := redis.IdemPeek(idemKey)
			if prev != "" && prev != "PENDING" {
				log.Printf("duplicate MESSAGE_SEND absorbed: user=%d reqId=%s messageId=%s",
					client.UserID, msg.RequestId, prev)
				client.enqueue(ackFrame(msg.RequestId, "MESSAGE_ACK", map[string]interface{}{
					"messageId": prev, "status": "SENT", "duplicate": true,
				}))
				return
			}
			// 上一条同 requestId 的还在处理中：宁可让客户端重试，也不要回一个假的 SENT
			client.enqueue(ackFrame(msg.RequestId, "MESSAGE_ACK", map[string]interface{}{
				"messageId": "", "status": "FAILED", "error": "DUP_IN_FLIGHT",
			}))
			return
		}
	}

	// 群聊在落库之前先取成员：禁言必须挡在存消息之前，否则会出现"服务端存下了、
	// 对方永远收不到、发送端还拿到 SENT"。这一份成员列表投递时复用，不多打一次。
	var members []httpclient.GroupMemberResponse
	if isGroupConversation(data.ConversationId) {
		groupId, err := strconv.ParseInt(strings.TrimPrefix(data.ConversationId, "g"), 10, 64)
		if err != nil {
			log.Printf("Invalid group conversation id: %s", data.ConversationId)
			return
		}
		members, err = httpclient.GetGroupMembers(m.cfg.GroupServiceUrl, client.UserID, groupId)
		if err != nil {
			log.Printf("Get group members error: conv=%s err=%v", data.ConversationId, err)
			client.enqueue(ackFrame(msg.RequestId, "MESSAGE_ACK", map[string]interface{}{
				"messageId": "",
				"status":    "FAILED",
				"error":     "MEMBER_LOOKUP_FAILED",
			}))
			return
		}
		for _, mem := range members {
			if mem.UserId == client.UserID && mem.Muted {
				log.Printf("Muted send rejected: user=%d conv=%s", client.UserID, data.ConversationId)
				client.enqueue(ackFrame(msg.RequestId, "MESSAGE_ACK", map[string]interface{}{
					"messageId": "",
					"status":    "FAILED",
					"error":     "MUTED",
				}))
				return
			}
		}
	}

	saveReq := &httpclient.SendMessageRequest{
		ConversationId: data.ConversationId,
		SenderId:       client.UserID,
		MessageType:    data.MessageType,
		Content:        data.Content,
		Extra:          data.Extra,
		// 带下去做持久去重：Redis 那份幂等只保 10 分钟，隔夜重发的要靠 message_request 表
		RequestId: msg.RequestId,
	}

	savedMsg, err := httpclient.SaveMessage(m.cfg.MessageServiceUrl, client.UserID, saveReq)
	if err != nil || savedMsg == nil || savedMsg.MessageId == "" {
		// 落库失败还继续投递，结果是对方屏幕上有了、记录里查不到，发送端还拿到一个
		// 编造的 messageId。宁可回 FAILED 让客户端重发。
		log.Printf("Save message failed: user=%d conv=%s err=%v", client.UserID, data.ConversationId, err)
		if idemKey != "" {
			// 占位必须撤掉，否则这次失败会占住 10 分钟窗口，让合法重试一直撞 DUP_IN_FLIGHT
			redis.IdemRelease(idemKey)
		}
		client.enqueue(ackFrame(msg.RequestId, "MESSAGE_ACK", map[string]interface{}{
			"messageId": "",
			"status":    "FAILED",
			"error":     "SAVE_FAILED",
		}))
		return
	}
	if idemKey != "" {
		redis.IdemFinish(idemKey, savedMsg.MessageId, 10*time.Minute)
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
		m.deliverToGroup(client, conversationId, savedMsg.MessageId, members, data)
	} else {
		// 一对一：投递给对方
		m.deliverToPrivate(client, conversationId, savedMsg.MessageId, data)
	}
}

// receiveFrame 组装投递给接收方的 MESSAGE_RECEIVE 帧
func receiveFrame(conversationId, messageId string, senderId int64, data sendPayload) *Message {
	payload, _ := json.Marshal(map[string]interface{}{
		"messageId":      messageId,
		"conversationId": conversationId,
		"senderId":       senderId,
		"messageType":    data.MessageType,
		"content":        data.Content,
		"extra":          data.Extra,
		"timestamp":      time.Now().UnixMilli(),
	})
	return &Message{Type: "MESSAGE_RECEIVE", Data: payload}
}

// deliverToPrivate 一对一消息投递
func (m *Manager) deliverToPrivate(client *Client, conversationId string, messageId string, data sendPayload) {
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
			m.SendToUser(uid, receiveFrame(conversationId, messageId, client.UserID, data))
		}
	}
}

// deliverToGroup 群聊消息投递；members 由调用方在禁言检查时取好，这里不再拉一次
func (m *Manager) deliverToGroup(client *Client, conversationId string, messageId string, members []httpclient.GroupMemberResponse, data sendPayload) {
	frame := receiveFrame(conversationId, messageId, client.UserID, data)
	// 一批人一个信封：500 人群里每人发一条 fanout 的话，一条消息要在总线上跑 500 次
	ids := make([]int64, 0, len(members))
	for _, member := range members {
		if member.UserId != client.UserID {
			ids = append(ids, member.UserId)
		}
	}
	m.SendToUsers(ids, frame)
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
	ids := make([]int64, 0, len(userIds))
	for _, uid := range userIds {
		if uid != client.UserID {
			ids = append(ids, uid)
		}
	}
	m.SendToUsers(ids, &Message{Type: "MESSAGE_DELETED", Data: deleteData})
}

// handleMessageDelivered 收件端确认"这些消息在我设备上落地了"。
// 落库之后转给这条会话里除报告人之外的所有人 —— 转发对象是问用户服务/群服务要的，
// 不接受客户端自报"我替谁发的回执"，否则任何人都能把"已送达"推到别人界面上。
func (m *Manager) handleMessageDelivered(client *Client, msg *Message) {
	var data struct {
		ConversationId string   `json:"conversationId"`
		MessageIds     []string `json:"messageIds"`
	}
	if err := json.Unmarshal(msg.Data, &data); err != nil || len(data.MessageIds) == 0 {
		return
	}
	if err := httpclient.MarkDelivered(m.cfg.MessageServiceUrl, client.UserID,
			data.ConversationId, data.MessageIds); err != nil {
		log.Printf("mark delivered failed: user=%d conv=%s err=%v",
			client.UserID, data.ConversationId, err)
	}

	userIds, err := m.conversationUserIds(client.UserID, data.ConversationId)
	if err != nil {
		log.Printf("delivered fanout skipped: conv=%s err=%v", data.ConversationId, err)
		return
	}

	peers := make([]int64, 0, len(userIds))
	for _, uid := range userIds {
		if uid != client.UserID {
			peers = append(peers, uid)
		}
	}
	payload, _ := json.Marshal(map[string]interface{}{
		"conversationId": data.ConversationId,
		"messageIds":     data.MessageIds,
		"userId":         client.UserID,
		"timestamp":      time.Now().UnixMilli(),
	})
	m.SendToUsers(peers, &Message{Type: "MESSAGE_DELIVERED", Data: payload})
}

// conversationUserIds 问用户服务/群服务要这条会话的成员 id。
// 一律不接受客户端自报"我和谁在这条会话里"：谁替别人发送达回执、谁补发谁的会话，
// 都得以令牌里的身份去问后端，不然任何人都能把别人的消息读走。
func (m *Manager) conversationUserIds(actingUserId int64, convId string) ([]int64, error) {
	if isGroupConversation(convId) {
		groupId, err := strconv.ParseInt(strings.TrimPrefix(convId, "g"), 10, 64)
		if err != nil {
			return nil, err
		}
		members, err := httpclient.GetGroupMembers(m.cfg.GroupServiceUrl, actingUserId, groupId)
		if err != nil {
			return nil, err
		}
		ids := make([]int64, 0, len(members))
		for _, mem := range members {
			ids = append(ids, mem.UserId)
		}
		return ids, nil
	}
	convNum, err := strconv.ParseInt(convId, 10, 64)
	if err != nil {
		return nil, err
	}
	return httpclient.GetConversationUsers(m.cfg.UserServiceUrl, actingUserId, convNum)
}

// isMemberOf 这个人在这条会话里吗。成员表在用户服务/群服务那边，只能去问，
// 不能信客户端自报——补发、检索、归纳三个读口都走这一个判断。
func (m *Manager) isMemberOf(userID int64, convId string) (bool, error) {
	ids, err := m.conversationUserIds(userID, convId)
	if err != nil {
		return false, err
	}
	for _, id := range ids {
		if id == userID {
			return true, nil
		}
	}
	return false, nil
}

// handleSyncMissing 断线重连后的补发：客户端交出"这条会话我最后看到的是哪一条"，
// 网关先确认它确实是这条会话的成员，再去消息服务取游标之后的条目，只回给它自己。
// 这是 LOAD_MESSAGES 缺的那一半：老路只给"今天这 50 条"，断线期间别人推给我的、
// 我压根没连上的那一截，从来没有人负责过。
func (m *Manager) handleSyncMissing(client *Client, msg *Message) {
	var data struct {
		ConversationId string `json:"conversationId"`
		AfterId        string `json:"afterId"`
		CursorDate     string `json:"cursorDate"`
		Limit          int    `json:"limit"`
	}
	if err := json.Unmarshal(msg.Data, &data); err != nil || data.ConversationId == "" {
		return
	}

	member, err := m.isMemberOf(client.UserID, data.ConversationId)
	if err != nil {
		log.Printf("sync missing members failed: conv=%s err=%v", data.ConversationId, err)
		return
	}
	if !member {
		log.Printf("sync missing denied: user=%d not a member of conv=%s", client.UserID, data.ConversationId)
		return
	}

	limit := data.Limit
	if limit <= 0 || limit > 200 {
		limit = 100
	}
	// 没有游标 = 这条会话一条都没看过，服务端从补发窗口头整体给；
	// 日期必填，因为 messages 的分区键里有 message_date，光一个 timeuuid 定不了去哪个分区找
	cursorDate := data.CursorDate
	if cursorDate == "" {
		cursorDate = time.Now().AddDate(0, 0, -8).Format("2006-01-02")
	}

	msgs, err := httpclient.GetMessagesAfter(m.cfg.MessageServiceUrl, client.UserID,
		data.ConversationId, data.AfterId, cursorDate, limit)
	if err != nil {
		log.Printf("sync missing read failed: conv=%s err=%v", data.ConversationId, err)
		return
	}
	list, _ := json.Marshal(msgs)
	resp, _ := json.Marshal(Message{
		Type:      "MESSAGE_CATCHUP",
		RequestId: msg.RequestId,
		Data:      list,
	})
	client.enqueue(resp)
}

/* 往前翻。原来网页端只有"点开会话拉最近 50 条"这一条路，更早的消息在界面上根本到不了
   （检索命中它们也只能提示"不在已加载的窗口里"）。这里补一帧 LOAD_OLDER。

   成员资格必须在网关判：/history 那条 REST 只要有效令牌就能读，Java 那边的注释自己点了这个缺口 ——
   翻页只是把"读别人的聊天记录"换成一次一次问，性质没变，别在检索上补了、在这儿漏着。

   游标 (messageId, messageDate) 两个都得带：messages 的分区键含 message_date。
   形状在这里就验掉：服务端是 UUID.fromString / LocalDate.parse，喂脏东西会回 500，
   而 500 到了界面上就成了一句没人看得懂的"翻页失败"。 */
var timeuuidRe = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)
var dayRe = regexp.MustCompile(`^\d{4}-\d{2}-\d{2}$`)

func (m *Manager) handleLoadOlder(client *Client, msg *Message) {
	var data struct {
		ConversationId  string `json:"conversationId"`
		BeforeMessageId string `json:"beforeMessageId"`
		MessageDate     string `json:"messageDate"`
		Limit           int    `json:"limit"`
	}
	if err := json.Unmarshal(msg.Data, &data); err != nil || data.ConversationId == "" {
		return
	}
	fail := func(why string) {
		client.enqueue(ackFrame(msg.RequestId, "LOAD_OLDER_RESULT", map[string]interface{}{
			"conversationId": data.ConversationId, "error": why,
		}))
	}
	limit := data.Limit
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	if !timeuuidRe.MatchString(data.BeforeMessageId) || !dayRe.MatchString(data.MessageDate) {
		fail("翻页的游标不对（要一个消息 id 加一个日期），往前翻不了")
		return
	}
	member, err := m.isMemberOf(client.UserID, data.ConversationId)
	if err != nil {
		log.Printf("load older members failed: conv=%s err=%v", data.ConversationId, err)
		fail("查不到这条会话的成员，往前翻没做")
		return
	}
	if !member {
		log.Printf("load older denied: user=%d not a member of conv=%s", client.UserID, data.ConversationId)
		fail("你不是这条会话的成员")
		return
	}
	msgs, err := httpclient.GetMessagesBefore(m.cfg.MessageServiceUrl, client.UserID,
		data.ConversationId, data.BeforeMessageId, data.MessageDate, limit)
	if err != nil {
		log.Printf("load older failed: conv=%s err=%v", data.ConversationId, err)
		fail("往前翻没翻动：" + err.Error())
		return
	}
	list, _ := json.Marshal(msgs)
	// 服务端一次最多往前回 7 天，所以"这批比上限少"不等于到顶了（也可能是那几天本就没人说话）。
	// 界面只认一个信号：翻出来是空 = 到最早的一条了
	client.enqueue(ackFrame(msg.RequestId, "LOAD_OLDER_RESULT", map[string]interface{}{
		"conversationId": data.ConversationId,
		"messages":       json.RawMessage(list),
		"exhausted":      len(msgs) == 0,
	}))
	log.Printf("load older: user=%d conv=%s 往前取了 %d 条", client.UserID, data.ConversationId, len(msgs))
}

// handleSearchMessages 会话内关键词检索。
// 检索是所有读口里最宽的一个：一个常用字就能把整条会话翻出来，所以成员资格在这里判死
// （成员表在用户服务/群服务那边，消息服务这一层拿不到），结果也只回给请求者本人。
func (m *Manager) handleSearchMessages(client *Client, msg *Message) {
	var data struct {
		ConversationId string `json:"conversationId"`
		Keyword        string `json:"keyword"`
		Limit          int    `json:"limit"`
		Days           int    `json:"days"`
	}
	if err := json.Unmarshal(msg.Data, &data); err != nil || data.ConversationId == "" {
		return
	}

	member, err := m.isMemberOf(client.UserID, data.ConversationId)
	if err != nil {
		log.Printf("search members failed: conv=%s err=%v", data.ConversationId, err)
		client.enqueue(ackFrame(msg.RequestId, "SEARCH_RESULT", map[string]interface{}{
			"error": "查不到这条会话的成员，检索没做"}))
		return
	}
	if !member {
		log.Printf("search denied: user=%d not a member of conv=%s", client.UserID, data.ConversationId)
		client.enqueue(ackFrame(msg.RequestId, "SEARCH_RESULT", map[string]interface{}{
			"error": "你不是这条会话的成员"}))
		return
	}

	limit := data.Limit
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	days := data.Days
	if days <= 0 || days > 90 {
		days = 30
	}
	body, err := httpclient.SearchMessages(m.cfg.MessageServiceUrl, client.UserID,
		data.ConversationId, data.Keyword, limit, days)
	if err != nil {
		log.Printf("search failed: conv=%s err=%v", data.ConversationId, err)
		client.enqueue(ackFrame(msg.RequestId, "SEARCH_RESULT", map[string]interface{}{
			"error": "检索没跑成：" + err.Error()}))
		return
	}
	// 消息服务回的是整个对象（provider/hits/scanned/truncated/tookMs），原样转给前端
	resp, _ := json.Marshal(struct {
		Type      string          `json:"type"`
		RequestId string          `json:"requestId,omitempty"`
		Data      json.RawMessage `json:"data"`
	}{Type: "SEARCH_RESULT", RequestId: msg.RequestId, Data: body})
	client.enqueue(resp)
}

// handleSearchGlobal 跨会话检索。两边服务都没有"按人查所有命中"这条路：
// 消息表的主键是 (会话, 日期)，没建全局索引，一次命中必须落在一条具体会话的分区上；
// 而"这个人有哪些会话"只有用户服务知道。所以扇开放在网关这一层 —— 它本来就管着人脉。
//
// 代价要说实话：一次全局搜索 = 会话数 × 一次分区扫。并发限 8 是给消息服务留余量，
// 扫不动的会话记进 skipped，超上限就只扫最近那几十条并在 note 里写明扫了几条。
func (m *Manager) handleSearchGlobal(client *Client, msg *Message) {
	var data struct {
		Keyword string `json:"keyword"`
		Limit   int    `json:"limit"`
		Days    int    `json:"days"`
	}
	if err := json.Unmarshal(msg.Data, &data); err != nil {
		return
	}
	keyword := strings.TrimSpace(data.Keyword)
	if keyword == "" {
		client.enqueue(ackFrame(msg.RequestId, "SEARCH_RESULT", map[string]interface{}{
			"global": true, "error": "没给关键词，扫不了"}))
		return
	}
	limit := data.Limit
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	days := data.Days
	if days <= 0 || days > 90 {
		days = 30
	}
	const convCap = 80   // 一次最多扫这么多条会话（列表本身就是按最近更新排的）
	const perConv = 10   // 每条会话最多取几条，合起来再按时间截到 limit
	const width = 8      // 同时在扫的会话数

	convs, err := httpclient.GetConversationList(m.cfg.UserServiceUrl, client.UserID)
	if err != nil {
		log.Printf("global search: 会话列表没取回来 user=%d err=%v", client.UserID, err)
		client.enqueue(ackFrame(msg.RequestId, "SEARCH_RESULT", map[string]interface{}{
			"global": true, "keyword": keyword, "error": "会话列表没取回来，检索没做：" + err.Error()}))
		return
	}
	scannedConvs := convs
	note := ""
	if len(convs) > convCap {
		scannedConvs = convs[:convCap]
		note = fmt.Sprintf("只翻了最近 %d 条会话（总共 %d 条；这一版没建全局索引）", convCap, len(convs))
	}

	// 每条会话各扫各的分区：消息表主键是 (会话, 日期)，这一版没建全局索引
	out := make([]convResult, len(scannedConvs))
	sem := make(chan struct{}, width)
	var wg sync.WaitGroup
	for i, c := range scannedConvs {
		cm, ok := c.(map[string]interface{})
		if !ok {
			out[i].err = "会话记录读不出字段"
			continue
		}
		id := fmt.Sprintf("%v", cm["id"])
		if id == "" || id == "<nil>" {
			out[i].err = "这条会话没有 id"
			continue
		}
		name := fmt.Sprintf("%v", cm["name"])
		if name == "<nil>" || name == "" {
			name = "会话 " + id
		}
		wg.Add(1)
		go func(i int, id, name string) {
			defer wg.Done()
			// 这一路崩了不能带走整个进程：结果格是预分配的，崩在半路最多这一条会话没结果
			defer safego.Guard("search-fanout")()
			sem <- struct{}{}
			defer func() { <-sem }()
			body, e := httpclient.SearchMessages(m.cfg.MessageServiceUrl, client.UserID, id, keyword, perConv, days)
			if e != nil {
				out[i].err = e.Error()
				return
			}
			var parsed map[string]interface{}
			if json.Unmarshal(body, &parsed) != nil {
				out[i].err = "消息服务回的不是对象"
				return
			}
			out[i] = convResult{convId: id, convName: name, body: parsed}
		}(i, id, name)
	}
	wg.Wait()

	hits, agg := mergeConvResults(out, limit)
	agg["global"] = true
	agg["keyword"] = keyword
	agg["conversationsTotal"] = len(convs)
	agg["note"] = note
	resp, _ := json.Marshal(agg)
	frame, _ := json.Marshal(Message{Type: "SEARCH_RESULT", RequestId: msg.RequestId, Data: resp})
	client.enqueue(frame)
	log.Printf("global search: user=%d keyword=%q 扫了 %v/%d 条会话 命中 %d skipped=%v",
		client.UserID, keyword, agg["conversations"], len(convs), len(hits), agg["skipped"])
}

// convResult 一条会话的检索回执。body 是消息服务原样回的那个对象
// （provider/hits/scanned/truncated/tookMs），err 非空就是这条没扫动。
type convResult struct {
	convId   string
	convName string
	body     map[string]interface{}
	err      string
}

// mergeConvResults 把各会话分头扫回来的东西合成一列，顺带把那几个实数汇总。
// 独立出来是因为这一段是全流程里最容易悄悄错的地方：次序、截断、没扫动的条数、
// 每条命中归哪条会话 —— 拿掉任何一项，界面上都是"看着对"的假结果。
//
// 次序按 createTime：后端回的是 UTC 的 ISO 串，字典序就是时间序，这里不自己解析时间戳，
// 免得"两种格式各解一半"再错一次。
func mergeConvResults(out []convResult, limit int) ([]map[string]interface{}, map[string]interface{}) {
	hits := make([]map[string]interface{}, 0, limit)
	var totalScanned float64
	var maxTook float64
	provider := ""
	truncated := false
	skipped := 0
	live := 0
	for _, cs := range out {
		if cs.err != "" || cs.body == nil {
			skipped++
			log.Printf("global search: conv=%s 没扫动 err=%s", cs.convId, cs.err)
			continue
		}
		live++
		if p, _ := cs.body["provider"].(string); p != "" {
			provider = p
		}
		if n, ok := cs.body["scanned"].(float64); ok {
			totalScanned += n
		}
		if t, ok := cs.body["tookMs"].(float64); ok && t > maxTook {
			maxTook = t
		}
		if tr, ok := cs.body["truncated"].(bool); ok && tr {
			truncated = true
		}
		list, _ := cs.body["hits"].([]interface{})
		for _, h := range list {
			hm, ok := h.(map[string]interface{})
			if !ok {
				continue
			}
			// 命中要带上"这条是哪条会话里的"：点进去先要能找到那条会话
			hm["conversationId"] = cs.convId
			hm["conversationName"] = cs.convName
			hits = append(hits, hm)
		}
	}
	sort.Slice(hits, func(i, j int) bool {
		return fmt.Sprintf("%v", hits[i]["createTime"]) > fmt.Sprintf("%v", hits[j]["createTime"])
	})
	if limit > 0 && len(hits) > limit {
		hits = hits[:limit]
		truncated = true
	}
	return hits, map[string]interface{}{
		"hits": hits, "provider": provider, "scanned": totalScanned,
		"conversations": live, "skipped": skipped,
		"truncated": truncated, "tookMs": maxTook,
	}
}

// handleSearchSummary 归纳这批命中。成员资格同样先判（能归纳就等于能读出这批原文），
// 而且客户端只传 (id, date)：原文由消息服务自己从库里读，谁都没法往提示词里塞话。
func (m *Manager) handleSearchSummary(client *Client, msg *Message) {
	var data struct {
		ConversationId string `json:"conversationId"`
		Keyword        string `json:"keyword"`
		Items          []struct {
			Id   string `json:"id"`
			Date string `json:"date"`
		} `json:"items"`
	}
	if err := json.Unmarshal(msg.Data, &data); err != nil || data.ConversationId == "" {
		return
	}
	member, err := m.isMemberOf(client.UserID, data.ConversationId)
	if err != nil || !member {
		log.Printf("summary denied: user=%d conv=%s err=%v", client.UserID, data.ConversationId, err)
		client.enqueue(ackFrame(msg.RequestId, "SEARCH_SUMMARY_RESULT", map[string]interface{}{
			"error": "你不是这条会话的成员"}))
		return
	}
	items := make([]map[string]string, 0, len(data.Items))
	for _, it := range data.Items {
		if it.Id == "" || it.Date == "" {
			continue
		}
		items = append(items, map[string]string{"id": it.Id, "date": it.Date})
	}
	if len(items) == 0 {
		client.enqueue(ackFrame(msg.RequestId, "SEARCH_SUMMARY_RESULT", map[string]interface{}{
			"error": "没有可归纳的条目"}))
		return
	}
	body, err := httpclient.SummarizeMessages(m.cfg.MessageServiceUrl, client.UserID,
		data.ConversationId, data.Keyword, items)
	if err != nil {
		log.Printf("summary failed: conv=%s err=%v", data.ConversationId, err)
		client.enqueue(ackFrame(msg.RequestId, "SEARCH_SUMMARY_RESULT", map[string]interface{}{
			"error": "归纳没跑成：" + err.Error()}))
		return
	}
	// 消息服务回的是整个对象（summary/model/tookMs/error），原样转给前端 ——
	// 拆成结构体就会把"这次为什么没成"那句实话丢掉
	resp, _ := json.Marshal(struct {
		Type      string          `json:"type"`
		RequestId string          `json:"requestId,omitempty"`
		Data      json.RawMessage `json:"data"`
	}{Type: "SEARCH_SUMMARY_RESULT", RequestId: msg.RequestId, Data: body})
	client.enqueue(resp)
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

/* LOAD_MESSAGES 是这四个读口里最后一个没判成员资格的：补发、往前翻、检索、归纳都问
   conversationUserIds，只有这条"打开会话时那一次整批加载"拿着 conversationId 直接去问消息服务。
   等于前门有锁、后门开着 —— 拿自己的令牌连上，随便报一个别人的会话 id 就能把那 50 条拖走。
   问不出结果时也按"不是成员"处理（回空）：把别人的记录摆在这条会话里，比这一屏暂时空白严重得多。 */
func (m *Manager) handleLoadMessages(client *Client, msg *Message) {
	var data struct {
		ConversationId string `json:"conversationId"`
		Limit          int    `json:"limit"`
	}
	json.Unmarshal(msg.Data, &data)
	if data.ConversationId == "" {
		return
	}

	member, err := m.isMemberOf(client.UserID, data.ConversationId)
	if err != nil {
		log.Printf("load messages member check failed: conv=%s err=%v", data.ConversationId, err)
	}
	if err != nil || !member {
		log.Printf("load messages denied: user=%d not a member of conv=%s", client.UserID, data.ConversationId)
		m.replyMessages(client, msg.RequestId, []httpclient.MessageResponse{})
		return
	}

	limit := data.Limit
	if limit <= 0 {
		limit = 50
	}
	// 上限夹住：不夹的话一条会话可以一次整表拖走（补发那条路早就夹在 200）
	if limit > 200 {
		limit = 200
	}

	msgs, err := httpclient.GetMessages(m.cfg.MessageServiceUrl, client.UserID, data.ConversationId, limit)
	if err != nil {
		log.Printf("Get messages error: %v", err)
		msgs = []httpclient.MessageResponse{}
	}
	m.replyMessages(client, msg.RequestId, msgs)
}

// replyMessages 把一批消息原样回给发起这次加载的那条连接
func (m *Manager) replyMessages(client *Client, requestId string, msgs []httpclient.MessageResponse) {
	list, _ := json.Marshal(msgs)
	resp, _ := json.Marshal(Message{
		Type:      "LOAD_MESSAGES",
		RequestId: requestId,
		Data:      list,
	})
	client.enqueue(resp)
}

/* ==== 通话信令 ====
   网关只管"谁叫谁、谁应了、给一张 LiveKit 票"，音频视频一律客户端直连 LiveKit，
   不经这个进程 —— 设计文档定的媒体面分工。 */

// push 给某个人的所有设备广播一帧
func (m *Manager) push(userID int64, msgType string, data map[string]interface{}) {
	payload, _ := json.Marshal(data)
	m.SendToUser(userID, &Message{Type: msgType, Data: payload})
}

func (m *Manager) deviceCount(userID int64) int {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return len(m.clients[userID])
}

// ledger 写一笔话单。失败只记日志：话单是流水，写不进去不该把一通电话掐断，
// 但也不能悄悄丢掉
func (m *Manager) ledger(path string, who int64, body map[string]interface{}) {
	if _, err := httpclient.CallLedger(m.cfg.UserServiceUrl, who, path, body); err != nil {
		log.Printf("call ledger failed: path=%s user=%d err=%v", path, who, err)
	}
}

// endCall 收线时一次做完三件事：写话单、在会话里留一条系统提示、由调用方通知对面。
// 提示走消息通道（messageType=SYSTEM），所以它自动进历史、自动成为会话列表上那一条。
// 通没通、通了多久只有话单那边算得准，所以措辞照 /api/call/end 的回包来。
// endCall 收线时一次做完四件事：写话单、在会话里留一条 SYSTEM 提示、把提示实时推给对面、
// 更新会话列表那句。提示走消息通道，所以它自动进历史。
// 通没通、通了多久只有话单那边算得准，措辞照 /api/call/end 的回包来。
func (m *Manager) endCall(room string, who int64, reason string, convId string, peer int64) {
	res, err := httpclient.CallLedger(m.cfg.UserServiceUrl, who,
		"/api/call/end", map[string]interface{}{"room": room, "reason": reason})
	if err != nil {
		log.Printf("call ledger failed: room=%s user=%d err=%v", room, who, err)
		return
	}
	if convId == "" || res == nil {
		return
	}
	connected, _ := res["connected"].(bool)
	secs, _ := res["durationSec"].(float64)
	text := ""
	if connected {
		s := int(secs)
		text = fmt.Sprintf("通话时长 %02d:%02d", s/60, s%60)
	} else if reason == "REJECT" {
		text = "已拒绝"
	} else if reason == "HANGUP" {
		text = "已取消"
	} else {
		text = "未接听"
	}
	m.notice(convId, who, peer, text)
}

// notice 往这条会话里落一句系统提示：存库 + 实时推给对面 + 会话列表那句跟着走。
// 通话结束、共享屏幕起止、远程控制请求都走这一条，措辞由调用方给。
// 不推的话对面只能等自己补拉，看起来就像"我这有那句、他那没有"。
func (m *Manager) notice(convId string, who int64, peer int64, text string) {
	if convId == "" {
		return
	}
	saved, err := httpclient.SaveMessage(m.cfg.MessageServiceUrl, who, &httpclient.SendMessageRequest{
		ConversationId: convId, SenderId: who, MessageType: "SYSTEM", Content: text,
	})
	if err != nil {
		log.Printf("notice not stored: conv=%s text=%q err=%v", convId, text, err)
		return
	}
	if saved != nil && saved.MessageId != "" {
		payload, _ := json.Marshal(map[string]interface{}{
			"messageId": saved.MessageId, "conversationId": convId, "senderId": who,
			"messageType": "SYSTEM", "content": text, "extra": "",
			"timestamp": time.Now().UnixMilli(),
		})
		m.SendToUser(peer, &Message{Type: "MESSAGE_RECEIVE", Data: payload})
	}
	// 会话列表上那句和未读角标跟着走：和发一条普通消息同一套路
	if err := httpclient.UpdateLastMessage(m.cfg.UserServiceUrl, who, convId, text); err != nil {
		log.Printf("notice last-message update failed: conv=%s err=%v", convId, err)
	}
}

// otherOf 这通里除 who 之外的另一个人
func otherOf(s *call.Session, who int64) int64 {
	if s.CallerId == who {
		return s.CalleeId
	}
	return s.CallerId
}

func (m *Manager) handleCallInvite(client *Client, msg *Message) {
	var data struct {
		PeerId         int64  `json:"peerId"`
		MediaType      string `json:"mediaType"`
		ConversationId string `json:"conversationId"`
	}
	if err := json.Unmarshal(msg.Data, &data); err != nil {
		return
	}
	if !m.calls.Configured() {
		client.enqueue(ackFrame(msg.RequestId, "CALL_ACK", map[string]interface{}{
			"status": "FAILED", "error": "LIVEKIT_NOT_CONFIGURED",
		}))
		return
	}
	media := data.MediaType
	if media != "VIDEO" {
		media = "AUDIO"
	}
	s, why := m.calls.StartCall(client.UserID, data.PeerId, media, data.ConversationId)
	if s == nil {
		client.enqueue(ackFrame(msg.RequestId, "CALL_ACK", map[string]interface{}{
			"status": "FAILED", "error": why,
		}))
		return
	}
	// 房间得先存在，拿到 roomJoin 票的人才进得来。建不出来就明确回失败，
	// 不能让界面先响铃、等应答时才发现进不去
	if err := m.calls.EnsureRoom(s.Room, 2); err != nil {
		log.Printf("Create room failed: room=%s err=%v", s.Room, err)
		m.calls.End(s.Room, client.UserID)
		client.enqueue(ackFrame(msg.RequestId, "CALL_ACK", map[string]interface{}{
			"status": "FAILED", "error": "LIVEKIT_UNREACHABLE",
		}))
		return
	}
	if m.deviceCount(s.CalleeId) == 0 {
		m.calls.End(s.Room, s.CallerId)
		client.enqueue(ackFrame(msg.RequestId, "CALL_ACK", map[string]interface{}{
			"status": "FAILED", "error": "CALLEE_OFFLINE", "room": s.Room,
		}))
		return
	}
	m.ledger("/api/call/start", client.UserID, map[string]interface{}{
		"room": s.Room, "calleeId": s.CalleeId, "mediaType": s.Media,
	})
	m.push(s.CalleeId, "CALL_INVITE", map[string]interface{}{
		"room": s.Room, "peerId": s.CallerId, "mediaType": s.Media,
	})
	client.enqueue(ackFrame(msg.RequestId, "CALL_ACK", map[string]interface{}{
		"status": "RINGING", "room": s.Room, "peerId": s.CalleeId, "mediaType": s.Media,
	}))
	log.Printf("Call ringing: %s -> %d room=%s media=%s", msg.RequestId, s.CalleeId, s.Room, s.Media)

	// 45 秒没人接：主叫那边不能一直卡在"呼叫中"，被叫的响铃也得收掉
	room := s.Room
	time.AfterFunc(45*time.Second, func() {
		if ex := m.calls.ExpireRinging(room); ex != nil {
			m.push(ex.CallerId, "CALL_TIMEOUT", map[string]interface{}{"room": room})
			m.endCall(room, ex.CallerId, "TIMEOUT", ex.ConvId, ex.CalleeId)
			m.push(ex.CalleeId, "CALL_CANCELLED", map[string]interface{}{"room": room})
			log.Printf("Call expired unanswered: room=%s", room)
		}
	})
}

func (m *Manager) handleCallAccept(client *Client, msg *Message) {
	var data struct {
		Room string `json:"room"`
	}
	if err := json.Unmarshal(msg.Data, &data); err != nil {
		return
	}
	s, why := m.calls.Accept(data.Room, client.UserID)
	if s == nil {
		client.enqueue(ackFrame(msg.RequestId, "CALL_ACCEPTED", map[string]interface{}{
			"status": "FAILED", "error": why,
		}))
		return
	}
	// 两个人各拿一张自己的票：票里的 room 和 identity 是绑死的，
	// 拿别人那张进不了别人的房
	tokMe, err1 := m.calls.Token(s.Room, client.UserID)
	tokPeer, err2 := m.calls.Token(s.Room, s.CallerId)
	if err1 != nil || err2 != nil {
		log.Printf("Token mint failed: room=%s err=%v/%v", s.Room, err1, err2)
		m.calls.End(s.Room, client.UserID)
		client.enqueue(ackFrame(msg.RequestId, "CALL_ACCEPTED", map[string]interface{}{
			"status": "FAILED", "error": "TOKEN_FAILED",
		}))
		return
	}
	body := map[string]interface{}{"status": "ok", "room": s.Room, "url": m.calls.URL(), "mediaType": s.Media}
	body["token"] = tokMe
	body["peerId"] = s.CallerId
	client.enqueue(ackFrame(msg.RequestId, "CALL_ACCEPTED", body))
	m.ledger("/api/call/connect", client.UserID, map[string]interface{}{"room": s.Room})
	m.push(s.CallerId, "CALL_ACCEPTED", map[string]interface{}{
		"status": "ok", "room": s.Room, "url": m.calls.URL(), "mediaType": s.Media,
		"token": tokPeer, "peerId": s.CalleeId,
	})
	log.Printf("Call connected: room=%s caller=%d callee=%d", s.Room, s.CallerId, s.CalleeId)
}

func (m *Manager) handleCallReject(client *Client, msg *Message) {
	var data struct {
		Room string `json:"room"`
	}
	if err := json.Unmarshal(msg.Data, &data); err != nil {
		return
	}
	s, ok := m.calls.End(data.Room, client.UserID)
	if !ok {
		client.enqueue(ackFrame(msg.RequestId, "CALL_ACK", map[string]interface{}{
			"status": "FAILED", "error": "NO_SUCH_CALL",
		}))
		return
	}
	m.endCall(s.Room, client.UserID, "REJECT", s.ConvId, s.CallerId)
	m.push(s.CallerId, "CALL_REJECTED", map[string]interface{}{"room": s.Room})
	client.enqueue(ackFrame(msg.RequestId, "CALL_ACK", map[string]interface{}{"status": "ok"}))
}

// handleCallShare 一边点了「共享屏幕」：另一边要立刻知道（换画面 + 那颗状态要跟着变），
// 并且这条会话里要留下那一句——和 QQ 一样摆在消息流里，不是一个只有通话条知道的秘密。
func (m *Manager) handleCallShare(client *Client, msg *Message) {
	var data struct {
		Room string `json:"room"`
		On   bool   `json:"on"`
	}
	if err := json.Unmarshal(msg.Data, &data); err != nil {
		return
	}
	s := m.calls.ByUser(client.UserID)
	if s == nil || (data.Room != "" && s.Room != data.Room) {
		client.enqueue(ackFrame(msg.RequestId, "CALL_ACK", map[string]interface{}{
			"status": "FAILED", "error": "NO_SUCH_CALL",
		}))
		return
	}
	other := otherOf(s, client.UserID)
	m.push(other, "CALL_SHARE", map[string]interface{}{"room": s.Room, "on": data.On, "peerId": client.UserID})
	client.enqueue(ackFrame(msg.RequestId, "CALL_ACK", map[string]interface{}{"status": "ok"}))
	m.notice(s.ConvId, client.UserID, other, map[bool]string{true: "开始共享屏幕", false: "停止共享屏幕"}[data.On])
	log.Printf("Call share: room=%s on=%v by=%d", s.Room, data.On, client.UserID)
}

func (m *Manager) handleCallHangup(client *Client, msg *Message) {
	var data struct {
		Room string `json:"room"`
	}
	if err := json.Unmarshal(msg.Data, &data); err != nil {
		return
	}
	s, ok := m.calls.End(data.Room, client.UserID)
	if !ok {
		// 已经不在了：可能是对方先挂的，也可能是超时清掉的。回 ok 让两端都收到线，
		// 回 FAILED 只会让这一端的界面卡在通话条上
		client.enqueue(ackFrame(msg.RequestId, "CALL_ACK", map[string]interface{}{"status": "ok", "already": true}))
		return
	}
	other := s.CallerId
	if other == client.UserID {
		other = s.CalleeId
	}
	m.push(other, "CALL_HANGUP", map[string]interface{}{"room": s.Room, "peerId": client.UserID})
	client.enqueue(ackFrame(msg.RequestId, "CALL_ACK", map[string]interface{}{"status": "ok"}))
	safego.Run("delete-room-on-hangup", func() { m.calls.DeleteRoom(s.Room) })
	m.endCall(s.Room, client.UserID, "HANGUP", s.ConvId, otherOf(s, client.UserID))
	log.Printf("Call ended: room=%s by=%d", s.Room, client.UserID)
}

/* ---- V3.0 远程控制：这一期只有控制面（发起 → 对方同意 → 一次性票据 → 停止）。
像素和鼠标键盘往哪走是 Provider 的事，一期是空实现，所以 handoff 会是空的、
channelError 会带着 CONTROL_NOT_CONFIGURED 原样送到界面上 —— 不假装连上了。
帧名照设计文档那四条：CONTROL_REQUEST / ACCEPT / REJECT / STOP ---- */

func (m *Manager) handleControlRequest(client *Client, msg *Message) {
	var data struct {
		PeerId         int64  `json:"peerId"`
		ConversationId string `json:"conversationId"`
	}
	if err := json.Unmarshal(msg.Data, &data); err != nil {
		return
	}
	s, why := m.controls.Request(client.UserID, data.PeerId, data.ConversationId)
	if s == nil {
		client.enqueue(ackFrame(msg.RequestId, "CONTROL_ACK", map[string]interface{}{
			"status": "FAILED", "error": why,
		}))
		return
	}
	// 发起也落一条事件到 chat-user（控制端）
	safego.Run("ledger-request", func() {
		err := control.PostControlLedger("/api/control/request", map[string]any{
			"targetId":   data.PeerId,
			"sessionId":  s.ID,
			"conversationId": data.ConversationId,
		}, client.UserID)
		if err != nil {
			log.Printf("control ledger request err=%v", err)
		}
	})
	if m.deviceCount(data.PeerId) == 0 {
		m.controls.ForceStop(s.ID)
		client.enqueue(ackFrame(msg.RequestId, "CONTROL_ACK", map[string]interface{}{
			"status": "FAILED", "error": "PEER_OFFLINE",
		}))
		return
	}
	m.push(data.PeerId, "CONTROL_REQUEST", map[string]interface{}{
		"id": s.ID, "peerId": client.UserID, "conversationId": s.ConvId,
	})
	client.enqueue(ackFrame(msg.RequestId, "CONTROL_ACK", map[string]interface{}{
		"status": "PENDING", "id": s.ID,
	}))
	m.notice(s.ConvId, client.UserID, data.PeerId, "请求远程控制你的屏幕")
	// 没人点头就得自己收掉：界面那一格"请求中"不能靠客户端数秒，
	// 关了页面的人更要被通知一声，否则这一场永远挂在这里，谁也发不起第二场
	id := s.ID
	time.AfterFunc(m.controls.PendingTTL(), func() {
		if ex := m.controls.ExpirePending(id); ex != nil {
			m.push(ex.ControllerId, "CONTROL_TIMEOUT", map[string]interface{}{"id": id})
			m.notice(ex.ConvId, ex.ControlledId, ex.ControllerId, "远程控制没人同意")
			log.Printf("Control unanswered: id=%s", id)
		}
	})
	log.Printf("Control requested: id=%s %d -> %d channel=%s", id, client.UserID, data.PeerId, m.controls.ProviderName())
}

func (m *Manager) handleControlAccept(client *Client, msg *Message) {
	var data struct {
		Id string `json:"id"`
	}
	if err := json.Unmarshal(msg.Data, &data); err != nil {
		return
	}
	s, why := m.controls.Accept(data.Id, client.UserID, true)
	if s == nil {
		client.enqueue(ackFrame(msg.RequestId, "CONTROL_ACK", map[string]interface{}{
			"status": "FAILED", "error": orDefault(why, "NO_SUCH_SESSION"),
		}))
		return
	}
	if why != "" {
		client.enqueue(ackFrame(msg.RequestId, "CONTROL_ACK", map[string]interface{}{
			"status": "FAILED", "error": why,
		}))
		return
	}
	tok, err := m.controls.Issue(s)
	if err != nil {
		m.controls.ForceStop(s.ID)
		client.enqueue(ackFrame(msg.RequestId, "CONTROL_ACK", map[string]interface{}{
			"status": "FAILED", "error": "TOKEN_FAILED",
		}))
		return
	}
	hand, herr := m.controls.Handoff(s)
	// 通道有没有、票据多久作废，两句话两边都要拿到：点头的那一方同样要知道
	// "我同意了，但这台没接通道"，不然只有发起方看得见这句实话
	ack := map[string]interface{}{
		"status": "ok", "id": s.ID, "channel": s.Channel, "ttlSec": m.controls.TokenTTLSeconds(),
	}
	body := map[string]interface{}{
		"id": s.ID, "peerId": client.UserID, "token": tok, "channel": s.Channel,
		"ttlSec": m.controls.TokenTTLSeconds(), "handoff": hand,
	}
	if herr != nil {
		// 对方确实点头了，是我们这边没有承载它的东西。这句话原样送到界面上，
		// 不能把"同意"演成"连上了"
		body["channelError"] = herr.Error()
		ack["channelError"] = herr.Error()
	}
	m.push(s.ControllerId, "CONTROL_ACCEPTED", body)
	client.enqueue(ackFrame(msg.RequestId, "CONTROL_ACK", ack))
	m.notice(s.ConvId, client.UserID, s.ControllerId, "同意远程控制")
	// 到最长时长强制收：两边都忘了点停止，不能让它一直开着别人的屏幕
	id := s.ID
	time.AfterFunc(m.controls.MaxTTL(), func() {
		if ex := m.controls.ForceStop(id); ex != nil {
			m.endControl(ex, 0, "远程控制到时长自动结束", "远程控制请求没人同意")
			log.Printf("Control hit max duration: id=%s", id)
		}
	})
	log.Printf("Control accepted: id=%s by=%d -> %d channel=%s handoff=%v err=%v",
		id, client.UserID, s.ControllerId, s.Channel, hand != nil, herr)
}

func (m *Manager) handleControlReject(client *Client, msg *Message) {
	var data struct {
		Id string `json:"id"`
	}
	if err := json.Unmarshal(msg.Data, &data); err != nil {
		return
	}
	s, why := m.controls.Accept(data.Id, client.UserID, false)
	if s == nil {
		client.enqueue(ackFrame(msg.RequestId, "CONTROL_ACK", map[string]interface{}{
			"status": "FAILED", "error": orDefault(why, "NO_SUCH_SESSION"),
		}))
		return
	}
	if why != "" {
		client.enqueue(ackFrame(msg.RequestId, "CONTROL_ACK", map[string]interface{}{"status": "FAILED", "error": why}))
		return
	}
	m.push(s.ControllerId, "CONTROL_REJECTED", map[string]interface{}{"id": s.ID, "peerId": client.UserID})
	client.enqueue(ackFrame(msg.RequestId, "CONTROL_ACK", map[string]interface{}{"status": "ok", "id": s.ID}))
	m.notice(s.ConvId, client.UserID, s.ControllerId, "拒绝了远程控制")
	log.Printf("Control rejected: id=%s by=%d", s.ID, client.UserID)
}

func (m *Manager) handleControlStop(client *Client, msg *Message) {
	var data struct {
		Id string `json:"id"`
	}
	if err := json.Unmarshal(msg.Data, &data); err != nil {
		return
	}
	s, ok := m.controls.Stop(data.Id, client.UserID)
	if !ok {
		// 已经不在了：可能是对面先停的，也可能是超时收的。回 ok，
		// 回 FAILED 只会让这一端的界面卡在"控制中"那一格
		client.enqueue(ackFrame(msg.RequestId, "CONTROL_ACK", map[string]interface{}{
			"status": "ok", "already": true,
		}))
		return
	}
	m.endControl(s, client.UserID, "远程控制结束", "取消了远程控制请求")
	client.enqueue(ackFrame(msg.RequestId, "CONTROL_ACK", map[string]interface{}{"status": "ok", "id": s.ID}))
	log.Printf("Control stopped: id=%s by=%d", s.ID, client.UserID)
}

// endControl 收场做两件事：给没动手的那一侧补一帧、在这条会话里留那一句。
// 时长只从"对方点头"那一刻算起，而且**没点头就收场的根本没有时长可写** ——
// 那一屏从没被别人动过，写成"远程控制结束 00:00"等于凭空造一条话单，
// 所以两句文案分开给。actor=0 是系统收的（到最长时长、断线），这种没有"是谁点的停止"，
// 那句挂到被控方名下，两侧各补一帧
func (m *Manager) endControl(s *control.Session, actor int64, activeLabel, pendingLabel string) {
	text := pendingLabel
	if s.State == control.Active {
		secs := int(time.Since(s.Since).Seconds())
		text = fmt.Sprintf("%s %02d:%02d", activeLabel, secs/60, secs%60)
	}
	/* 只给没动手的那一侧补一帧。
	   试过"两端都推，包括喊停的那一位"，在门禁上被实测打回来了：浏览器一次 Tab 可见性变化会
	   让 useCall 重挂一次，重挂后 ctl.value 被回填成 {}（id 是空的），而 phase 还留着 active ——
	   这一头补来的 CONTROL_STOPPED 于是靠 `!ctl.value.id` 认账，把这一端自己收场的那一帧当成
	   "别人的那一场结束了"，跟着 reset 并把那句"对方结束了远程控制"写进会话。
	   喊停那一侧的复位仍然靠本地 reset()；它真卡住的话，靠的是发起时那一次租约回收，不是这一帧。 */
	for _, uid := range []int64{s.ControllerId, s.ControlledId} {
		if uid == actor {
			continue
		}
		m.push(uid, "CONTROL_STOPPED", map[string]interface{}{"id": s.ID, "peerId": actor})
	}
	who, peer := actor, s.ControllerId
	if actor == 0 {
		who = s.ControlledId
	} else if actor == s.ControllerId {
		peer = s.ControlledId
	}
	m.notice(s.ConvId, who, peer, text)
}

func orDefault(v, def string) string {
	if v == "" {
		return def
	}
	return v
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
