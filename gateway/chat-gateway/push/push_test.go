/* 小米推送那一路的请求形状。凭据要他自己在小米开放平台注册才拿得到，
   所以这一层用假服务器验：验的是"我们发出去的到底长什么样"——
   鉴权头形式、多 regId 怎么拼、包名有没有带、被拒时能不能把死号认出来。
   这四条只要有一条飘了，真接上之后就是"接口通了但一条都送不到"，那种最难查。 */
package push

import (
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestSendXiaomiRequestShape(t *testing.T) {
	var gotAuth, gotCT, gotRegID, gotPkg, gotTitle, gotPayload, gotPath string
	var n int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n++
		_ = r.ParseForm()
		gotPath = r.URL.Path
		gotAuth = r.Header.Get("Authorization")
		gotCT = r.Header.Get("Content-Type")
		gotRegID = r.Form.Get("registration_id")
		gotPkg = r.Form.Get("restricted_package_name")
		gotTitle = r.Form.Get("title")
		gotPayload = r.Form.Get("payload")
		io.WriteString(w, `{"result":"ok","code":0,"info":"Received push messages for 2 regid"}`)
	}))
	defer srv.Close()

	Init("xiaomi", "com.chat.mobile", "the-app-secret", srv.URL)
	if !Enabled() {
		t.Fatal("配齐了 provider + secret 应该算接通")
	}
	dead, err := sendXiaomi([]string{"reg-a", "reg-b"}, "新消息", "今晚八点开会", "1234")
	if err != nil || len(dead) != 0 {
		t.Fatalf("成功那一路不该报错：err=%v dead=%v", err, dead)
	}
	if n != 1 {
		t.Fatalf("应该只打一次，实际 %d", n)
	}
	if gotPath != "/v2/message/regid" {
		t.Errorf("路径不对：%s", gotPath)
	}
	// 文档明写"字符 key 必须小写"，写成 Key=/Bearer 都是 401
	if gotAuth != "key=the-app-secret" {
		t.Errorf("鉴权头形式不对：%q（应为 \"key=<AppSecret>\"）", gotAuth)
	}
	if gotRegID != "reg-a,reg-b" {
		t.Errorf("多设备应该逗号拼在一个请求里，实际 %q", gotRegID)
	}
	if gotPkg != "com.chat.mobile" || gotTitle != "新消息" || gotPayload != "今晚八点开会" {
		t.Errorf("包名/标题/正文没带对：pkg=%q title=%q payload=%q", gotPkg, gotTitle, gotPayload)
	}
	if gotCT != "application/x-www-form-urlencoded" {
		t.Errorf("小米这个接口吃的是表单，不是 JSON：%q", gotCT)
	}
}

// 被拒时要能分清"这个 regId 已经废了"和"这次没送出去"：
// 前者要摘台账，后者不能摘（摘了这人就再也没有系统通知了）
func TestSendXiaomiDeadRegIdVsTransient(t *testing.T) {
	for _, c := range []struct {
		name, body string
		wantDead   int
	}{
		{"regId 无效", `{"result":"error","reason":"invalid registration_id","code":10028}`, 1},
		{"服务端临时故障", `{"result":"error","reason":"Internal server error","code":10000}`, 0},
	} {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			io.WriteString(w, c.body)
		}))
		Init("xiaomi", "com.chat.mobile", "s", srv.URL)
		dead, err := sendXiaomi([]string{"reg-x"}, "新消息", "内容", "9")
		srv.Close()
		if err == nil {
			t.Fatalf("%s：被拒了却当成功返回", c.name)
		}
		if len(dead) != c.wantDead {
			t.Errorf("%s：该摘的死号数=%d，实际 %d（err=%v）", c.name, c.wantDead, len(dead), err)
		}
	}
}

// 没配凭据 = 这条通道整个不存在：Enabled() 必须为 false，Notify 必须一个请求都不发
func TestDisabledIsTrulyInert(t *testing.T) {
	n := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { n++ }))
	defer srv.Close()
	Init("", "com.chat.mobile", "", srv.URL)
	if Enabled() {
		t.Fatal("什么都没配不该算接通")
	}
	Init("xiaomi", "com.chat.mobile", "", srv.URL)
	if Enabled() {
		t.Fatal("缺 AppSecret 不该算接通（半套配置比没配更坏）")
	}
}
