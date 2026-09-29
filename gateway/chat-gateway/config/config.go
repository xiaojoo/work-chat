package config

import (
	"os"
	"strconv"
)

type Config struct {
	Port              string
	RedisAddr         string
	RedisPassword     string
	JwtSecret         string
	InternalJwtSecret string
	UserServiceUrl    string
	MessageServiceUrl string
	GroupServiceUrl   string
	HeartbeatInterval int
	MaxHeartbeatMiss  int
	LivekitUrl        string
	LivekitApiKey     string
	LivekitApiSecret  string
	// 集群里每个实例要有一个能区分自己的名字：跨实例广播靠它跳过自己发的那条
	GatewayId string
}

func Load() *Config {
	return &Config{
		Port:              getEnv("GATEWAY_PORT", ":8082"),
		RedisAddr:         getEnv("REDIS_ADDR", "localhost:6379"),
		RedisPassword:     getEnv("REDIS_PASSWORD", "redis123456"),
		JwtSecret:         getEnv("JWT_SECRET", "chat-system-jwt-secret-key-must-be-at-least-256-bits-long-for-hs256"),
		InternalJwtSecret: getEnv("INTERNAL_JWT_SECRET", ""),
		UserServiceUrl:    getEnv("USER_SERVICE_URL", "http://localhost:8081"),
		MessageServiceUrl: getEnv("MESSAGE_SERVICE_URL", "http://localhost:8083"),
		GroupServiceUrl:   getEnv("GROUP_SERVICE_URL", "http://localhost:8084"),
		HeartbeatInterval: getEnvInt("HEARTBEAT_INTERVAL_SEC", 30),
		MaxHeartbeatMiss:  getEnvInt("HEARTBEAT_MAX_MISS", 3),
		// 通话凭据故意不给默认值：没配就是"这台没接 LiveKit"，
		// 界面要能问出原因，而不是让那颗按钮默默转圈
		LivekitUrl:       getEnv("LIVEKIT_URL", "ws://127.0.0.1:7880"),
		LivekitApiKey:    getEnv("LIVEKIT_API_KEY", ""),
		LivekitApiSecret: getEnv("LIVEKIT_API_SECRET", ""),
		// 多实例要靠这个 id 分辨"这条 fanout 是不是我自己发的"，
		// 一台机上起两个实例必须给两个不同的 GATEWAY_ID
		GatewayId: getEnv("GATEWAY_ID", "gateway-01"),
	}
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func getEnvInt(key string, fallback int) int {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			return n
		}
	}
	return fallback
}
