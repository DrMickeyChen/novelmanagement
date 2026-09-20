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
 * 菜单结构。ready: false 的项只展示不可点（业务页面还没做）。
 *
 * 这些路径来自 项目大纲 §四 的 admin-web/views 清单。
 *
 * ★ perm 是「看到这一项需要哪个权限点」，取值必须来自 sys_menu 表里已有的
 *   perms（见 docs/sql/seed.sql），【不要临时造一个新的】——
 *   造了它就得同时往 sys_menu 和 sys_role_menu 里加数据，
 *   否则那个菜单永远没人看得见，而且不报错。
 *
 * ★ 「章节管理」挂的是 content:novel:list 而不是一个独立的章节权限：
 *   sys_menu 里没有 content:chapter:*，章节本来就是作品的下级视图
 *   （一章属于某本书，接口也是 /api/author/novels/{novelId}/chapters）。
 *   为一个从作品管理进去的子页面单开权限点，只会让角色配置更碎。
 */
const menus = ref([
  { path: '/', title: '工作台', icon: '📊', ready: true },
  { path: '/novel', title: '小说管理', icon: '📚', ready: false, perm: 'content:novel:list' },
  { path: '/chapter', title: '章节管理', icon: '📄', ready: false, perm: 'content:novel:list' },
  { path: '/category', title: '分类管理', icon: '🏷️', ready: false, perm: 'content:category:list' },
  { path: '/comment', title: '评论审核', icon: '💬', ready: false, perm: 'content:comment:list' },
  { path: '/user', title: '用户管理', icon: '👥', ready: false, perm: 'system:user:list' },
  { path: '/author-apply', title: '作者申请', icon: '📝', ready: false, perm: 'system:author:apply' },
  { path: '/ai', title: 'AI 配置', icon: '🤖', ready: false, perm: 'system:ai:config' },
  { path: '/log', title: '审核日志', icon: '📋', ready: false, perm: 'ops:auditlog:list' },
])

/**
 * 按权限过滤后的菜单。没有 perm 的项（工作台）对所有人可见。
 *
 * ★★ <b>这是「少显示一个链接」，不是「挡住一个操作」—— 千万别当成安全措施。</b>
 * 真正的门在服务端：{@code SecurityConfig} 里 {@code /api/admin/**} 要求 ADMIN 角色，
 * 作者就算手动改 localStorage 把菜单变出来，点进去的每个请求也都会拿到 403。
 * 反过来，如果有人以为「菜单藏起来了就安全」，从而把服务端那道闸撤掉，
 * 那就成了真的没人拦 —— 菜单过滤挡不住任何 HTTP 客户端。
 *
 * ★ 权限点的来源是 localStorage 里那份 userInfo（登录时后端返回、见 utils/auth.js）。
 * 所以管理员在后台改了某个角色的权限，那个用户要【重新登录】才会看到菜单变化。
 * 这是有意的取舍：每次渲染菜单都去问一次后端，会让首屏多一次请求，
 * 而权限变更本来就不是高频操作。
 */
const visibleMenus = computed(() => menus.value.filter((m) => !m.perm || userStore.hasPerm(m.perm)))

function gotoApp(url) {
  window.location.href = url
}

/**
 * 退出登录。
 *
 * ★ 退完必须离开 —— 理由和 author-web/src/layouts/AppLayout.vue 里
 *   写的一模一样：路由守卫挡住了「没登录就进不来」，
 *   但退出不会触发路由跳转，守卫不会重跑，页面就卡在管理端了。
 *
 * ★ 也【不能用 redirectToReaderLogin()】—— 它带的 ?redirect= 会让用户
 *   登录后跳回管理端，不是管理员就再被拦回来，形成死循环。
 */
async function handleLogout() {
  await userStore.logout()
  gotoApp(READER_WEB_URL)
}
</script>

<template>
  <el-container class="layout">
    <el-aside width="210px" class="aside">
      <div class="logo">🛠️ 管理端</div>
      <el-menu :default-active="activeMenu" class="menu" :router="false">
        <el-menu-item
          v-for="m in visibleMenus"
          :key="m.path"
          :index="m.path"
          :disabled="!m.ready"
          @click="m.ready && $router.push(m.path)"
        >
          <span class="icon">{{ m.icon }}</span>
          <span>{{ m.title }}</span>
          <!-- ★ 标签写「待开发」而不是某个期号：期号会随排期变化，
               而这一项的真相只是「页面还没做」，写期号迟早变成错的 -->
          <el-tag v-if="!m.ready" size="small" type="info" effect="plain" class="pending">
            待开发
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
            <el-button link @click="handleLogout">退出</el-button>
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
