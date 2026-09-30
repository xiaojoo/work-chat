// V3.0 远程控制这一摊的**界面侧**：发起 → 对方同意 → 拿到一次性临时票据 → 停止。
// 状态机单独成一个模块而不是塞进 Chat.vue，理由和 useCall 一样：
// 四五种帧、两边的角色、超时和票据有效期混在界面里，没人能读出"现在到底是谁在控制谁"。
//
// 这一期**没有传输**：网关那边的 Provider 是空实现，所以 handoff 是 null、
// channelError 会是 CONTROL_NOT_CONFIGURED。这两样都要在界面上说出来 ——
// "对方同意了"是真的，"屏幕已经过去了"不是，两者不能混成一句"控制中"。
import { ref, computed, getCurrentScope, onScopeDispose } from 'vue'
import { toast } from '../utils/ui'

// 网关回的错误码翻成人话，界面上不许出现裸机器码
const CONTROL_ERRORS = {
  SELF: '不能控制自己',
  SENDER_BUSY: '你已经有一场远程控制没结束',
  PEER_BUSY: '对方已经有一场远程控制没结束',
  PEER_OFFLINE: '对方不在线',
  NO_SUCH_SESSION: '这场控制已经结束了',
  NOT_YOURS: '这不是发给你的控制请求',
  ALREADY_ANSWERED: '对方已经答复过了',
  TOKEN_FAILED: '拿不到临时票据'
}

export function useControl({ send, onMessage, onEnded }) {
  // idle | out(我问了，等对方点头) | in(对方问我) | active(对方已同意)
  const phase = ref('idle')
  const ctl = ref(null)   // {id, peerId, channel, ttlSec, secs, noChannel}

  let tick = null

  const label = computed(() => {
    const c = ctl.value
    if (!c) return ''
    if (phase.value === 'out') return '等待对方同意…'
    if (phase.value === 'in') return '远程控制请求'
    if (phase.value === 'active') return c.noChannel ? '已同意（通道未接入）' : '远程控制中'
    return ''
  })

  // 谁在控制谁这一句必须说清楚：方向和"正在共享屏幕"那种中性话不一样，
  // 被控的那一方要一眼知道现在是别人在动自己的机器
  const who = computed(() => {
    const c = ctl.value
    if (!c || phase.value !== 'active') return ''
    return c.outgoing ? '你正在控制对方的屏幕' : '对方正在控制你的屏幕'
  })

  const time = computed(() => {
    const s = (ctl.value && ctl.value.secs) || 0
    return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`
  })

  function stopTick() {
    if (tick) { clearInterval(tick); tick = null }
  }

  function reset() {
    stopTick()
    phase.value = 'idle'
    ctl.value = null
  }

  function startTick() {
    stopTick()
    tick = setInterval(() => { if (ctl.value) ctl.value.secs = (ctl.value.secs || 0) + 1 }, 1000)
  }

  // 这一帧是不是说"我这一场"。信令是按人投的、不是按发起那一条连接投的：
  // 同一个人的多个页面都会收到，认错了就会有一页替另一页把控制停掉（通话那一路过同样的事）
  function isMine(d) {
    if (!ctl.value) return false
    // 本地 id 还没回填（刚点发起、ctl.id 是空串、等 ACK 那几毫秒）时，**不认任何带 id 的帧**。
    // 原来这里写的是 `!ctl.value.id` 直接放行，于是别人那一场结束推来的 STOPPED/REJECTED
    // 会把刚挂起来的"等待对方同意"当场撤掉 —— 网关那边因此不能给喊停的人也补一份收场帧。
    // 先把这里收紧，两端都推才是安全的。
    return !d.id || !ctl.value.id || d.id === ctl.value.id
  }

  function onFrame(msg) {
    const d = typeof msg.data === 'string' ? JSON.parse(msg.data) : (msg.data || {})
    switch (msg.type) {
      case 'CONTROL_REQUEST':
        // 同一场重复投递要认下来：网关的跨实例总线是按用户推的，一场请求可能到达两次，
        // 老逻辑会把第二次当成"新的一场"而自动拒掉 —— 于是自己把自己拒了。
        if (ctl.value && d.id && ctl.value.id === d.id) break
        if (phase.value !== 'idle') { send('CONTROL_REJECT', { id: d.id }); break }
        ctl.value = { id: d.id, peerId: d.peerId, secs: 0, ttlSec: 0, channel: '', outgoing: false }
        phase.value = 'in'
        break
      case 'CONTROL_ACK':
        if (d.status === 'FAILED') {
          toast(CONTROL_ERRORS[d.error] || ('远程控制没走通：' + (d.error || '未知原因')), 'error')
          reset()
          break
        }
        if (d.status === 'PENDING' && ctl.value) { ctl.value.id = d.id; break }
        if (d.already) { reset(); later(); break }
        /* 点头的是我，可 CONTROL_ACCEPTED 是发给对面那位的 —— 我自己这一侧只有这条 ack。
           不在这里转成 active，被控的那一方就永远停在"请求"那一格，
           而"对方正在控制你的屏幕"这句恰恰是最该让他看见的话 */
        if (d.status === 'ok' && phase.value === 'in' && d.id) {
          ctl.value = {
            id: d.id, peerId: ctl.value.peerId, secs: 0, outgoing: false,
            channel: d.channel || '', ttlSec: d.ttlSec || 0, noChannel: !!d.channelError
          }
          phase.value = 'active'
          startTick()
          // "同意远程控制"那一句是网关以**我的名义**写进会话的，推送只发给对面那位：
          // 我自己不补拉一次，就看不见自己刚点的那一下留下了什么
          later()
        }
        break
      case 'CONTROL_ACCEPTED':
        if (!isMine(d) || phase.value !== 'out') break
        // 同意是真的，通道有没有是另一件事：两处分开写，界面上不和成一句"控制中"
        ctl.value = {
          id: d.id, peerId: d.peerId, secs: 0, outgoing: true,
          channel: d.channel || '', ttlSec: d.ttlSec || 0,
          noChannel: !!d.channelError
        }
        phase.value = 'active'
        startTick()
        toast(d.channelError ? '对方已同意，但这台还没接远程控制通道' : '对方同意了', 'success')
        break
      case 'CONTROL_REJECTED':
        if (!isMine(d)) break
        toast('对方拒绝了', 'info'); reset(); later()
        break
      case 'CONTROL_TIMEOUT':
        if (!isMine(d)) break
        toast('对方没答复', 'info'); reset(); later()
        break
      case 'CONTROL_STOPPED':
        if (!isMine(d)) break
        toast('远程控制结束了', 'info'); reset(); later()
        break
    }
  }

  // 卸载/热更新时必须把自己从帧分发里摘掉：这个数组是模块级的，不摘就会累积，
  // 一条帧被处理两次 —— 远程控制那一路因此自己把自己拒了（界面上只看见"对方拒绝了"）
  const offFrame = onMessage(onFrame)
  // 只在确实有活动作用域时才挂注销：Chat.vue 的 setup 里有"await 完六个接口再挂处理器"
  // 那种历史写法，作用域那时可能已经停了 —— 直接 onScopeDispose 会当场执行，
  // 等于这一页从来没订阅过帧（实测：被控方收到了 CONTROL_REQUEST 却不处理，门禁从 14/16 掉到 9/16）
  if (getCurrentScope()) onScopeDispose(offFrame)

  // 网关那句系统提示是收场之后才写进库的，晚一点补拉，会话里才有那一行
  function later() {
    if (onEnded) setTimeout(onEnded, 500)
  }

  function request(peerId, conversationId = '') {
    if (phase.value !== 'idle') { toast('你已经有一场远程控制没结束', 'info'); return }
    if (!peerId) { toast('没带对方 id', 'error'); return }
    if (!send('CONTROL_REQUEST', {
      peerId: Number(peerId), conversationId: String(conversationId || '')
    }, `CR-${Date.now()}`)) {
      toast('没连上消息服务器，发不了', 'error'); return
    }
    phase.value = 'out'
    ctl.value = { id: '', peerId: Number(peerId), secs: 0, outgoing: true, channel: '', ttlSec: 0 }
  }

  function accept() {
    const c = ctl.value
    if (phase.value !== 'in' || !c) return
    if (!send('CONTROL_ACCEPT', { id: c.id }, `CA-${Date.now()}`)) {
      toast('没连上消息服务器', 'error'); reset()
    }
  }

  function refuse() {
    const c = ctl.value
    if (phase.value !== 'in' || !c) return
    send('CONTROL_REJECT', { id: c.id })
    reset()
    later()
  }

  function stop() {
    const c = ctl.value
    if (phase.value === 'idle' || !c) return
    if (c.id) send('CONTROL_STOP', { id: c.id })
    reset()
    later()
  }

  onScopeDispose(() => stopTick())

  return { phase, ctl, label, who, time, request, accept, refuse, stop }
}
