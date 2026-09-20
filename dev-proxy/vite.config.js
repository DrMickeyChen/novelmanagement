import { defineConfig } from 'vite'

/*
 * ============================================================
 * 开发代理 —— 这个文件对应【生产环境的那段 Nginx 配置】
 * ============================================================
 *
 * ★ 它为什么必须存在：
 *
 * 三个前端是三个独立应用，原本各跑一个端口（5173 / 5174 / 5175）。
 * 但 localStorage 是按 **origin（协议 + 主机 + 端口）** 隔离的，
 * 端口不同就是不同 origin —— 所以在 5173 登录，5174 读不到 token。
 *
 * 这一点实测验证过：
 *     ON_5173=WRITTEN_ON_5173
 *     ON_5174=NULL(读不到)      ← 端口不同，读不到
 *     ON_5175=NULL(读不到)
 *
 * 而生产的部署方案（项目大纲 §7.5）是把三个应用放在【同一个域名】的
 * 三个路径下，由 Nginx 分发 —— 那是同一个 origin，localStorage 天然共享。
 *
 * 所以这里在开发期搭一个一模一样的拓扑：三个应用都收在 3000 端口下的
 * 三个路径里。这样开发的拓扑 == 生产的拓扑，
 * 「只在部署后才暴露」的那一类问题就不可能发生。
 *
 * ------------------------------------------------------------
 * 对照表：
 *   开发（本文件）              生产（Nginx）
 *   ────────────────────────    ──────────────────────────────
 *   :3000/reader/ → :5173       https://域名/reader/ → reader-web/dist
 *   :3000/author/ → :5174       https://域名/author/ → author-web/dist
 *   :3000/admin/  → :5175       https://域名/admin/  → admin-web/dist
 *   :3000/api/    → :8080       https://域名/api/    → gateway:8080
 * ------------------------------------------------------------
 *
 * 启动顺序：三个前端各自 npm run dev，然后这里 npm run dev。
 * 访问 http://localhost:3000/ 会看到一个导航页。
 */
export default defineConfig({
  server: {
    port: 3000,

    proxy: {
      // ---------- 后端 ----------
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/uploads': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },

      /*
       * ---------- 三个前端 ----------
       *
       * ws: true 是必须的 —— Vite 的热更新（HMR）走的是 WebSocket，
       * 不转发 WebSocket 的话页面能打开，但改代码不会自动刷新，
       * 而且控制台会一直报 WebSocket 连接失败。
       *
       * changeOrigin: true 让转发出去的请求 Host 头变成目标地址，
       * 否则 Vite 会认为请求不是发给自己的而拒绝。
       */
      '/reader': {
        target: 'http://localhost:5173',
        changeOrigin: true,
        ws: true,
      },
      '/author': {
        target: 'http://localhost:5174',
        changeOrigin: true,
        ws: true,
      },
      '/admin': {
        target: 'http://localhost:5175',
        changeOrigin: true,
        ws: true,
      },
    },
  },
})
