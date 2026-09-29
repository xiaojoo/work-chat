// 通话这一摊：信令走聊天网关的 CALL_* 帧，媒体直连 LiveKit（不经网关）。
// 抽成一个模块而不是塞进 Chat.vue，是因为状态机（响铃/应答/超时/两端各自的票）
// 和 LiveKit 的订阅回调混在界面里会没人能读出"到底连上了没有"。
import { ref, computed, onScopeDispose } from 'vue'
import { Room, RoomEvent } from 'livekit-client'
import { toast } from '../utils/ui'

// 网关回的错误码翻成人话；界面上不许出现裸机器码
const CALLED_ERRORS = {
  LIVEKIT_NOT_CONFIGURED: '这台还没接 LiveKit，打不了',
  LIVEKIT_UNREACHABLE: 'LiveKit 连不上，通话没起来',
  CALLEE_OFFLINE: '对方不在线',
  CALLEE_BUSY: '对方正在另一通通话里',
  CALLER_BUSY: '你已经在一通通话里了',
  SELF: '不能给自己打',
  TOKEN_FAILED: '拿票失败'
}

// els 是界面上的三个媒体元素 ref：Vue 的模板 ref 只认 setup 里的同名变量，
// 由模块自己持有就等于挂不上去
export function useCall({ send, onMessage, els, onEnded }) {
  // idle | out | connecting | in | live
  const phase = ref('idle')
  const call = ref(null)          // {room, peerId, mediaType, status, secs}

  let room = null
  let tick = null
  let pending = null

  const inCall = computed(() => phase.value === 'live' || phase.value === 'connecting')
  const label = computed(() => {
    const c = call.value
    if (!c) return ''
    if (phase.value === 'out') return c.mediaType === 'VIDEO' ? '正在呼叫（视频）…' : '正在呼叫…'
    if (phase.value === 'in') return '来电'
    if (phase.value === 'connecting') return '接通中…'
    if (phase.value === 'live') return c.mediaType === 'VIDEO' ? '视频通话中' : '语音通话中'
    return ''
  })

  function stopTick() {
    if (tick) { clearInterval(tick); tick = null }
  }

  function reset() {
    stopTick()
    phase.value = 'idle'
    call.value = null
    pending = null
  }

  async function teardown(msgType, data) {
    if (room) {
      try { await room.disconnect() } catch (e) { /* 已经断了就算过 */ }
      room = null
    }
    if (msgType) send(msgType, data)
    reset()
    // 网关那句系统提示是收线之后才写进库的，晚一点补拉，界面才有"通话时长/未接听"这一行
    if (onEnded) setTimeout(onEnded, 500)
  }

  function startTick() {
    stopTick()
    tick = setInterval(() => { if (call.value) call.value.secs = (call.value.secs || 0) + 1 }, 1000)
  }

  // 连 LiveKit：票是网关在 CALL_ACCEPTED 里给的，房间已经由网关建好
  async function lkConnect(d) {
    if (!d || !d.token || !d.url) { toast('没拿到通话票据', 'error'); reset(); return }
    call.value = { room: d.room, peerId: d.peerId, mediaType: d.mediaType || 'AUDIO', secs: 0 }
    phase.value = 'live'
    room = new Room({ adaptiveStream: true, dynacap: true })
    room.on(RoomEvent.TrackSubscribed, (track) => {
      // 远端的摄像头轨道挂到视频元素上，麦克风轨道挂到一个不带控件的 audio 上
      if (track.kind === 'Camera' && els.remoteVideo.value) track.attach(els.remoteVideo.value)
      else if (els.remoteAudio.value) track.attach(els.remoteAudio.value)
    })
    // 自己这一路用发布事件挂：v2 里没有稳当的 getVideoTracks()，发布回调上的 kind/track 才是定的
    room.on(RoomEvent.LocalTrackPublished, (pub) => {
      if (pub.kind === 'Camera' && els.localVideo.value && pub.track) pub.track.attach(els.localVideo.value)
    })
    room.on(RoomEvent.Disconnected, () => { if (phase.value === 'live') teardown('CALL_HANGUP', { room: call.value?.room }) })
    try {
      await room.connect(d.url, d.token)
      if (call.value.mediaType === 'VIDEO') await room.localParticipant.setCameraEnabled(true)
      await room.localParticipant.setMicrophoneEnabled(true)
    } catch (e) {
      // 麦克风/摄像头被拒是用户要看见的事，不是可以吞掉的异常
      const m = String(e && e.message || e)
      toast(m.includes('Permission') || m.includes('PermissionDenied')
        ? '浏览器没给麦克风/摄像头权限，通话没起来' : '连不上语音服务器：' + m, 'error')
      await teardown(null)
      return
    }
    startTick()
  }

  function onFrame(msg) {
    const d = typeof msg.data === 'string' ? JSON.parse(msg.data) : (msg.data || {})
    switch (msg.type) {
      case 'CALL_INVITE':
        if (phase.value !== 'idle') { send('CALL_REJECT', { room: d.room }); break }
        // 被叫侧也得当场建这条：来电那一格要写"来电 + 对方是谁"，
        // 等 accept 才建的话响铃时界面上只有两颗按钮，没人知道是谁打来的
        call.value = {
          room: d.room, peerId: d.peerId,
          mediaType: d.mediaType || 'AUDIO', secs: 0
        }
        phase.value = 'in'
        pending = d
        break
      case 'CALL_ACK':
        if (d.status === 'FAILED') {
          toast(CALLED_ERRORS[d.error] || ('通话没起来：' + (d.error || '未知原因')), 'error')
          reset()
        }
        break
      case 'CALL_ACCEPTED':
        if (d.status === 'FAILED') { toast(CALLED_ERRORS[d.error] || '对方没接进来', 'error'); reset(); break }
        lkConnect(d)
        break
      case 'CALL_REJECTED':
        toast('对方拒绝了', 'info'); teardown('CALL_HANGUP', { room: d.room }); break
      case 'CALL_CANCELLED':
        if (phase.value === 'in') { toast('对方取消了呼叫', 'info'); reset() }
        break
      case 'CALL_TIMEOUT':
        if (phase.value === 'out') { toast('对方没接', 'info'); teardown('CALL_HANGUP', { room: d.room }) }
        break
      case 'CALL_HANGUP':
        if (d.room && call.value && d.room === call.value.room) {
          toast(phase.value === 'live' ? '对方已挂断' : '通话结束', 'info')
          teardown(null)
        }
        break
    }
  }

  onMessage(onFrame)

  function invite(peerId, mediaType = 'AUDIO', conversationId = '') {
    if (phase.value !== 'idle') { toast('你已经在一通通话里', 'info'); return }
    if (!peerId) { toast('没带对方 id', 'error'); return }
    // 会话 id 一并带上：网关收线时要在**这条会话里**留一句系统提示，
    // 没有它就没地方写那句"未接听 / 通话时长"
    if (!send('CALL_INVITE', {
      peerId: Number(peerId), mediaType, conversationId: String(conversationId || '')
    }, `CI-${Date.now()}`)) {
      toast('没连上消息服务器，打不了', 'error'); return
    }
    phase.value = 'out'
    call.value = { peerId: Number(peerId), mediaType, secs: 0 }
  }

  function accept() {
    if (phase.value !== 'in' || !pending) return
    // 被叫这边也得有 call 这条：收线全靠 room 名，不建就等于发出去的是 undefined
    call.value = {
      room: pending.room, peerId: pending.peerId,
      mediaType: pending.mediaType || 'AUDIO', secs: 0
    }
    phase.value = 'connecting'
    if (!send('CALL_ACCEPT', { room: pending.room }, `CA-${Date.now()}`)) {
      toast('没连上消息服务器', 'error'); reset()
    }
  }

  function reject() {
    if (phase.value !== 'in' || !pending) return
    send('CALL_REJECT', { room: pending.room })
    reset()
    // 拒绝也会在那条会话里留一句"已拒绝"；这条路不经 teardown，得自己补拉一次
    if (onEnded) setTimeout(onEnded, 500)
  }

  function cancel() {
    if (phase.value !== 'out') return
    teardown('CALL_HANGUP', { room: (call.value || {}).room })
  }

  function hangup() {
    if (phase.value === 'idle') return
    teardown('CALL_HANGUP', { room: (call.value || {}).room })
  }

  async function toggleMic() {
    if (!room) return
    const on = room.localParticipant.isMicrophoneEnabled
    try { await room.localParticipant.setMicrophoneEnabled(!on) } catch (e) { toast('麦克风切换失败', 'error') }
  }

  async function toggleShare() {
    if (!room) return
    const on = room.localParticipant.isScreenShareEnabled
    try {
      await room.localParticipant.setScreenShareEnabled(!on)
      toast(on ? '已停止共享屏幕' : '正在共享屏幕', 'success')
    } catch (e) {
      const m = String(e && e.message || e)
      if (!m.includes('denied') && !m.includes('AbortError')) toast('共享屏幕失败：' + m, 'error')
    }
  }

  onScopeDispose(() => {
    stopTick()
    if (room) { try { room.disconnect() } catch (e) {} }
  })

  return {
    phase, call, label, inCall,
    invite, accept, reject, cancel, hangup, toggleMic, toggleShare,
    micOn: computed(() => !!(room && room.localParticipant.isMicrophoneEnabled)),
    sharing: computed(() => !!(room && room.localParticipant.isScreenShareEnabled))
  }
}
