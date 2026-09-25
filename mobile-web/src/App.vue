<script setup>
import { onLaunch, onShow, onPageNotFound } from '@dcloudio/uni-app'

/*
 * 应用级生命周期。App.vue 是 uni-app 里唯一【不需要 <template>】的组件 ——
 * 它不是一个页面，是各端运行时的宿主。
 */
onLaunch(() => {
  // 启动日志刻意留一行：验收时用它确认「跑的是哪个端、哪一版」。
  const sys = uni.getSystemInfoSync()
  console.log(`[mobile-web] launch uniPlatform=${sys.uniPlatform} v=${sys.appVersion}`)
})

onShow(() => {})

/*
 * ★ 404 兜底 —— 这一条在 uni-app 里【必须显式写】，不能像 vue-router 那样配一条 `*` 路由。
 *
 * 原因：uni-app 的路由表来自 pages.json，而 pages.json **没有 catch-all 的写法**。
 * H5 端命中一个不在表里的路径时，uni-app 的默认行为是**回落到首个页面**（= 首页），
 * 于是一个错链接看起来「正常打开了首页」—— 静默，且和「首页」无法区分。
 * 所以这里显式接住它，跳到一个真实存在的错误页（`pages/error/404`）。
 * ★ 那个页面**没有 catch-all 路由可依赖**这件事本身，就写在那份文件的头部。
 */
onPageNotFound((res) => {
  console.warn('[mobile-web] 页面不存在：', res.path)
  uni.reLaunch({ url: '/pages/error/404' })
})
</script>

<style>
/*
 * 全局公共样式。★ 这里只放【真正全局】的东西；
 * 三端那份 styles/index.css 里的桌面端规则（html/body/#app 选择器、桌面栅格）
 * 在 uni-app 里大部分没有对应物，不做搬运 —— 见 docs/E3-uni-app.md「明确不改」。
 */
page {
  background-color: #f5f6f8;
  color: #1f2329;
  font-size: 28rpx;
}
</style>
