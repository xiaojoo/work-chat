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
	// 远程控制的可插拔注入点。一期只有 "none"（不接任何第三方通道），
	// 但"发起 → 对方同意 → 一次性票据 → 停止"这条控制链路是我们自己的、已经在跑：
	// 换 RustDesk 还是自研 Remote Agent 都不该改动网关里那几条状态转移。
	// ControlSecret 不给默认值：没给就每次进程随机一把，签出去的票重启即失效
	ControlProvider string
	ControlSecret   string
	// V3.0: 远程控制的落库服务地址，chat-user API，可选；没配的话控制面照样工作只是不落库
	ControlUserAPI string
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
		// 一期只有 none；CONTROL_TOKEN_SECRET 没给就每次进程随机一把（票重启即失效）
		ControlProvider: getEnv("CONTROL_PROVIDER", "none"),
		ControlSecret:   getEnv("CONTROL_TOKEN_SECRET", ""),
		// V3.0：远程控制的落库服务地址（chat-user）。没配 = 不落库，控制链路照跑
		ControlUserAPI: getEnv("CONTROL_USER_API", ""),
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
