import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'

export function useTokenValidation() {
  const router = useRouter()
  const userStore = useUserStore()
  const isTokenValid = ref(true)
  const tokenExpiry = ref(null)

  // 解析 Token
  function parseToken(token) {
    try {
      if (!token) return null
      const parts = token.split('.')
      if (parts.length !== 3) return null

      const payload = JSON.parse(atob(parts[1]))
      return {
        exp: payload.exp * 1000, // 转换为毫秒
        iat: payload.iat * 1000,
        sub: payload.sub,
        username: payload.username
      }
    } catch (e) {
      console.error('Failed to parse token:', e)
      return null
    }
  }

  // 验证 Token 是否有效
  function validateToken() {
    const token = userStore.accessToken
    if (!token) {
      isTokenValid.value = false
      return false
    }

    const payload = parseToken(token)
    if (!payload) {
      isTokenValid.value = false
      return false
    }

    // 检查是否过期（留出 5 分钟的缓冲时间）
    const bufferTime = 5 * 60 * 1000 // 5 分钟
    const now = Date.now()

    if (now >= payload.exp - bufferTime) {
      console.warn('Token expired or expiring soon')
      isTokenValid.value = false
      tokenExpiry.value = new Date(payload.exp)
      return false
    }

    isTokenValid.value = true
    tokenExpiry.value = new Date(payload.exp)
    return true
  }

  // 跳转到登录页
  function redirectToLogin(expired = true) {
    userStore.logout()
    router.push({
      path: '/login',
      query: expired ? { expired: 'true' } : {}
    })
  }

  // 定期检查 Token
  let checkInterval = null

  function startTokenCheck(intervalMs = 60000) {
    // 每分钟检查一次
    checkInterval = setInterval(() => {
      if (!validateToken()) {
        redirectToLogin(true)
      }
    }, intervalMs)
  }

  function stopTokenCheck() {
    if (checkInterval) {
      clearInterval(checkInterval)
      checkInterval = null
    }
  }

  // 组件挂载时开始检查
  onMounted(() => {
    validateToken()
    if (isTokenValid.value) {
      startTokenCheck()
    }
  })

  // 组件卸载时停止检查
  onUnmounted(() => {
    stopTokenCheck()
  })

  return {
    isTokenValid,
    tokenExpiry,
    validateToken,
    redirectToLogin,
    parseToken
  }
}