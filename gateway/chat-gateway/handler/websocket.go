package handler

import (
	"chat-gateway/config"
	"chat-gateway/connection"
	"chat-gateway/middleware"
	"fmt"
	"log"
	"net/http"
	"sync/atomic"

	"github.com/gin-gonic/gin"
	"github.com/gorilla/websocket"
)

var anonDeviceSeq uint64

var upgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
	CheckOrigin: func(r *http.Request) bool {
		return true
	},
}

func HandleWebSocket(cfg *config.Config) gin.HandlerFunc {
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

		go client.WritePump()
		go client.ReadPump(connection.GetManager())
	}
}
