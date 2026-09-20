import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

/*
 * 创作中心 —— 独立前端应用，端口 5174。
 *
 * 三个前端的 vite.config.js 内容基本一致，只有 server.port 和 base 不同：
 *   reader-web :5173 /reader/   author-web :5174 /author/   admin-web :5175 /admin/
 *
 * 「为什么三个应用用不同端口却不跨域」：因为每个应用都配了自己的
 * 开发代理，前端写的是相对路径 /api，由各自的 Vite 转发到 8080。
 * 浏览器看来始终是同源请求。
 *
 * ★ 但注意：开发期【正常访问入口不是 5174】，而是 dev-proxy 的
 *   http://localhost:3000/author/。原因见下面的 base 说明。
 */
export default defineConfig({
  /*
   * ★ base —— 应用部署在子路径下，不是域名根目录。
   *
   * 生产：https://域名/author/ → author-web/dist
   * 开发：http://localhost:3000/author/ → 本应用（由 dev-proxy 转发）
   *
   * ★ 为什么开发期也非要套这一层路径：
   *
   *   localStorage 是按【origin = 协议 + 主机 + 端口】隔离的。
   *   端口不同 = 不同 origin = 读不到对方的 localStorage。
   *   实测过：在 5173 写入 token，5174 和 5175 读出来都是 null。
   *
   *   而 P0 的验收项要求「阅读端登录后跳创作中心，无需重新登录」。
   *   所以开发期也必须让三个应用同源 —— 办法就是照搬生产的拓扑，
   *   用 dev-proxy(:3000) 把三者收进同一个端口的三个路径下。
   *
   *   这一层不做的话，这个 bug 会一直藏到部署那天才炸。
   */
  base: '/author/',

  plugins: [
    vue(),
    // Element Plus 按需引入，理由见 reader-web/vite.config.js 的详细说明
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
    port: 5174,

    // 和后端 8080 打通。★ 后端不需要配任何跨域，这里转发是同源请求
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
