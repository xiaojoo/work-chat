// 全局自绘气泡，接管所有原生 title。
// 原生那个的毛病：要等约 1 秒才出、样式完全不可控、在无边框窗口里会被裁掉、深色/浅色主题下会糊成一片。
// 做法：元素一挂上 title 就把文案搬进 data-tip 并摘掉 title（顺手补 aria-label，别让读屏丢了名字），
//       悬停/聚焦时由页面上唯一的一个 .ua-tip 弹层显示。模板里照常写 title，不用改任何调用点。

const DELAY = 150
const MARGIN = 8

let host = null
let current = null
let timer = 0
let pressed = false   // 左键按着 = 正在做下一步操作（点击、拖动），这段时间不显示气泡

function ensureHost() {
  if (host && host.isConnected) return host
  host = document.createElement('div')
  host.className = 'ua-tip'
  host.setAttribute('role', 'tooltip')
  document.body.appendChild(host)
  return host
}

// 返回该元素要显示的文案；没有就返回 null
function claim(node) {
  if (!node || node.nodeType !== 1) return null
  const t = node.getAttribute('title')
  if (!t) return node.dataset.tip || null
  node.dataset.tip = t
  if (!node.getAttribute('aria-label')) node.setAttribute('aria-label', t)
  node.removeAttribute('title')
  return t
}

function show(node) {
  const text = claim(node)
  if (!text) return hide()
  const h = ensureHost()
  current = node
  h.textContent = text
  const r = node.getBoundingClientRect()
  const b = h.getBoundingClientRect()
  let x = r.x + r.width / 2 - b.width / 2
  x = Math.max(MARGIN, Math.min(x, innerWidth - b.width - MARGIN))
  let y = r.y - b.height - 8
  if (y < MARGIN) y = Math.min(innerHeight - b.height - MARGIN, r.bottom + 8)
  h.style.left = `${Math.round(x)}px`
  h.style.top = `${Math.round(y)}px`
  h.classList.add('on')
}

function hide() {
  current = null
  if (timer) { clearTimeout(timer); timer = 0 }
  if (host) host.classList.remove('on')
}

function schedule(node) {
  if (pressed) return          // 按住鼠标就是要做下一步操作了，别再飘气泡
  if (node === current) return
  // 指针一进来就把 title 摘走，不等延迟 —— 否则这 150ms 里原生气泡可能已经抢跑
  claim(node)
  hide()
  timer = setTimeout(() => { timer = 0; show(node) }, DELAY)
}

function tipTarget(from) {
  if (!from || !from.closest) return null
  return from.closest('[title], [data-tip]')
}

export function initUaTip() {
  if (!document.body || document.body.dataset.uaTip) return
  document.body.dataset.uaTip = '1'
  document.querySelectorAll('[title]').forEach(claim)

  const over = e => {
    // 在窗口外松开鼠标时 pointerup 收不到，靠下一次移动自愈，免得 pressed 卡住再也不出气泡
    if (pressed && !e.buttons) pressed = false
    const n = tipTarget(e.target); n ? schedule(n) : hide()
  }
  document.addEventListener('pointerover', over)
  // 按下就收，并且按住期间不再弹：拖动把手那种 setPointerCapture 会让 pointerover
  // 一直落在同一个元素上，只 hide 不挡 schedule 的话气泡会被抖回来
  document.addEventListener('pointerdown', () => { pressed = true; hide() })
  const release = () => { pressed = false; hide() }
  document.addEventListener('pointerup', release)
  document.addEventListener('pointercancel', release)
  // focusin 原来是直接 show：一次鼠标点击的事件顺序是 pointerdown → focusin → pointerup，
  // 所以点完一下，气泡反倒被这次 focus 叫回来了。现在只在键盘（Tab）移焦点时才显示。
  document.addEventListener('focusin', e => {
    if (pressed) return
    const n = tipTarget(e.target); if (n) show(n)
  })
  document.addEventListener('focusout', hide)
  // 滚动/缩放后元素已经不在原来的位置，气泡跟着飘不如直接收掉
  document.addEventListener('scroll', hide, true)
  window.addEventListener('resize', hide)

  // :title 绑定的值变了、或 Vue 新建元素时先写好 title 再插进来（这种属性变化 observer 看不到），
  // 所以除了属性观察，指针进入时还会兜底 claim 一次
  new MutationObserver(ms => {
    for (const m of ms) {
      const n = m.target
      if (n.nodeType !== 1 || !n.hasAttribute('title')) continue
      claim(n)
      if (n === current && !pressed) show(n)
    }
  }).observe(document.body, { attributes: true, attributeFilter: ['title'], subtree: true })
}
