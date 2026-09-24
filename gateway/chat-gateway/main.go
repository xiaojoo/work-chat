package main

import (
	"chat-gateway/config"
	"chat-gateway/connection"
	"chat-gateway/handler"
	"chat-gateway/httpclient"
	"chat-gateway/redis"
	"chat-gateway/router"
	"log"
)

func main() {
	cfg := config.Load()
	if cfg.InternalJwtSecret == "" {
		// 没有可验证的身份就没有服务间调用，宁可不起服务也不要退化成自报家门
		log.Fatal("INTERNAL_JWT_SECRET is not set, refusing to start")
	}
	httpclient.Init(cfg.InternalJwtSecret)

	redis.Init(cfg.RedisAddr, cfg.RedisPassword)

	connection.InitManager(cfg)

	go connection.Heartbeat()

	r := router.Setup(cfg, handler.HandleWebSocket)

	log.Printf("Gateway starting on %s", cfg.Port)
	if err := r.Run(cfg.Port); err != nil {
		log.Fatal(err)
	}
}
