const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('chatDesktop', {
  isDesktop: true,
  platform: process.platform,
  // 每个窗口一个 deviceId：网关的连接表是 map[userId][deviceId]，
  // 同名会互相顶号，所以不能像网页那样写死 'web'
  deviceId: () => ipcRenderer.invoke('chat:device-id'),
  notify: (payload) => ipcRenderer.send('chat:notify', payload),
  // 来电那两声：卡片是渲染端画的，"把窗口从别的程序后面抬出来"只有壳做得到
  callIn: () => ipcRenderer.send('chat:call-in'),
  callEnd: () => ipcRenderer.send('chat:call-end'),
  // 托盘自绘菜单要画"当前是哪个状态"，所以渲染端状态一变就送过来
  presence: (key) => ipcRenderer.send('chat:presence', key),
  onTrayPresence: (cb) => {
    const handler = (_e, key) => cb(key)
    ipcRenderer.on('tray:presence', handler)
    return () => ipcRenderer.removeListener('tray:presence', handler)
  },
  // 「另存为」：字节在渲染端取好送过来，主进程弹系统对话框再写盘
  save: (payload) => ipcRenderer.invoke('chat:save', payload),
  // 文件点开：落到「文档路径」那个目录，再交系统默认应用打开
  openFile: (payload) => ipcRenderer.invoke('chat:open-file', payload),
  // 设置 → 文档路径：读当前值、弹系统选目录框、存新值（都只存这台设备）
  paths: () => ipcRenderer.invoke('chat:paths'),
  pickRecvDir: () => ipcRenderer.invoke('chat:pick-recv-dir'),
  setRecvDir: (dir) => ipcRenderer.invoke('chat:set-recv-dir', dir),
  // 截图：抓屏和遮罩窗都在主进程，这边只发起 + 收结果
  shot: () => ipcRenderer.invoke('chat:shot'),
  onShotResult: (cb) => {
    const handler = (_e, r) => cb(r)
    ipcRenderer.on('chat:shot-result', handler)
    return () => ipcRenderer.removeListener('chat:shot-result', handler)
  },
  win: {
    min: () => ipcRenderer.send('chat:win', 'min'),
    max: () => ipcRenderer.send('chat:win', 'max'),
    close: () => ipcRenderer.send('chat:win', 'close'),
    isMaximized: () => ipcRenderer.invoke('chat:win-maximized'),
    onState: (cb) => {
      const handler = (_e, maximized) => cb(maximized)
      ipcRenderer.on('chat:win-state', handler)
      return () => ipcRenderer.removeListener('chat:win-state', handler)
    }
  }
})
