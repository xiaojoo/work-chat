const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('chatDesktop', {
  isDesktop: true,
  platform: process.platform,
  // 每个窗口一个 deviceId：网关的连接表是 map[userId][deviceId]，
  // 同名会互相顶号，所以不能像网页那样写死 'web'
  deviceId: () => ipcRenderer.invoke('chat:device-id'),
  notify: (payload) => ipcRenderer.send('chat:notify', payload),
  // 「另存为」：字节在渲染端取好送过来，主进程弹系统对话框再写盘
  save: (payload) => ipcRenderer.invoke('chat:save', payload),
  // 截图：抓屏和遮罩窗都在主进程，这边只发起 + 收结果
  shot: () => ipcRenderer.invoke('chat:shot'),
  // 大图查看器的「编辑」：把这张图的原始字节送过去，主进程开同一个标注器（不重编码）
  editImage: (payload) => ipcRenderer.invoke('chat:shot-edit', payload),
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
