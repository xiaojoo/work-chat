const { contextBridge, ipcRenderer } = require('electron')

// 遮罩窗和钉图窗共用这一份；两个窗各用各的那几个方法
contextBridge.exposeInMainWorld('shot', {
  onData: cb => ipcRenderer.on('shot:data', (e, p) => cb(p)),
  // 图画上屏了才让主进程露窗（早露一下就是整屏黑闪）
  painted: () => ipcRenderer.send('shot:painted'),
  copy: dataUrl => ipcRenderer.send('shot:copy', dataUrl),
  pin: dataUrl => ipcRenderer.send('shot:pin', dataUrl),
  cancel: () => ipcRenderer.send('shot:cancel')
})

contextBridge.exposeInMainWorld('pin', {
  onData: cb => ipcRenderer.on('pin:data', (e, url) => cb(url)),
  close: () => ipcRenderer.send('pin:close')
})
