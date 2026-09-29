package connection

import (
	"log"
	"time"
)

func Heartbeat() {
	ticker := time.NewTicker(10 * time.Second)
	defer ticker.Stop()

	for range ticker.C {
		// 连续 MaxHeartbeatMiss 个心跳周期没有响应才算掉线
		staleAfter := time.Duration(manager.cfg.HeartbeatInterval*manager.cfg.MaxHeartbeatMiss) * time.Second

		manager.mu.RLock()
		for userID, devices := range manager.clients {
			for deviceID, client := range devices {
				client.mu.Lock()
				if time.Since(client.LastActive) > staleAfter {
					log.Printf("User %d device %s timeout, closing", userID, deviceID)
					client.Conn.Close()
				}
				client.mu.Unlock()
			}
		}
		manager.mu.RUnlock()
	}
}
