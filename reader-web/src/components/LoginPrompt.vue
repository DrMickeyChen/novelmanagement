<script setup>
/**
 * 未登录引导条 —— 书评区 / 章评区 / 书架空态三处共用。
 *
 * ★★ 为什么要有这个组件：P6 之前读者端**没有任何「未登录」的界面**。
 *   首页、分类、详情、阅读器全是匿名可读的，唯一需要登录的是
 *   P3 没做的收藏/评分。P6 一下多出好几处（发评论、回复、点赞、书架），
 *   所以必须**先定一套说法**，否则三处会各自长成一个样子：
 *   一处弹 toast、一处把入口藏起来、一处直接跳登录页。
 *
 * ------------------------------------------------------------
 * ★ 本组件定的四条规矩（改的时候三处一起改，别只改一处）
 * ------------------------------------------------------------
 * ① **不隐藏功能入口，把入口换成说明 + 「去登录」**。
 *    直接不渲染的话，游客根本不知道这里本来有什么，
 *    也就不知道登录能换来什么 —— 他只会觉得「这个页面是空的」。
 * ② **引导语说「登录后能做什么」，不说「请先登录」**。
 *    后者只描述了我们的要求，没给用户任何理由。
 * ③ **不自动跳转**。跳转永远由用户点这个按钮触发。
 *    读到一半被弹去登录页是最糟的一种体验，而「点了一下才发现要登录」
 *    只损失一次点击。
 * ④ **登录后回到原地**（带 redirect）。不带的话用户登录完落在首页，
 *    想做的事（发这条评论）得自己重新找回来 —— 那多半就不会再做了。
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'

defineProps({
  /** 引导语。★ 写「登录后可以发表书评」，别写「请先登录」 */
  text: {
    type: String,
    required: true,
  },
})

const route = useRoute()

/**
 * 登录页地址（带 redirect）。
 *
 * ★ 用 `route.fullPath` 而不是 `route.path`：fullPath 带 query，
 *   从「分类页筛出来的详情页」跳去登录再回来时，筛选条件还在。
 *
 * ★ **必须 encodeURIComponent**：fullPath 里的 `?` `&` `=` 会直接和
 *   `/login?redirect=` 自己的 query 串在一起，登录页只会收到
 *   `redirect=/novel/1`，剩下那半截变成登录页自己的查询参数 ——
 *   表现是「登录完跳回了一个没有筛选条件的页面」，不报错。
 *   （登录页那边的开放重定向防护照常生效，这里只是别把地址拼坏。）
 *
 * ★ 用 `/login?...` 字面量而不是 `router.resolve({name:'login'})`：
 *   本仓所有跳转都是字面量路径（见 `views/novel/index.vue` 的
 *   `/read/${novelId}/${chapterId}`），保持一致。
 */
const loginPath = computed(() => `/login?redirect=${encodeURIComponent(route.fullPath)}`)
</script>

<template>
  <div class="login-prompt">
    <span class="text">{{ text }}</span>
    <router-link :to="loginPath">
      <el-button type="primary" link>去登录</el-button>
    </router-link>
  </div>
</template>

<style scoped>
/*
 * ★ 刻意长成一条「虚线框的提示条」而不是一个 el-alert：
 *   它出现在评论区/书架的正中间，位置就已经说明了它和下面那块内容的关系，
 *   再加一个带图标、带关闭按钮的告警框会显得像出了错。
 */
.login-prompt {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 16px;
  border: 1px dashed #dcdfe6;
  border-radius: 4px;
  color: #909399;
  font-size: 14px;
}

.text {
  color: #606266;
}
</style>
