package redis

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"strconv"
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

func SetUserOnline(userID int64, deviceID, connectionID, status string) {
	key := fmt.Sprintf("user:online:%d", userID)
	data := map[string]interface{}{
		"deviceId":     deviceID,
		"connectionId": connectionID,
		"lastActive":   time.Now().Unix(),
		"status":       status,
	}
	bytes, _ := json.Marshal(data)
	// 原来这里不检查错误：地址配错时 HSet 一直失败，在线点永远不亮，日志里却一个字都没有
	if err := rdb.HSet(ctx, key, deviceID, string(bytes)).Err(); err != nil {
		log.Printf("presence write failed: user=%d device=%s status=%s err=%v", userID, deviceID, status, err)
	}
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

// FanoutChannel 是网关实例之间的投递总线：
// 收件人连在另一个实例上时，本实例投不到，就把帧广播出去让持有它的那个实例投。
// 没有这条总线时，"对方在另一实例"表现成发送方拿到 SENT、对方屏幕上什么都没有。
const FanoutChannel = "gateway:fanout"

func PublishFanout(payload []byte) error {
	return rdb.Publish(ctx, FanoutChannel, payload).Err()
}

// SubscribeFanout 单独占一条订阅连接：Redis 的订阅连接上不能再跑普通命令，
// 不能复用 rdb 的那些调用
func SubscribeFanout() *redis.PubSub {
	return rdb.Subscribe(ctx, FanoutChannel)
}

// 幂等窗口：客户端重发同一条消息（弱网重试、点了两次）不应该在库里多出第二条。
// 主流聊天软件都是客户端给自己那条一个 id、服务端按它去重；这里的 key 用的是
// 客户端本来就在生成的 requestId，所以三个端都不用改就能受益。
const idemPrefix = "msg:idem:"

func IdemClaim(key string, ttl time.Duration) bool {
	ok, err := rdb.SetNX(ctx, idemPrefix+key, "PENDING", ttl).Result()
	if err != nil {
		// Redis 不通时不能假装去重成功，也不能因此不让发：交给调用方按"没去重"继续
		log.Printf("idem claim failed: %s err=%v", key, err)
		return true
	}
	return ok
}

func IdemPeek(key string) string {
	v, err := rdb.Get(ctx, idemPrefix+key).Result()
	if err != nil {
		return ""
	}
	return v
}

func IdemFinish(key, messageId string, ttl time.Duration) {
	if err := rdb.Set(ctx, idemPrefix+key, messageId, ttl).Err(); err != nil {
		log.Printf("idem finish failed: %s err=%v", key, err)
	}
}

// IdemRelease 落库失败要把占位撤掉，否则这次失败会占住窗口、让合法重试一直看到 PENDING
func IdemRelease(key string) {
	if err := rdb.Del(ctx, idemPrefix+key).Err(); err != nil {
		log.Printf("idem release failed: %s err=%v", key, err)
	}
}

/* ==== 厂商推送的 regId 台账 ====
   一个人可以有多台设备（手机 A、平板 B），所以是一个 set；
   放 Redis 不放进程内存：多网关实例都要查得到，重启也不用等客户端重新上报。 */

func pushKey(userID int64) string { return "push:reg:" + strconv.FormatInt(userID, 10) }

func PushRemember(userID int64, regID string) {
	if regID == "" {
		return
	}
	if err := rdb.SAdd(ctx, pushKey(userID), regID).Err(); err != nil {
		log.Printf("push regId 没存进去: user=%d err=%v", userID, err)
	}
}

func PushRegIds(userID int64) []string {
	v, err := rdb.SMembers(ctx, pushKey(userID)).Result()
	if err != nil {
		log.Printf("push regId 没读出来: user=%d err=%v", userID, err)
		return nil
	}
	return v
}

// 小米说某个 regId 已失效（卸载、换机、号被回收）时摘掉那一条，
// 不然每次投递都带着一个死号
func PushForget(userID int64, regID string) {
	if err := rdb.SRem(ctx, pushKey(userID), regID).Err(); err != nil {
		log.Printf("push regId 没摘掉: user=%d err=%v", userID, err)
	}
}
