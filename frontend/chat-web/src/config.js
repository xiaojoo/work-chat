// 网页端走 vite 代理（/api、/ws 同源），桌面壳里是 app:// 协议、没有代理层，
// 所以服务地址必须是绝对的：构建桌面版时给 VITE_API_ORIGIN / VITE_WS_BASE 赋值即可，
// 不给就跟网页完全一样，一行行为都不变。
const origin = import.meta.env.VITE_API_ORIGIN || ''
const PORTS = { user: 8081, group: 8084, message: 8083 }

export const isDesktop = typeof window !== 'undefined' && !!window.chatDesktop

export function apiBase(service) {
  return origin ? `${origin}:${PORTS[service]}/api` : '/api'
}

// 空字符串表示"和页面同源"，由 websocket 客户端自己从 location 推
export const WS_BASE = import.meta.env.VITE_WS_BASE || ''

let deviceId = 'web'

// 桌面壳里每个窗口一个 deviceId：网关的连接表是 map[userId][deviceId]，
// 同 deviceId 会互相顶号，所以不能沿用网页端写死的 'web'
export async function initDeviceId() {
  if (window.chatDesktop) {
    deviceId = await window.chatDesktop.deviceId()
  }
}

export function getDeviceId() {
  return deviceId
}

export function notifyDesktop(title, body) {
  if (window.chatDesktop) {
    window.chatDesktop.notify({ title, body })
  }
}
