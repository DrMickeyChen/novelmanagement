<script setup>
/**
 * 根组件。
 *
 * 这里只放 <router-view />，真正的页面框架在 layouts/BasicLayout.vue 里 ——
 * 因为登录页【不需要】顶部导航（它是个独立的全屏页面），
 * 所以布局不能写在根组件上，必须由路由决定用哪个 layout。
 *
 * ------------------------------------------------------------
 * ★★ 关于 <el-config-provider> 和中文语言包（P3 补的）
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
 * ★ 放在 App.vue 的最外层，是为了让它管到【所有】路由 ——
 *   包括不走 BasicLayout 的登录页和阅读器。
 *
 * ★★ 它管不到【命令式 API】：ElMessageBox.confirm(...) 是直接调函数、
 *   临时挂一个节点到 body 上，不在这个组件的插槽里，所以拿不到这里的 locale。
 *   用到它的地方（比如「确认删除」的按钮文案）必须显式传参：
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
