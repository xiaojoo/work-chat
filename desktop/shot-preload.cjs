const { contextBridge, ipcRenderer } = require('electron')

// 遮罩窗和钉图窗共用这一份；两个窗各用各的那几个方法
contextBridge.exposeInMainWorld('shot', {
  onData: cb => ipcRenderer.on('shot:data', (e, p) => cb(p)),
  // 图画上屏了才让主进程露窗（早露一下就是整屏黑闪）
  painted: () => ipcRenderer.send('shot:painted'),
  copy: dataUrl => ipcRenderer.send('shot:copy', dataUrl),
  // 带选区左上角一起送：钉图要留在截的那一块的位置，不能被摆到屏幕正中
  pin: payload => ipcRenderer.send('shot:pin', payload),
  cancel: () => ipcRenderer.send('shot:cancel')
})

contextBridge.exposeInMainWorld('pin', {
  onData: cb => ipcRenderer.on('pin:data', (e, url) => cb(url)),
  close: () => ipcRenderer.send('pin:close'),
  copy: () => ipcRenderer.send('pin:copy'),
  save: () => ipcRenderer.invoke('pin:save'),
  zoom: dir => ipcRenderer.send('pin:zoom', dir),
  reset: () => ipcRenderer.send('pin:reset'),
  // 就地标注「完成」：把改过的图交回主进程，右键的复制/另存为要用的是这一份
  setImage: dataUrl => ipcRenderer.send('pin:set-image', dataUrl),
  grow: n => ipcRenderer.send('pin:grow', n),
  shrink: () => ipcRenderer.send('pin:shrink'),
  dragStart: () => ipcRenderer.send('pin:drag-start'),
  dragMove: d => ipcRenderer.send('pin:drag-move', d),
  dragEnd: () => ipcRenderer.send('pin:drag-end'),
  onZoomed: cb => ipcRenderer.on('pin:zoomed', (e, pct) => cb(pct)),
  onNote: cb => ipcRenderer.on('pin:note', (e, text) => cb(text))
})
