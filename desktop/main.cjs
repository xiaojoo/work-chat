const { app, BrowserWindow, Tray, Notification, shell, ipcMain, protocol, net, nativeImage, dialog, desktopCapturer, clipboard, screen, session } = require('electron')
const path = require('path')
const fs = require('fs')
const crypto = require('crypto')
const { execFileSync, spawn } = require('child_process')
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
let mainWin = null

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

// 接收目录（设置 → 文档路径）：存在本机 userData 里，不跟账号走。
// 默认是"安装程序根目录下新建的那个文件夹"——开发跑 electron . 时根目录就是 desktop/，
// 打包后是 exe 所在目录；直接拿 dirname(exe) 在开发态会量到 electron 自己的 dist 里，所以分开取。
const RECV_DIR_NAME = '接收文件'
function defaultRecvDir() {
  const root = app.isPackaged ? path.dirname(app.getPath('exe')) : app.getAppPath()
  return path.join(root, RECV_DIR_NAME)
}
function settingsFile() { return path.join(app.getPath('userData'), 'settings.json') }
function readSettings() {
  try {
    const o = JSON.parse(fs.readFileSync(settingsFile(), 'utf8'))
    return o && typeof o === 'object' ? o : {}
  } catch (e) {
    return {}
  }
}
function recvDir() {
  const d = String(readSettings().recvDir || '')
  return d || defaultRecvDir()
}
// 目录是用户自己挑/自己打的，但仍要能建能写，否则点开会以一句系统错误收场
function ensureWritable(dir) {
  try {
    fs.mkdirSync(dir, { recursive: true })
    fs.accessSync(dir, fs.constants.W_OK)
    return ''
  } catch (e) {
    return String(e.message || e)
  }
}
function setRecvDir(dir) {
  const s = String(dir || '').trim()
  if (!s) return { ok: false, error: '目录不能留空，留空就点「恢复默认」' }
  const d = path.resolve(s)
  const err = ensureWritable(d)
  if (err) return { ok: false, error: `这个目录建不了或不能写：${err}` }
  const st = readSettings()
  st.recvDir = d
  try {
    fs.writeFileSync(settingsFile(), JSON.stringify(st, null, 2))
  } catch (e) {
    return { ok: false, error: `存不下来：${String(e.message || e)}` }
  }
  log(`recv dir = ${d}`)
  return { ok: true, dir: d, def: defaultRecvDir() }
}

// 文件名是别的用户传上来的：可能带 "../x"、Windows 非法字符、或 CON/NUL 这类保留名，
// 洗干净才敢拼进接收目录
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

// 有没有默认应用要自己查，不能拿 shell.openPath 的返回值当判据：实测没关联的扩展名
// （.qqzz、以及无扩展名的文件）它照样返回空串，而屏幕上是 Windows 自己弹的
// "你要如何打开此文件"（窗口类 Open With Dummy Window Class For Interim Dialog）。
// 判据 = UserChoice 或 HKCR 里那个 ProgID 到底有没有 shell\open\command。
function regRun(args) {
  try {
    return execFileSync('reg', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })
  } catch (e) {
    return null
  }
}
function regValue(out) {
  const m = String(out || '').match(/REG_(?:EXPAND_SZ|SZ)[ \t]+([\x20-\x7E]+)/)
  return m ? m[1].trim() : ''
}
function hasDefaultHandler(file) {
  const ext = path.extname(file).toLowerCase()
  if (!ext) return false
  // ProgId 在 FileExts\<扩展名>\UserChoice 那个子键里，不在 FileExts\<扩展名> 本身；
  // 没有 UserChoice 才退回 HKCR\<扩展名> 的默认值（.txt 那条就是 txtfilelegacy）
  const uc = regValue(regRun(['query',
    `HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\FileExts\\${ext}\\UserChoice`, '/v', 'ProgId']))
  const progId = uc || regValue(regRun(['query', `HKCR\\${ext}`, '/ve']))
  if (!progId) return false
  // 只问这个 ProgID 的打开命令键在不在，不读它的内容：命令路径里带中文时，按控制台代码页
  // 读回来是乱码，会被判成"没有默认应用"
  return regRun(['query', `HKCR\\${progId}\\shell\\open\\command`]) !== null
}

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

// 没有默认应用时的兜底：这么小的文件交记事本，超了就不开（他给的分界是 200KB）
const NOTEPAD_MAX = 200 * 1024
function openWithNotepad(file) {
  const exe = path.join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'notepad.exe')
  if (!fs.existsSync(exe)) return false
  spawn(exe, [file], { detached: true, stdio: 'ignore' }).unref()
  return true
}

// 落好盘之后统一走这一步：可执行不启动 → 有默认应用交系统 → 没默认应用按大小兜底
async function deliver(target, dir, size) {
  const ext = path.extname(target).slice(1).toLowerCase()
  // 可执行/脚本类只落文件、不启动：那等于替别人在他机器上跑代码
  if (EXEC_EXT.has(ext)) {
    log(`refused to open ${target} (${ext})`)
    return { ok: true, path: target, dir, size, opened: false, how: 'refused' }
  }
  if (hasDefaultHandler(target)) {
    const err = await shell.openPath(target)
    log(`open-file ${target} (${size} bytes) 有默认应用 → ${err || 'ok'}`)
    return err
      ? { ok: false, path: target, dir, size, opened: false, how: 'default', error: err }
      : { ok: true, path: target, dir, size, opened: true, how: 'default' }
  }
  if (size <= NOTEPAD_MAX && openWithNotepad(target)) {
    log(`open-file ${target} (${size} bytes) 无默认应用 → 记事本`)
    return { ok: true, path: target, dir, size, opened: true, how: 'notepad' }
  }
  log(`open-file ${target} (${size} bytes) 无默认应用且过大 → 只落文件`)
  return { ok: true, path: target, dir, size, opened: false, how: 'saved-only' }
}

// 「这条已经存过了吗」按 fileId 记一张索引，不按名字+大小猜：
// 同名同大小但内容改过的那份会被猜成旧的，而 fileId 就是这条文件的身份。
// 索引里那条还得在磁盘上真的在、大小也对得上，才算数（他可能自己把文件删了）。
function recvIndexFile() { return path.join(app.getPath('userData'), 'recv-index.json') }
function readRecvIndex() {
  try {
    const o = JSON.parse(fs.readFileSync(recvIndexFile(), 'utf8'))
    return o && typeof o === 'object' ? o : {}
  } catch (e) {
    return {}
  }
}
function savedPathFor(fileId, size) {
  const rec = readRecvIndex()[String(fileId)]
  if (!rec || !rec.path) return ''
  try {
    const st = fs.statSync(rec.path)
    if (!st.isFile()) return ''
    if (Number(size) > 0 && st.size !== Number(size)) return ''
    return rec.path
  } catch (e) {
    return ''
  }
}
function rememberSaved(fileId, size, p) {
  if (!fileId) return
  const idx = readRecvIndex()
  idx[String(fileId)] = { path: p, size: Number(size) || 0, at: Date.now() }
  try {
    fs.writeFileSync(recvIndexFile(), JSON.stringify(idx))
  } catch (e) {
    log(`recv-index 写不下：${e.message}`)
  }
}

// 图标不再代码画了：换成和手机端同一颗标记（两颗气泡，蓝 + 青），
// 由 out/pc-icon-src.html 按移动端 ic_launcher_fg 的同一条几何光栅化出来。
// 256 给窗口/任务栏，64 给托盘（Windows 再按 DPI 缩）。
// dot=true 那帧右上角多一颗未读红点——托盘闪烁就是这两帧来回切。
// 两张图各缓存一次：闪烁是 500ms 一次的 setImage，不能每次重新解码 PNG。
const ICON = { base: 'icon.png', dot: 'icon-dot.png' }
const ICON_TRAY = { base: 'icon-64.png', dot: 'icon-64-dot.png' }
let iconBase = null
let iconDot = null

function loadIcon(file) {
  const img = nativeImage.createFromPath(path.join(__dirname, file))
  return img.isEmpty() ? null : img
}
function makeIcon(dot = false) {
  const cached = dot ? iconDot : iconBase
  if (cached) return cached
  const img = loadIcon(dot ? ICON.dot : ICON.base)
  if (dot) iconDot = img; else iconBase = img
  return img
}
// 托盘那颗要小一号：给它 256 的那张，Windows 缩到 16 会糊
let trayBase = null, trayDot = null
function makeTrayIcon(dot = false) {
  const cached = dot ? trayDot : trayBase
  if (cached) return cached
  const img = loadIcon(dot ? ICON_TRAY.dot : ICON_TRAY.base)
  if (dot) trayDot = img; else trayBase = img
  return img
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
  win.on('maximize', () => win.webContents.send('chat:win-state', true))
  win.on('unmaximize', () => win.webContents.send('chat:win-state', false))

  // 点关闭收进托盘而不是销毁：连接、未读、正在播放的提示音都不该被一次点击打断
  win.on('close', (event) => {
    if (!quitting) {
      event.preventDefault()
      win.hide()
    }
  })
  /* 'closed' 触发时 win.webContents 已经被销毁，再 win.webContents.id 就是
     "Object has been destroyed" —— 主进程未捕获异常，托盘点「退出」弹的就是它。
     id 在建窗时扣下来。 */
  const wcId = win.webContents.id
  win.on('closed', () => { deviceIds.delete(wcId); if (mainWin === win) mainWin = null })
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
  mainWin = win
  return win
}

/* 只认主窗：getAllWindows() 里还混着钉图窗和遮罩窗，
   原先取"第一个没销毁的"，托盘一点就可能把钉图置顶、把主窗留在身后 */
function focusMainWindow() {
  if (mainWin && !mainWin.isDestroyed()) {
    if (mainWin.isMinimized()) mainWin.restore()
    mainWin.show()
    // 托盘点击属于"后台进程要前台"，Windows 会拒绝 SetForegroundWindow、只闪任务栏按钮。
    // 必须先 app.focus({steal:true}) 把前台权抢下来，再 focus 窗口本身
    app.focus({ steal: true })
    mainWin.focus()
    return mainWin
  }
  return createWindow()
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

/* ---- 托盘闪烁 + 气泡 ----
   Windows 没有"托盘图标闪烁"的一等 API（startFlashingFrame 是 macOS 的），
   常规做法就是自己按节拍换图。这里 500ms 一次在"无红点/有红点"两帧之间切。 */
let flashTimer = null
let flashOn = false

function setTrayIcon(dot) {
  if (tray) tray.setImage(makeTrayIcon(dot))
}

function startTrayFlash() {
  if (!tray || flashTimer || !traySettings.flash) return
  flashOn = true
  setTrayIcon(true)
  flashTimer = setInterval(() => {
    flashOn = !flashOn
    setTrayIcon(flashOn)
  }, 500)
  log('tray flash started')
}

function stopTrayFlash() {
  if (flashTimer) {
    clearInterval(flashTimer)
    flashTimer = null
    log('tray flash stopped')
  }
  flashOn = false
  setTrayIcon(false)
}

/* 托盘上方的"小弹框"。
   先试过 tray.displayBalloon()：调用不报错、日志也打了，但 2s / 4s 两次抓屏都没有气泡，
   Windows 11 会把它吞掉（不返回失败）。所以这块自己画一个无边框置顶小窗，
   位置按工作区右下角算（任务栏上方、离右边 12px），和原来气泡该出现的地方一致。 */
const POP_W = 320
const POP_H = 92
const POP_HOLD = 6000
let popWin = null
let popTimer = null

function esc(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;')
}

function popHtml(title, text) {
  return '<!doctype html><meta charset="utf-8"><style>'
    + 'html,body{margin:0;height:100%;background:transparent;overflow:hidden;'
    + 'font-family:"Microsoft YaHei","Segoe UI",system-ui,sans-serif;-webkit-font-smoothing:antialiased}'
    + '.card{box-sizing:border-box;margin:8px;height:76px;padding:11px 13px;border-radius:10px;'
    + 'background:#fff;border:1px solid #e2e6ee;box-shadow:0 6px 18px rgba(16,24,40,.18)}'
    + '.hd{display:flex;align-items:center;gap:7px;font-size:13px;color:#1b2434;font-weight:600;'
    + 'white-space:nowrap;overflow:hidden;text-overflow:ellipsis}'
    + '.dot{width:8px;height:8px;border-radius:50%;background:#26c6da;flex:0 0 8px}'
    + '.bd{margin-top:6px;font-size:12px;line-height:17px;color:#8a97ab;'
    + 'display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}'
    + '</style><div class="card"><div class="hd"><span class="dot"></span>'
    + esc(title) + '</div><div class="bd">' + esc(text) + '</div></div>'
}

function hideTrayPopup() {
  if (!popWin || popWin.isDestroyed() || !popWin.isVisible()) return
  popWin.setOpacity(0)
  popWin.setIgnoreMouseEvents(true)
}

function showTrayPopup(title, text) {
  const wa = screen.getPrimaryDisplay().workArea
  const x = wa.x + wa.width - POP_W - 12
  const y = wa.y + wa.height - POP_H - 10
  if (!popWin || popWin.isDestroyed()) {
    popWin = new BrowserWindow({
      width: POP_W, height: POP_H, x, y, frame: false, transparent: true,
      alwaysOnTop: true, skipTaskbar: true, focusable: false, resizable: false,
      movable: false, minimizable: false, maximizable: false, fullscreenable: false,
      show: false, webPreferences: { sandbox: true }
    })
    popWin.setMenuBarVisibility(false)
    popWin.on('closed', () => { popWin = null })
  }
  const pb = popWin.getBounds()
  if (pb.x !== x || pb.y !== y || pb.width !== POP_W || pb.height !== POP_H) popWin.setBounds({ x, y, width: POP_W, height: POP_H })
  // 同上：ready-to-show 只发一次，连着两条同样的消息时第二条就不弹了
  popWin.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(`${popHtml(title, text)}<!--${++loadNonce}-->`))
    .then(() => {
      if (!popWin || popWin.isDestroyed()) return
      if (!popWin.isVisible()) popWin.showInactive()
      popWin.setIgnoreMouseEvents(false)
      popWin.setOpacity(1)
    })
    .catch((e) => log(`tray popup 载入失败: ${e.message}`))
  clearTimeout(popTimer)
  clearTimeout(popTimer)
  popTimer = setTimeout(() => {
    hideTrayPopup()
    log('tray popup 到时收起')
  }, POP_HOLD)
  log(`tray popup "${title}" 内容 ${(text || '').length} 字 @ ${x},${y}`)
}

function showBalloon(title, content) {
  if (!traySettings.popup) { log('托盘菜单里关了弹窗，跳过'); return }
  const text = String(content || '').slice(0, 60)
  showTrayPopup(title, text)
  // 系统通知留作兜底：自己画的小窗失败时（比如被策略挡了置顶）至少不丢消息
  if (process.platform !== 'win32') {
    const n = new Notification({ title: String(title || 'Chat'), body: text })
    n.on('click', () => focusMainWindow())
    n.show()
  }
}

/* ---- 托盘右键菜单 ----
   原生 Menu 的圆角是 Windows 画的，我们改不动（QQ 那张是自己画的），
   所以这里也走"无边框小窗 + HTML"这一条，圆角、图标、勾选都归我们。
   状态只列接口真认的：网关 handlePresenceSet 收 ONLINE / BUSY，别的回 BAD_STATUS；
   "离线"不是发出去的 status，是断连，所以它走渲染端那套 setPresence('OFFLINE')。 */
let trayMenuWin = null
let trayMenuTimer = null
let trayMenuShownAt = 0
// 上一次装进菜单窗的 HTML：内容没变就别再 loadURL——透明窗重新导航会先空一帧，看起来就是闪
let trayMenuHtmlLast = null
/* 点别处就收起：菜单窗不抢前台（抢了就闪），所以系统不会告诉我们"用户点到别的地方了"。
   这里补一层全屏透明捕获窗，专吃那一下点击并回报关闭——原生菜单也是这么做的：
   点外面的第一下只关菜单，不会落到底下的程序上。它比菜单窗低一层，所以菜单上的点击照旧有效。
   范围是整个虚拟屏幕（含任务栏、含副屏）：点任务栏也先收掉菜单，那一下和原生菜单一样被吞。 */
let clickCatcher = null
const CATCHER_HTML = '<!doctype html><meta charset="utf-8">'
  + '<style>html,body{margin:0;width:100%;height:100%;background:rgba(0,0,0,0);overflow:hidden}</style>'
  + '<script>function fire(){window.trayMenu.pick("dismiss")}'
  + 'document.addEventListener("pointerdown",fire,true);'
  + 'document.addEventListener("contextmenu",function(e){e.preventDefault();fire()},true)</script>'
let trayPresence = 'ONLINE'
// 每次 loadURL 都让文档内容差一个自增注释，保证是"真导航"而不是原地复用
let loadNonce = 0
const TRAY_MENU_W = 208
const TRAY_MENU_ROW = 30
// 我们应用内右键菜单是 12px 圆角，这里取它的一半
const TRAY_MENU_RADIUS = 6

function traySettingsFile() {
  return path.join(app.getPath('userData'), 'tray-settings.json')
}

function readTraySettings() {
  try {
    const j = JSON.parse(fs.readFileSync(traySettingsFile(), 'utf8'))
    return { popup: j.popup !== false, flash: j.flash !== false }
  } catch (e) {
    return { popup: true, flash: true }
  }
}

function writeTraySettings(s) {
  try {
    fs.writeFileSync(traySettingsFile(), JSON.stringify(s))
  } catch (e) {
    log(`tray settings 存不下: ${e.message}`)
  }
}

let traySettings = { popup: true, flash: true }

function trayMenuItems() {
  const st = traySettings
  return [
    { id: 'presence:ONLINE', text: '我在线上', radio: trayPresence === 'ONLINE' },
    { id: 'presence:BUSY', text: '忙碌', radio: trayPresence === 'BUSY' },
    { id: 'presence:OFFLINE', text: '离线', radio: trayPresence === 'OFFLINE' },
    { sep: true },
    { id: 'toggle:popup', text: '关闭弹窗', check: !st.popup },
    { id: 'toggle:flash', text: '关闭闪动', check: !st.flash },
    { sep: true },
    { id: 'show', text: '显示主窗口' },
    { id: 'quit', text: '退出' }
  ]
}

function trayMenuHtml() {
  // 状态色点和客户端图标栏那颗点同源：--ok #1f9d55 / --warn #b76e10 / --nb-dim-2 #8a97ab
  const DOT = { 'presence:ONLINE': '#1f9d55', 'presence:BUSY': '#b76e10', 'presence:OFFLINE': '#8a97ab' }
  const rows = trayMenuItems().map((it) => {
    if (it.sep) return '<div class="sep"></div>'
    const dot = DOT[it.id] ? `<span class="dot" style="background:${DOT[it.id]}"></span>` : '<span class="dot none"></span>'
    const tick = it.radio || it.check ? '<span class="tick">✓</span>' : '<span class="tick"></span>'
    return `<div class="row" data-id="${it.id}"><span class="mk">${dot}</span><span class="tx">${it.text}</span>${tick}</div>`
  }).join('')
  return '<!doctype html><meta charset="utf-8"><style>'
    + 'html,body{margin:0;height:100%;background:transparent;overflow:hidden;'
    + 'font-family:"Microsoft YaHei","Segoe UI",system-ui,sans-serif;-webkit-font-smoothing:antialiased;user-select:none}'
    + `.menu{box-sizing:border-box;margin:6px;background:rgba(255,255,255,.98);border-radius:${TRAY_MENU_RADIUS}px;`
    + 'box-shadow:0 2px 14px rgba(20,32,56,.13);padding:4px 0;overflow:hidden}'
    + '.row{display:flex;align-items:center;height:30px;padding:0 10px 0 8px;font-size:13px;color:#1b2434;cursor:default}'
    + '.row:hover{background:#eef2f8}'
    + '.mk{width:18px;flex:0 0 18px;display:flex;align-items:center;justify-content:center}'
    + '.dot{width:8px;height:8px;border-radius:50%}'
    + '.dot.none{background:transparent}'
    + '.tx{flex:1;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}'
    + '.tick{width:16px;flex:0 0 16px;text-align:center;font-size:12px;color:#26c6da}'
    + '.sep{height:1px;margin:4px 8px;background:#e8ecf3}'
    + '</style><div class="menu">' + rows + '</div>'
    + '<script>document.addEventListener("click",function(e){var r=e.target.closest(".row");if(r)window.trayMenu.pick(r.dataset.id)})</script>'
}

function trayMenuHeight() {
  const items = trayMenuItems()
  const rows = items.filter(i => !i.sep).length
  const seps = items.length - rows
  return rows * TRAY_MENU_ROW + seps * 9 + 8 + 12
}

function hideClickCatcher() {
  if (clickCatcher && !clickCatcher.isDestroyed() && clickCatcher.isVisible()) clickCatcher.hide()
}

function showClickCatcher(menuRect) {
  // 用整个虚拟屏幕（含任务栏、含副屏），不用 workArea：只盖工作区的话，
  // 点任务栏那一下不会收掉菜单，和原生菜单的行为不一致
  const ds = screen.getAllDisplays()
  const L = Math.min(...ds.map((d) => d.bounds.x))
  const Tp = Math.min(...ds.map((d) => d.bounds.y))
  const Rt = Math.max(...ds.map((d) => d.bounds.x + d.bounds.width))
  const Bt = Math.max(...ds.map((d) => d.bounds.y + d.bounds.height))
  const geo = { x: L, y: Tp, width: Rt - L, height: Bt - Tp }
  if (!clickCatcher || clickCatcher.isDestroyed()) {
    clickCatcher = new BrowserWindow(Object.assign({ frame: false, transparent: true, alwaysOnTop: true,
      skipTaskbar: true, focusable: false, resizable: false, movable: false, minimizable: false,
      maximizable: false, fullscreenable: false, show: false, opacity: 1,
      webPreferences: { sandbox: true, preload: path.join(__dirname, 'tray-menu-preload.cjs') } }, geo))
    clickCatcher.setAlwaysOnTop(true, 'screen-saver', 1)   // 菜单是 2，压在它上面
    clickCatcher.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(CATCHER_HTML))
    clickCatcher.on('closed', () => { clickCatcher = null })
  } else {
    clickCatcher.setBounds(geo)
  }
  clickCatcher.showInactive()
}

function hideTrayMenu(why) {
  if (trayMenuTimer) { clearTimeout(trayMenuTimer); trayMenuTimer = null }
  hideClickCatcher()
  if (!trayMenuWin || trayMenuWin.isDestroyed() || !trayMenuWin.isVisible()) return
  // 不能用 hide()：透明（分层）窗一隐藏，DWM 就把它的表面销毁，下次显示要先给两三帧空的
  // ——那就是他说的"弹出来先闪两下才稳"。改成留在原地、透明度归零 + 点击穿透
  trayMenuWin.setOpacity(0)
  trayMenuWin.setIgnoreMouseEvents(true)
  log(`tray menu 收起（${why || '-'}）`)
}

function showTrayMenu(bounds) {
  // 菜单不抢前台，系统不会帮我们关掉它，所以"再点一次右键"就当收起用。
  // 但一次物理右键常被 shell 投递两次（日志里同一秒两条 open，甚至 open→收起→open→收起），
  // 不认窗口期的话这第二下就被当成"再点一次"，菜单当场开→关→开，正是他说的"闪两下才稳"
  const openNow = trayMenuWin && !trayMenuWin.isDestroyed() && trayMenuWin.isVisible() && trayMenuWin.getOpacity() > 0.5
  if (openNow) {
    if (Date.now() - trayMenuShownAt < 250) { log('tray menu 忽略 250ms 内的重复投递'); return }
    hideTrayMenu('再次右键')
    return
  }
  const wa = screen.getPrimaryDisplay().workArea
  const h = trayMenuHeight()
  // 贴图标左上角往上长；越界就沿工作区夹回来
  let x = (bounds && bounds.x) ? bounds.x : wa.x + wa.width - TRAY_MENU_W
  let y = (bounds && bounds.y) ? bounds.y - h - 4 : wa.y + wa.height - h - 40
  x = Math.min(Math.max(x, wa.x + 4), wa.x + wa.width - TRAY_MENU_W - 4)
  y = Math.min(Math.max(y, wa.y + 4), wa.y + wa.height - h - 4)
  if (!trayMenuWin || trayMenuWin.isDestroyed()) {
    trayMenuWin = new BrowserWindow({
      width: TRAY_MENU_W, height: h, x, y, frame: false, transparent: true,
      alwaysOnTop: true, skipTaskbar: true, resizable: false, movable: false,
      minimizable: false, maximizable: false, fullscreenable: false, show: false,
      // 关键：不抢前台。右键的前台权在 explorer 手里，菜单窗一 focus 就被顶回来，
      // 于是 open -> blur 收起 -> open 在同一秒里来回，就是他说的"闪烁几下"
      focusable: false,
      webPreferences: { sandbox: true, preload: path.join(__dirname, 'tray-menu-preload.cjs') }
    })
    trayMenuWin.setMenuBarVisibility(false)
    trayMenuWin.setAlwaysOnTop(true, 'screen-saver', 2)   // 压在捕获层上面（同带内比 relativeLevel）
    // 新建的窗是空文档，缓存的 HTML 对它不作数
    trayMenuHtmlLast = null
    // 不再监听 blur：窗口不聚焦就不会有 blur，也就不会被系统顶来顶去
    trayMenuWin.on('closed', () => { trayMenuWin = null })
  }
  const geo = { x, y, width: TRAY_MENU_W, height: h }
  const b = trayMenuWin.getBounds()
  // 收起时留在原地（只是透明了），所以要按"目标位置"而不是"可见性"决定要不要搬
  // 位置没变就别 setBounds：移动/缩放一个透明窗同样会重绘一次
  if (b.x !== geo.x || b.y !== geo.y || b.width !== geo.width || b.height !== geo.height) trayMenuWin.setBounds(geo)
  const showMenu = () => {
    if (!trayMenuWin || trayMenuWin.isDestroyed()) return
    trayMenuShownAt = Date.now()
    // 只有第一次（还没显示过）才需要 show；之后每次只是把透明度和穿透翻回来
    if (!trayMenuWin.isVisible()) trayMenuWin.showInactive()
    trayMenuWin.setIgnoreMouseEvents(false)
    trayMenuWin.setOpacity(1)
    // 捕获层是后 show 的，会压在菜单上面：hover 拿不到、真点菜单项还会被当成"点别处"。
    // 层级数字不算数，show 之后再显式重申一次并提到自己这组的最前
    trayMenuWin.setAlwaysOnTop(true, 'pop-up-menu', 2)
    trayMenuWin.moveTop()
  }
  const html = trayMenuHtml()
  // 不能等 ready-to-show：它只在首次渲染时发一次，同样的内容第二次不再触发（右键像没反应）。
  // 内容变了才重新导航，且导航完成后才 show；没变就直接 show 已经画好的那一版
  if (html !== trayMenuHtmlLast) {
    trayMenuHtmlLast = html
    const url = 'data:text/html;charset=utf-8,' + encodeURIComponent(`${html}<!--${++loadNonce}-->`)
    trayMenuWin.loadURL(url).then(() => { showClickCatcher(geo); showMenu() })
      .catch((e) => log(`tray menu 载入失败: ${e.message}`))
  } else {
    showClickCatcher(geo)
    showMenu()
  }
  clearTimeout(trayMenuTimer)
  // 兜底自动收起：任务栏的"应用按钮"那条压不住 explorer 的 appbar，点那里不会触发捕获层，
  // 所以留一个短的自动收起，避免菜单一直挂着
  trayMenuTimer = setTimeout(() => hideTrayMenu('5s 兜底'), 5000)
  log(`tray menu @ ${x},${y} ${TRAY_MENU_W}x${h} 状态=${trayPresence} 弹窗=${traySettings.popup} 闪动=${traySettings.flash}`)
}

function handleTrayMenuPick(id) {
  log(`tray menu pick ${id}`)
  hideTrayMenu(id === 'dismiss' ? '点到别处' : '菜单选择')
  if (id === 'dismiss') return
  if (id.startsWith('presence:')) {
    const key = id.slice(9)
    trayPresence = key
    if (mainWin && !mainWin.isDestroyed()) mainWin.webContents.send('tray:presence', key)
    if (key === 'OFFLINE') stopTrayFlash()
    return
  }
  if (id === 'toggle:popup') {
    traySettings.popup = !traySettings.popup
    writeTraySettings(traySettings)
    if (!traySettings.popup) hideTrayPopup()
    return
  }
  if (id === 'toggle:flash') {
    traySettings.flash = !traySettings.flash
    writeTraySettings(traySettings)
    if (!traySettings.flash) stopTrayFlash()
    return
  }
  if (id === 'show') focusMainWindow()
  else if (id === 'quit') { quitting = true; app.quit() }
}

function buildTray() {
  tray = new Tray(makeTrayIcon())
  tray.setToolTip('Chat')
  // 右键走自绘菜单，不再 setContextMenu——原生菜单和自绘的会同时冒出来
  tray.on('right-click', (_e, bounds) => showTrayMenu(bounds))
  // 点托盘=把窗口带到眼前，那就算"人已经来看了"，闪烁该停
  // 先判断状态再动作。原来写成 toggleWindow(focusMainWindow())：参数会先把窗口显示出来，
  // 紧接着"可见且已聚焦"就成立了，于是刚显示又被藏掉——他看到的就是"点一下闪一下就没了"
  tray.on('click', () => {
    stopTrayFlash()
    hideTrayMenu('托盘左键')
    const up = !!mainWin && !mainWin.isDestroyed() && mainWin.isVisible() && mainWin.isFocused()
    // 把分支打进日志：外部脚本读"是否前台"会被自己的控制台抢焦点干扰，只有这里说的是真话
    log(`tray click visible=${mainWin && !mainWin.isDestroyed() ? mainWin.isVisible() : 'no-win'} focused=${mainWin && !mainWin.isDestroyed() ? mainWin.isFocused() : '-'} -> ${up ? 'hide' : 'show+steal'}`)
    if (up) mainWin.hide()
    else focusMainWindow()
  })
  // 图标真实边界：菜单要贴着它往上长，Windows 11 还可能把它收进溢出区，位置不固定
  try { log(`tray bounds ${JSON.stringify(tray.getBounds())}`) } catch (e) { log(`tray bounds 读不到: ${e.message}`) }
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

    // 通话要摄像头/麦克风/屏幕。Electron 在打包后不会替我们弹授权框，
    // 不挂这两个 handler 的话点"语音通话"是静默失败——LiveKit 连不上、界面也不报错。
    // 只放行我们自己的来源，别的 origin 一概不给。
    const oursOrigin = (o) => {
      const s = String(o || '')
      return s.startsWith('app://') || s.startsWith('file://') ||
        (typeof DEV_URL === 'string' && DEV_URL && s.startsWith(DEV_URL))
    }
    const ses = session.defaultSession
    ses.setPermissionCheckHandler((wc, permission, origin) =>
      (permission === 'media' || permission === 'displayMedia') && oursOrigin(origin))
    ses.setPermissionRequestHandler((wc, permission, callback, details) => {
      const url = details && details.requestingUrl
      const from = url ? new URL(url).origin : ''
      callback((permission === 'media' || permission === 'displayMedia') && oursOrigin(from))
    })
    // 屏幕共享交给系统那份选择器（Electron 30+ 的 useSystemPicker）。
    // 故意不调 callback：调了就等于替用户默认选了第一块屏，那没过同意。
    ses.setDisplayMediaRequestHandler((request, callback, userMedia) => {
      void userMedia
    }, { useSystemPicker: true })

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
      const win = BrowserWindow.fromWebContents(event.sender)
      const inFront = !!win && win.isVisible() && win.isFocused()
      showBalloon(title, body)
      // 窗口就在眼前时只弹气泡、不闪托盘——人已经在看屏幕了，闪是噪音；
      // 收进托盘/ minimized / 失焦时才闪，等下一次窗口聚焦自动停
      if (!inFront) startTrayFlash()
      else log('窗口在前台，只弹气泡不闪')
    })
    // 自绘托盘菜单的点击回传，以及渲染端把当前状态同步过来（菜单要画勾选）
    ipcMain.on('chat:tray-menu-pick', (event, id) => handleTrayMenuPick(id))
    ipcMain.on('chat:presence', (event, key) => {
      const k = String(key || 'ONLINE')
      if (k !== trayPresence) log(`presence from renderer: ${k}`)
      trayPresence = k
    })
    // 「另存为」：渲染端在沙箱里，既弹不出系统保存对话框也不知道用户选了哪儿，
    // 所以字节由渲染端取好送过来，主进程弹框 + 写盘。对话框默认开在「文档路径」那个目录
    ipcMain.handle('chat:save', async (event, { name, bytes }) => {
      const win = BrowserWindow.fromWebContents(event.sender)
      const buf = Buffer.from(bytes || new Uint8Array())
      const at = path.join(recvDir(), safeName(name))
      log(`save dialog 默认开在 ${at}`)
      const { canceled, filePath } = await dialog.showSaveDialog(win, {
        defaultPath: at,
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

    // 「文档路径」这条：读、挑、存。挑目录的对话框只有主进程弹得出来
    ipcMain.handle('chat:paths', () => ({ ok: true, dir: recvDir(), def: defaultRecvDir() }))
    ipcMain.handle('chat:set-recv-dir', (event, dir) => setRecvDir(dir))
    ipcMain.handle('chat:pick-recv-dir', async (event) => {
      const win = BrowserWindow.fromWebContents(event.sender)
      const { canceled, filePaths } = await dialog.showOpenDialog(win, {
        title: '选择接收文件的目录',
        defaultPath: recvDir(),
        properties: ['openDirectory', 'createDirectory']
      })
      if (canceled || !filePaths || !filePaths[0]) return { ok: false, canceled: true }
      // 只把挑中的路径交回去，不在这一步写盘：界面上还有"保存"那颗，两处都写会打架
      return { ok: true, dir: filePaths[0], def: defaultRecvDir() }
    })

    // 「存到文档路径 + 用系统默认应用打开」：渲染端在沙箱里既拿不到那个目录，
    // 也没权限启动外部程序，所以字节送过来，主进程落盘再交给 shell.openPath。
    // 先不带字节问一次：这条 fileId 已经存过就直接开，省掉那趟下载；没存过回 { need: true }，
    // 渲染端再去取字节发第二趟
    ipcMain.handle('chat:open-file', async (event, { name, size, fileId, bytes }) => {
      const dir = recvDir()
      const known = fileId ? savedPathFor(fileId, size) : ''
      if (known) {
        log(`open-file 复用已存的 ${known}（fileId ${fileId}）`)
        return { ...(await deliver(known, dir, fs.statSync(known).size)), existed: true }
      }
      if (!bytes) return { ok: true, need: true, dir }
      const buf = Buffer.from(bytes)
      let target
      try {
        target = placeFile(dir, safeName(name), buf)
      } catch (e) {
        log(`open-file write failed: ${e.message}`)
        return { ok: false, error: String(e.message || e) }
      }
      rememberSaved(fileId, buf.length, target)
      return { ...(await deliver(target, dir, buf.length)), existed: false }
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

    traySettings = readTraySettings()
    log(`tray settings 弹窗=${traySettings.popup} 闪动=${traySettings.flash}`)
    buildTray()
    createWindow()
    // 只认主窗：这个事件对应用内任何窗口都触发，菜单窗自己 show()+focus() 也算一次，
    // 不区分的话"刚打开就被自己关掉"，而且打开菜单会误停闪烁
    app.on('browser-window-focus', (_e, win) => {
      if (win !== mainWin) return
      stopTrayFlash()
      hideTrayMenu('主窗聚焦')
    })

    app.on('window-all-closed', () => {
      // 收进托盘时窗口是被 hide 而不是 close，所以这里不能直接退
      if (quitting && process.platform !== 'darwin') app.quit()
    })
    app.on('before-quit', () => { quitting = true })
  })
}
