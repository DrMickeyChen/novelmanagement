<script setup>
/**
 * 阅读端整体布局：顶部导航（含搜索框） + 内容区。
 *
 * 登录页和阅读器不走这个布局（它们要全屏），所以布局挂在路由的父级上，
 * 而不是写在 App.vue 里。见 router/index.js。
 */
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { READER_WEB_URL, AUTHOR_WEB_URL, ADMIN_WEB_URL } from '@/utils/auth'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

/**
 * 顶部搜索框的关键词。
 *
 * ★ 初始值从当前 URL 取：用户已经在 /search?keyword=xxx 上了，
 *   输入框应该是 xxx，而不是空的 —— 否则「搜完想改一个字重搜」
 *   得先把词删了重打一遍。
 */
const keyword = ref(route.query.keyword || '')

/**
 * ★ 路由变化时把输入框同步过来。
 *
 * 为什么需要这段：用户点浏览器【后退】回到上一个搜索结果页时，
 * route.query.keyword 变了，但输入框是组件内的 ref，不会跟着变 ——
 * 页面显示 A 的搜索结果，输入框里却写着 B。
 * 不动手同步的话，这种不一致只在前进/后退时出现，很难注意到。
 *
 * ★ 只在【搜索页】同步：如果用户在别的页面（比如首页）往搜索框里打了半个词
 *   还没回车，这时路由一变就清空，会把他的输入冲掉。
 */
watch(
  () => route.query.keyword,
  (value) => {
    if (route.name === 'search') {
      keyword.value = value || ''
    }
  },
)

/** 提交搜索。空关键词不跳转 —— 服务端会返回空列表，跳过去是个没意义的空页面 */
function handleSearch() {
  const value = keyword.value.trim()
  if (!value) return
  router.push({ name: 'search', query: { keyword: value } })
}

/**
 * 跳到创作中心 / 管理端。
 *
 * ★ 用 location.href 而不是 router.push —— 这是【跨应用】跳转，
 *   本应用的路由器管不到另外两个应用（§7.5 第 2 条约定）。
 *   token 存在共用的 localStorage 里，跳过去自动就是登录态。
 *
 * ★ 必须包成一个函数，不能直接在模板里写 window.location.href ——
 *   Vue 的模板表达式【不暴露 window 这个全局对象】，模板里的标识符会被
 *   编译成 _ctx.window，运行时是 undefined，直接报错。
 *   模板里能用的全局只有 Math / Date / JSON 那一小撮白名单。
 */
function gotoApp(url) {
  window.location.href = url
}

/**
 * 退出登录。
 *
 * ★ 必须 await：logout() 要先请求后端把 Redis 里这条登录态删掉，
 *   不 await 的话请求可能还没发出去，页面就已经因为状态变了而重新渲染。
 *
 * ★ 退出后【不跳转】—— 阅读端的首页是公开页面，未登录也能看，
 *   停在原地正好让用户看到导航栏从「昵称 + 退出」变成了「登录」，
 *   这是最直观的反馈。
 *   （创作中心和管理端的布局不一样，那两个端退出后必须离开 —— 见那边的注释。）
 */
async function handleLogout() {
  await userStore.logout()
}
</script>

<template>
  <el-container class="layout">
    <el-header class="header">
      <div class="brand" @click="gotoApp(READER_WEB_URL)">
        📖 小说阅读
        <el-tag size="small" type="info" effect="plain">/reader/</el-tag>
      </div>

      <div class="nav">
        <router-link to="/" class="nav-link">首页</router-link>
        <router-link to="/rank" class="nav-link">排行榜</router-link>

        <!--
          搜索框。
          ★ 用 @keyup.enter 而不是包一个 <form> 提交：
            表单提交会让浏览器整页刷新（丢掉 SPA 的登录态以外的所有状态），
            还得额外 preventDefault。
          ★ 分类没有放在这里 —— 它没有独立的落地页（只有 /category/:id），
            入口在首页的分类区块里。理由见相关文档。
        -->
        <el-input
          v-model="keyword"
          class="search-input"
          placeholder="搜书名或作者名"
          clearable
          @keyup.enter="handleSearch"
        >
          <template #append>
            <el-button @click="handleSearch">搜索</el-button>
          </template>
        </el-input>

        <!--
          跨应用跳转：这两个是【另外两个独立应用】。
          地址走网关（同源），跳过去能读到本应用写的登录态。
        -->
        <el-button link type="primary" @click="gotoApp(AUTHOR_WEB_URL)">创作中心</el-button>
        <el-button link type="primary" @click="gotoApp(ADMIN_WEB_URL)">管理端</el-button>

        <el-divider direction="vertical" />

        <template v-if="userStore.isLogin">
          <el-tag type="success" size="small">{{ userStore.nickname || '已登录' }}</el-tag>
          <el-button link @click="handleLogout">退出</el-button>
        </template>
        <template v-else>
          <router-link to="/login" class="nav-link">登录</router-link>
        </template>
      </div>
    </el-header>

    <el-main class="main">
      <router-view />
    </el-main>
  </el-container>
</template>

<style scoped>
.layout {
  min-height: 100%;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  /*
   * ★ 高度从 56px 放到 64px 是为了容纳搜索框（el-input 默认 32px 高，
   *   加上下留白正好需要这么多）。同时 el-header 的默认高度也是 60px，
   *   写死一个值免得不同 Element Plus 版本表现不一致。
   */
  height: 64px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 17px;
  font-weight: 600;
  cursor: pointer;
  color: #409eff;
  white-space: nowrap;
}

.nav {
  display: flex;
  align-items: center;
  gap: 12px;
}

.nav-link {
  color: #303133;
  font-size: 14px;
  white-space: nowrap;
}

/*
 * ★ 当前路由高亮。用 :deep() 是因为 .router-link-active 是 vue-router
 *   加在 <a> 上的类，而 <a> 是模板里的元素、不是子组件 ——
 *   理论上不需要 :deep()。但 el-button 那种组件渲染出来的链接就需要，
 *   统一写法省得记。
 *
 * ★ exact-active 用于首页：不加 exact 的话，首页的"/"会匹配所有路由，
 *   于是在任何一个页面上「首页」都是高亮的。
 */
.nav-link.router-link-active {
  color: #409eff;
  font-weight: 600;
}

.router-link-exact-active {
  color: #409eff;
  font-weight: 600;
}

.search-input {
  width: 260px;
}

.main {
  max-width: 960px;
  width: 100%;
  margin: 0 auto;
}
</style>
