import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

/*
 * 管理端 —— 独立前端应用，端口 5175。
 *
 * 三个前端的 vite.config.js 基本一致，只有 server.port 和 base 不同：
 *   reader-web :5173 /reader/   author-web :5174 /author/   admin-web :5175 /admin/
 *
 * ★ 开发期正常入口是 http://localhost:3000/admin/（dev-proxy），
 *   直接开 5175 是另一个 origin，读不到另外两端的登录态。
 */
export default defineConfig({
  // 生产挂载路径：https://域名/admin/。理由详见 author-web/vite.config.js 的 base 说明
  base: '/admin/',

  plugins: [
    vue(),
    // Element Plus 按需引入。★ 后台管理页面组件多，按需引入的收益比另两端更明显
    AutoImport({
      imports: ['vue', 'vue-router', 'pinia'],
      resolvers: [ElementPlusResolver()],
      dts: false,
    }),
    Components({
      resolvers: [ElementPlusResolver()],
      dts: false,
    }),
  ],

  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },

  server: {
    port: 5175,

    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/uploads': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
