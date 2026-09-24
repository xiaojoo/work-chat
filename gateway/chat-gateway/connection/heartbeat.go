package connection

import (
	"log"
	"time"
)

func Heartbeat() {
	ticker := time.NewTicker(10 * time.Second)
	defer ticker.Stop()

	for range ticker.C {
		manager.mu.RLock()
		for userID, devices := range manager.clients {
			for deviceID, client := range devices {
				client.mu.Lock()
				if time.Since(client.LastActive) > 90*time.Second {
					log.Printf("User %d device %s timeout, closing", userID, deviceID)
					client.Conn.Close()
				}
				client.mu.Unlock()
			}
		}
		manager.mu.RUnlock()
	}
}
