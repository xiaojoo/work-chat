// Package call 只管通话的信令侧：房间命名、签 LiveKit 访问票、通话状态表。
// 媒体一律不过网关（网关只给一张票和一个 URL），这是设计文档定的分工。
package call

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// State 一通 1-1 通话的信令状态。持久化（call/call_member/call_record 三张表）还没接，
// 这份表只在网关内存里，重启即丢。
type State int

const (
	Ringing State = iota
	Active
)

type Session struct {
	Room     string
	CallerId int64
	CalleeId int64
	Media    string
	ConvId   string
	State    State
	Since    time.Time
}

// Manager 房间名唯一性靠"两个 id + 一个 nonce"，同两人再打一通不会撞进上一间房。
type Manager struct {
	mu        sync.RWMutex
	byRoom    map[string]*Session
	byUser    map[int64]string
	nonce     int64
	apiKey    string
	apiSecret string
	url       string
	maxTTL    time.Duration
	hc        *http.Client
}

func NewManager(url, apiKey, apiSecret string) *Manager {
	return &Manager{
		byRoom:    map[string]*Session{},
		byUser:    map[int64]string{},
		apiKey:    apiKey,
		apiSecret: apiSecret,
		url:       strings.TrimSuffix(url, "/"),
		maxTTL:    10 * time.Minute,
		hc:        &http.Client{Timeout: 5 * time.Second},
	}
}

// Configured 没配 key 时通话整条不可用，界面要能问出"为什么不能打"而不是默默转圈
func (m *Manager) Configured() bool { return m.apiKey != "" && m.apiSecret != "" && m.url != "" }

func (m *Manager) URL() string { return m.url }

// StartCall 开一通新通话；同一个人已经在一通里就直接拒绝（Busy），避免出现"第三个人
// 在门外听两边说话"这种没人授权过的三方桥接。
func (m *Manager) StartCall(caller, callee int64, media, convId string) (*Session, string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if caller == callee {
		return nil, "SELF"
	}
	if room, ok := m.byUser[caller]; ok {
		if s := m.byRoom[room]; s != nil && s.State == Active {
			return nil, "CALLER_BUSY"
		}
	}
	if room, ok := m.byUser[callee]; ok {
		if s := m.byRoom[room]; s != nil {
			return nil, "CALLEE_BUSY"
		}
	}
	m.nonce++
	lo, hi := caller, callee
	if lo > hi {
		lo, hi = hi, lo
	}
	room := fmt.Sprintf("call-%d-%d-%d%d", lo, hi, time.Now().Unix()%100000, m.nonce%1000)
	s := &Session{Room: room, CallerId: caller, CalleeId: callee, Media: media, ConvId: convId, State: Ringing, Since: time.Now()}
	m.byRoom[room] = s
	m.byUser[caller] = room
	m.byUser[callee] = room
	return s, ""
}

// Accept 被叫应答。只有这通里的被叫能应，别人拿着房间名来应不算。
func (m *Manager) Accept(room string, who int64) (*Session, string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	s := m.byRoom[room]
	if s == nil {
		return nil, "NO_SUCH_CALL"
	}
	if s.State == Active {
		return nil, "ALREADY_ACTIVE"
	}
	if s.CalleeId != who {
		return nil, "NOT_CALLEE"
	}
	s.State = Active
	return s, ""
}

// End 任一方收线，或者被叫拒绝。
func (m *Manager) End(room string, who int64) (*Session, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	s := m.byRoom[room]
	if s == nil {
		return nil, false
	}
	if s.CallerId != who && s.CalleeId != who {
		return nil, false
	}
	m.dropLocked(room, s)
	return s, true
}

// Other 返回这通里除 who 之外的另一个人 id
func (m *Manager) Other(room string, who int64) int64 {
	m.mu.RLock()
	defer m.mu.RUnlock()
	s := m.byRoom[room]
	if s == nil {
		return 0
	}
	if s.CallerId == who {
		return s.CalleeId
	}
	return s.CallerId
}

// Has 这个人此刻在不在这通里（网关转发前挡一手，免得有人拿别人的房间名收线）
func (m *Manager) Has(room string, who int64) bool {
	m.mu.RLock()
	defer m.mu.RUnlock()
	s := m.byRoom[room]
	return s != nil && (s.CallerId == who || s.CalleeId == who)
}

// ByUser 这个人此刻在哪一通里。断线清理要用：浏览器直接关掉时不会有任何 CALL_HANGUP，
// 状态留在表里就等于这个人"永远在通话中"，后面谁都打不进来。
func (m *Manager) ByUser(userID int64) *Session {
	m.mu.RLock()
	defer m.mu.RUnlock()
	room, ok := m.byUser[userID]
	if !ok {
		return nil
	}
	return m.byRoom[room]
}

// ExpireRinging 没人应答的振铃到期后清掉；调用方在超时里给主叫回一句没接。
func (m *Manager) ExpireRinging(room string) *Session {
	m.mu.Lock()
	defer m.mu.Unlock()
	s := m.byRoom[room]
	if s == nil || s.State != Ringing {
		return nil
	}
	m.dropLocked(room, s)
	return s
}

func (m *Manager) dropLocked(room string, s *Session) {
	delete(m.byRoom, room)
	delete(m.byUser, s.CallerId)
	delete(m.byUser, s.CalleeId)
}

// httpBase 客户端给的通常是 ws://…，房间要用同一路径的 http 口去建
func (m *Manager) httpBase() string {
	u := m.url
	switch {
	case strings.HasPrefix(u, "wss://"):
		u = "https://" + u[len("wss://"):]
	case strings.HasPrefix(u, "ws://"):
		u = "http://" + u[len("ws://"):]
	}
	return u
}

// EnsureRoom 把这通电话的房间在 LiveKit 里建出来。
// roomJoin 的票进不了一张不存在的房，而"创建房间 / 生成 Token / 权限控制"本来就是
// 设计文档派给服务侧的活，不能指望第一个进房的人自己去建房。
func (m *Manager) EnsureRoom(room string, maxParticipants int) error {
	now := time.Now()
	admin, err := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{
		"iss": m.apiKey, "sub": "chat-gateway", "nbf": now.Unix(),
		"exp": now.Add(time.Minute).Unix(), "iat": now.Unix(),
		"video": map[string]any{"room": room, "roomCreate": true, "roomAdmin": true},
	}).SignedString([]byte(m.apiSecret))
	if err != nil {
		return err
	}
	body, _ := json.Marshal(map[string]any{
		"name": room, "empty_timeout": 60, "max_participants": maxParticipants,
	})
	req, err := http.NewRequest("POST", m.httpBase()+"/twirp/livekit.RoomService/CreateRoom", bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+admin)
	resp, err := m.hc.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("create room %s -> %d %s", room, resp.StatusCode, string(raw))
	}
	return nil
}

// DeleteRoom 收线后放掉房间；LiveKit 自己有 empty_timeout 兜底，这里只是让它快点回收
func (m *Manager) DeleteRoom(room string) {
	admin, err := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{
		"iss": m.apiKey, "sub": "chat-gateway", "nbf": time.Now().Unix(),
		"exp": time.Now().Add(time.Minute).Unix(), "iat": time.Now().Unix(),
		"video": map[string]any{"room": room, "roomAdmin": true},
	}).SignedString([]byte(m.apiSecret))
	if err != nil {
		return
	}
	body, _ := json.Marshal(map[string]any{"room": room})
	req, _ := http.NewRequest("POST", m.httpBase()+"/twirp/livekit.RoomService/DeleteRoom", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+admin)
	if resp, err := m.hc.Do(req); err == nil {
		resp.Body.Close()
	}
}

// Token 给这通里的某个人签一张 LiveKit 访问票。
//
// 手写而不是引 github.com/livekit/protocol/auth：那个包会往网关里拖进 grpc + otel + psrpc
// 一整串依赖，而票本身就是一个 HS256 JWT，网关早就依赖 golang-jwt 了。
// claim 形状照 protocol 的 VideoGrant：{"video":{"room","roomJoin","canPublish","canSubscribe"}}
func (m *Manager) Token(room string, identity int64) (string, error) {
	now := time.Now()
	who := fmt.Sprintf("%d", identity)
	claims := jwt.MapClaims{
		"iss": m.apiKey,
		"sub": who,
		// LiveKit 取的参与者身份是顶层 identity，不是 sub。
		// 只给 sub 的票签名是对的，但进房时身份为空，服务器直接 404 且不写日志
		"identity": who,
		"nbf":      now.Unix(),
		"exp":      now.Add(m.maxTTL).Unix(),
		"video": map[string]any{
			"room":     room,
			"roomJoin": true,
			// 这两条必须显式给：源码注释写明"三个权限都不显式设置时默认全给"，
			// 那等于把我们自己的默认值交给对方的库决定
			"canPublish":   true,
			"canSubscribe": true,
		},
		"iat": now.Unix(),
	}
	t := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	// LiveKit 的验签按 base64url(HS256) 走，密钥就是 apiSecret 原文
	return t.SignedString([]byte(m.apiSecret))
}
