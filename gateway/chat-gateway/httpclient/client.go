package httpclient

import (
	"bytes"
	"chat-gateway/middleware"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"time"
)

var client = &http.Client{Timeout: 10 * time.Second}

// 归纳这一路单独给一条长命的水池：本机模型要先想 ~530 个 token 才答话（实测 9-25 秒），
// 消息服务自己给的超时是 90 秒，网关要是 10 秒就撒手，前端只会看到一句"超时"——
// 而那其实是网关先挂的，不是模型慢。慢的东西要给它匹配的超时，不能全局调大去掩盖别的卡死。
var slowClient = &http.Client{Timeout: 120 * time.Second}

var internalSecret string

// Init 注入服务间令牌密钥；未配置时所有内部调用直接报错而不是退化成无鉴权请求
func Init(secret string) {
	internalSecret = secret
}

// newRequest 以 userID 的身份发起一次服务间调用。身份来自网关在 WS 握手时验证过的 JWT，
// 服务端只校验这个短期令牌的签名，不再接受自报的 X-User-Id。
func newRequest(method, url string, body io.Reader, userID int64) (*http.Request, error) {
	req, err := http.NewRequest(method, url, body)
	if err != nil {
		return nil, err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	token, err := middleware.MintInternalToken(internalSecret, userID, 30*time.Second)
	if err != nil {
		return nil, err
	}
	req.Header.Set("X-Internal-Token", token)
	return req, nil
}

func do(method, url string, body io.Reader, userID int64) (*http.Response, error) {
	req, err := newRequest(method, url, body, userID)
	if err != nil {
		return nil, err
	}
	return client.Do(req)
}

type SendMessageRequest struct {
	ConversationId string `json:"conversationId"`
	SenderId       int64  `json:"senderId"`
	MessageType    string `json:"messageType"`
	Content        string `json:"content"`
	Extra          string `json:"extra,omitempty"`
	// RequestId 客户端生成的这笔发送的 id；服务端按它做持久去重（发端 outbox 重放）
	RequestId string `json:"requestId,omitempty"`
}

type MessageResponse struct {
	MessageId    string `json:"messageId"`
	ConversationId string `json:"conversationId"`
	// MessageDate 是这条落在哪个日期分区里（服务端本地 +08 的日期）。
	// 补发游标必须是 (messageId, messageDate) 一对：只给 id 定不了分区。
	MessageDate  string `json:"messageDate"`
	Content      string `json:"content"`
	SenderId     int64  `json:"senderId"`
	MessageType  string `json:"messageType"`
	Extra        string `json:"extra"`
	CreateTime   string `json:"createTime"`
}

type GroupMemberResponse struct {
	UserId int64 `json:"userId"`
	Role   int   `json:"role"`
	// Muted 由群服务按它自己的时钟算好：muteUntil 序列化出来没有时区、
	// 秒为 0 时还会被整段省掉，网关这边解析字符串判禁言不可靠。
	Muted bool `json:"muted"`
}

func SaveMessage(baseUrl string, userId int64, req *SendMessageRequest) (*MessageResponse, error) {
	body, _ := json.Marshal(req)
	resp, err := do(http.MethodPost, baseUrl+"/api/message/send", bytes.NewReader(body), userId)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != 200 {
		b, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("save message failed: %s", string(b))
	}

	var msg MessageResponse
	if err := json.NewDecoder(resp.Body).Decode(&msg); err != nil {
		return nil, err
	}
	return &msg, nil
}

func GetMessages(baseUrl string, userId int64, conversationId string, limit int) ([]MessageResponse, error) {
	url := fmt.Sprintf("%s/api/message/list/%s?limit=%d", baseUrl, conversationId, limit)
	resp, err := do(http.MethodGet, url, nil, userId)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	var msgs []MessageResponse
	if err := json.NewDecoder(resp.Body).Decode(&msgs); err != nil {
		return nil, err
	}
	return msgs, nil
}

// GetMessagesAfter 补发：游标之后直到今天的消息，服务端按正序给回来。
// 游标是 (messageId, messageDate) 一对：messages 的分区键含 message_date，
// 单给一个 timeuuid 定不了该去哪个分区找。
func GetMessagesAfter(baseUrl string, userId int64, conversationId, afterId, cursorDate string, limit int) ([]MessageResponse, error) {
	full := fmt.Sprintf("%s/api/message/after/%s?afterMessageId=%s&messageDate=%s&limit=%d",
		baseUrl, conversationId, url.QueryEscape(afterId), url.QueryEscape(cursorDate), limit)
	resp, err := do(http.MethodGet, full, nil, userId)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("get messages after: status %d", resp.StatusCode)
	}

	var msgs []MessageResponse
	if err := json.NewDecoder(resp.Body).Decode(&msgs); err != nil {
		return nil, err
	}
	return msgs, nil
}

// SearchMessages 会话内关键词检索。整个响应体原样带回去给前端 ——
// provider / scanned / truncated / tookMs 这几个"这次查得干不干净"的字段前端要说给用户听，
// 在这里拆成结构体就会把它们悄悄丢掉。
func SearchMessages(baseUrl string, userId int64, conversationId, keyword string, limit, days int) (json.RawMessage, error) {
	u := fmt.Sprintf("%s/api/message/search?conversationId=%s&keyword=%s&limit=%d&days=%d",
		baseUrl, url.QueryEscape(conversationId), url.QueryEscape(keyword), limit, days)
	resp, err := do(http.MethodGet, u, nil, userId)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("search failed: status %d %s", resp.StatusCode, string(b))
	}
	return io.ReadAll(resp.Body)
}

// SummarizeMessages 让服务端把这几条命中喂给模型归纳一句。
// items 只带 (id, date)：原文由消息服务自己从库里读，客户端塞不进提示词。
func SummarizeMessages(baseUrl string, userId int64, conversationId, keyword string, items []map[string]string) (json.RawMessage, error) {
	body, _ := json.Marshal(map[string]interface{}{"keyword": keyword, "items": items})
	u := fmt.Sprintf("%s/api/message/search/summarize?conversationId=%s", baseUrl, url.QueryEscape(conversationId))
	req, err := newRequest(http.MethodPost, u, bytes.NewReader(body), userId)
	if err != nil {
		return nil, err
	}
	resp, err := slowClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("summarize failed: status %d %s", resp.StatusCode, string(b))
	}
	return io.ReadAll(resp.Body)
}

func GetConversationList(baseUrl string, userId int64) ([]interface{}, error) {
	resp, err := do(http.MethodGet, baseUrl+"/api/conversation/list", nil, userId)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	var convs []interface{}
	if err := json.NewDecoder(resp.Body).Decode(&convs); err != nil {
		return nil, err
	}
	return convs, nil
}

func CreateConversation(baseUrl string, userId, targetUserId int64) (int64, error) {
	body, _ := json.Marshal(map[string]int64{"targetUserId": targetUserId})
	resp, err := do(http.MethodPost, baseUrl+"/api/conversation/create", bytes.NewReader(body), userId)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()

	var result map[string]int64
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return 0, err
	}
	return result["conversationId"], nil
}

// UpdateLastMessage 更新会话最后消息
func UpdateLastMessage(baseUrl string, userId int64, conversationId string, content string) error {
	body, _ := json.Marshal(map[string]interface{}{
		"conversationId": conversationId,
		"userId":         userId,
		"messageId":      content,
		"content":        content,
	})
	resp, err := do(http.MethodPost, baseUrl+"/api/conversation/update-last-message", bytes.NewReader(body), userId)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	return nil
}

// GetGroupMembers 获取群成员列表，actingUserId 是发起这次拉取的当前用户
func GetGroupMembers(baseUrl string, actingUserId, groupId int64) ([]GroupMemberResponse, error) {
	url := fmt.Sprintf("%s/api/group/%d/members", baseUrl, groupId)
	resp, err := do(http.MethodGet, url, nil, actingUserId)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	var members []GroupMemberResponse
	if err := json.NewDecoder(resp.Body).Decode(&members); err != nil {
		return nil, err
	}
	return members, nil
}

// MarkMessageRead 标记消息已读
func MarkMessageRead(baseUrl string, userId int64, conversationId string, lastReadMessageId string) error {
	body, _ := json.Marshal(map[string]interface{}{
		"conversationId":   conversationId,
		"userId":           userId,
		"lastReadMessageId": lastReadMessageId,
	})
	resp, err := do(http.MethodPost, baseUrl+"/api/message/read", bytes.NewReader(body), userId)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	return nil
}

// GetReadStatus 获取已读状态
func GetReadStatus(baseUrl string, userId int64, conversationId string) ([]map[string]interface{}, error) {
	url := fmt.Sprintf("%s/api/message/read/%s", baseUrl, conversationId)
	resp, err := do(http.MethodGet, url, nil, userId)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	var status []map[string]interface{}
	if err := json.NewDecoder(resp.Body).Decode(&status); err != nil {
		return nil, err
	}
	return status, nil
}

// GetConversationUsers 获取会话中的用户列表
func GetConversationUsers(baseUrl string, actingUserId int64, conversationId int64) ([]int64, error) {
	url := fmt.Sprintf("%s/api/conversation/users/%d", baseUrl, conversationId)
	resp, err := do(http.MethodGet, url, nil, actingUserId)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	var result map[string][]int64
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, err
	}
	return result["userIds"], nil
}

// MarkDelivered 上报"这批消息在 my 的设备上落地了"。身份仍走短期内部令牌。
func MarkDelivered(baseUrl string, userId int64, conversationId string, messageIds []string) error {
	body, _ := json.Marshal(map[string]interface{}{
		"conversationId": conversationId, "messageIds": messageIds,
	})
	resp, err := do(http.MethodPost, baseUrl+"/api/message/delivered", bytes.NewReader(body), userId)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		raw, _ := io.ReadAll(io.LimitReader(resp.Body, 200))
		return fmt.Errorf("mark delivered -> %d %s", resp.StatusCode, string(raw))
	}
	return nil
}

// CallLedger 往 chat-user 的话单接口写一笔，并把服务端回的那点事实带回来
// （通没通、通了多久只有话单那边知道，措辞要照它说）。
// 返回错误由调用方决定是掐断还是只记日志。
func CallLedger(baseUrl string, actingUserId int64, path string, body map[string]interface{}) (map[string]interface{}, error) {
	b, _ := json.Marshal(body)
	resp, err := do(http.MethodPost, baseUrl+path, bytes.NewReader(b), actingUserId)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 2000))
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("call ledger %s -> %d %s", path, resp.StatusCode, string(raw))
	}
	var out map[string]interface{}
	json.Unmarshal(raw, &out)
	return out, nil
}

// DeleteMessage 撤回一条消息。返回服务端给出的可读原因（归属/超时/不存在），
// 网关据此回 ACK —— 原来只看 err、不看状态码，服务端拒绝会被当成成功吞掉。
func DeleteMessage(baseUrl string, userId int64, conversationId, messageId string) (string, error) {
	url := fmt.Sprintf("%s/api/message/%s/%s?userId=%d", baseUrl, conversationId, messageId, userId)
	resp, err := do(http.MethodDelete, url, nil, userId)
	if err != nil {
		return "消息服务不可用", err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		var body map[string]string
		if json.Unmarshal(b, &body) == nil && body["error"] != "" {
			return body["error"], fmt.Errorf("revoke rejected(%d): %s", resp.StatusCode, body["error"])
		}
		return "撤回失败", fmt.Errorf("revoke failed(%d): %s", resp.StatusCode, string(b))
	}
	return "", nil
}
