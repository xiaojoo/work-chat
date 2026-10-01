package main

import (
	"chat-gateway/config"
	"chat-gateway/connection"
	"chat-gateway/control"
	"chat-gateway/handler"
	"chat-gateway/httpclient"
	"chat-gateway/push"
	"chat-gateway/redis"
	"chat-gateway/router"
	"chat-gateway/safego"
	"log"
)

func main() {
	cfg := config.Load()
	if cfg.JwtSecret == "" {
		// 没有这把密钥，签出去/验回来的用户令牌就无从判断真假；
		// 更不能退化成"用仓库里那把公开过的默认值"——那等于谁都能自签
		log.Fatal("JWT_SECRET is not set, refusing to start")
	}
	if cfg.InternalJwtSecret == "" {
		// 没有可验证的身份就没有服务间调用，宁可不起服务也不要退化成自报家门
		log.Fatal("INTERNAL_JWT_SECRET is not set, refusing to start")
	}
	httpclient.Init(cfg.InternalJwtSecret)

	// V3.0: 远程控制的落库接口（可选）
	if cfg.ControlUserAPI != "" {
		control.InitControlLedger(cfg.ControlUserAPI, cfg.InternalJwtSecret)
	}

	redis.Init(cfg.RedisAddr, cfg.RedisPassword)

	// 厂商推送（一期只做小米）。放在 redis 之后：regId 台账在 redis 里
	push.Init(cfg.PushProvider, cfg.PushAppPackage, cfg.PushSecret, cfg.PushBase)

	connection.InitManager(cfg)

	safego.Run("heartbeat", connection.Heartbeat)
	// 跨实例投递总线：没有它，收件人连在另一个网关实例上时发送方照样拿到 SENT，
	// 而对方屏幕上什么都没有
	safego.Run("fanout", func() { connection.GetManager().ServeFanout() })

	r := router.Setup(cfg, handler.HandleWebSocket)

	log.Printf("Gateway starting on %s", cfg.Port)
	if err := r.Run(cfg.Port); err != nil {
		log.Fatal(err)
	}
}
