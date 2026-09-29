package connection

import "testing"

// 顶号的那一毫秒：同设备的新连接把旧的 shutdown 掉（里面是 close(Send)），
// 而旧的那条自己的 goroutine 正在给它回 LOAD_MESSAGES。
// 老代码在这一步 panic: send on closed channel —— 网关整个进程没了，所有在线的人一起掉线。
// 这条测试就是那一格的门禁：修复前它 panic（测试进程直接崩），修复后它安静地返回 false。
func TestEnqueueAfterShutdownDoesNotPanic(t *testing.T) {
	c := &Client{Send: make(chan []byte, 8), UserID: 1, DeviceID: "d1"}
	c.shutdown()

	defer func() {
		if r := recover(); r != nil {
			t.Fatalf("已关闭的连接上入队 panic 了：%v", r)
		}
	}()

	if c.enqueue([]byte("LOAD_MESSAGES 的回包")) {
		t.Fatal("已关闭的连接不该接受入队（这条帧本来也没人收了）")
	}
	// 幂等：再来一次 shutdown 不能 close of closed
	c.shutdown()
}

// 活着的那条照常入队，别把守卫写成"谁都发不出去"
func TestEnqueueOnLiveClientStillWorks(t *testing.T) {
	c := &Client{Send: make(chan []byte, 8), UserID: 1, DeviceID: "d2"}
	if !c.enqueue([]byte("pong")) {
		t.Fatal("活着的连接入队失败")
	}
	if len(c.Send) != 1 {
		t.Fatalf("队列里应该有 1 帧，实际 %d", len(c.Send))
	}
}
