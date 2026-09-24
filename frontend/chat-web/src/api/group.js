import api from './index'

export function createGroup(name, avatar, memberIds = [], extra = {}) {
  // 从 localStorage 获取当前用户 ID
  const userId = localStorage.getItem('userId')
  // announcement / groupType 只有新弹窗会传；老调用点不传即维持原行为
  return api.post('/group/create', {
    name, avatar, ownerId: userId, memberIds,
    ...(extra.announcement ? { announcement: extra.announcement } : {}),
    ...(extra.groupType ? { groupType: extra.groupType } : {})
  })
}

export function getGroup(groupId) {
  return api.get(`/group/${groupId}`)
}

export function updateGroup(groupId, data) {
  const userId = localStorage.getItem('userId')
  return api.put(`/group/${groupId}?userId=${encodeURIComponent(userId || '')}`, data)
}

export function dissolveGroup(groupId) {
  // 获取当前用户 ID 作为操作者
  const operatorId = localStorage.getItem('userId')
  if (!operatorId) {
    throw new Error('用户未登录或用户ID为空')
  }
  // 通过 URL 查询参数传递 userId（最稳妥，避免自定义请求头被代理丢弃）
  return api.delete(`/group/${groupId}?userId=${encodeURIComponent(operatorId)}`, {
    headers: { 'X-User-Id': operatorId }
  })
}

export function getMyGroups() {
  const userId = localStorage.getItem('userId')
  return api.get('/group/my', { params: { userId } })
}

export function getGroupMembers(groupId) {
  return api.get(`/group/${groupId}/members`)
}

export function inviteMembers(groupId, userIds) {
  // 获取当前用户 ID 作为操作者
  const operatorId = localStorage.getItem('userId')
  return api.post(`/group/${groupId}/invite`, { userIds, operatorId })
}

export function removeMember(groupId, targetUserId) {
  const userId = localStorage.getItem('userId')
  return api.delete(`/group/${groupId}/member/${targetUserId}?userId=${encodeURIComponent(userId || '')}`)
}

export function leaveGroup(groupId) {
  const userId = localStorage.getItem('userId')
  return api.post(`/group/${groupId}/leave?userId=${encodeURIComponent(userId || '')}`)
}

export function setMemberRole(groupId, targetUserId, role) {
  const userId = localStorage.getItem('userId')
  return api.put(`/group/${groupId}/member/${targetUserId}/role?userId=${encodeURIComponent(userId || '')}`, { role })
}

export function muteMember(groupId, targetUserId, muteUntil) {
  const userId = localStorage.getItem('userId')
  return api.post(`/group/${groupId}/member/${targetUserId}/mute?userId=${encodeURIComponent(userId || '')}`, { muteUntil })
}
