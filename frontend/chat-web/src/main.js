import { createApp } from 'vue'
import { createPinia } from 'pinia'
import './styles/fonts.css'
import './styles/nebula.css'
import './styles/theme.css'
import App from './App.vue'
import router from './router'
import { initDeviceId } from './config'
import { applyAccent, currentAccent } from './styles/accent'
import { initUaTip } from './utils/uaTip'

// 先拿到本窗口的 deviceId 再挂载：WebSocket 的连接参数要用它
initDeviceId().then(start)

// 先贴回上次选的主题色，免得挂载完再闪一下颜色
applyAccent(currentAccent())

function start() {
  const app = createApp(App)
  app.use(createPinia())
  app.use(router)
  app.mount('#app')
  // 挂载完再接管 title：首屏那批元素要扫到，后续由 MutationObserver 兜住
  initUaTip()
}
