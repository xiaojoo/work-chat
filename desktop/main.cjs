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

async function startShot(sender) {
  if (shotWin && !shotWin.isDestroyed()) return { ok: false, busy: true }
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

function pinImage(dataUrl) {
  const img = nativeImage.createFromDataURL(dataUrl)
  const b = img.getSize()
  const disp = currentDisplay()
  // 等比缩进屏幕里，别单独夹宽高——那会把图拉变形
  const fit = Math.min(1, (disp.bounds.width - 120) / (b.width || 1), (disp.bounds.height - 120) / (b.height || 1))
  const w = Math.max(120, Math.round((b.width || 420) * fit))
  const h = Math.max(90, Math.round((b.height || 300) * fit))
  const win = new BrowserWindow({
    width: w, height: h,
    x: disp.bounds.x + Math.round((disp.bounds.width - w) / 2),
    y: disp.bounds.y + Math.round((disp.bounds.height - h) / 3),
    show: false, frame: false, transparent: false, hasShadow: true, skipTaskbar: true,
    alwaysOnTop: true, minimizable: false, maximizable: false, fullscreenable: false,
    webPreferences: shotPrefs()
  })
  win.setAlwaysOnTop(true, 'screen-saver')
  pinWins.push(win)
  win.once('ready-to-show', () => win.show())
  win.webContents.on('did-finish-load', () => win.webContents.send('pin:data', dataUrl))
  win.on('closed', () => { const i = pinWins.indexOf(win); if (i >= 0) pinWins.splice(i, 1) })
  win.loadFile(path.join(__dirname, 'pin.html'))
  log(`pinned ${w}x${h} from ${b.width}x${b.height}`)
  return { ok: true, w, h }
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

    // 截图：主窗口只能"发起"，抓屏/遮罩窗/剪贴板都在上面那几个函数里
    ipcMain.handle('chat:shot', (event) => startShot(event.sender))
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
    ipcMain.on('shot:pin', (event, dataUrl) => {
      pinImage(String(dataUrl || ''))
      tellOpener({ ok: true, action: 'pin' })
      closeShot()
    })
    ipcMain.on('shot:painted', () => { if (shotReveal) shotReveal() })
    ipcMain.on('shot:cancel', () => closeShot())
    ipcMain.on('pin:close', (event) => {
      const win = BrowserWindow.fromWebContents(event.sender)
      if (win && !win.isDestroyed()) win.close()
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
