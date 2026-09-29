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

// 网关按 (userId, deviceId) 占一个连接槽，同槽会顶号，所以这个 id 必须"每个标签页一个、
// 同一标签页重连不变"。写死 'web' 的话：开两个标签页就是一直互踢，
// 而 sessionStorage 恰好是每标签页一份、刷新保留、新开标签页换新值。
function tabDeviceId() {
  const KEY = 'chat_device_id'
  try {
    let v = sessionStorage.getItem(KEY)
    if (!v) {
      v = 'web-' + Math.random().toString(36).slice(2, 8) + '-' + Date.now().toString(36)
      sessionStorage.setItem(KEY, v)
    }
    return v
  } catch (e) {
    // 隐私模式下 sessionStorage 会抛：退化成进程内随机，至少两个标签页不打架
    return 'web-' + Math.random().toString(36).slice(2, 8)
  }
}

let deviceId = tabDeviceId()

// 桌面壳里每个窗口一个 deviceId，盖掉网页端这个按标签页算出来的
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
