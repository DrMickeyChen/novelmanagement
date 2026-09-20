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
 * unplugin-vue-components 做按需引入，样式会跟着组件一起自动引进来，
 * 全量引入反而会让打包体积大十几倍。
 */
import './styles/index.css'

const app = createApp(App)

app.use(createPinia())  // Pinia 要在 router 之前注册 —— 路由守卫里会用到 store
app.use(router)

app.mount('#app')
