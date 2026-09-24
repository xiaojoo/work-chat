import axios from 'axios'
import { apiBase } from '../config'

const api = axios.create({
  baseURL: '/api',
  timeout: 10000
})

api.interceptors.request.use(config => {
  // 桌面壳里没有 vite 代理，按路径前缀挑服务；网页端 apiBase 返回 '/api' 等于不改行为
  config.baseURL = apiBase((config.url || '').startsWith('/group') ? 'group' : 'user')
  const token = localStorage.getItem('accessToken')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

api.interceptors.response.use(
  response => response.data,
  error => {
    if (error.response?.status === 401) {
      localStorage.clear()
      window.location.href = '/login'
    }
    return Promise.reject(error)
  }
)

export default api
