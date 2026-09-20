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
 * ★★ 但上面那句里的「模板里用到的」是个必须读到的限定词 ——
 *    漏掉它就会漏掉紧挨着下面那个样式。
 */
/*
 * ★★★ 补一个「按需引入管不到」的样式：ElMessageBox。
 *
 * 为什么和上面那句不矛盾：unplugin-vue-components 的 resolver 是
 * **看模板**决定引入什么的，而 ElMessageBox 是**在 JS 里调用**的
 * （`ElMessageBox.confirm(...)`），模板里根本没有它，
 * resolver 自然不会引任何东西。
 *
 * ★ 而且**手写 `import { ElMessageBox } from 'element-plus'` 会让
 *   unplugin-auto-import 也不介入** —— 它只负责「未声明的标识符」，
 *   而我们已经把它声明了。两边都不管，样式就漏了。
 *
 * ★★ 同一个漏法，`ElMessage` 那边【已经有人补过】——
 *    `utils/request.js` 顶部有一行
 *      import 'element-plus/es/components/message/style/css'
 *    连注释都写着「不引这一行，弹出来的提示会是个没样式的裸框」。
 *    所以 ElMessage 一直是好的；**漏掉的只有 ElMessageBox**。
 *
 * 症状很难联想到样式，所以写在这里：
 *   `.el-overlay-message-box` 的 `position: fixed; top:0; bottom:0; ...`
 *   就写在 `el-message-box.css` 里。这个文件没被引进来，那个 div 就
 *   退化成一个**普通的 static 块级元素挂在 body 末尾** ——
 *   于是「新建子节点」「重命名」「删除」这些确认框**全部出现在
 *   页面最下面**：没有遮罩、没有白底、按钮挤成一行。
 *   ★ 而且它是「时好时坏」的：`.el-overlay` 的样式本来只出现在
 *     el-dialog 所在的 chunk 里，而 el-dialog 在「章节管理」页 ——
 *     进过那个页面再回工作台，弹窗就从「页面最下面」变成「屏幕中间」。
 *     这个薛定谔现象就是「样式漏了」而不是「定位写错了」的铁证。
 *
 * ★ 为什么不用 `import 'element-plus/dist/index.css'`（全量样式）：
 *   那会把几百 KB、这个应用一行都用不到的 CSS 全塞进包里。
 *   `.../style/css` 是 element-plus 的**按需样式入口**，它会带上
 *   自己依赖的 base / input / button / overlay —— 确认框要用的东西
 *   一个都不会缺。
 *   ★ 最后那个 `overlay` 很关键：遮罩的样式本来只出现在 el-dialog
 *     所在的 chunk 里 —— 没进过章节管理页的人，连遮罩都看不到。
 *     按需样式入口把这条隐蔽的依赖也一起堵上了。
 */
import 'element-plus/es/components/message-box/style/css'

import './styles/index.css'

const app = createApp(App)

app.use(createPinia())  // Pinia 要在 router 之前注册 —— 路由守卫里会用到 store
app.use(router)

app.mount('#app')
