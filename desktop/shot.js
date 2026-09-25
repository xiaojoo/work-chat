/* 遮罩窗里的框选 + 标注。坐标一律用 DIP（窗口自己的像素），只有导出时才乘 dpr 回到抓屏的
   物理分辨率——不乘的话 125%/150% 缩放屏上截出来的图会糊一圈。 */
const bg = document.getElementById('bg')
const sel = document.getElementById('sel')
const cv = document.getElementById('cv')
const g2 = cv.getContext('2d')
const bar = document.getElementById('bar')
const tip = document.getElementById('tip')
const txtIn = document.getElementById('txt')

let dpr = 1
let sx = 1
let sy = 1
let ready = false
let tool = null
let color = '#ff3b30'
let ops = []
let R = null
let act = null
// 编辑态（钉图/大图查看器点「编辑」进来）：底图是这张图本身，不是刚抓的屏幕，
// 所以不用框选那一步——整张图先当选中区，改完再复制/钉回去
let editing = false
/* 图在窗口里的位置和大小（DIP）。编辑态小图时窗口会比图大一圈（工具条实测 355x38，
   比图宽就得让工具条有地方待，否则右边那三颗按钮出窗、点不着），所以坐标不能直接拿
   clientX 当图内坐标，一律先减 OX/OY、再乘 sx/sy。抓屏态这三个就是整窗。 */
let OX = 0, OY = 0, IW = 0, IH = 0

function sizeCanvas() {
  cv.width = Math.round(innerWidth * dpr)
  cv.height = Math.round(innerHeight * dpr)
  g2.setTransform(dpr, 0, 0, dpr, 0, 0)
  g2.lineCap = 'round'
  g2.lineJoin = 'round'
}

// 图在窗口里摆哪儿：抓屏态就是整窗；编辑态小图时四周留一圈（工具条要有地方待）
function placeImg() {
  if (!editing) {
    OX = 0; OY = 0; IW = innerWidth; IH = innerHeight
    bg.style.left = bg.style.top = bg.style.width = bg.style.height = ''
    return
  }
  bg.style.left = OX + 'px'; bg.style.top = OY + 'px'
  bg.style.width = IW + 'px'; bg.style.height = IH + 'px'
}

window.shot.onData(async p => {
  dpr = p.dpr || 1
  editing = !!p.edit
  // 主进程送过来的是原始 RGBA 位图（4K 上 33MB，但 toBitmap 只 4ms；换成 toPNG 要 490ms，
  // 而且是同步跑在主进程上的——那半秒整个客户端都停着）。所以底图在这里自己拼。
  bg.width = p.w
  bg.height = p.h
  const bg2 = bg.getContext('2d')
  if (editing) {
    // 底图就是这张图（dataURL），解码直接画，不用过位图；图在窗口里摆哪儿由主进程算好送过来
    OX = p.img.x; OY = p.img.y; IW = p.img.w; IH = p.img.h
    const im = new Image()
    im.src = p.url
    try { await im.decode() } catch (e) { window.shot.cancel(); return }
    bg2.drawImage(im, 0, 0, bg.width, bg.height)
  } else {
    // Windows 上 toBitmap 给的是 **BGRA**，而 ImageData 要 RGBA：不换手整张底图就是蓝橙对调
    // （实测黄色文件夹图标在底图里变蓝、品牌蓝 #2b6be8 变 #e86b2b，就是他说的"黄色主调色"）。
    // 这道手必须放在渲染端换：主进程多干一遍 8.3M 像素的同步活，就又把它卡住了
    const u8 = new Uint8ClampedArray(p.bmp)
    if (p.bgra) {
      for (let i = 0; i < u8.length; i += 4) {
        const t = u8[i]; u8[i] = u8[i + 2]; u8[i + 2] = t
      }
    }
    bg2.putImageData(new ImageData(u8, p.w, p.h), 0, 0)
  }
  // 抓到的图是物理像素、窗口是 DIP（编辑态还要再减掉图在窗口里的那圈留白），
  // 用两者之比当映射系数：比直接信 devicePixelRatio 实在——窗口尺寸被任务栏吃掉过一次
  // （2160 的屏只给 2112），那时 dpr 没错但映射是错的
  placeImg()
  sx = bg.width / IW
  sy = bg.height / IH
  sizeCanvas()
  ready = true
  if (editing) {
    // 编辑态没有"先框一刀"这一步：整张图就是选区，四个角还在，要裁就拖角
    // 光标也不再是十字准星：那是"等着框选"的意思，这里已经选好了
    document.body.classList.add('editing')
    tip.style.display = 'none'
    R = { x: OX, y: OY, w: IW, h: IH }
    paintSel()
  } else {
    tip.style.display = 'block'
    // 鼠标进来之前不动就没有放大镜，所以按主进程给的初始光标位置先摆一次
    if (p.cursor) showLoupe({ clientX: p.cursor.x, clientY: p.cursor.y })
  }
  // 下一帧再报给主进程露窗：这时候图已经真的在屏上了，不会先闪一层黑
  requestAnimationFrame(() => window.shot.painted())
})
addEventListener('resize', () => { sizeCanvas(); redraw() })

const clamp = (v, a, b) => Math.min(b, Math.max(a, v))
// 光标位置一律夹到"图"那块矩形里（编辑态图四周有留白，不能把选区拖到留白上去）
const pt = e => ({ x: clamp(e.clientX, OX, OX + IW), y: clamp(e.clientY, OY, OY + IH) })
const inR = p => !!R && p.x >= R.x && p.x <= R.x + R.w && p.y >= R.y && p.y <= R.y + R.h

function paintSel() {
  if (!R) { sel.classList.remove('on'); bar.classList.remove('on'); cv.classList.remove('on'); return }
  sel.classList.add('on')
  sel.style.left = R.x + 'px'; sel.style.top = R.y + 'px'
  sel.style.width = R.w + 'px'; sel.style.height = R.h + 'px'
  sel.classList.toggle('tool-none', !tool)
  // 挑了画笔之后按住选区里头是"画"，不是"搬"，光标得跟着换
  sel.style.cursor = tool ? 'crosshair' : 'move'
  cv.classList.add('on')
  placeBar()
}

function placeBar() {
  if (!R) return
  bar.classList.add('on')
  const bw = bar.offsetWidth, bh = bar.offsetHeight
  let top = R.y + R.h + 8
  if (top + bh > innerHeight - 6) top = R.y - bh - 8
  if (top < 6) top = Math.min(innerHeight - bh - 6, R.y + R.h + 8)
  bar.style.top = top + 'px'
  bar.style.left = clamp(R.x + R.w / 2 - bw / 2, 6, Math.max(6, innerWidth - bw - 6)) + 'px'
  document.getElementById('undo').disabled = ops.length === 0
}

/* ---- 标注 ---- */
function stroke(c) { c.strokeStyle = color; c.fillStyle = color; c.lineWidth = 3 }

function drawOp(c, o) {
  stroke(c)
  if (o.t === 'rect') {
    c.strokeRect(Math.min(o.x0, o.x1), Math.min(o.y0, o.y1), Math.abs(o.x1 - o.x0), Math.abs(o.y1 - o.y0))
  } else if (o.t === 'arrow') {
    const a = Math.atan2(o.y1 - o.y0, o.x1 - o.x0), L = Math.max(9, Math.hypot(o.x1 - o.x0, o.y1 - o.y0) * 0.16)
    c.lineWidth = 3
    c.beginPath(); c.moveTo(o.x0, o.y0); c.lineTo(o.x1, o.y1); c.stroke()
    c.beginPath(); c.moveTo(o.x1, o.y1)
    c.lineTo(o.x1 - L * Math.cos(a - 0.42), o.y1 - L * Math.sin(a - 0.42))
    c.lineTo(o.x1 - L * Math.cos(a + 0.42), o.y1 - L * Math.sin(a + 0.42))
    c.closePath(); c.fill()
  } else if (o.t === 'pen') {
    c.beginPath()
    o.pts.forEach((p, i) => i ? c.lineTo(p.x, p.y) : c.moveTo(p.x, p.y))
    c.stroke()
  } else if (o.t === 'text') {
    c.font = o.size + 'px "Microsoft YaHei", system-ui, sans-serif'
    c.textBaseline = 'top'
    c.fillText(o.text, o.x, o.y)
  }
}

function redraw() {
  g2.clearRect(0, 0, innerWidth, innerHeight)
  ops.forEach(o => drawOp(g2, o))
}

function exportPng() {
  if (!R || R.w < 2 || R.h < 2) return ''
  const out = document.createElement('canvas')
  out.width = Math.max(1, Math.round(R.w * sx))
  out.height = Math.max(1, Math.round(R.h * sy))
  const c = out.getContext('2d')
  // 底图那块要减掉图在窗口里的偏移；标注画布 cv 是整窗的，所以它按窗口坐标取
  c.drawImage(bg, Math.round((R.x - OX) * sx), Math.round((R.y - OY) * sy), out.width, out.height, 0, 0, out.width, out.height)
  c.drawImage(cv, Math.round(R.x * dpr), Math.round(R.y * dpr), Math.round(R.w * dpr), Math.round(R.h * dpr),
    0, 0, out.width, out.height)
  return out.toDataURL('image/png')
}

function finish(kind) {
  const url = exportPng()
  if (!url) return
  if (kind === 'pin') window.shot.pin(url)
  else window.shot.copy(url)
}

/* ---- 手势 ---- */
document.addEventListener('pointerdown', e => {
  if (e.target.closest('#bar')) return
  if (e.target === txtIn) return
  if (e.button !== 0) return
  const p = pt(e)
  const h = e.target.dataset && e.target.dataset.h
  if (h) { act = { k: 'resize', h, s: p, r: { ...R } }; return }
  if (!R) { act = { k: 'select', s: p }; tip.style.display = 'none'; return }
  if (tool && inR(p)) {
    if (tool === 'text') { startText(p); return }
    act = { k: 'draw', o: tool === 'pen' ? { t: 'pen', pts: [p], color } : { t: tool, x0: p.x, y0: p.y, x1: p.x, y1: p.y, color } }
    return
  }
  if (!tool && inR(p)) act = { k: 'move', s: p, r: { ...R } }
  else act = { k: 'select', s: p }
})

document.addEventListener('pointermove', e => {
  if (!act) return
  const p = pt(e)
  if (act.k === 'select') {
    R = { x: Math.min(act.s.x, p.x), y: Math.min(act.s.y, p.y), w: Math.abs(p.x - act.s.x), h: Math.abs(p.y - act.s.y) }
    if (R.w > 3 && R.h > 3) paintSel()
  } else if (act.k === 'move') {
    R.x = clamp(act.r.x + (p.x - act.s.x), OX, OX + IW - act.r.w)
    R.y = clamp(act.r.y + (p.y - act.s.y), OY, OY + IH - act.r.h)
    paintSel()
  } else if (act.k === 'resize') {
    const r = act.r, x2 = r.x + r.w, y2 = r.y + r.h
    let nx = r.x, ny = r.y, nw = r.w, nh = r.h
    if (act.h.includes('w')) { nx = clamp(p.x, OX, x2 - 8); nw = x2 - nx }
    if (act.h.includes('e')) { nw = clamp(p.x, r.x + 8, OX + IW) - r.x }
    if (act.h.includes('n')) { ny = clamp(p.y, OY, y2 - 8); nh = y2 - ny }
    if (act.h.includes('s')) { nh = clamp(p.y, r.y + 8, OY + IH) - r.y }
    R = { x: nx, y: ny, w: nw, h: nh }
    paintSel()
  } else if (act.k === 'draw') {
    const o = act.o
    if (o.t === 'pen') o.pts.push(p)
    else { o.x1 = p.x; o.y1 = p.y }
    redraw()
    drawOp(g2, o)
  }
})

document.addEventListener('pointerup', () => {
  if (!act) return
  if (act.k === 'select' && (!R || R.w < 4 || R.h < 4)) { R = null; paintSel(); tip.style.display = 'block' }
  else if (act.k === 'draw') { ops.push(act.o); redraw() }
  else if (R) { ops = ops.filter(o => keepInR(o)); redraw() }
  act = null
  paintSel()
})

// 换选区时把落在外面的标注丢掉，不然导出会看不到、撤销又"没反应"
function keepInR(o) {
  const pts = o.t === 'pen' ? o.pts : [{ x: o.x0, y: o.y0 }, { x: o.x1, y: o.y1 }]
  const p = o.t === 'text' ? [{ x: o.x, y: o.y }] : pts
  return p.every(q => inR(q))
}

function startText(p) {
  txtIn.style.display = 'block'
  txtIn.style.left = p.x + 'px'; txtIn.style.top = p.y + 'px'
  txtIn.style.fontSize = '16px'
  txtIn.value = ''
  txtIn.dataset.x = p.x; txtIn.dataset.y = p.y
  setTimeout(() => txtIn.focus(), 0)
}
function commitText() {
  const v = txtIn.value.trim()
  txtIn.style.display = 'none'
  if (!v) return
  ops.push({ t: 'text', x: Number(txtIn.dataset.x), y: Number(txtIn.dataset.y), text: v, size: 16, color })
  redraw()
  placeBar()
}
txtIn.addEventListener('keydown', e => {
  e.stopPropagation()
  if (e.key === 'Enter') { e.preventDefault(); commitText() }
  else if (e.key === 'Escape') { txtIn.value = ''; txtIn.style.display = 'none' }
})
txtIn.addEventListener('blur', () => { if (txtIn.style.display === 'block') commitText() })

/* ---- 工具条 ---- */
bar.querySelectorAll('.b[data-t]').forEach(b => b.addEventListener('click', () => {
  tool = tool === b.dataset.t ? null : b.dataset.t
  bar.querySelectorAll('.b[data-t]').forEach(x => x.classList.toggle('on', x.dataset.t === tool))
  paintSel()
}))
bar.querySelectorAll('.dot').forEach(d => d.addEventListener('click', () => {
  color = d.dataset.c
  bar.querySelectorAll('.dot').forEach(x => x.classList.toggle('on', x === d))
}))
bar.querySelector('[data-c="#ff3b30"]').classList.add('on')
document.getElementById('undo').addEventListener('click', () => { ops.pop(); redraw(); placeBar() })
document.getElementById('copy').addEventListener('click', () => finish('copy'))
document.getElementById('pin').addEventListener('click', () => finish('pin'))
document.getElementById('cancel').addEventListener('click', () => window.shot.cancel())

document.addEventListener('dblclick', e => { if (R && inR(pt(e)) && !tool) finish('copy') })
document.addEventListener('keydown', e => {
  if (e.key === 'Escape') window.shot.cancel()
  else if (e.key === 'Enter' && R && document.activeElement !== txtIn) finish('copy')
})

/* ---- 放大镜：跟随光标的 15×15 源像素放大 10 倍 + 坐标 + 取色（微信截图那套） ---- */
const loupe = document.getElementById('loupe')
const lp = document.getElementById('lp')
const lpg = lp.getContext('2d')
const lpsw = document.getElementById('lpsw')
const lpcv = document.getElementById('lpcv')
const lpp = document.getElementById('lpp')
const lpsz = document.getElementById('lpsz')
const LP_SRC = 15, LP_BOX = 150
const scratch = document.createElement('canvas')
scratch.width = scratch.height = 1
const sg = scratch.getContext('2d', { willReadFrequently: true })

function colorAt(px, py) {
  sg.clearRect(0, 0, 1, 1)
  sg.drawImage(bg, px, py, 1, 1, 0, 0, 1, 1)
  const d = sg.getImageData(0, 0, 1, 1).data
  const hex = v => v.toString(16).padStart(2, '0')
  return '#' + hex(d[0]) + hex(d[1]) + hex(d[2])
}

function showLoupe(e) {
  // 挑好画笔就收起来：那时候要看的是准星和已经画上去的东西，不是像素格
  if (!ready || tool) { loupe.classList.remove('on'); return }
  const px = clamp(Math.round((e.clientX - OX) * sx), 0, bg.width - 1)
  const py = clamp(Math.round((e.clientY - OY) * sy), 0, bg.height - 1)
  const x0 = clamp(px - Math.floor(LP_SRC / 2), 0, Math.max(0, bg.width - LP_SRC))
  const y0 = clamp(py - Math.floor(LP_SRC / 2), 0, Math.max(0, bg.height - LP_SRC))
  const z = LP_BOX / LP_SRC
  lpg.imageSmoothingEnabled = false
  lpg.clearRect(0, 0, LP_BOX, LP_BOX)
  lpg.drawImage(bg, x0, y0, LP_SRC, LP_SRC, 0, 0, LP_BOX, LP_BOX)
  lpg.lineWidth = 1
  lpg.strokeStyle = 'rgba(255,255,255,.18)'
  lpg.beginPath()
  for (let i = 1; i < LP_SRC; i++) {
    lpg.moveTo(i * z + 0.5, 0); lpg.lineTo(i * z + 0.5, LP_BOX)
    lpg.moveTo(0, i * z + 0.5); lpg.lineTo(LP_BOX, i * z + 0.5)
  }
  lpg.stroke()
  // 中心那一格描红：对准的是哪个像素一目了然
  lpg.strokeStyle = '#ff3b30'
  lpg.lineWidth = 1.5
  lpg.strokeRect((px - x0) * z + 0.5, (py - y0) * z + 0.5, z, z)
  const hex = colorAt(px, py)
  // 色值 / 坐标 / 选区尺寸 分三行，不挤在一行里
  lpsw.style.background = hex
  lpcv.textContent = hex
  lpp.textContent = px + ',' + py
  lpsz.textContent = R ? Math.round(R.w) + ' × ' + Math.round(R.h) : ''
  lpsz.style.display = R ? 'block' : 'none'
  loupe.classList.add('on')
  // 默认挂光标右下；顶到屏幕边就翻到另一侧，不让自己被裁掉
  const w = loupe.offsetWidth, h = loupe.offsetHeight
  let x = e.clientX + 18, y = e.clientY + 18
  if (x + w > innerWidth - 4) x = e.clientX - w - 18
  if (y + h > innerHeight - 4) y = e.clientY - h - 18
  loupe.style.left = clamp(x, 4, Math.max(4, innerWidth - w - 4)) + 'px'
  loupe.style.top = clamp(y, 4, Math.max(4, innerHeight - h - 4)) + 'px'
}
document.addEventListener('pointermove', showLoupe)
document.addEventListener('pointerdown', showLoupe)
