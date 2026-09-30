package control

import (
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

const (
	alice = int64(1900000000000101) // 想控制别人的人
	bob   = int64(1900000000000102) // 被控制的人
	carol = int64(1900000000000103) // 路人
)

func newTestManager(t *testing.T) *Manager {
	t.Helper()
	m := NewManager([]byte("test-secret-test-secret-test-0"), nil)
	if m.Configured() {
		t.Fatal("默认就该是没接通道的")
	}
	return m
}

func request(t *testing.T, m *Manager) *Session {
	t.Helper()
	s, why := m.Request(alice, bob, "123")
	if s == nil {
		t.Fatalf("发起失败：%s", why)
	}
	return s
}

// 越权最容易发生的一处：控制端替被控端"同意"自己。这条一断，整套授权就只剩个样子
func TestOnlyControlledSideCanAnswer(t *testing.T) {
	m := newTestManager(t)
	s := request(t, m)
	if _, why := m.Accept(s.ID, alice, true); why != "NOT_YOURS" {
		t.Errorf("控制端替对方同意：期望 NOT_YOURS，实际 %q", why)
	}
	if _, why := m.Accept(s.ID, carol, true); why != "NOT_YOURS" {
		t.Errorf("路人替对方同意：期望 NOT_YOURS，实际 %q", why)
	}
	if got, why := m.Accept(s.ID, bob, true); got == nil || why != "" {
		t.Fatalf("被控方自己同意没成：%v / %s", got, why)
	}
	if _, why := m.Accept(s.ID, bob, true); why != "ALREADY_ANSWERED" {
		t.Errorf("答了第二次还当成功：期望 ALREADY_ANSWERED，实际 %q", why)
	}
}

// 没点头之前签不出票
func TestIssueRequiresAccept(t *testing.T) {
	m := newTestManager(t)
	s := request(t, m)
	if _, err := m.Issue(s); err == nil || !strings.Contains(err.Error(), "CONTROL_NOT_ACCEPTED") {
		t.Fatalf("Pending 状态签出了票：%v", err)
	}
	if _, why := m.Accept(s.ID, bob, true); why != "" {
		t.Fatalf("同意没成：%s", why)
	}
	tok, err := m.Issue(s)
	if err != nil || tok == "" {
		t.Fatalf("同意之后还是签不出票：%v", err)
	}
}

// 停止之后这张票必须立刻换不到连接 —— 到 exp 为止还算数的话，"喊停"就停不住
func TestStopRevokesToken(t *testing.T) {
	m := newTestManager(t)
	s := request(t, m)
	m.Accept(s.ID, bob, true)
	tok, err := m.Issue(s)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := m.Verify(tok); err != nil {
		t.Fatalf("刚签出来的票验不过：%v", err)
	}
	if _, ok := m.Stop(s.ID, bob); !ok {
		t.Fatal("被控方喊停没停下来")
	}
	_, err = m.Verify(tok)
	if err == nil || !strings.Contains(err.Error(), "作废") {
		t.Fatalf("停止之后这张票还能用：%v", err)
	}
	if m.Get(s.ID) != nil {
		t.Fatal("停止之后这场还挂在表里")
	}
}

func TestVerifyRejectsTamperedAndForeign(t *testing.T) {
	m := newTestManager(t)
	s := request(t, m)
	m.Accept(s.ID, bob, true)
	tok, err := m.Issue(s)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := m.Verify(tok + "x"); err == nil {
		t.Error("改了末尾一个字符还验得过")
	}
	parts := strings.Split(tok, ".")
	if len(parts) != 3 {
		t.Fatal("票不三段")
	}
	forged := parts[0] + "." + parts[1] + "." + strings.Repeat("A", len(parts[2]))
	if _, err := m.Verify(forged); err == nil {
		t.Error("签名整段换掉还验得过")
	}
	// 别的密钥签的票：形状完全对，但不是我签的
	other := NewManager([]byte("another-secret-another-secret-x0"), nil)
	oseq, why := other.Request(alice, bob, "123")
	if oseq == nil {
		t.Fatalf("对照用的那场没开起来：%s", why)
	}
	other.Accept(oseq.ID, bob, true)
	otok, err := other.Issue(oseq)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := m.Verify(otok); err == nil {
		t.Error("拿另一把密钥签的票在这边验过了")
	}
}

// 不是"远程控制"这个用途的票不能当控制票用（同一把密钥签出来的别的票据也一样是 JWT）
func TestVerifyRejectsWrongKind(t *testing.T) {
	m := newTestManager(t)
	claims := jwt.MapClaims{
		"iss": "chat-gateway", "sub": "1", "aud": "2", "sid": "x", "kind": "something-else",
		"jti": "abc", "nbf": time.Now().Add(-time.Second).Unix(),
		"iat": time.Now().Unix(), "exp": time.Now().Add(time.Minute).Unix(),
	}
	tok, err := jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString(m.secret)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := m.Verify(tok); err == nil || !strings.Contains(err.Error(), "远程控制") {
		t.Fatalf("别的用途的票当控制票收了：%v", err)
	}
	// 算法降级：alg=none 的票必须拒
	unsigned, _ := jwt.NewWithClaims(jwt.SigningMethodNone, claims).SignedString(jwt.UnsafeAllowNoneSignatureType)
	if _, err := m.Verify(unsigned); err == nil {
		t.Error("alg=none 的票验过了")
	}
}

// 票到期就要用不了。TTL 写死成 60 秒没法测，这里把它调短
func TestVerifyRejectsExpired(t *testing.T) {
	m := newTestManager(t)
	m.tokenTTL = 30 * time.Millisecond
	s := request(t, m)
	m.Accept(s.ID, bob, true)
	tok, err := m.Issue(s)
	if err != nil {
		t.Fatal(err)
	}
	time.Sleep(60 * time.Millisecond)
	if _, err := m.Verify(tok); err == nil {
		t.Fatal("过期票据还能用")
	}
}

// 一个人同时只能挂一场：两场互相顶，最后没人说得清现在是谁在控制谁
func TestOneSessionPerPerson(t *testing.T) {
	m := newTestManager(t)
	request(t, m)
	if _, why := m.Request(alice, carol, "123"); why != "SENDER_BUSY" {
		t.Errorf("控制端又开一场：期望 SENDER_BUSY，实际 %q", why)
	}
	if _, why := m.Request(carol, bob, "123"); why != "PEER_BUSY" {
		t.Errorf("被控方又被别人开一场：期望 PEER_BUSY，实际 %q", why)
	}
	if _, why := m.Request(alice, alice, "123"); why != "SELF" {
		t.Errorf("自己控制自己：期望 SELF，实际 %q", why)
	}
}

func TestRejectAndExpireAndByUser(t *testing.T) {
	m := newTestManager(t)
	s := request(t, m)
	if got, why := m.Accept(s.ID, bob, false); got == nil || why != "" {
		t.Fatalf("拒绝这条路没走通：%v / %s", got, why)
	}
	if m.Get(s.ID) != nil {
		t.Error("拒了之后这场还挂在表里")
	}
	if m.ByUser(alice) != nil || m.ByUser(bob) != nil {
		t.Error("收了场还能按人查到")
	}

	s2 := request(t, m)
	if m.ByUser(alice) == nil || m.ByUser(bob) == nil {
		t.Fatal("在跑的那两场按人查不到（断线收不掉）")
	}
	if e := m.ExpirePending(s2.ID); e == nil {
		t.Fatal("到点没点头的没收掉")
	}
	if m.Get(s2.ID) != nil {
		t.Fatal("超时之后还挂在表里")
	}

	s3 := request(t, m)
	m.Accept(s3.ID, bob, true)
	if e := m.ExpirePending(s3.ID); e != nil {
		t.Error("已经同意的那场被超时收掉了 —— 超时只该管没点头的")
	}
}

// 空实现必须"像个空实现"：说清没接，而不是假装成功
func TestNullProviderShape(t *testing.T) {
	m := newTestManager(t)
	if m.ProviderName() != "none" {
		t.Errorf("通道名期望 none，实际 %q", m.ProviderName())
	}
	s := request(t, m)
	m.Accept(s.ID, bob, true)
	if _, err := m.Handoff(s); !errors.Is(err, ErrNoChannel) {
		t.Fatalf("空实现 Handoff 期望 ErrNoChannel，实际 %v", err)
	}
}

// 插得上：换一个假 Provider，控制链路一行都不用改
type stubProvider struct{ released int }

func (p *stubProvider) Name() string     { return "stub" }
func (p *stubProvider) Configured() bool { return true }
func (p *stubProvider) Handoff(*Session) (map[string]any, error) {
	return map[string]any{"addr": "10.0.0.9:5500", "pin": "6-1-2-3-4-5"}, nil
}
func (p *stubProvider) Release(*Session) { p.released++ }

func TestProviderIsSwappable(t *testing.T) {
	p := &stubProvider{}
	m := NewManager([]byte("test-secret-test-secret-test-0"), p)
	if !m.Configured() || m.ProviderName() != "stub" {
		t.Fatalf("换 Provider 没换过来：%s / %v", m.ProviderName(), m.Configured())
	}
	s := request(t, m)
	m.Accept(s.ID, bob, true)
	h, err := m.Handoff(s)
	if err != nil || h["pin"] != "6-1-2-3-4-5" {
		t.Fatalf("接入信息没给出来：%v / %v", h, err)
	}
	if tok, err := m.Issue(s); err != nil {
		t.Fatal(err)
	} else if _, err := m.Verify(tok); err != nil {
		t.Fatalf("换了 Provider 票据就验不过：%v", err)
	}
	m.Stop(s.ID, alice)
	if p.released != 1 {
		t.Errorf("收场没叫 Provider 释放：released=%d", p.released)
	}
}

// 停止这件事要有归属：路人凭一个 id 就能掐掉别人的控制，等于把"喊停"变成了攻击面。
// 系统自己收场（超时/断线）走 ForceStop，它不过归属校验，但作废一步都不能少
func TestStopNeedsOwnershipAndForceStopStillRevokes(t *testing.T) {
	m := newTestManager(t)
	s := request(t, m)
	m.Accept(s.ID, bob, true)
	tok, err := m.Issue(s)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := m.Stop(s.ID, carol); ok {
		t.Error("路人一句话就掐掉了别人的控制")
	}
	if m.Get(s.ID) == nil {
		t.Fatal("归属没过，这场却没了")
	}
	if _, err := m.Verify(tok); err != nil {
		t.Errorf("没被停止的票先失效了：%v", err)
	}
	if ex := m.ForceStop(s.ID); ex == nil {
		t.Fatal("系统收场没收掉")
	}
	if _, err := m.Verify(tok); err == nil {
		t.Error("强制收场之后这张票还能换到连接")
	}
}

// 作废记录不能只长不清：票据自己到期之后，那条记录就该被扫掉
func TestRevokedRecordGetsSwept(t *testing.T) {
	m := newTestManager(t)
	m.tokenTTL = 20 * time.Millisecond
	s := request(t, m)
	m.Accept(s.ID, bob, true)
	if _, err := m.Issue(s); err != nil {
		t.Fatal(err)
	}
	m.Stop(s.ID, alice)
	if len(m.revoked) != 1 {
		t.Fatalf("停止之后没记下作废：len=%d", len(m.revoked))
	}
	time.Sleep(40 * time.Millisecond)
	m.sweepRevoked()
	if len(m.revoked) != 0 {
		t.Errorf("票据都过期了作废记录还在长：len=%d", len(m.revoked))
	}
}
