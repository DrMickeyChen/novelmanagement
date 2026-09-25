import { createSSRApp } from 'vue'
import * as Pinia from 'pinia'
import App from './App.vue'

/*
 * ★ 入口必须【导出一个 createApp 函数】，不能像普通 Vue 应用那样直接 app.mount()。
 *   uni-app 的各端运行时（H5 / 小程序 / App）自己会调它，由它决定挂载时机。
 *
 * ★ 用 createSSRApp 而不是 createApp —— 这是 uni-app 的硬性要求
 *   （各端共用同一份入口代码，小程序端需要 SSR 形态的应用实例）。
 *
 * ★ 返回值里必须把 Pinia 一起交出去：小程序端的运行时拿不到模块作用域，
 *   只能从返回值里取到 Pinia 实例。这是官方文档里 pinia + uni-app 的固定写法，
 *   少写这一项 H5 可能看不出问题，小程序端会挂。
 */
export function createApp() {
  const app = createSSRApp(App)
  app.use(Pinia.createPinia())
  return { app, Pinia }
}
