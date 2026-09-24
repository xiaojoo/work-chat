package httpclient

import (
	"bytes"
	"chat-gateway/middleware"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

var client = &http.Client{Timeout: 10 * time.Second}

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
}

type MessageResponse struct {
	MessageId    string `json:"messageId"`
	ConversationId string `json:"conversationId"`
	Content      string `json:"content"`
	SenderId     int64  `json:"senderId"`
	MessageType  string `json:"messageType"`
	Extra        string `json:"extra"`
	CreateTime   string `json:"createTime"`
}

type GroupMemberResponse struct {
	UserId int64  `json:"userId"`
	Role   int    `json:"role"`
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

// DeleteMessage 删除消息
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
