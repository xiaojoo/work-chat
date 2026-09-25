const { app, BrowserWindow, Tray, Menu, Notification, shell, ipcMain, protocol, net, nativeImage, dialog } = require('electron')
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

    buildTray()
    createWindow()

    app.on('window-all-closed', () => {
      // 收进托盘时窗口是被 hide 而不是 close，所以这里不能直接退
      if (quitting && process.platform !== 'darwin') app.quit()
    })
    app.on('before-quit', () => { quitting = true })
  })
}
