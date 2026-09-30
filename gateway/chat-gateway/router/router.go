package router

import (
	"chat-gateway/config"
	"fmt"
	"os"
	"strings"

	"github.com/gin-gonic/gin"
)

func Setup(cfg *config.Config, wsHandler func(*config.Config) gin.HandlerFunc) *gin.Engine {
	// 默认 Release：Debug 模式会把每条请求的完整 URL 打到标准输出，而这份输出在这台机上
	// 是被 nohup 落进 gateway.log 的 —— 那条 URL 上挂着 ?token=...
	if os.Getenv("GIN_MODE") == "" {
		gin.SetMode(gin.ReleaseMode)
	}
	r := gin.New()
	r.Use(gin.Recovery())
	/* 不用 gin.Logger()：它默认那行里带 RawQuery。就算自己写 Formatter，
	   gin 1.10 的 LogFormatterParams.Path 也是**带查询串的**那一条（实测：
	   "[req] ... GET 101 765µs /ws?token=eyJhbGciOi..." 整串进了 gateway.log），
	   所以这里再切一刀 —— 访问日志只留路径，令牌不落盘。 */
	r.Use(gin.LoggerWithFormatter(func(p gin.LogFormatterParams) string {
		path := p.Path
		if i := strings.IndexByte(path, '?'); i >= 0 {
			path = path[:i]
		}
		return fmt.Sprintf("[req] %s %s %d %s %s\n",
			p.TimeStamp.Format("2006-01-02T15:04:05"), p.Method, p.StatusCode, p.Latency, path)
	}))

	r.GET("/ws", wsHandler(cfg))

	r.GET("/health", func(c *gin.Context) {
		c.JSON(200, gin.H{"status": "ok"})
	})

	return r
}
