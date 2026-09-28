// 托盘自绘菜单的桥：菜单项被点时把 id 送回主进程。
// 这个窗口不需要任何应用能力，所以只暴露一个 pick。
const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('trayMenu', {
  pick: (id) => ipcRenderer.send('chat:tray-menu-pick', String(id || ''))
})
