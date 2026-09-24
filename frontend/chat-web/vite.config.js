import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 代理目标原来写死成某台机器的局域网 IP，换台机器就全 502；现在默认指本机，
// 端口和网关端口都能用环境变量覆盖（网关在这台机器上跑 18082，因为 8082 被别的程序占着）
const HOST = process.env.VITE_PROXY_HOST || '127.0.0.1'
const P = (name, dflt) => Number(process.env[name]) || dflt

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 3000,
    proxy: {
      '/api/group': {
        target: `http://${HOST}:${P('VITE_GROUP_PORT', 8084)}`,
        changeOrigin: true
      },
      '/api/message': {
        target: `http://${HOST}:${P('VITE_MESSAGE_PORT', 8083)}`,
        changeOrigin: true
      },
      '/api': {
        target: `http://${HOST}:${P('VITE_USER_PORT', 8081)}`,
        changeOrigin: true
      },
      '/ws': {
        target: `ws://${HOST}:${P('VITE_GATEWAY_PORT', 18082)}`,
        ws: true,
        changeOrigin: true
      }
    }
  }
})
