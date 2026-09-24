import api from './index'

export function getConversationList() {
  const userId = localStorage.getItem('userId')
  return api.get('/conversation/list', { params: { userId } })
}

export function createConversation(targetUserId) {
  const userId = localStorage.getItem('userId')
  return api.post(`/conversation/create?userId=${encodeURIComponent(userId || '')}`, { targetUserId })
}

export function clearUnread(conversationId) {
  const userId = localStorage.getItem('userId')
  // 对于群聊，conversationId 格式是 "g123456"，需要提取数字部分
  let convId = conversationId
  if (typeof conversationId === 'string' && conversationId.startsWith('g')) {
    convId = parseInt(conversationId.substring(1), 10)
  }
  return api.post(`/conversation/unread/clear?userId=${encodeURIComponent(userId || '')}`, { conversationId: convId, userId })
}

export function deleteConversation(conversationId) {
  // 对于群聊，conversationId 格式是 "g123456"，需要提取数字部分
  let convId = conversationId
  if (typeof conversationId === 'string' && conversationId.startsWith('g')) {
    convId = parseInt(conversationId.substring(1), 10)
  }
  // 获取当前用户 ID，通过 URL 查询参数传递（避免自定义请求头被丢弃）
  const userId = localStorage.getItem('userId')
  return api.delete(`/conversation/${convId}?userId=${encodeURIComponent(userId || '')}`)
}
