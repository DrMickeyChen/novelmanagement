<script setup>
/**
 * 创作中心全局布局：顶部导航 + 左侧作品栏（§四）。
 *
 * P0 阶段左侧栏只是占位 —— 等 P1 有了真实的作品数据，
 * 这里会变成「我的作品列表」，点进去进入工作台（§四的
 * AppLayout → WorkLayout → NodeWorkspace 三层嵌套路由）。
 */
import { useUserStore } from '@/stores/user'
import { READER_WEB_URL, ADMIN_WEB_URL } from '@/utils/auth'

const userStore = useUserStore()

/** 跨应用跳转一律用 location.href，不能用 router.push（§7.5 第 2 条） */
function gotoApp(url) {
  window.location.href = url
}

function backToReader() {
  gotoApp(READER_WEB_URL)
}

/**
 * 退出登录。
 *
 * ★ 退完必须【离开这个页面】。
 *   创作中心的路由守卫要求 AUTHOR 角色，能停在这个页面本身就意味着
 *   「token 有效 + 角色对」。退出之后这两个条件都不成立了，
 *   但守卫不会自己重跑（没有发生路由跳转），结果是：
 *   页面还停在创作中心，顶栏变成了「未登录/未登录」，
 *   再点任何东西都会 401 被踢走 —— 看起来很莫名其妙。
 *
 * ★ 用 location.href 而不是 router.replace('/')：
 *   这是跨应用跳转，本应用的路由器管不到阅读端（§7.5 第 2 条）。
 *   而且硬跳转会整页重新加载，不存在「守卫被跳过」的可能。
 *
 * ★ 这里【故意不用 redirectToReaderLogin()】——
 *   那个函数会带上 ?redirect=<当前地址>，于是变成：
 *   退出 → 登录页 → 登录成功 → 跳回创作中心 → 又不是作者被拦 → 回登录页……
 *   一个死循环。它只适合「该登录而没登录」的场景，不适合「主动退出」。
 */
async function handleLogout() {
  await userStore.logout()
  gotoApp(READER_WEB_URL)
}
</script>

<template>
  <el-container class="layout">
    <el-header class="header">
      <div class="brand">
        ✍️ 创作中心
        <el-tag size="small" type="info" effect="plain">/author/</el-tag>
      </div>

      <div class="nav">
        <el-button link type="primary" @click="backToReader">阅读端</el-button>
        <el-button link type="primary" @click="gotoApp(ADMIN_WEB_URL)">管理端</el-button>

        <el-divider direction="vertical" />

        <!--
          这里显示的是【从共享 localStorage 读出来的】登录态。
          在阅读端登录后跳过来，这里直接就是已登录 —— 这正是 P0 要验证的。
        -->
        <template v-if="userStore.isLogin">
          <el-tag type="success" size="small">{{ userStore.nickname || '已登录' }}</el-tag>
          <el-tag v-if="userStore.role" size="small" effect="plain">{{ userStore.role }}</el-tag>
          <el-button link @click="handleLogout">退出</el-button>
        </template>
        <el-tag v-else type="danger" size="small">未登录</el-tag>
      </div>
    </el-header>

    <el-container>
      <el-aside width="200px" class="aside">
        <div class="aside-title">我的作品</div>
        <el-empty description="P1 接入真实作品数据" :image-size="60" />
      </el-aside>

      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  min-height: 100vh;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
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
  color: #67c23a;
}

.nav {
  display: flex;
  align-items: center;
  gap: 4px;
}

.aside {
  background: #fff;
  border-right: 1px solid #e4e7ed;
  padding: 16px 12px;
}

.aside-title {
  font-weight: 600;
  margin-bottom: 12px;
  color: #303133;
}

.main {
  background: #f5f7fa;
  padding: 20px;
}
</style>
