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
  },
})
