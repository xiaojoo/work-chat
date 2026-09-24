<template>
  <WinBar v-if="!isAuth" />
  <router-view />
  <TokenExpiredModal ref="tokenExpiredModalRef" @login="handleLoginRedirect" />
</template>

<script setup>
import { computed, ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from './stores/user'
import TokenExpiredModal from './components/TokenExpiredModal.vue'
import WinBar from './components/WinBar.vue'

const router = useRouter()
const route = useRoute()
// 登录/注册页做成微信那种满幅窗口，标题栏这一层不要；窗口靠页面自己的拖拽区移动
const isAuth = computed(() => route.name === 'Login')
const userStore = useUserStore()
const tokenExpiredModalRef = ref(null)

// 检查 Token 是否过期
function checkTokenExpiration() {
  const token = userStore.accessToken
  if (!token) return

  try {
    const payload = JSON.parse(atob(token.split('.')[1]))
    const exp = payload.exp * 1000

    // 检查是否过期
    if (Date.now() >= exp) {
      tokenExpiredModalRef.value?.show()
      return
    }

    // 检查是否即将过期（5分钟内）
    const fiveMinutes = 5 * 60 * 1000
    if (Date.now() >= exp - fiveMinutes) {
      console.warn('Token will expire soon')
    }
  } catch (e) {
    console.error('Failed to parse token:', e)
  }
}

// 监听 WebSocket 连接错误
function setupWebSocketErrorHandling() {
  // 从 WebSocket 客户端导入
  import('./websocket/client').then(({ useWebSocket }) => {
    const { isTokenExpired, setTokenExpiredCallback } = useWebSocket()

    setTokenExpiredCallback(() => {
      tokenExpiredModalRef.value?.show()
    })

    // 定期检查 Token
    setInterval(checkTokenExpiration, 60000) // 每分钟检查一次
  })
}

function handleLoginRedirect() {
  // 登录成功后重新检查 Token
  checkTokenExpiration()
}

onMounted(() => {
  // 初始检查 Token
  checkTokenExpiration()

  // 设置 WebSocket 错误处理
  setupWebSocketErrorHandling()
})
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

html, body, #app {
  height: 100%;
  font-family: 'Rajdhani', 'Microsoft YaHei', 'PingFang SC', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
}

/* 自绘标题栏在顶部占一行，页面根容器改用 --winbar-h 减掉，谁都不许多出一屏 */
#app {
  display: flex;
  flex-direction: column;
}

a {
  text-decoration: none;
}
</style>
