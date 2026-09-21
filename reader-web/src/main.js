import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import router from './router'

/*
 * 注意这里【没有】这两行：
 *     import ElementPlus from 'element-plus'
 *     import 'element-plus/dist/index.css'
 *
 * 因为已经在 vite.config.js 里配了 unplugin-auto-import /
 * unplugin-vue-components 做按需引入，**模板里用到的组件**的样式
 * 会跟着组件一起自动引进来，全量引入反而会让打包体积大十几倍。
 *
 * ★★ 补一个「按需引入管不到」的样式：ElMessageBox。
 *    它是**在 JS 里调用**的（`ElMessageBox.confirm(...)`），模板里没有它，
 *    resolver 看模板决定引什么，于是什么都不会引 ——
 *    弹出来是个**没有遮罩、按钮错位的裸框**，而页面其它中文全是好的，
 *    极难联想到是样式。
 *    ★ 完整论证（含「时好时坏」那个薛定谔现象）在
 *    `author-web/src/main.js` 里写过一遍，这里不重复。
 *
 * ★ 本端为什么也需要它：P6 起读者端有确认框了 ——
 *   「移除书架」和「删除自己的评论」都是不可逆动作，必须二次确认。
 *   在 P6 之前 reader-web 一个 ElMessageBox 都没用过，所以一直没暴露。
 */
import 'element-plus/es/components/message-box/style/css'

import './styles/index.css'

/*
 * ★ 窄屏适配（P6 S15）。**必须排在 `index.css` 之后** ——
 *   它里面有覆盖 Element Plus 默认值的规则（确认框宽度），
 *   顺序反了就会被 `index.css` 里的东西压回去。
 *   （`index.css` 是 md5 锁定的，不能改它，只能在自己这侧排好顺序。）
 */
import './styles/responsive.css'

const app = createApp(App)

app.use(createPinia())  // Pinia 要在 router 之前注册 —— 路由守卫里会用到 store
app.use(router)

app.mount('#app')
