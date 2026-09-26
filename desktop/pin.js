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

/* ---- 就地标注：改的就是这张钉图本身，不开第二个窗、不另出一张图 ----
   画布后备像素 = 图片原始尺寸，所以坐标就是图片像素、「完成」时底图和标注两张叠一块
   换回本窗的图 —— 右键里的复制 / 另存为拿到的就是改过的那份。 */
const cv = document.getElementById('cv')
const tools = document.getElementById('tools')
const txtIn = document.getElementById('txt')
const g2 = cv.getContext('2d')
let editing = false
let tool = null
let color = '#ff3b30'
let ops = []
let live = null

const clamp = (v, a, b) => Math.min(b, Math.max(a, v))
// 描边/字号按「图片像素 / 屏幕上看到的像素」放大：滚轮放大看图时线不会跟着变粗，缩小时也不会糊成一团
const scaleOf = () => (cv.clientWidth ? cv.width / cv.clientWidth : 1)
function pt(e) {
  const r = cv.getBoundingClientRect()
  const k = r.width ? cv.width / r.width : 1
  return { x: clamp((e.clientX - r.left) * k, 0, cv.width), y: clamp((e.clientY - r.top) * k, 0, cv.height) }
}
function drawOp(g, o) {
  g.strokeStyle = o.color
  g.fillStyle = o.color
  g.lineWidth = o.w
  if (o.t === 'rect') {
    g.strokeRect(Math.min(o.x0, o.x1), Math.min(o.y0, o.y1), Math.abs(o.x1 - o.x0), Math.abs(o.y1 - o.y0))
  } else if (o.t === 'arrow') {
    const a = Math.atan2(o.y1 - o.y0, o.x1 - o.x0)
    const L = Math.max(o.w * 3, Math.hypot(o.x1 - o.x0, o.y1 - o.y0) * 0.16)
    g.beginPath(); g.moveTo(o.x0, o.y0); g.lineTo(o.x1, o.y1); g.stroke()
    g.beginPath(); g.moveTo(o.x1, o.y1)
    g.lineTo(o.x1 - L * Math.cos(a - 0.42), o.y1 - L * Math.sin(a - 0.42))
    g.lineTo(o.x1 - L * Math.cos(a + 0.42), o.y1 - L * Math.sin(a + 0.42))
    g.closePath(); g.fill()
  } else if (o.t === 'pen') {
    g.beginPath()
    o.pts.forEach((p, i) => i ? g.lineTo(p.x, p.y) : g.moveTo(p.x, p.y))
    g.stroke()
  } else if (o.t === 'text') {
    g.font = o.size + 'px "Microsoft YaHei", system-ui, sans-serif'
    g.textBaseline = 'top'
    g.fillText(o.text, o.x, o.y)
  }
}
function redraw() {
  g2.clearRect(0, 0, cv.width, cv.height)
  g2.lineCap = 'round'
  g2.lineJoin = 'round'
  ops.forEach(o => drawOp(g2, o))
}
function syncTools() {
  tools.querySelectorAll('.tb[data-t]').forEach(x => x.classList.toggle('on', x.dataset.t === tool))
  tools.querySelectorAll('.td').forEach(x => x.classList.toggle('on', x.dataset.c === color))
  cv.classList.toggle('draw', !!tool)
  document.getElementById('undo').disabled = ops.length === 0
}
function enterEdit() {
  if (editing) return
  cv.width = im.naturalWidth || 1
  cv.height = im.naturalHeight || 1
  ops = []; tool = null; live = null
  editing = true
  document.body.classList.add('editing')
  tools.hidden = false
  syncTools()
  showNote('画几笔，点「完成」画回这张图')
}
// apply=false（ESC）就是丢掉这一轮标注，图上不留痕
function exitEdit(apply) {
  if (apply && ops.length) {
    const out = document.createElement('canvas')
    out.width = cv.width
    out.height = cv.height
    const g = out.getContext('2d')
    g.drawImage(im, 0, 0)
    g.drawImage(cv, 0, 0)
    const url = out.toDataURL('image/png')
    im.src = url
    // 主进程那份原始 dataURL 也要换，不然右键的复制/另存为还是改之前那张
    window.pin.setImage(url)
    showNote('已画回这张图')
  }
  editing = false
  tool = null; live = null; ops = []
  document.body.classList.remove('editing')
  tools.hidden = true
  txtIn.style.display = 'none'
  g2.clearRect(0, 0, cv.width, cv.height)
}
function startText(e) {
  const r = cv.getBoundingClientRect(), p = pt(e)
  txtIn.style.display = 'block'
  txtIn.style.left = (e.clientX - r.left) + 'px'
  txtIn.style.top = (e.clientY - r.top) + 'px'
  txtIn.dataset.x = p.x
  txtIn.dataset.y = p.y
  txtIn.value = ''
  setTimeout(() => txtIn.focus(), 0)
}
function commitText() {
  const v = txtIn.value.trim()
  txtIn.style.display = 'none'
  if (!v) return
  ops.push({ t: 'text', x: Number(txtIn.dataset.x), y: Number(txtIn.dataset.y),
    text: v, size: Math.max(14, Math.round(16 * scaleOf())), color })
  redraw()
  syncTools()
}
txtIn.addEventListener('keydown', e => {
  e.stopPropagation()
  if (e.key === 'Enter') { e.preventDefault(); commitText() }
  else if (e.key === 'Escape') { e.preventDefault(); txtIn.value = ''; txtIn.style.display = 'none' }
})
txtIn.addEventListener('blur', () => { if (txtIn.style.display === 'block') commitText() })

cv.addEventListener('pointerdown', e => {
  if (e.button !== 0 || !editing || !tool) return
  // 挡住 mousedown 的默认动作：否则刚 focus 的文字输入框会被抢走焦点、空提交一次
  e.preventDefault()
  if (tool === 'text') { startText(e); return }
  const p = pt(e), w = Math.max(2, Math.round(3 * scaleOf()))
  live = tool === 'pen' ? { t: 'pen', pts: [p], w, color }
    : { t: tool, x0: p.x, y0: p.y, x1: p.x, y1: p.y, w, color }
  ops.push(live)
  try { cv.setPointerCapture(e.pointerId) } catch (err) { /* 合成事件下可能没有这个指针 */ }
})
cv.addEventListener('pointermove', e => {
  if (!live) return
  const p = pt(e)
  if (live.t === 'pen') live.pts.push(p)
  else { live.x1 = p.x; live.y1 = p.y }
  redraw()
})
addEventListener('pointerup', () => { live = null })

tools.addEventListener('click', e => {
  const b = e.target.closest('.tb[data-t]')
  if (b) { tool = tool === b.dataset.t ? null : b.dataset.t; syncTools(); return }
  const d = e.target.closest('.td')
  if (d) { color = d.dataset.c; syncTools(); return }
  if (e.target.closest('#undo')) { ops.pop(); redraw(); syncTools(); return }
  if (e.target.closest('#done')) exitEdit(true)
})

let grew = false

function hideMenu() {
  if (menu.hidden) return
  menu.hidden = true
  // 菜单撑出来的那点尺寸还回去，图恢复铺满
  if (grew) {
    grew = false
    im.style.width = im.style.height = ''
    // 标注画布和图是叠在一起的两层，只把图缩回去会让画布比图大一圈（坐标就错开了）
    cv.style.width = cv.style.height = ''
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
    // 标注画布和图叠在一起，图锁尺寸了画布也得锁，否则两层错开、坐标对不上
    cv.style.width = innerWidth + 'px'
    cv.style.height = innerHeight + 'px'
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
  if (a === 'edit') enterEdit()
  else if (a === 'copy') window.pin.copy()
  else if (a === 'save') window.pin.save()
  else if (a === 'reset') window.pin.reset()
  else if (a === 'quit') window.pin.close()
})

// 按住图拖窗口：用屏幕坐标算位移（窗口本身在动，clientX 会几乎不变，所以不能用它）
let drag = null
let dragRaf = 0
let last = { dx: 0, dy: 0 }
document.addEventListener('pointerdown', e => {
  // 编辑态里选了工具，按住图就是"画"，不是"搬窗口"；工具条和文字框上也不该起拖
  if (e.button !== 0 || e.target.closest('#menu') || e.target.closest('#x')) return
  if (e.target.closest('#tools') || e.target.closest('#txt')) return
  if (editing && tool) return
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
  if (e.key === 'Escape') {
    // 编辑态里 ESC 是"退出编辑、丢掉这一轮"，不是把整张钉图关掉
    if (editing) { exitEdit(false); showNote('已退出编辑，这一轮没画回图上'); return }
    if (!menu.hidden) { hideMenu(); return }
    window.pin.close()
  }
  else if (e.key === '0') window.pin.reset()
})
