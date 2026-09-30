// 通话这一摊：信令走聊天网关的 CALL_* 帧，媒体直连 LiveKit（不经网关）。
// 抽成一个模块而不是塞进 Chat.vue，是因为状态机（响铃/应答/超时/两端各自的票）
// 和 LiveKit 的订阅回调混在界面里会没人能读出"到底连上了没有"。
import { ref, computed, getCurrentScope, onScopeDispose } from 'vue'
import { Room, RoomEvent, Track } from 'livekit-client'
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
  const peerSharing = ref(false)  // 对面这一路屏幕共享在不在
  /* 自己这两颗状态原本是 computed，里面读的是闭包变量 room —— Vue 追不到非响应式变量，
     这种 computed 只在第一次求值时算一遍就定住不动了。实测屏幕真发出去了、对面也看见了，
     自己这端的"你正在共享屏幕"和那颗按钮却一辈子写着"共享屏幕"。
     改成由 LiveKit 的发布/撤发布事件写进 ref，界面跟着事件走 */
  const micOn = ref(false)
  const sharing = ref(false)

  let room = null
  let tick = null
  let pending = null
  /** 真的进过房间没有。没进过房间的 Disconnected 不算"通话断了"——见 lkConnect 里那段注释 */
  let joined = false

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

  /* 这一帧说的到底是不是"我这一通"。同一个人可以有多个页面同时连着网关（多个标签页、
     桌面壳 + 浏览器），而信令是按人投的、不是按那条拨号的连接投的。不认这一条的话，
     一个根本没拨号的页面也会拿着刚收到的票进房 —— LiveKit 一间房里一个身份只留一条
     连接，后进的把先进的那条踢下线（reason=DUPLICATE_IDENTITY），被踢那页的 Disconnected
     又去收线，一通好好的通话就被第三个页面替它挂了。
     房间名是这里唯一的身份：call 没建起来的页面一定不是这通的一方。 */
  function isMine(d) {
    if (!call.value) return false
    return !d.room || !call.value.room || d.room === call.value.room
  }

  function reset() {
    stopTick()
    phase.value = 'idle'
    call.value = null
    peerSharing.value = false
    micOn.value = false
    sharing.value = false
    pending = null
    joined = false
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
    // 拿到票 ≠ 连上了：先写"接通中"，connect() 真回来才算"通话中"。
    // 原来这里直接置 live，计时器在还没连上就开始走，界面上"通话中 0:03"是假的
    phase.value = 'connecting'
    room = new Room({ adaptiveStream: true, dynacap: true })
    room.on(RoomEvent.TrackSubscribed, (track) => {
      /* 屏幕共享那一路也是视频轨道。原来只按 kind 分：Camera 挂视频、其余一律挂到那个
         不带控件的 audio 上——共享出去的屏幕就这么静默丢进 <audio>，对面只看得到一片黑。
         所以先按 source 分，屏幕那一路单独挂到大画面元素上。 */
      if (track.source === Track.Source.ScreenShare) {
        peerSharing.value = true
        if (els.screenVideo.value) track.attach(els.screenVideo.value)
      } else if (track.kind === 'Camera' && els.remoteVideo.value) {
        track.attach(els.remoteVideo.value)
      } else if (els.remoteAudio.value) {
        track.attach(els.remoteAudio.value)
      }
    })
    room.on(RoomEvent.TrackUnsubscribed, (track) => {
      if (track.source === Track.Source.ScreenShare) {
        peerSharing.value = false
        try { track.detach() } catch (e) { /* 元素已经不在了 */ }
      }
    })
    // 自己这一路用发布事件挂：v2 里没有稳当的 getVideoTracks()，发布回调上的 kind/track 才是定的
    room.on(RoomEvent.LocalTrackPublished, (pub) => {
      if (pub.source === Track.Source.ScreenShare) sharing.value = true
      else if (pub.kind === 'Microphone') micOn.value = true
      if (pub.source === Track.Source.ScreenShare && els.screenVideo.value && pub.track) {
        pub.track.attach(els.screenVideo.value)
      } else if (pub.kind === 'Camera' && els.localVideo.value && pub.track) {
        pub.track.attach(els.localVideo.value)
      }
    })
    // 自己这一路撤发布：静音/停止共享都在这把 ref 落回 false（按钮和状态字靠它）。
    // 事件名是 LocalTrackUnpublished —— livekit-client 2.22.3 里没有 LocalTrackUnsubscribed，
    // 写错的那个键 room.on(undefined) 不报错，只是永远不响
    room.on(RoomEvent.LocalTrackUnpublished, (pub) => {
      if (pub.source === Track.Source.ScreenShare) sharing.value = false
      else if (pub.kind === 'Microphone') micOn.value = false
    })
    room.on(RoomEvent.Disconnected, () => {
      /* 只有真进过房间，Disconnected 才算"通话断了"。还在 connect() 里的时候它可能只是
         LiveKit 内部换了一次传输，那时 teardown 会把正在 await 的 connect 自己掐掉，
         报错就成了"连不上语音服务器：Client initiated disconnect"——那是我们自己掐的，
         不是服务器的问题（实测被叫这一路正好就是这么没的） */
      if (joined && phase.value === 'live') teardown('CALL_HANGUP', { room: call.value?.room })
    })
    // 卡在哪一步要说得出来：连上服务器 / 开摄像头 / 开麦克风是三件事，
    // 原来只印一句 e.message，被叫那路永远是一条没头没尾的 "Client initiated disconnect"
    let step = '连上语音服务器'
    try {
      await room.connect(d.url, d.token)
      joined = true
      if (call.value.mediaType === 'VIDEO') {
        step = '打开摄像头'
        await room.localParticipant.setCameraEnabled(true)
      }
      step = '打开麦克风'
      await room.localParticipant.setMicrophoneEnabled(true)
      step = ''
      phase.value = 'live'
    } catch (e) {
      // 麦克风/摄像头被拒是用户要看见的事，不是可以吞掉的异常
      const why = [e && e.name, e && e.message, e && e.error && e.error.reason, e && e.reason]
        .filter(Boolean).join(' / ')
      const state = room ? room.connectionState : '?'
      console.warn('[call] 接通失败', { step, why, state, joined, room: d.room })
      const perm = /Permission|NotAllowed|denied/i.test(why)
      toast(perm ? '浏览器没给麦克风/摄像头权限，通话没起来'
                 : `${step}这一步没成：${why}（连接状态 ${state}）`, 'error')
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
          break
        }
        // 主叫要到这条回信里才知道房间名。不记下来，后面每一处"是不是我这一通"的
        // 判定都只能看 call.room=undefined，取消/挂断发出去的 room 也是 undefined
        if (d.status === 'RINGING' && call.value && d.room) call.value.room = d.room
        break
      case 'CALL_ACCEPTED':
        if (!isMine(d)) break
        if (d.status === 'FAILED') { toast(CALLED_ERRORS[d.error] || '对方没接进来', 'error'); reset(); break }
        lkConnect(d)
        break
      case 'CALL_REJECTED':
        if (!isMine(d)) break
        toast('对方拒绝了', 'info'); teardown('CALL_HANGUP', { room: d.room }); break
      case 'CALL_CANCELLED':
        if (phase.value === 'in') { toast('对方取消了呼叫', 'info'); reset() }
        break
      case 'CALL_TIMEOUT':
        if (phase.value === 'out') { toast('对方没接', 'info'); teardown('CALL_HANGUP', { room: d.room }) }
        break
      case 'CALL_SHARE':
        // 对面那一路屏幕的开关。状态以信令为准（轨道订阅回调也置一次，两处谁先到都算）
        if (!isMine(d)) break
        peerSharing.value = !!d.on
        toast(d.on ? '对方开始共享屏幕' : '对方停止了共享屏幕', 'info')
        break
      case 'CALL_HANGUP':
        if (d.room && call.value && d.room === call.value.room) {
          toast(phase.value === 'live' ? '对方已挂断' : '通话结束', 'info')
          teardown(null)
        }
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
      // 告诉网关一声：对面要知道、这条会话里也要留下那一句。
      // 共享成功的标志不是"我这按钮变了"，而是"对方看见了"——少这一步就只有我知道我在共享
      send('CALL_SHARE', { room: (call.value || {}).room, on: !on })
      toast(on ? '已停止共享屏幕' : '正在共享屏幕', 'success')
    } catch (e) {
      const m = String(e && e.message || e)
      if (!m.includes('denied') && !m.includes('AbortError')) toast('共享屏幕失败：' + m, 'error')
    }
  }

  onScopeDispose(() => {
    stopTick()
    if (room) {
      /* 连接中途被 dispose 会把 connect() 掐成 "Client initiated disconnect"，
         界面上只剩一句没头没尾的报错。留一行日志：谁在通话还没落地的时候把这页拆了。 */
      console.warn('[call] 组件卸载时通话还没落地，主动断开', { phase: phase.value, joined })
      try { room.disconnect() } catch (e) { /* 已经断了 */ }
    }
  })

  return {
    phase, call, label, inCall, peerSharing,
    invite, accept, reject, cancel, hangup, toggleMic, toggleShare,
    micOn, sharing
  }
}
