const im = document.getElementById('im')
const menu = document.getElementById('menu')
const note = document.getElementById('note')
let noteTimer = null

window.pin.onData(url => { im.src = url })
document.getElementById('x').addEventListener('click', () => window.pin.close())

function showNote(t) {
  note.textContent = t
  note.classList.add('on')
  clearTimeout(noteTimer)
  noteTimer = setTimeout(() => note.classList.remove('on'), 1300)
}
window.pin.onNote(showNote)
// 滚轮缩放后主进程会回一个百分比，图本身没有滚动条，所以只能这样给反馈
window.pin.onZoomed(pct => showNote(pct + '%'))

let grew = false

function hideMenu() {
  if (menu.hidden) return
  menu.hidden = true
  // 菜单撑出来的那点尺寸还回去，图恢复铺满
  if (grew) {
    grew = false
    im.style.width = im.style.height = ''
    window.pin.shrink()
  }
}

document.addEventListener('contextmenu', e => {
  e.preventDefault()
  hideMenu()
  menu.hidden = false
  const w = menu.offsetWidth, h = menu.offsetHeight
  // 上一版这里写成了 menu.offsetWidth（菜单自己宽）去当窗口宽，
  // 于是 min(光标, 200-199-4) 恒等于 4 —— 菜单被钉死在左上角，不跟鼠标。
  // 只有"窗口本身就比菜单还小"时才把窗口长到装得下光标右下角这块菜单；
  // 大钉图不长窗口（否则右下会多出一条白边），改成在窗口内翻转夹取。
  const tooSmall = innerWidth < w + 8 || innerHeight < h + 8
  let availW = innerWidth, availH = innerHeight
  if (tooSmall) {
    availW = Math.max(innerWidth, e.clientX + w + 12)
    availH = Math.max(innerHeight, e.clientY + h + 12)
    grew = true
    im.style.width = innerWidth + 'px'
    im.style.height = innerHeight + 'px'
    window.pin.grow({ w: availW, h: availH })
  }
  let left = e.clientX, top = e.clientY
  if (left + w > availW - 4) left = Math.max(4, availW - w - 4)
  if (top + h > availH - 4) top = Math.max(4, availH - h - 4)
  menu.style.left = left + 'px'
  menu.style.top = top + 'px'
})
// 菜单开着的时候点别处要收掉（含拖窗口那一下，不然菜单会挂在图上）
document.addEventListener('pointerdown', e => { if (!menu.hidden && !e.target.closest('#menu')) hideMenu() })

menu.addEventListener('click', e => {
  const it = e.target.closest('.pin-menu-item')
  if (!it || it.classList.contains('off')) return
  const a = it.dataset.a
  hideMenu()
  if (a === 'copy') window.pin.copy()
  else if (a === 'save') window.pin.save()
  else if (a === 'reset') window.pin.reset()
  else if (a === 'quit') window.pin.close()
})

// 按住图拖窗口：用屏幕坐标算位移（窗口本身在动，clientX 会几乎不变，所以不能用它）
let drag = null
let dragRaf = 0
let last = { dx: 0, dy: 0 }
document.addEventListener('pointerdown', e => {
  if (e.button !== 0 || e.target.closest('#menu') || e.target.closest('#x')) return
  drag = { px: e.screenX, py: e.screenY }
  last = { dx: 0, dy: 0 }
  window.pin.dragStart()
  try { document.getElementById('wrap').setPointerCapture(e.pointerId) } catch (err) { /* 合成事件下可能没有这个指针 */ }
})
document.addEventListener('pointermove', e => {
  if (!drag) return
  last = { dx: e.screenX - drag.px, dy: e.screenY - drag.py }
  if (dragRaf) return
  dragRaf = requestAnimationFrame(() => {
    dragRaf = 0
    if (drag) window.pin.dragMove(last)
  })
})
document.addEventListener('pointerup', () => { if (drag) { drag = null; window.pin.dragEnd() } })
document.addEventListener('pointercancel', () => { if (drag) { drag = null; window.pin.dragEnd() } })

// 滚轮 = 缩放整张图（改的是窗口尺寸，图一直铺满，所以不会又出现留白）；向上滚是放大
addEventListener('wheel', e => {
  e.preventDefault()
  hideMenu()
  window.pin.zoom(e.deltaY < 0 ? 1 : -1)
}, { passive: false })

addEventListener('keydown', e => {
  if (e.key === 'Escape') { if (!menu.hidden) hideMenu(); else window.pin.close() }
  else if (e.key === '0') window.pin.reset()
})
