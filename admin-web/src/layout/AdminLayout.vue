<script setup>
/**
 * 管理端布局：左侧菜单 + 顶栏 + 内容区（§四）。
 *
 * P0 阶段菜单里只有「工作台」是真能点的，其余按 §四 的页面清单列出来
 * 但标为未开放 —— 这样一眼能看出整体规划，也不会点进去 404。
 * 等 P1 之后每做完一页就把对应项的 disabled 去掉。
 */
import { ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { READER_WEB_URL, AUTHOR_WEB_URL } from '@/utils/auth'

const route = useRoute()
const userStore = useUserStore()

const activeMenu = computed(() => route.path)

/**
 * 菜单结构。ready: false 的项在 P0 只展示不可点。
 *
 * 这些路径来自 项目大纲 §四 的 admin-web/views 清单。
 */
const menus = ref([
  { path: '/', title: '工作台', icon: '📊', ready: true },
  { path: '/novel', title: '小说管理', icon: '📚', ready: false },
  { path: '/chapter', title: '章节管理', icon: '📄', ready: false },
  { path: '/category', title: '分类管理', icon: '🏷️', ready: false },
  { path: '/comment', title: '评论审核', icon: '💬', ready: false },
  { path: '/user', title: '用户管理', icon: '👥', ready: false },
  { path: '/author-apply', title: '作者申请', icon: '📝', ready: false },
  { path: '/ai', title: 'AI 配置', icon: '🤖', ready: false },
  { path: '/log', title: '审核日志', icon: '📋', ready: false },
])

function gotoApp(url) {
  window.location.href = url
}
</script>

<template>
  <el-container class="layout">
    <el-aside width="210px" class="aside">
      <div class="logo">🛠️ 管理端</div>
      <el-menu :default-active="activeMenu" class="menu" :router="false">
        <el-menu-item
          v-for="m in menus"
          :key="m.path"
          :index="m.path"
          :disabled="!m.ready"
          @click="m.ready && $router.push(m.path)"
        >
          <span class="icon">{{ m.icon }}</span>
          <span>{{ m.title }}</span>
          <el-tag v-if="!m.ready" size="small" type="info" effect="plain" class="pending">
            P1
          </el-tag>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="left">
          <span class="page-title">{{ route.meta.title || '工作台' }}</span>
          <el-tag size="small" type="info" effect="plain">/admin/</el-tag>
        </div>

        <div class="right">
          <el-button link type="primary" @click="gotoApp(READER_WEB_URL)">阅读端</el-button>
          <el-button link type="primary" @click="gotoApp(AUTHOR_WEB_URL)">创作中心</el-button>

          <el-divider direction="vertical" />

          <template v-if="userStore.isLogin">
            <el-tag type="success" size="small">{{ userStore.nickname || '已登录' }}</el-tag>
            <el-tag v-if="userStore.role" size="small" effect="plain">{{ userStore.role }}</el-tag>
            <el-button link @click="userStore.logout()">退出</el-button>
          </template>
          <el-tag v-else type="danger" size="small">未登录</el-tag>
        </div>
      </el-header>

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

.aside {
  background: #304156;
  overflow-y: auto;
}

.logo {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 17px;
  font-weight: 600;
  border-bottom: 1px solid #3a4c60;
}

.menu {
  border-right: none;
  background: #304156;
}

/* Element Plus 的菜单默认是浅色的，这里改成深色侧边栏配色 */
.menu :deep(.el-menu-item) {
  color: #bfcbd9;
}

.menu :deep(.el-menu-item:hover) {
  background: #263445;
  color: #fff;
}

.menu :deep(.el-menu-item.is-active) {
  background: #263445;
  color: #409eff;
}

.menu :deep(.el-menu-item.is-disabled) {
  opacity: 0.5;
}

.icon {
  margin-right: 8px;
}

.pending {
  margin-left: auto;
  transform: scale(0.85);
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

.left,
.right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.page-title {
  font-weight: 600;
  font-size: 15px;
}

.main {
  background: #f5f7fa;
  padding: 20px;
}
</style>
