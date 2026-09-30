// Package control 是 V3.0 远程控制的**控制面**：A 发起 → B 同意 → 一次性临时票据 → 任一方停止。
//
// 屏幕像素、鼠标键盘怎么走不在这里 —— 那是 Provider 的事，一期只有空实现
// （2026-09-30 定的口径：这一期只立可插拔接口和控制链路）。之所以现在就把接口立起来：
// 能被审计、能被拒、票据会过期这三件事不依赖用哪家通道，换 RustDesk 还是自研 Remote Agent
// 都不该改动下面这几条状态转移。
package control

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

var httpClient = &http.Client{Timeout: 10 * time.Second}

// errControlLedger 是"话单服务没接上"这句实话。和 ErrNoChannel 同级——
// 网关启动期只要求配置`CONTROL_USER_API`，没给的话就静默跳过落库，不挡业务
var errControlLedger = errors.New("CONTROL_LEDGER_DISABLED")

var (
	controlUserAPI string
	internalSecret string
)

// InitControlLedger 注入话单服务和内部调用令牌；未配置时所有落库直接报错而不是退化成无鉴权请求
func InitControlLedger(userAPI, secret string) {
	controlUserAPI = userAPI
	internalSecret = secret
}

// PostControlLedger 向 chat-user 的 POST /api/control/* 打一笔，只认短期内部令牌鉴权
func PostControlLedger(path string, body map[string]any, by int64) error {
	if controlUserAPI == "" || internalSecret == "" {
		return errControlLedger
	}
	b, _ := json.Marshal(body)
	req, err := http.NewRequest(http.MethodPost, controlUserAPI+path, bytes.NewReader(b))
	if err != nil {
		return err
	}
	token, err := generateInternalToken(internalSecret, by, 30*time.Second)
	if err != nil {
		return err
	}
	req.Header.Set("X-Internal-Token", token)
	req.Header.Set("Content-Type", "application/json")
	resp, err := httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		raw := make([]byte, 200)
		resp.Body.Read(raw)
		return fmt.Errorf("control ledger %s -> %d %s", path, resp.StatusCode, string(raw))
	}
	return nil
}

// generateInternalToken 和 middleware.MintInternalToken 同口径：短期令牌供服务间调用用。
// chat-user 那边 requireIssuer("chat-gateway")、subject 就是数字 userId，两者必须对上；
// 少一个字段或换个 header 名，落库会被静默 401。
func generateInternalToken(secret string, userId int64, ttl time.Duration) (string, error) {
	now := time.Now()
	claims := jwt.MapClaims{
		"iss":   "chat-gateway",
		"sub":   fmt.Sprintf("%d", userId),
		"type":  "internal",
		"nbf":   now.Unix(),
		"iat":   now.Unix(),
		"exp":   now.Add(ttl).Unix(),
	}
	t := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return t.SignedString([]byte(secret))
}

// ledger 把 PostControlLedger 的错误喊出来：go 语句丢 err = 静默吞，
// 网关日志里"看起来一切正常"而表 0 行，这种排查最费时间
func ledger(path string, body map[string]any, by int64) {
	if err := PostControlLedger(path, body, by); err != nil && err != errControlLedger {
		fmt.Printf("control ledger failed: path=%s by=%d err=%v\n", path, by, err)
	}
}

// ErrNoChannel 是"通道没接"这句实话。Provider 的空实现返回它，
// 界面上就照原话说"对方已同意，但这台还没接远程控制通道"——不假装连上了
var ErrNoChannel = errors.New("CONTROL_NOT_CONFIGURED")

// 状态：pending（等对方点头）→ active（对方已同意）。结束即从表里摘掉，
// 只留日志和票据作废记录，界面上不出现"已结束"这一格
type State int

const (
	Pending State = iota
	Active
)

// Session 一场控制。Token 不落在这上面：票据只在签出的那一刻回给控制端一次，
// 表里只留 jti 和有效期，网关重启也带不走一张还能用的票
type Session struct {
	ID           string
	ControllerId int64
	ControlledId int64
	ConvId       string
	State        State
	Since        time.Time
	Jti          string
	Channel      string // Provider.Name()，让人一眼看出这场的通道是谁在承载
}

func (s *Session) pairKey() [2]int64 {
	lo, hi := s.ControllerId, s.ControlledId
	if lo > hi {
		lo, hi = hi, lo
	}
	return [2]int64{lo, hi}
}

// Provider 是那根可拔插的缝。Handoff 的返回故意是 map：
// RustDesk 要给人家 peerId+一次性口令，自研 Agent 可能给 addr+pin，
// 键不一样但控制链路不用跟着改。
//
// **二期实现要求**：
//   Name() → 唯一标识（如"rustdesk", "remotescreen"）
//   Configured() → 已配置才算数（密钥 + 服务端地址都齐）
//   Handoff(s) → 签临时票给对端：成功回 (map{"peerId":"xxx","token":"yyy"}),失败回(nil, err)
//   Release(s) → 释放资源：吊销票据、关 TCP 连接等（幂等，可重复调）
//
// 一期只有 `none` 实现：不配任何第三方通道时走这个，界面上说清楚"还没接远程控制通道"
type Provider interface {
	Name() string
	Configured() bool
	Handoff(s *Session) (map[string]any, error)
	Release(s *Session)
}

// NullProvider 空实现：不配通道时用。Release 什么都不做是**有意的** ——
// 它没有发过任何口令，也就没有要作废的东西
type NullProvider struct{}

func (NullProvider) Name() string                             { return "none" }
func (NullProvider) Configured() bool                         { return false }
func (NullProvider) Handoff(*Session) (map[string]any, error) { return nil, ErrNoChannel }
func (NullProvider) Release(*Session)                         {}

// Manager 只管内存里的这几场控制：谁在控制谁、点到没点头、票据还作不作废。
// 谁在线、会话里要不要留一句、日志落哪 —— 那些是 connection.Manager 的事
type Manager struct {
	mu      sync.RWMutex
	byId    map[string]*Session
	byPair  map[[2]int64]string
	revoked map[string]time.Time // jti -> 过期时刻，停止/超时之后这张票就不能再换到连接
	prov    Provider
	secret  []byte
	// 三个窗口都是"没人看着也要自己收掉"的：等同意 60s、票据 60s 内要建立起连接、
	// 一场控制最长 30 分钟（到点强制停，不靠两边都记得点停止）
	pendingTTL time.Duration
	tokenTTL   time.Duration
	maxTTL     time.Duration
}

func NewManager(secret []byte, prov Provider) *Manager {
	if prov == nil {
		prov = NullProvider{}
	}
	if len(secret) == 0 {
		// 没给密钥就每次进程随机一把：签出去的票重启即失效，这比"用一把公开在源码里的密钥"诚实
		b := make([]byte, 32)
		rand.Read(b)
		secret = b
	}
	return &Manager{
		byId:       map[string]*Session{},
		byPair:     map[[2]int64]string{},
		revoked:    map[string]time.Time{},
		prov:       prov,
		secret:     secret,
		pendingTTL: 60 * time.Second,
		tokenTTL:   60 * time.Second,
		maxTTL:     30 * time.Minute,
	}
}

// ProviderName 给界面和日志用：说清这场的通道是谁
func (m *Manager) ProviderName() string { return m.prov.Name() }

// Configured 通道到底接没接。控制链路照跑，只是不给接入信息
func (m *Manager) Configured() bool { return m.prov.Configured() }

func newId() string {
	b := make([]byte, 8)
	rand.Read(b)
	return "cs-" + hex.EncodeToString(b)
}

// Request 开一场控制请求。同一个人同一时刻只能有一场在跑：
// 两边各开一场会互相把对方的同意请求顶掉，那种"谁在控制谁"没人说得清
func (m *Manager) Request(controller, controlled int64, convId string) (*Session, string) {
	if controller == controlled {
		return nil, "SELF"
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.byUserLocked(controller); ok {
		return nil, "SENDER_BUSY"
	}
	if _, ok := m.byUserLocked(controlled); ok {
		return nil, "PEER_BUSY"
	}
	s := &Session{
		ID: newId(), ControllerId: controller, ControlledId: controlled,
		ConvId: convId, State: Pending, Since: time.Now(), Channel: m.prov.Name(),
	}
	m.byId[s.ID] = s
	m.byPair[s.pairKey()] = s.ID
	return s, ""
}

func (m *Manager) byUserLocked(uid int64) (*Session, bool) {
	for _, s := range m.byId {
		if s.ControllerId == uid || s.ControlledId == uid {
			return s, true
		}
	}
	return nil, false
}

// ByUser 这个人身上那一场（可能在跑也可能在等点头）。断线收线用
func (m *Manager) ByUser(uid int64) *Session {
	m.mu.RLock()
	defer m.mu.RUnlock()
	s, _ := m.byUserLocked(uid)
	return s
}

func (m *Manager) Get(id string) *Session {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.byId[id]
}

// Accept 被控方点头。agree=false 就是拒。只有被控的那一位能答这一条 ——
// 控制端替对方"同意"自己，是这套设计里最不能发生的事
func (m *Manager) Accept(id string, by int64, agree bool) (*Session, string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	s := m.byId[id]
	if s == nil {
		return nil, "NO_SUCH_SESSION"
	}
	if s.ControlledId != by {
		return nil, "NOT_YOURS"
	}
	if s.State != Pending {
		return nil, "ALREADY_ANSWERED"
	}
	if !agree {
		delete(m.byId, s.ID)
		delete(m.byPair, s.pairKey())
		// 拒绝也落一条事件到 chat-user
		go ledger("/api/control/reject", map[string]any{
			"sessionId":      id,
			"initiatorId":    s.ControllerId,
			"targetId":       s.ControlledId,
			"conversationId": s.ConvId,
		}, by)
		return s, ""
	}
	s.State = Active
	s.Since = time.Now()
	// 同意也落一条事件到 chat-user
	go ledger("/api/control/accept", map[string]any{
		"sessionId":      id,
		"initiatorId":    s.ControllerId,
		"targetId":       s.ControlledId,
		"conversationId": s.ConvId,
	}, by)
	return s, ""
}

// Issue 签一张一次性临时票据给控制端。claim 形状照 LiveKit 那张票的路子：
// 顶层谁在用（controller）、被谁用（controlled）、哪一场（sid）、多久作废（jti+exp）。
// **对方还没点头就不签**：这张票的凭据意义全在"B 同意过"这句话上，
// 发起方自己点一下就能拿到票，整套授权就只剩个样子
func (m *Manager) Issue(s *Session) (string, error) {
	m.mu.RLock()
	state := s.State
	m.mu.RUnlock()
	if state != Active {
		return "", errors.New("CONTROL_NOT_ACCEPTED")
	}
	jti := make([]byte, 12)
	if _, err := rand.Read(jti); err != nil {
		return "", err
	}
	now := time.Now()
	claims := jwt.MapClaims{
		"iss":  "chat-gateway",
		"sub":  fmt.Sprintf("%d", s.ControllerId),
		"aud":  fmt.Sprintf("%d", s.ControlledId),
		"sid":  s.ID,
		"kind": "control",
		"jti":  hex.EncodeToString(jti),
		"nbf":  now.Unix(),
		"iat":  now.Unix(),
		"exp":  now.Add(m.tokenTTL).Unix(),
	}
	t := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	signed, err := t.SignedString(m.secret)
	if err != nil {
		return "", err
	}
	m.mu.Lock()
	s.Jti = claims["jti"].(string)
	m.mu.Unlock()
	return signed, nil
}

// Handoff 问 Provider 要"怎么连过去"。没接通道时返回 ErrNoChannel，
// 这一场的状态不变：对方确实同意了，只是我们这边没有承载它的东西
func (m *Manager) Handoff(s *Session) (map[string]any, error) {
	return m.prov.Handoff(s)
}

// Stop 任一方收场（控制端取消、被控端喊停）。身份不对就当没这回事，
// 免得任何人都能凭一个 id 把别人的控制掐掉
func (m *Manager) Stop(id string, by int64) (*Session, bool) {
	m.mu.RLock()
	s := m.byId[id]
	own := s != nil && (by == s.ControllerId || by == s.ControlledId)
	m.mu.RUnlock()
	if !own {
		return nil, false
	}
	return m.finish(id, by), true
}

// ForceStop 系统收的场：到最长时长、或者这一位断线了。这类没有"是谁点的停止"，
// 所以不过归属校验 —— 但票据一样要作废，走的是同一条 finish
func (m *Manager) ForceStop(id string) *Session { return m.finish(id, -1) }

// finish 收场只此一条路：摘表 + 作废票据 + 叫 Provider 释放。
// 分成几处各写一半，迟早有一处漏掉作废那一步
func (m *Manager) finish(id string, by int64) *Session {
	m.mu.Lock()
	s := m.byId[id]
	if s == nil {
		m.mu.Unlock()
		return nil
	}
	delete(m.byId, s.ID)
	delete(m.byPair, s.pairKey())
	if s.Jti != "" {
		// 作废记录要活过票据自己的 exp：只记 jti 不记到什么时候，
		// 扫的时候就没法判断这条是不是早该丢了
		m.revoked[s.Jti] = time.Now().Add(m.tokenTTL)
	}
	m.mu.Unlock()
	m.sweepRevoked()
	m.prov.Release(s)
	// 断开也落一条事件到 chat-user
	if by > 0 {
		go ledger("/api/control/stop", map[string]any{
			"sessionId":      id,
			"initiatorId":    s.ControllerId,
			"targetId":       s.ControlledId,
			"conversationId": s.ConvId,
		}, by)
	}
	return s
}

// ExpirePending 只收"到点还没人点头"的那一场：控制端不该一直等在"请求中"这一格
func (m *Manager) ExpirePending(id string) *Session {
	m.mu.Lock()
	s := m.byId[id]
	if s == nil || s.State != Pending {
		m.mu.Unlock()
		return nil
	}
	delete(m.byId, s.ID)
	delete(m.byPair, s.pairKey())
	m.mu.Unlock()
	// 超时也落一条事件到 chat-user
	go ledger("/api/control/timeout", map[string]any{
		"sessionId":      id,
		"initiatorId":    s.ControllerId,
		"targetId":       s.ControlledId,
		"conversationId": s.ConvId,
	}, s.ControllerId)
	return s
}

// Verify 给将来的 Remote Agent 用：拿到一张票先在这里过三道 —— 签名、有效期、作废没有。
// 三道都过才算这张票能换一次连接。密钥只有两把都相同的人解得开，所以 Agent 和网关同密钥
func (m *Manager) Verify(tok string) (*jwt.MapClaims, error) {
	claims := jwt.MapClaims{}
	parsed, err := jwt.ParseWithClaims(tok, claims, func(t *jwt.Token) (any, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, fmt.Errorf("签名算法不对：%v", t.Header["alg"])
		}
		return m.secret, nil
	})
	if err != nil {
		return nil, err
	}
	if !parsed.Valid {
		return nil, errors.New("票据无效")
	}
	if kind, _ := claims["kind"].(string); kind != "control" {
		return nil, errors.New("这不是远程控制的票据")
	}
	jti, _ := claims["jti"].(string)
	m.mu.RLock()
	_, gone := m.revoked[jti]
	m.mu.RUnlock()
	if gone {
		return nil, errors.New("票据已作废")
	}
	return &claims, nil
}

// sweepRevoked 清掉早就过期的作废记录，不然这张表只会长。挂在每次收场后面顺手扫一遍：
// 一场控制就一条记录，扫的频率和它增长的频率天生一致，不值得为它单开一个定时器
func (m *Manager) sweepRevoked() {
	now := time.Now()
	m.mu.Lock()
	for j, until := range m.revoked {
		if until.Before(now) {
			delete(m.revoked, j)
		}
	}
	m.mu.Unlock()
}

// MaxTTL 给上层挂"到点强制收场"的定时器用
func (m *Manager) MaxTTL() time.Duration { return m.maxTTL }

// PendingTTL 同上：等同意的窗口
func (m *Manager) PendingTTL() time.Duration { return m.pendingTTL }

// TokenTTLSeconds 回给界面，让"票据 60 秒内有效"这句话是从配置来的，不是写死的文案
func (m *Manager) TokenTTLSeconds() int { return int(m.tokenTTL / time.Second) }
