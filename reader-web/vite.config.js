import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// https://vite.dev/config/
export default defineConfig({
  /*
   * ★ base —— 应用部署在【子路径】下，不是域名根目录。
   *
   * 生产环境三个前端挂在同一个域名的三个路径下（§7.5）：
   *     https://域名/reader/   ← 本应用
   *     https://域名/author/
   *     https://域名/admin/
   *
   * 不设 base 的话，Vite 产出的 index.html 里资源路径是 /assets/xxx.js，
   * 在 /reader/ 下会去域名根目录找，404 白屏。
   * 设成 '/reader/' 之后路径变成 /reader/assets/xxx.js，才对得上。
   *
   * 开发期也一样 —— 三个应用由 dev-proxy（:3000）按路径分发，
   * 开发拓扑和生产拓扑保持一致，避免「上线才发现路径不对」。
   *
   * ★ 路由那边不用改：router 里写的是 createWebHistory(import.meta.env.BASE_URL)，
   *   BASE_URL 会自动等于这里的 base，路由前缀跟着一起走。
   *
   * ★ /api 也不受影响：request.js 里 baseURL 是 '/api'，是绝对路径，
   *   不会被 base 拼接（base 只影响相对路径和资源引用）。
   */
  base: '/reader/',

  plugins: [
    vue(),

    /*
     * Element Plus 按需引入（官方推荐做法）。
     *
     * 不用这两个插件的话，只能全量引入：
     *     import ElementPlus from 'element-plus'
     *     import 'element-plus/dist/index.css'
     *     app.use(ElementPlus)
     * 那样会把整个组件库打进产物（约 1MB gzip），而我们实际用到的
     * 可能只有十几个组件。
     *
     * 装了这两个插件之后：
     *   - AutoImport   负责 API 类：ref / computed / onMounted / ElMessage ...
     *   - Components   负责组件类：<el-button> / <el-table> ...（模板里写了才引入）
     * 两者都会【连带引入对应的样式文件】，所以不用再手动 import css。
     *
     * ★ 面试常问：「Element Plus 怎么按需引入？」答的就是这套。
     */
    AutoImport({
      // 这三个的 API 也不用再手写 import 了，直接用 ref / computed / useRouter 等
      imports: ['vue', 'vue-router', 'pinia'],
      resolvers: [ElementPlusResolver()],
      // 纯 JS 项目不需要生成类型声明文件，关掉免得项目根目录多出两个 .d.ts
      dts: false,
    }),
    Components({
      resolvers: [ElementPlusResolver()],
      dts: false,
    }),
  ],

  resolve: {
    alias: {
      // 用 @ 指向 src，避免 ../../../ 这种相对路径地狱
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },

  server: {
    port: 5173,

    /*
     * ★ 本应用自己的代理 —— 「proxy 打通」是 P0 的验收项之一。
     *
     * 注意别和根目录的 dev-proxy/ 搞混，是两个东西：
     *   - 这里（server.proxy）：管【跨域】。本应用的 /api 转发到后端。
     *   - dev-proxy/：管【同源】。把三个应用收进 :3000 的三个路径下。
     * 前者各应用都有自己的，后者只有一份。
     *
     * 为什么需要它：前端跑在 localhost:5173，后端在 localhost:8080，
     * 浏览器会当成【跨域】请求而拦截。
     *
     * 这里配好之后，前端代码里写的 /api/ping 会被 Vite 转发到
     * http://localhost:8080/api/ping，而且是在 Node 层面转发 ——
     * 浏览器只看到「同源请求」，根本不触发跨域检查。
     *
     * ★ 所以后端【不需要】配 @CrossOrigin，也不需要 CorsFilter。
     *   很多人前后端都要跨域了就去后端加跨域配置，那是多余且更危险的
     *   （通配符的 Access-Control-Allow-Origin 是真会出事的）。
     *
     * 生产环境用 Nginx 做同样的转发，见项目大纲 §7.5。
     */
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // 上传的文件（封面图/头像）也走后端，一并转发
      '/uploads': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
