package router

import (
	"chat-gateway/config"

	"github.com/gin-gonic/gin"
)

func Setup(cfg *config.Config, wsHandler func(*config.Config) gin.HandlerFunc) *gin.Engine {
	r := gin.Default()

	r.GET("/ws", wsHandler(cfg))

	r.GET("/health", func(c *gin.Context) {
		c.JSON(200, gin.H{"status": "ok"})
	})

	return r
}
