import api from './index'

export function login(data) {
  return api.post('/auth/login', data)
}

export function register(data) {
  return api.post('/auth/register', data)
}

export function refreshToken(refreshToken) {
  return api.post('/auth/refresh', { refreshToken })
}
