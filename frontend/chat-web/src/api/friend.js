import api from './index'

export function getFriendList() {
  return api.get('/friend/list')
}

export function addFriend(friendId) {
  return api.post('/friend/add', { friendId })
}

export function removeFriend(friendId) {
  return api.delete(`/friend/remove/${friendId}`)
}

export function checkFriend(friendId) {
  return api.get(`/friend/check/${friendId}`)
}
