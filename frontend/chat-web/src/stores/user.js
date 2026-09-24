import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { login, register, refreshToken } from '../api/auth'

export const useUserStore = defineStore('user', () => {
  const accessToken = ref(localStorage.getItem('accessToken') || '')
  const refreshTokenValue = ref(localStorage.getItem('refreshToken') || '')
  const userId = ref(localStorage.getItem('userId') || '')
  const username = ref(localStorage.getItem('username') || '')

  const isLoggedIn = computed(() => !!accessToken.value)

  async function doLogin(form) {
    const res = await login(form)
    setTokens(res)
    return res
  }

  async function doRegister(form) {
    const res = await register(form)
    setTokens(res)
    return res
  }

  function setTokens(data) {
    accessToken.value = data.accessToken
    refreshTokenValue.value = data.refreshToken
    userId.value = data.userId
    username.value = data.username

    localStorage.setItem('accessToken', data.accessToken)
    localStorage.setItem('refreshToken', data.refreshToken)
    localStorage.setItem('userId', data.userId)
    localStorage.setItem('username', data.username)
  }

  async function doRefreshToken() {
    const res = await refreshToken(refreshTokenValue.value)
    setTokens(res)
  }

  function logout() {
    accessToken.value = ''
    refreshTokenValue.value = ''
    userId.value = ''
    username.value = ''
    localStorage.clear()
  }

  return {
    accessToken,
    refreshToken: refreshTokenValue,
    userId,
    username,
    isLoggedIn,
    doLogin,
    doRegister,
    doRefreshToken,
    logout
  }
})
