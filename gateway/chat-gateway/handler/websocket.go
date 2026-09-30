package handler

import (
	"chat-gateway/config"
	"chat-gateway/connection"
	"chat-gateway/middleware"
	"chat-gateway/safego"
	"fmt"
	"log"
	"net/http"
	"strings"
	"sync/atomic"

	"github.com/gin-gonic/gin"
	"github.com/gorilla/websocket"
)

var anonDeviceSeq uint64

// originAllowed 判这次 WS 握手能不能放行。
//
// 缺 Origin 头放行：原生客户端（安卓 OkHttp、命令行工具）压根不发这个头，只有浏览器发。
// 所以"没有 Origin"不是可疑信号，"从一个陌生站点来的 Origin"才是 —— 原来这里恒 true，
// 任何网页拿浏览器里还活着的凭据就能连上这条连接，收这个人所有的消息帧（跨站 WebSocket 劫持）。
// 比对是全等（忽略大小写），不做前缀匹配：http://evil/?x=http://127.0.0.1:3000 那种把戏
// 在 StartsWith 下会过。
func originAllowed(origin string, allow []string) bool {
	if origin == "" {
		return true
	}
	for _, a := range allow {
		if a == "*" || strings.EqualFold(a, origin) {
			return true
		}
	}
	return false
}

func newUpgrader(cfg *config.Config) websocket.Upgrader {
	return websocket.Upgrader{
		ReadBufferSize:  1024,
		WriteBufferSize: 1024,
		CheckOrigin: func(r *http.Request) bool {
			return originAllowed(r.Header.Get("Origin"), cfg.WSAllowedOrigins)
		},
	}
}

func HandleWebSocket(cfg *config.Config) gin.HandlerFunc {
	upgrader := newUpgrader(cfg)
	return func(c *gin.Context) {
		token := c.Query("token")
		userId, err := middleware.ParseJWT(token, cfg.JwtSecret)
		if err != nil {
			c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid token"})
			return
		}

		deviceId := c.Query("deviceId")
		if deviceId == "" {
			// 不能统一写成 "web"：网关按 (userId, deviceId) 占一个槽位，
			// 所有报不上名字的客户端会互相顶掉。
			deviceId = fmt.Sprintf("anon-%d", atomic.AddUint64(&anonDeviceSeq, 1))
		}

		conn, err := upgrader.Upgrade(c.Writer, c.Request, nil)
		if err != nil {
			log.Printf("Upgrade error: %v", err)
			return
		}

		connectionId := fmt.Sprintf("conn-%d-%s", userId, deviceId)
		client := connection.NewClient(cfg, userId, deviceId, connectionId, conn)

		connection.GetManager().Add(client)

		/* 两个泵各自带 recover：它们是在 gin 那个请求 goroutine 返回之后才跑起来的，
		   中间件那层 Recovery 管不到 —— 一次 panic 就是整个网关没电，在线的人一起掉线。
		   崩掉的那一条连接会被心跳收走，不用在这里补。 */
		safego.Run("write-pump", client.WritePump)
		safego.Run("read-pump", func() { client.ReadPump(connection.GetManager()) })
	}
}
