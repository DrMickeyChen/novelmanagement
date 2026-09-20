<script setup>
/**
 * 阅读端整体布局：顶部导航 + 内容区。
 *
 * 登录页不走这个布局（它需要全屏），所以布局挂在路由的父级上，
 * 而不是写在 App.vue 里。
 */
import { useUserStore } from '@/stores/user'
import { READER_WEB_URL, AUTHOR_WEB_URL, ADMIN_WEB_URL } from '@/utils/auth'

const userStore = useUserStore()

/**
 * 跳到创作中心 / 管理端。
 *
 * ★ 用 location.href 而不是 router.push —— 这是【跨应用】跳转，
 *   本应用的路由器管不到另外两个应用（§7.5 第 2 条约定）。
 *   token 存在共用的 localStorage 里，跳过去自动就是登录态。
 */
function gotoApp(url) {
  window.location.href = url
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
        <router-link to="/">首页</router-link>

        <el-divider direction="vertical" />

        <!--
          跨应用跳转：这两个是【另外两个独立应用】。
          地址走网关（同源），跳过去能读到本应用写的登录态。
        -->
        <el-button link type="primary" @click="gotoApp(AUTHOR_WEB_URL)">创作中心</el-button>
        <el-button link type="primary" @click="gotoApp(ADMIN_WEB_URL)">管理端</el-button>

        <el-divider direction="vertical" />

        <template v-if="userStore.isLogin">
          <el-tag type="success" size="small">{{ userStore.nickname || '已登录' }}</el-tag>
          <el-button link @click="userStore.logout()">退出</el-button>
        </template>
        <template v-else>
          <router-link to="/login">登录</router-link>
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
  height: 56px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 17px;
  font-weight: 600;
  cursor: pointer;
  color: #409eff;
}

.nav {
  display: flex;
  align-items: center;
  gap: 4px;
}

.main {
  max-width: 960px;
  width: 100%;
  margin: 0 auto;
}
</style>
