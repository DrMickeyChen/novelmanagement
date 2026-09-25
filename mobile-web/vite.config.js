import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

/*
 * ★★ 这里【不能】再手动 import @vitejs/plugin-vue。
 *
 * `@dcloudio/vite-plugin-uni` 自己就把 `@vitejs/plugin-vue@5.2.4` 写在 dependencies 里，
 * 由它统一注册。再手动加一次 = 每个 .vue 被两个 vue 插件各编译一遍，
 * 症状是报 `At least one <template> or <script> is required in a single file component`
 * —— 而文件里明明有 template。看到这个报错先来查这一行。
 *
 * ★ 5176 端口写在这里（而不是 manifest.json 的 h5.devServer）：
 *   这是标准 Vite 语义，行为确定；uni 插件会把它透传给 dev server。
 */
export default defineConfig({
  plugins: [uni()],
  server: {
    port: 5176,

    /*
     * ★ 与三端和 dev-proxy 同款：端口被占时【直接失败】，不要静默漂移到 5177。
     * 漂移不报错的后果在 dev-proxy 那份注释里写全了，这里是同一个理由 ——
     * 界面看着正常，其实连着上一个还活着的进程。
     */
    strictPort: true,
    host: '127.0.0.1',

    /*
     * ★★★ 这段 proxy 是【给 HBuilderX「运行到浏览器」用的】，它不改变主入口。
     *
     * 背景（一次真实的坑）：H5 的接口地址默认值是【相对】的 `/api`
     * （见 src/utils/request.js 里那条按平台分叉的 DEFAULT_API_BASE）——
     * 相对地址要靠**同源的转发**才有意义。设计好的那层转发在 dev-proxy(`:3000`) 上，
     * 所以从 `:3000/mobile/` 打开时一行配置都不用配。
     * 而 HBuilderX 运行到浏览器时**直接起这个 dev server**（不经过 `:3000`、
     * 也不经过 npm 脚本），于是 `/api/...` 打到了 5176 自己身上 ——
     * 症状是「页面能开、每个接口都连不上」，而它看起来完全像【后端没起】。
     * ★ 加这段之后，**5176 直开**与 **`:3000/mobile/`** 两条路都能通接口。
     *
     * ★★ 为什么不去把 H5 的地址改成绝对地址（那样就不需要转发这一层）：
     *    实测网关**不发 CORS 头** —— 带 `Origin` 的请求没有 `Access-Control-Allow-Origin`，
     *    预检 `OPTIONS` 直接 **403**，且整个 `backend/` 里 CORS 配置 **0 条**。
     *    改绝对地址只会让 H5 从「连不上」变成「被浏览器拦」，
     *    而后者那个报错**更像配置问题**，更难查。
     *
     * ★★ 而它**不解决**另一件事，别把它读成「5176 从此也是入口了」：
     *    localStorage 按 origin 隔离 —— 在 5176 上登录，`/reader/` 里读不到
     *    （CLAUDE.md 那条承重条款：移动端必须挂在 `:3000` 下）。
     *    **验收与演示仍然走 `:3000/mobile/`**，这段只是让「在 HBuilderX 里点一下就跑」成立。
     *
     * ★ `/uploads` 必须一起转：H5 上 assetUrl() 取的 origin 就是
     *   `window.location.origin`（asset.js 那个 IIFE 的第 ② 种情形），
     *   5176 直开时它算出来的是 5176 —— 只转 `/api` 的话，
     *   症状是「接口全通了、头像全 404」，而它看起来像上传坏了。
     *   （与本仓库 dev-proxy 那份**同形**：target 指网关、changeOrigin、**不 rewrite**。）
     */
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
