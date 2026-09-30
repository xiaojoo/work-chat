/* WS 握手的来源门禁。原来 CheckOrigin 恒 true：任何网页只要让浏览器发一条
   ws://网关/ws?token=... ，同源策略并不会拦 WebSocket（它不受 CORS 管），
   于是那个页面就能拿这个人还活着的凭据建一条连接，收他往后所有的消息帧。
   判据两头都要有：开发期那几种开法必须放行（放错杀了等于把他自己的客户端锁在门外），
   陌生站点必须拒 —— 尤其"把允许的串塞进 query/子域"这类前缀把戏。 */
package handler

import "testing"

func TestOriginAllowed(t *testing.T) {
	dev := []string{"app://chat", "http://127.0.0.1:3000", "http://localhost:3000", "file://"}
	cases := []struct {
		origin string
		want   bool
		why    string
	}{
		{"", true, "原生客户端（安卓 OkHttp、命令行）压根不发 Origin"},
		{"app://chat", true, "桌面壳的自定义方案"},
		{"http://127.0.0.1:3000", true, "vite dev"},
		{"http://localhost:3000", true, "vite dev 另一种写法"},
		{"APP://chat", true, "方案名大小写不敏感"},
		{"http://evil.test", false, "陌生站点"},
		{"http://127.0.0.1:3000.evil.test", false, "拿允许值当前缀"},
		{"http://evil.test/?x=http://127.0.0.1:3000", false, "把允许值塞进 query"},
		{"null", false, "沙箱 iframe 的 origin 是字面量 null，不算熟人"},
	}
	for _, c := range cases {
		if got := originAllowed(c.origin, dev); got != c.want {
			t.Errorf("origin=%q 期望 %v，实际 %v（%s）", c.origin, c.want, got, c.why)
		}
	}
}

// 显式配了白名单之后，开发期那组默认值就不该再偷偷生效
func TestOriginListReplacesDefault(t *testing.T) {
	prod := []string{"https://chat.example.com"}
	if originAllowed("http://127.0.0.1:3000", prod) {
		t.Fatal("配了生产白名单还放行本机 dev，等于白名单没起作用")
	}
	if !originAllowed("https://chat.example.com", prod) {
		t.Fatal("生产白名单里的来源被拒了")
	}
}
