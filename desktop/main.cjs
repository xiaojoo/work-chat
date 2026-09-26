const { app, BrowserWindow, Tray, Menu, Notification, shell, ipcMain, protocol, net, nativeImage, dialog, desktopCapturer, clipboard, screen } = require('electron')
const path = require('path')
const fs = require('fs')
const crypto = require('crypto')
const { pathToFileURL } = require('url')

const DIST = path.join(__dirname, '..', 'frontend', 'chat-web', 'dist')
const DEV_URL = process.argv.includes('--vite-dev')
  ? (process.env.VITE_DEV_SERVER_URL || 'http://127.0.0.1:3000')
  : ''

// webContents id -> deviceId，preload 通过 ipc 拿自己那一份
const deviceIds = new Map()
let installId = ''
let windowSeq = 0
let tray = null
let quitting = false

const log = (...args) => console.log('[desktop]', new Date().toISOString().slice(11, 19), ...args)

function readInstallId() {
  const file = path.join(app.getPath('userData'), 'device-id')
  try {
    return fs.readFileSync(file, 'utf8').trim()
  } catch (e) {
    const id = crypto.randomBytes(6).toString('hex')
    fs.mkdirSync(path.dirname(file), { recursive: true })
    fs.writeFileSync(file, id)
    return id
  }
}

// 文件名是别的用户传上来的：可能带 "../x"、Windows 非法字符、或 CON/NUL 这类保留名，
// 洗干净才敢拼进下载目录
function safeName(name) {
  let s = path.basename(String(name || ''))
    .replace(/[<>:"/\\|?*]/g, '_')
    .replace(/\s+/g, ' ')
    .replace(/[. ]+$/, '')
    .trim()
  const stem = (s.split('.')[0] || '').toLowerCase()
  if (['con', 'prn', 'aux', 'nul'].includes(stem) || /^(com|lpt)[1-9]$/.test(stem)) s = '_' + s
  return s || '文件'
}

// 可执行 / 脚本类不自动"打开"——那等于替别人在他机器上跑代码，只落文件
const EXEC_EXT = new Set(['exe', 'bat', 'cmd', 'com', 'msi', 'msp', 'ps1', 'psm1', 'vbs', 'vbe',
  'js', 'jse', 'wsf', 'wsh', 'scr', 'cpl', 'hta', 'jar', 'reg', 'lnk', 'sh'])

// 落盘并返回真实路径：同名且内容一样（sha256）就复用那一份，不重不堆副本；
// 同名不同内容往后加 " (2)"，绝不覆盖已有文件
function placeFile(dir, name, buf) {
  const { name: stem, ext } = path.parse(name)
  const digest = crypto.createHash('sha256').update(buf).digest('hex')
  for (let i = 1; ; i++) {
    const p = i === 1 ? path.join(dir, name) : path.join(dir, `${stem} (${i})${ext}`)
    if (!fs.existsSync(p)) {
      fs.writeFileSync(p, buf)
      return p
    }
    if (crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex') === digest) return p
  }
}

// 没有现成图标文件，按 32x32 直接画一个：实心圆 + 一道斜环
function makeIcon() {
  const size = 32
  const rgba = Buffer.alloc(size * size * 4)
  const c = (size - 1) / 2
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const dx = (x - c) / c
      const dy = (y - c) / c
      const r = Math.hypot(dx, dy)
      const i = (y * size + x) * 4
      const inDisc = r <= 0.86
      const onRing = r > 0.9 && r <= 1.0 && Math.abs(dy - dx * 0.45) < 0.34
      if (inDisc || onRing) {
        const shade = inDisc ? 1 : 0.6
        rgba[i] = Math.round(38 * shade)
        rgba[i + 1] = Math.round(198 * shade)
        rgba[i + 2] = Math.round(218 * shade)
        rgba[i + 3] = 255
      }
    }
  }
  return nativeImage.createFromBitmap(rgba, { width: size, height: size })
}

// net.fetch(file://) 给不出正确的 MIME，woff2 会变成 application/octet-stream
// 而被 Chromium 拒绝应用（字体直接失效），所以按扩展名显式改写 content-type
const MIME = {
  '.html': 'text/html',
  '.js': 'text/javascript',
  '.css': 'text/css',
  '.json': 'application/json',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
  '.woff': 'font/woff',
  '.ttf': 'font/ttf'
}

function serve(file) {
  const type = MIME[path.extname(file).toLowerCase()]
  const fetched = net.fetch(pathToFileURL(file).toString())
  if (!type) return fetched
  return fetched.then(res => new Response(res.body, {
    status: res.status,
    statusText: res.statusText,
    headers: { 'content-type': type, 'access-control-allow-origin': '*' }
  }))
}

function createWindow() {
  windowSeq += 1
  const win = new BrowserWindow({
    width: 1280,
    height: 820,
    minWidth: 940,
    minHeight: 600,
    show: false,
    frame: false,
    icon: makeIcon(),
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      // 收进托盘后窗口不可见，Chromium 会把 setInterval 节流到 1 分钟一次，
      // 而网关心跳判定是 60s 读超时 / 90s 静止即踢，节流就会表现为莫名掉线
      backgroundThrottling: false
    }
  })

  deviceIds.set(win.webContents.id, `${installId}-w${windowSeq}`)
  log(`window #${windowSeq} deviceId=${installId}-w${windowSeq} mode=${DEV_URL ? 'dev-server' : 'app://'}`)

  win.once('ready-to-show', () => win.show())
  win.on('closed', () => deviceIds.delete(win.webContents.id))
  win.on('maximize', () => win.webContents.send('chat:win-state', true))
  win.on('unmaximize', () => win.webContents.send('chat:win-state', false))

  // 点关闭收进托盘而不是销毁：连接、未读、正在播放的提示音都不该被一次点击打断
  win.on('close', (event) => {
    if (!quitting) {
      event.preventDefault()
      win.hide()
    }
  })

  win.webContents.on('did-fail-load', (event, code, description, validatedUrl) => {
    log(`load failed ${code} ${description} ${validatedUrl}`)
  })
  win.webContents.on('did-finish-load', () => log(`loaded ${win.webContents.getURL()}`))

  // 页面上的 target=_blank / window.open 一律交给系统浏览器，不在客户端里开新窗口
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/.test(url)) shell.openExternal(url)
    return { action: 'deny' }
  })

  if (DEV_URL) {
    win.loadURL(DEV_URL)
  } else {
    // 必须加载根路径：vue-router 的 createWebHistory 拿 pathname 匹配路由，
    // 写成 /index.html 会一条路由都不命中，页面只剩背景、#app 空着
    win.loadURL('app://chat/')
  }
  return win
}

function focusMainWindow() {
  const win = BrowserWindow.getAllWindows().find(w => !w.isDestroyed())
  if (!win) return createWindow()
  if (win.isMinimized()) win.restore()
  win.show()
  win.focus()
  return win
}

function toggleWindow(win) {
  if (win.isVisible() && win.isFocused()) win.hide()
  else focusMainWindow()
}

/* ---- 截图 ----
   抓光标所在那张屏 → 开一层全屏遮罩（遮罩里放的就是刚抓到的这张"冻住的屏幕"）→
   框选 + 箭头/方框/文字/涂鸦 → 复制到剪贴板，或者钉成一个小窗悬浮在最上面。
   抓屏、开遮罩窗、写剪贴板都只能在主进程做（渲染端是 sandbox，既拿不到屏幕也写不了系统剪贴板） */
let shotWin = null
let shotOpener = null
let shotShown = false
let shotReveal = null
const pinWins = []
// 每个钉图窗的原始数据（截图那块）和当前缩放，右键菜单的复制/另存为/还原都从这取
const pinInfo = new WeakMap()

const shotPrefs = () => ({
  preload: path.join(__dirname, 'shot-preload.cjs'),
  contextIsolation: true,
  nodeIntegration: false,
  sandbox: true
})

/* 光标在哪个显示器上就用哪张屏。注意 API 名字：getDisplayMatching 收的是 **Rect**
   （要 x/y/width/height），传一个点会抛 "Error processing argument at index 0, conversion failure from"；
   要按点找屏得用 getDisplayNearestPoint。 */
function currentDisplay() {
  const cur = screen.getCursorScreenPoint()
  return screen.getDisplayNearestPoint({ x: Math.round(cur.x), y: Math.round(cur.y) })
}

/* 露窗/收窗都走一道淡入淡出：直接 show 会先给出一两帧窗口底色（没设 backgroundColor 时是白的，
   实测 4K 上有 4 帧 ~250 的白闪），直接 close 是硬切回桌面。setOpacity 步进 16ms 一帧。 */
function fadeShow(win) {
  win.setOpacity(0)
  win.show()
  win.focus()
  let o = 0
  const t = setInterval(() => {
    if (!win || win.isDestroyed()) { clearInterval(t); return }
    o += 0.25
    if (o >= 1) { win.setOpacity(1); clearInterval(t) }
    else win.setOpacity(o)
  }, 16)
}

function fadeClose(win) {
  if (!win || win.isDestroyed()) return
  let o = win.getOpacity()
  const t = setInterval(() => {
    if (!win || win.isDestroyed()) { clearInterval(t); return }
    o -= 0.25
    if (o <= 0) { clearInterval(t); win.close() }
    else win.setOpacity(o)
  }, 16)
}

/* 已经有一个截图/编辑窗开着：别只回一句"忙"。那个窗可能就在主窗口后面、他没看见，
   于是每次点编辑都没反应——把它调到眼前，他才知道要处理的是哪一扇。 */
function shotBusy() {
  log('截图/编辑窗已开着，把它调到前面')
  const w = shotWin
  if (w && !w.isDestroyed()) { if (!w.isVisible()) w.show(); w.focus() }
  return { ok: false, busy: true, error: '已经有一个截图/编辑窗开着，已把它调到最前面' }
}

async function startShot(sender) {
  // 每一条早退都要留一行日志：以前只有成功才 log，"点了没反应"那种情况在主进程这边查不到痕迹
  if (shotWin && !shotWin.isDestroyed()) return shotBusy()
  let disp, dpr, cur
  try {
    cur = screen.getCursorScreenPoint()
    disp = screen.getDisplayNearestPoint({ x: Math.round(cur.x), y: Math.round(cur.y) })
    dpr = disp.scaleFactor || 1
  } catch (e) {
    log(`display lookup failed: ${e.message}`)
    return { ok: false, error: '找不到当前显示器：' + String(e.message || e) }
  }
  const t0 = Date.now()
  shotOpener = sender ? BrowserWindow.fromWebContents(sender) : null
  const win = new BrowserWindow({
    x: disp.bounds.x, y: disp.bounds.y,
    width: disp.bounds.width, height: disp.bounds.height,
    show: false, frame: false, resizable: false, movable: false, minimizable: false,
    maximizable: false, fullscreenable: false, skipTaskbar: true, alwaysOnTop: true,
    // 不设的话窗口底色是白的：露窗前那两三帧就是整屏白闪（实测过）
    backgroundColor: '#000000',
    hasShadow: false, webPreferences: shotPrefs()
  })
  shotWin = win
  win.setAlwaysOnTop(true, 'screen-saver')
  // Electron 会把窗口夹到工作区（实测 2160 的屏只给 2112，底下 48px 是任务栏），
  // 遮罩就会把整张屏幕压扁 2.2%、裁出来的区域偏 48px。setBounds 的第二个参数可以拒绝这次夹取。
  win.setBounds(disp.bounds, { disallowWorkAreaClamping: true })
  win.on('closed', () => { if (shotWin === win) shotWin = null })
  const loaded = new Promise(res => win.webContents.once('did-finish-load', res))
  // 先让页面开始加载，再抓屏：这两段本来互不依赖，串行就是白等
  win.loadFile(path.join(__dirname, 'shot.html')).catch(e => log(`shot load failed: ${e.message}`))
  const tWin = Date.now()
  let bmp
  let tGot = 0
  try {
    const sources = await desktopCapturer.getSources({
      types: ['screen'],
      thumbnailSize: { width: Math.round(disp.size.width * dpr), height: Math.round(disp.size.height * dpr) }
    })
    tGot = Date.now()
    // 多屏时按 display_id 认领自己那张：sources 的顺序不保证跟显示器编号一致
    const src = sources.find(s => String(s.display_id) === String(disp.id)) || sources[0]
    if (!src) { closeShot(); return { ok: false, error: '没抓到任何屏幕' } }
    // 传原始 RGBA 位图，不传编码结果：4K 上 toPNG 要 490~530ms，而且是**同步跑在主进程**的，
    // 那半秒里整个客户端都停着（他说的"点一下卡一下"就是这个）。toBitmap 实测 4ms。
    bmp = src.thumbnail.toBitmap()
  } catch (e) {
    log(`capture failed: ${e.message}`)
    closeShot()
    return { ok: false, error: String(e.message || e) }
  }
  const tCap = Date.now()
  await loaded
  const tLoad = Date.now()
  if (win.isDestroyed()) return { ok: false, error: '遮罩窗被关掉了' }
  win.webContents.send('shot:data', {
    bmp,
    w: Math.round(disp.size.width * dpr), h: Math.round(disp.size.height * dpr),
    dpr,
    // 这台机器实测：Windows 的 toBitmap 是 BGRA，渲染端要换 R/B；别的平台没验过，就不瞎换
    bgra: process.platform === 'win32',
    // 不动鼠标也该立刻有放大镜，所以把光标位置一起送过去（换成窗口内的 DIP）
    cursor: { x: Math.round(cur.x - disp.bounds.x), y: Math.round(cur.y - disp.bounds.y) }
  })
  // 图真的画上一帧之后才淡入：早露一下就是整屏底色闪
  shotShown = false
  shotReveal = () => {
    if (shotShown || win.isDestroyed()) return
    shotShown = true
    fadeShow(win)
    log(`shot 共 ${Date.now() - t0}ms｜建窗 ${tWin - t0} 抓屏 ${tGot - tWin} 取位图 ${tCap - tGot} 传+画 ${Date.now() - tLoad}｜bmp=${bmp.length}`)
  }
  setTimeout(shotReveal, 1500)   // 渲染端万一没回话（图坏了），也别把截图卡死
  return { ok: true }
}

function closeShot() {
  fadeClose(shotWin)   // 关掉由窗口的 closed 事件负责置空
}

function tellOpener(payload) {
  if (shotOpener && !shotOpener.isDestroyed()) shotOpener.webContents.send('chat:shot-result', payload)
}

function pinImage(dataUrl, at) {
  const img = nativeImage.createFromDataURL(dataUrl)
  const b = img.getSize()
  const disp = currentDisplay()
  // 钉图就得是"截下来那一块"的原尺寸。以前用 Math.max(120, …)/Math.max(90, …) 兜底，
  // 小区域被撑成 120x90、图在里面上下留白，看着就是"默认框大小"（实测 120x80 → 窗口 120x90）。
  // 现在只在"比屏幕还大"时才等比缩，其余一律 1:1。
  let w = b.width || 420
  let h = b.height || 300
  const cap = Math.min(1, (disp.bounds.width - 40) / w, (disp.bounds.height - 40) / h)
  w = Math.max(1, Math.round(w * cap))
  h = Math.max(1, Math.round(h * cap))
  // at = 选区在屏幕上的左上角（DIP）。给了就留在原地，别摆到屏幕正中——截完图眼睛还盯着那块呢
  const px = at && Number.isFinite(at.x) ? Math.round(at.x) : disp.bounds.x + Math.round((disp.bounds.width - w) / 2)
  const py = at && Number.isFinite(at.y) ? Math.round(at.y) : disp.bounds.y + Math.round((disp.bounds.height - h) / 3)
  const win = new BrowserWindow({
    width: w, height: h, useContentSize: true,
    x: px, y: py,
    show: false, frame: false, transparent: false, hasShadow: true, skipTaskbar: true,
    alwaysOnTop: true, minimizable: false, maximizable: false, fullscreenable: false,
    resizable: true, backgroundColor: '#ffffff',
    webPreferences: shotPrefs()
  })
  win.setAlwaysOnTop(true, 'screen-saver')
  pinInfo.set(win, { dataUrl, w, h, zoom: 1 })
  pinWins.push(win)
  win.once('ready-to-show', () => fadeShow(win))
  win.webContents.on('did-finish-load', () => win.webContents.send('pin:data', dataUrl))
  win.on('closed', () => { const i = pinWins.indexOf(win); if (i >= 0) pinWins.splice(i, 1); pinInfo.delete(win); log('pin closed') })
  win.loadFile(path.join(__dirname, 'pin.html'))
  log(`pinned ${w}x${h} @${px},${py}${at ? '（留在选区位置）' : '（屏幕居中）'} ← 源图 ${b.width}x${b.height}${cap < 1 ? '（比屏幕大，等比缩到 ' + Math.round(cap * 100) + '%）' : ''}`)
  return { ok: true, w, h }
}

// 滚轮缩放：改的是窗口本身的大小，图始终铺满，所以不会出现留白；左上角不动，读内容时不跳。
// dir > 0 = 放大（向上滚），和微信/Snipaste 一致
function pinZoom(win, dir) {
  const info = pinInfo.get(win)
  if (!info || !win || win.isDestroyed()) return
  const z = Math.min(4, Math.max(0.25, info.zoom * (dir > 0 ? 1.1 : 1 / 1.1)))
  if (z === info.zoom) return
  info.zoom = z
  pinSizeTo(win, info.w * z, info.h * z)
  win.webContents.send('pin:zoomed', Math.round(z * 100))
}

function pinReset(win) {
  const info = pinInfo.get(win)
  if (!info || !win || win.isDestroyed()) return
  info.zoom = 1
  pinSizeTo(win, info.w, info.h)
  win.webContents.send('pin:zoomed', 100)
}

// 窗口按"图的自然尺寸 × 缩放"来定，左上角不动；disallowWorkAreaClamping 防止被夹回工作区
function pinSizeTo(win, w, h) {
  const b = win.getBounds()
  win.setBounds({ x: b.x, y: b.y, width: Math.max(1, Math.round(w)), height: Math.max(1, Math.round(h)) },
    { disallowWorkAreaClamping: true })
}

// 菜单比钉图本身还大时（小区域），窗口临时长到装得下菜单，图锁在原来的像素尺寸上不被拉伸；
// 菜单一收就缩回去。不这么做的话菜单会被 overflow:hidden 裁掉（实测 120x80 的钉图裁掉 200x221 的菜单）
function pinGrowForMenu(win, needW, needH) {
  const info = pinInfo.get(win)
  if (!info || !win || win.isDestroyed()) return
  pinSizeTo(win, Math.max(info.w * info.zoom, needW), Math.max(info.h * info.zoom, needH))
}

function pinShrinkBack(win) {
  const info = pinInfo.get(win)
  if (!info || !win || win.isDestroyed()) return
  pinSizeTo(win, info.w * info.zoom, info.h * info.zoom)
}

function buildTray() {
  tray = new Tray(makeIcon())
  tray.setToolTip('Chat')
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: '显示主窗口', click: () => focusMainWindow() },
    { label: '新建窗口', click: () => createWindow() },
    { type: 'separator' },
    { label: '退出', click: () => { quitting = true; app.quit() } }
  ]))
  tray.on('click', () => toggleWindow(focusMainWindow()))
}

if (!app.requestSingleInstanceLock()) {
  app.quit()
} else {
  app.on('second-instance', () => focusMainWindow())

  // 标准 scheme 才能让 vue-router 的 history 模式和绝对路径 /assets 在打包后照常工作
  protocol.registerSchemesAsPrivileged([
    { scheme: 'app', privileges: { standard: true, secure: true, supportFetchAPI: true } }
  ])

  app.whenReady().then(() => {
    installId = readInstallId()

    if (!DEV_URL) {
      protocol.handle('app', (request) => {
        const url = new URL(request.url)
        let pathname = decodeURIComponent(url.pathname)
        if (pathname === '/' || path.extname(pathname) === '') pathname = '/index.html'
        const file = path.resolve(DIST, '.' + pathname)
        // 挡掉 app://chat/../.. 这类越界读取
        if (!file.startsWith(path.resolve(DIST) + path.sep)) {
          return new Response('forbidden', { status: 403 })
        }
        if (!fs.existsSync(file)) {
          return serve(path.join(DIST, 'index.html'))
        }
        return serve(file)
      })
    }

    ipcMain.handle('chat:device-id', (event) => deviceIds.get(event.sender.id) || `${installId}-w?`)
    ipcMain.on('chat:win', (event, kind) => {
      const win = BrowserWindow.fromWebContents(event.sender)
      if (!win) return
      if (kind === 'min') win.minimize()
      else if (kind === 'max') win.isMaximized() ? win.unmaximize() : win.maximize()
      else if (kind === 'close') win.close()
    })
    ipcMain.handle('chat:win-maximized', (event) => {
      const win = BrowserWindow.fromWebContents(event.sender)
      return win ? win.isMaximized() : false
    })
    ipcMain.on('chat:notify', (event, { title, body }) => {
      log(`notify "${title}"`)
      const n = new Notification({ title: String(title || 'Chat'), body: String(body || '') })
      n.on('click', () => focusMainWindow())
      n.show()
    })
    // 「另存为」：渲染端在沙箱里，既弹不出系统保存对话框也不知道用户选了哪儿，
    // 所以字节由渲染端取好送过来，主进程弹框 + 写盘
    ipcMain.handle('chat:save', async (event, { name, bytes }) => {
      const win = BrowserWindow.fromWebContents(event.sender)
      const buf = Buffer.from(bytes || new Uint8Array())
      const { canceled, filePath } = await dialog.showSaveDialog(win, {
        defaultPath: path.basename(String(name || '文件')),
        filters: [{ name: '所有文件', extensions: ['*'] }]
      })
      if (canceled || !filePath) return { ok: false, canceled: true }
      try {
        fs.writeFileSync(filePath, buf)
        log(`saved ${filePath} (${buf.length} bytes)`)
        return { ok: true, path: filePath, size: buf.length }
      } catch (e) {
        log(`save failed: ${e.message}`)
        return { ok: false, error: String(e.message || e) }
      }
    })

    // 「下载到默认目录 + 用系统默认应用打开」：渲染端在沙箱里既拿不到下载目录，
    // 也没权限启动外部程序，所以字节送过来，主进程落盘再交给 shell.openPath。
    ipcMain.handle('chat:open-file', async (event, { name, bytes }) => {
      const buf = Buffer.from(bytes || new Uint8Array())
      let dir
      try {
        dir = app.getPath('downloads')
      } catch (e) {
        dir = app.getPath('home')
      }
      let target
      try {
        target = placeFile(dir, safeName(name), buf)
      } catch (e) {
        log(`open-file write failed: ${e.message}`)
        return { ok: false, error: String(e.message || e) }
      }
      const ext = path.extname(target).slice(1).toLowerCase()
      // 可执行/脚本类只落文件、不启动：那等于替别人在他机器上跑代码
      if (EXEC_EXT.has(ext)) {
        log(`refused to open ${target} (${ext})`)
        return { ok: true, path: target, dir, size: buf.length, opened: false, refused: true }
      }
      const err = await shell.openPath(target)
      log(`open-file ${target} (${buf.length} bytes) → ${err || 'ok'}`)
      return err
        ? { ok: false, path: target, dir, size: buf.length, error: err }
        : { ok: true, path: target, dir, size: buf.length, opened: true }
    })

    // 截图：主窗口只能"发起"，抓屏/遮罩窗/剪贴板都在上面那几个函数里
    ipcMain.handle('chat:shot', (event) => startShot(event.sender))
    // 钉图就地标注「完成」：改过的那份交回主进程存着，右键的复制/另存为才拿得到它
    // （图本来就在钉图窗里换掉了，这边只是把原始数据同步一份）
    ipcMain.on('pin:set-image', (event, dataUrl) => {
      const win = BrowserWindow.fromWebContents(event.sender)
      const info = win && pinInfo.get(win)
      const url = String(dataUrl || '')
      if (!info || !url.startsWith('data:image/')) return
      info.dataUrl = url
      log(`pin image edited → ${Math.round(url.length / 1024)}KB dataURL`)
    })
    ipcMain.on('shot:copy', (event, dataUrl) => {
      try {
        clipboard.writeImage(nativeImage.createFromDataURL(String(dataUrl || '')))
        log('shot copied to clipboard')
        tellOpener({ ok: true, action: 'copy' })
      } catch (e) {
        log(`clipboard failed: ${e.message}`)
        tellOpener({ ok: false, error: String(e.message || e) })
      }
      closeShot()
    })
    ipcMain.on('shot:pin', (event, payload) => {
      // 遮罩/编辑窗送过来的是 {url, at}：at 是选区在**自己窗口内**的 DIP 左上角，
      // 加上窗口自己的屏幕原点才是"截的那一块"在屏幕上的位置
      const url = typeof payload === 'string' ? payload : String(payload?.url || '')
      const sel = typeof payload === 'string' ? null : payload?.at
      const src = BrowserWindow.fromWebContents(event.sender)
      let at = null
      if (sel && src && !src.isDestroyed()) {
        const b = src.getBounds()
        at = { x: b.x + Number(sel.x), y: b.y + Number(sel.y) }
      }
      pinImage(url, at)
      tellOpener({ ok: true, action: 'pin' })
      closeShot()
    })
    ipcMain.on('shot:painted', () => { if (shotReveal) shotReveal() })
    ipcMain.on('shot:cancel', () => closeShot())
    ipcMain.on('pin:close', (event) => {
      const win = BrowserWindow.fromWebContents(event.sender)
      if (win && !win.isDestroyed()) win.close()
    })
    // 钉图窗的右键菜单项：复制 / 另存为 走主进程（渲染端在沙箱里，写不了剪贴板也弹不出保存框）
    ipcMain.on('pin:zoom', (event, dir) => pinZoom(BrowserWindow.fromWebContents(event.sender), dir > 0 ? 1 : -1))
    ipcMain.on('pin:reset', (event) => pinReset(BrowserWindow.fromWebContents(event.sender)))
    ipcMain.on('pin:grow', (event, n) => pinGrowForMenu(BrowserWindow.fromWebContents(event.sender), n.w, n.h))
    ipcMain.on('pin:shrink', (event) => pinShrinkBack(BrowserWindow.fromWebContents(event.sender)))
    // 手动拖窗：整块 app-region: drag 会把右键和滚轮挡在"非客户区"，所以拖动得自己实现
    ipcMain.on('pin:drag-start', (event) => {
      const win = BrowserWindow.fromWebContents(event.sender)
      const info = win && pinInfo.get(win)
      if (!info) return
      const b = win.getBounds()
      info.drag = { x: b.x, y: b.y }
    })
    ipcMain.on('pin:drag-move', (event, d) => {
      const win = BrowserWindow.fromWebContents(event.sender)
      const info = win && pinInfo.get(win)
      if (!info || !info.drag || win.isDestroyed()) return
      win.setPosition(Math.round(info.drag.x + d.dx), Math.round(info.drag.y + d.dy))
    })
    ipcMain.on('pin:drag-end', (event) => {
      const info = pinInfo.get(BrowserWindow.fromWebContents(event.sender))
      if (info) info.drag = null
    })
    ipcMain.on('pin:copy', (event) => {
      const win = BrowserWindow.fromWebContents(event.sender)
      const info = win && pinInfo.get(win)
      if (!info) return
      try {
        clipboard.writeImage(nativeImage.createFromDataURL(info.dataUrl))
        win.webContents.send('pin:note', '已复制')
      } catch (e) {
        win.webContents.send('pin:note', '复制失败')
        log(`pin copy failed: ${e.message}`)
      }
    })
    ipcMain.handle('pin:save', async (event) => {
      const win = BrowserWindow.fromWebContents(event.sender)
      const info = win && pinInfo.get(win)
      if (!info) return { ok: false, error: '这张钉图没有原始数据' }
      const buf = Buffer.from(String(info.dataUrl).split(',')[1] || '', 'base64')
      const name = `截图-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.png`
      const { canceled, filePath } = await dialog.showSaveDialog(win, {
        defaultPath: name, filters: [{ name: 'PNG 图片', extensions: ['png'] }]
      })
      if (canceled || !filePath) return { ok: false, canceled: true }
      try {
        fs.writeFileSync(filePath, buf)
        log(`saved ${filePath} (${buf.length} bytes)`)
        win.webContents.send('pin:note', '已保存')
        return { ok: true, path: filePath, size: buf.length }
      } catch (e) {
        log(`pin save failed: ${e.message}`)
        return { ok: false, error: String(e.message || e) }
      }
    })

    buildTray()
    createWindow()

    app.on('window-all-closed', () => {
      // 收进托盘时窗口是被 hide 而不是 close，所以这里不能直接退
      if (quitting && process.platform !== 'darwin') app.quit()
    })
    app.on('before-quit', () => { quitting = true })
  })
}
