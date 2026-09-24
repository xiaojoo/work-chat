import { ref } from 'vue'
import router from '../router'
import { useUserStore } from '../stores/user'
import { WS_BASE, getDeviceId } from '../config'

const ws = ref(null)
const connected = ref(false)
const reconnectAttempts = ref(0)
const maxReconnectAttempts = 20
let reconnectInterval = 1000

let heartbeatTimer = null
let reconnectTimer = null
let messageCallbacks = []
let tokenRef = ''
let isTokenExpired = ref(false)

// Token 失效时的回调
let onTokenExpired = null

export function useWebSocket() {
  function setTokenExpiredCallback(callback) {
    onTokenExpired = callback
  }

  function checkTokenExpired(code) {
    // WebSocket 关闭码：4001-4003 表示认证相关错误
    if (code === 4001 || code === 4002 || code === 4003) {
      return true
    }
    return false
  }

  function handleTokenExpired() {
    console.error('Token expired or invalid, redirecting to login...')
    isTokenExpired.value = true
    stopHeartbeat()
    clearTimeout(reconnectTimer)

    // 通知外部组件
    if (onTokenExpired) {
      onTokenExpired()
    }

    // 清除本地存储
    const userStore = useUserStore()
    userStore.logout()

    // 跳转到登录页
    router.push('/login')
  }

  function connect(token) {
    if (ws.value && ws.value.readyState === WebSocket.OPEN) {
      return
    }

    tokenRef = token
    isTokenExpired.value = false

    // 网页端同源走 vite 代理；桌面壳里没有代理层，VITE_WS_BASE 给的是网关绝对地址
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const base = WS_BASE || `${protocol}//${window.location.host}`
    const url = `${base}/ws?token=${token}&deviceId=${encodeURIComponent(getDeviceId())}`

    console.log('Connecting to:', url)

    ws.value = new WebSocket(url)

    ws.value.onopen = () => {
      connected.value = true
      reconnectAttempts.value = 0
      reconnectInterval = 1000
      startHeartbeat()
      console.log('WebSocket connected')
    }

    ws.value.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data)

        // 检查是否是 Token 错误消息
        if (data.type === 'ERROR' && data.code === 4001) {
          handleTokenExpired()
          return
        }

        handleMessage(data)
      } catch (e) {
        console.error('Parse message error:', e)
      }
    }

    ws.value.onclose = (event) => {
      connected.value = false
      stopHeartbeat()
      console.log(`WebSocket disconnected: code=${event.code}, reason=${event.reason}`)

      // 检查是否是 Token 过期导致的关闭
      if (checkTokenExpired(event.code) || event.reason === 'token_expired') {
        handleTokenExpired()
        return
      }

      attemptReconnect()
    }

    ws.value.onerror = (error) => {
      console.error('WebSocket error:', error)
    }
  }

  function disconnect() {
    stopHeartbeat()
    clearTimeout(reconnectTimer)
    reconnectAttempts.value = maxReconnectAttempts
    if (ws.value) {
      ws.value.close()
      ws.value = null
    }
    connected.value = false
  }

  function send(type, data, requestId = null) {
    if (!ws.value || ws.value.readyState !== WebSocket.OPEN) {
      console.warn('WebSocket not connected, queuing message')
      // 将消息加入队列，等待连接建立后发送
      return false
    }

    const message = {
      type,
      requestId: requestId || `req-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      data
    }

    ws.value.send(JSON.stringify(message))
    return true
  }

  // 等待连接建立后发送消息
  function sendWhenConnected(type, data, maxWait = 5000) {
    return new Promise((resolve, reject) => {
      const startTime = Date.now()

      const checkAndSend = () => {
        if (ws.value && ws.value.readyState === WebSocket.OPEN) {
          const result = send(type, data)
          resolve(result)
        } else if (Date.now() - startTime >= maxWait) {
          console.warn('WebSocket connection timeout')
          reject(new Error('WebSocket connection timeout'))
        } else {
          setTimeout(checkAndSend, 100)
        }
      }

      checkAndSend()
    })
  }

  function onMessage(callback) {
    messageCallbacks.push(callback)
  }

  function startHeartbeat() {
    stopHeartbeat()
    heartbeatTimer = setInterval(() => {
      send('PING')
    }, 30000)
  }

  function stopHeartbeat() {
    if (heartbeatTimer) {
      clearInterval(heartbeatTimer)
      heartbeatTimer = null
    }
  }

  function attemptReconnect() {
    if (reconnectAttempts.value >= maxReconnectAttempts) {
      console.log('Max reconnect attempts reached')
      return
    }

    reconnectTimer = setTimeout(() => {
      reconnectAttempts.value++
      console.log(`Reconnecting... attempt ${reconnectAttempts.value}`)
      connect(tokenRef)
    }, reconnectInterval)

    reconnectInterval = Math.min(reconnectInterval * 2, 30000)
  }

  function handleMessage(msg) {
    messageCallbacks.forEach(cb => cb(msg))
  }

  return {
    ws,
    connected,
    isTokenExpired,
    connect,
    disconnect,
    send,
    sendWhenConnected,
    onMessage,
    setTokenExpiredCallback
  }
}
