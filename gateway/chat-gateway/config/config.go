package config

import "os"

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
		HeartbeatInterval: 30,
		MaxHeartbeatMiss:  3,
	}
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
