/* 一个 goroutine 里 panic 会不会带走整个进程 —— 这条不是假想：顶号那一毫秒
   Client.enqueue 往已关闭的 channel 里发，实测把网关整个打没了，在线的人一起掉线。
   gin 的 Recovery 只覆盖 HTTP 请求那一个 goroutine，读写泵和落库那些 go 出去它管不到。 */
package safego

import (
	"sync"
	"testing"
	"time"
)

func TestRunRecoversPanic(t *testing.T) {
	var wg sync.WaitGroup
	wg.Add(2)
	Run("故意崩的那一路", func() { defer wg.Done(); panic("模拟一次 send on closed channel") })
	Run("崩过之后还要能起", func() { defer wg.Done() })

	done := make(chan struct{})
	go func() { wg.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("两条都没收尾：recover 没接住，或者计数没还回去")
	}
}

// Guard 那种写法要和调用方自己的 defer 共存：别的收尾必须照样执行
func TestGuardKeepsCallerDefers(t *testing.T) {
	var order []string
	finished := make(chan struct{})
	Run("guard-order", func() {
		defer func() { finished <- struct{}{} }()
		defer Guard("guard-order")()
		defer func() { order = append(order, "sem") }()
		panic("崩在中途")
	})
	select {
	case <-finished:
	case <-time.After(3 * time.Second):
		t.Fatal("goroutine 没收尾")
	}
	if len(order) != 1 || order[0] != "sem" {
		t.Fatalf("调用方自己的 defer 被吞了，实际 %v（期望 [sem]）", order)
	}
}
