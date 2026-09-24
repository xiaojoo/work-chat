import api from './index'

export function getUserProfile(userId) {
  return api.get(`/user/${userId}`)
}

export function searchUser(username) {
  return api.get('/user/search', { params: { username } })
}

export function searchUsersByKeyword(keyword) {
  return api.get('/user/search/users', { params: { keyword } })
}

export function getMe() {
  return api.get('/user/me')
}

/** 组织树：[{department, count, members:[...]}]，「添加用户」左侧结构 */
export function getOrg() {
  return api.get('/user/org')
}

/** 当前在线的用户 id 列表（网关写 Redis，chat-user 读出来） */
export function getOnline() {
  return api.get('/user/online')
}

export function updateProfile(data) {
  return api.put('/user/profile', data)
}

export function changePassword(oldPassword, newPassword) {
  return api.post('/user/change-password', { oldPassword, newPassword })
}
