<script setup>
/**
 * 根组件。
 *
 * 这里只放 <router-view />，真正的页面框架在 layouts/AppLayout.vue 里 ——
 * 因为将来若有独立的全屏页面（比如编辑器全屏模式），它不该套着左侧作品栏，
 * 所以布局不能写在根组件上，必须由路由决定用哪个 layout。
 *
 * ------------------------------------------------------------
 * ★★ 关于 <el-config-provider> 和中文语言包（P4 从 reader-web 抄过来的）
 * ------------------------------------------------------------
 * 加它之前，全站 grep `zh-cn` 是【零命中】—— main.js 里没有 app.use(ElementPlus)
 * （按需引入），也就没有人去装语言包。后果不是报错，而是 Element Plus 自带的
 * 文案全是英文，且【不报任何错】：
 *
 *     el-pagination  →  "Total 137"、"10/page"、"Go to"
 *     el-empty       →  "No Data"
 *     el-popconfirm  →  "OK" / "Cancel"
 *
 * 这种「界面语言不对但一切正常运行」的问题，测试用例是发现不了的 ——
 * 只有真的打开页面看一眼才会注意到。
 *
 * ★ 为什么用 <el-config-provider> 而不是 app.use(ElementPlus, { locale: zhCn })：
 *   按需引入的写法下根本没有 app.use(ElementPlus) 这一句，无从挂起。
 *   ConfigProvider 是官方为「按需引入 + 局部定制」准备的方案：
 *   它通过 provide/inject 往下传配置，包住谁就管谁。
 *
 * ★ 放在 App.vue 的最外层，是为了让它管到【所有】路由。
 *
 * ★★ 它管不到【命令式 API】：ElMessageBox.confirm(...) 是直接调函数、
 *   临时挂一个节点到 body 上，不在这个组件的插槽里，所以拿不到这里的 locale。
 *   创作中心里用到它的地方特别多（删作品、删章节、删大纲子树全都是确认框），
 *   每一处都必须显式传参：
 *       ElMessageBox.confirm(msg, title, { confirmButtonText: '确定', cancelButtonText: '取消' })
 *   忘了传的话，那两个按钮会是 "OK" / "Cancel"。
 *   （ElMessage 只显示一句话，没有按钮，所以不受影响。）
 */
import zhCn from 'element-plus/es/locale/lang/zh-cn'
</script>

<template>
  <el-config-provider :locale="zhCn">
    <router-view />
  </el-config-provider>
</template>
