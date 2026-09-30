// Package safego 只管一件事：一个 goroutine 里 panic，别把整个网关带走。
//
// 这不是假设性风险：被同设备新连接顶掉那一毫秒，Client.enqueue 往已关闭的 channel 里发，
// 整个进程跟着没了，所有在线的人一起掉线（那次是 close(Send) 和发送没在同一把锁里）。
// gin 的 Recovery 中间件只覆盖 HTTP 请求那一个 goroutine —— WritePump/ReadPump、
// 落库、删房间这些是在它返回之后才 go 出去的，panic 一路捅到 runtime 直接 exit(2)。
package safego

import (
	"log"
	"runtime/debug"
)

// Run 起一个带 recover 的 goroutine。name 只用来在崩的那一刻说清是哪一路崩的。
// 崩了不重启：这些活（一条连接的读写、一笔话单、一次删房间）重来一遍的代价比"状态没对上"小，
// 但也不能悄悄吞掉 —— 栈打到日志里，级别用 PANIC 开头方便 grep。
func Run(name string, fn func()) {
	go func() {
		defer Guard(name)()
		fn()
	}()
}

// Guard 给"已经有自己的 defer（wg.Done、释放信号量）"那种 goroutine 用：
// 调用方写成 defer safego.Guard("名字")() 放在自己那几个 defer 中间就行，
// 不用把整段搬进一个闭包。
func Guard(name string) func() {
	return func() {
		if r := recover(); r != nil {
			log.Printf("PANIC recovered in %s: %v\n%s", name, r, debug.Stack())
		}
	}
}
