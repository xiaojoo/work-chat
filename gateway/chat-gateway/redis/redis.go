package redis

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"
)

var rdb *redis.Client
var ctx = context.Background()

func Init(addr, password string) {
	rdb = redis.NewClient(&redis.Options{
		Addr:     addr,
		Password: password,
		DB:       0,
	})
}

func SetUserOnline(userID int64, deviceID, connectionID string) {
	key := fmt.Sprintf("user:online:%d", userID)
	data := map[string]interface{}{
		"deviceId":     deviceID,
		"connectionId": connectionID,
		"lastActive":   time.Now().Unix(),
	}
	bytes, _ := json.Marshal(data)
	rdb.HSet(ctx, key, deviceID, string(bytes))
	rdb.Expire(ctx, key, 24*time.Hour)
}

func SetUserOffline(userID int64) {
	key := fmt.Sprintf("user:online:%d", userID)
	rdb.Del(ctx, key)
}

func GetUserOnline(userID int64) map[string]string {
	key := fmt.Sprintf("user:online:%d", userID)
	result, _ := rdb.HGetAll(ctx, key).Result()
	return result
}

func SetUserGateway(userID int64, gatewayId string) {
	key := fmt.Sprintf("user:gateway:%d", userID)
	rdb.Set(ctx, key, gatewayId, 24*time.Hour)
}

func GetUserGateway(userID int64) string {
	key := fmt.Sprintf("user:gateway:%d", userID)
	result, _ := rdb.Get(ctx, key).Result()
	return result
}
