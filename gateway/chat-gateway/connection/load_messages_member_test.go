/* LOAD_MESSAGES（打开会话时那一次整批加载）的成员资格判据。
   补发/往前翻/检索/归纳四条路都问 isMemberOf，只有这一条原来不问 —— 前门有锁后门开着：
   拿自己的令牌连上，随便报一个别人的会话 id 就能把那 50 条拖走。
   三条判据各管一头：不是成员时**根本不许去问消息服务**（不是"问完再筛"）、
   是成员时照常给、以及 limit 不许拿一个天文数字把整表拖走。 */
package connection

import (
	"chat-gateway/config"
	"chat-gateway/httpclient"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// 假的两个后端：users 决定"这条会话里有哪些人"，hist 记录自己被问过几次、问的时候 limit 是多少
func fakeBackends(memberIds string) (users *httptest.Server, hist *httptest.Server, histHits *int, lastQuery *string) {
	users = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/api/conversation/users/") {
			io.WriteString(w, `{"userIds":`+memberIds+`}`)
			return
		}
		http.NotFound(w, r)
	}))
	hits := 0
	q := ""
	hist = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits++
		q = r.URL.RawQuery
		io.WriteString(w, `[{"messageId":"m1","conversationId":"123","content":"别人的记录","senderId":2,"messageType":"TEXT"}]`)
	}))
	return users, hist, &hits, &q
}

func loadMessages(t *testing.T, userUrls string, msgUrls string, who int64, conv string, limit int) (frameType string, rows int) {
	t.Helper()
	m := &Manager{cfg: &config.Config{UserServiceUrl: userUrls, MessageServiceUrl: msgUrls}}
	c := &Client{Send: make(chan []byte, 8), UserID: who, DeviceID: "d1"}
	body, _ := json.Marshal(map[string]interface{}{"conversationId": conv, "limit": limit})
	m.handleLoadMessages(c, &Message{Type: "LOAD_MESSAGES", RequestId: "r-1", Data: body})
	close(c.Send)
	raw, ok := <-c.Send
	if !ok {
		t.Fatal("一条回包都没给：界面上会停在上一条会话的内容上，比给空列表更糟")
	}
	var msg Message
	if err := json.Unmarshal(raw, &msg); err != nil {
		t.Fatalf("回包不是合法帧: %v", err)
	}
	var arr []map[string]interface{}
	if err := json.Unmarshal(msg.Data, &arr); err != nil {
		t.Fatalf("LOAD_MESSAGES 的 data 不是数组（前端只认数组，给别的它就什么都不做）: %v", err)
	}
	return msg.Type, len(arr)
}

func TestMain(m *testing.M) {
	// 服务间调用一律带短期内部令牌，签不出来就每个请求都失败 —— 假后端也就白搭
	httpclient.Init("connection-test-secret-connection-test-secret")
	m.Run()
}

func TestLoadMessagesDeniesNonMember(t *testing.T) {
	users, hist, hits, _ := fakeBackends("[2,3]")
	defer users.Close()
	defer hist.Close()

	typ, rows := loadMessages(t, users.URL, hist.URL, 1, "123", 50)
	if *hits != 0 {
		t.Fatalf("不是这条会话的成员，却去问了消息服务 %d 次 —— 别人的记录已经被读出来了，筛掉也晚了", *hits)
	}
	if typ != "LOAD_MESSAGES" || rows != 0 {
		t.Fatalf("拒绝时应该回一个空的 LOAD_MESSAGES（让界面清屏），实际 type=%s rows=%d", typ, rows)
	}
}

func TestLoadAllowsMember(t *testing.T) {
	users, hist, hits, _ := fakeBackends("[1,2,3]")
	defer users.Close()
	defer hist.Close()

	typ, rows := loadMessages(t, users.URL, hist.URL, 1, "123", 50)
	if *hits != 1 || rows != 1 || typ != "LOAD_MESSAGES" {
		t.Fatalf("成员应该照常拿到这一批：hits=%d rows=%d type=%s", *hits, rows, typ)
	}
}

func TestLoadMessagesClampsLimit(t *testing.T) {
	users, hist, _, q := fakeBackends("[1,2,3]")
	defer users.Close()
	defer hist.Close()

	loadMessages(t, users.URL, hist.URL, 1, "123", 99999)
	if !strings.Contains(*q, "limit=200") {
		t.Fatalf("天文数字的 limit 应该夹到 200，实际打出去的是 %q", *q)
	}
	// 0 和负数仍然按默认 50 给，别把"没填"改成"一条都不给"
	loadMessages(t, users.URL, hist.URL, 1, "123", 0)
	if !strings.Contains(*q, "limit=50") {
		t.Fatalf("limit 没填应该回落到 50，实际 %q", *q)
	}
}
