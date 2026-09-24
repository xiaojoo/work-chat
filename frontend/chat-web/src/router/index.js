import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../stores/user'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/login/Login.vue')
  },
  {
    path: '/',
    name: 'Chat',
    component: () => import('../views/chat/Chat.vue'),
    meta: { requiresAuth: true }
  },
  {
    // 工作台各分区（首页/任务/内容/文档…），section 缺省为 home
    path: '/workbench/:section?',
    name: 'Workbench',
    component: () => import('../views/workbench/Workbench.vue'),
    meta: { requiresAuth: true }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  const userStore = useUserStore()

  // 检查是否需要认证
  if (to.meta.requiresAuth) {
    // 如果未登录，跳转到登录页
    if (!userStore.isLoggedIn) {
      // 如果是从 Token 过期跳转过来的，添加 expired 参数
      const expired = from.name ? 'true' : 'false'
      next({ path: '/login', query: { expired } })
      return
    }

    // 检查 Token 是否过期（简单的前端检查）
    try {
      const token = userStore.accessToken
      if (token) {
        const payload = JSON.parse(atob(token.split('.')[1]))
        const exp = payload.exp * 1000 // 转换为毫秒
        if (Date.now() > exp) {
          // Token 已过期，清除并跳转到登录页
          userStore.logout()
          next({ path: '/login', query: { expired: 'true' } })
          return
        }
      }
    } catch (e) {
      // Token 解析失败，可能是无效的 Token
      console.error('Token validation error:', e)
      userStore.logout()
      next({ path: '/login', query: { expired: 'true' } })
      return
    }
  }

  next()
})

export default router
