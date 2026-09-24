import axios from 'axios'
import { apiBase } from '../config'

// 文件与图片走消息服务的 REST，不过 WebSocket、也就不过网关的 64KB 读帧上限。
// 消息内容里只存对象键引用：{"fileId","name","size","contentType"}

const authHeaders = () => ({ Authorization: `Bearer ${localStorage.getItem('accessToken') || ''}` })
const objectUrls = new Map()

export async function uploadFile(file) {
  const form = new FormData()
  form.append('file', file)
  const { data } = await axios.post(`${apiBase('message')}/message/file/upload`, form, {
    headers: authHeaders(),
    timeout: 120000
  })
  return data
}

/** 新格式的文件/图片消息是 JSON 引用；历史消息是 data:开头的 base64，返回 null 让调用方走老逻辑 */
export function parseFileRef(content) {
  const s = String(content || '').trim()
  if (!s.startsWith('{') || !s.includes('"fileId"')) return null
  try {
    const o = JSON.parse(s)
    return o && o.fileId ? o : null
  } catch (e) {
    return null
  }
}

/** 侧栏/列表用的短预览；后端 last_message_id 只有 255 字符，不能直接塞原文 */
export function previewOf(content) {
  const ref = parseFileRef(content)
  if (ref) {
    const kind = String(ref.contentType || '').startsWith('image/') ? '[图片] ' : '[文件] '
    return kind + (ref.name || '文件')
  }
  const s = String(content || '')
  return s.length <= 100 ? s : s.slice(0, 100)
}

/** 下载要带令牌，<img src> 指不过去 —— 取回来转 objectURL 并缓存复用 */
export async function fileObjectUrl(fileId) {
  if (!fileId) return ''
  if (objectUrls.has(fileId)) return objectUrls.get(fileId)
  const r = await axios.get(`${apiBase('message')}/message/file/${fileId}`, {
    responseType: 'blob',
    headers: authHeaders(),
    timeout: 120000
  })
  const url = URL.createObjectURL(r.data)
  objectUrls.set(fileId, url)
  return url
}
