<script setup>
/**
 * 管理端布局：左侧菜单 + 顶栏 + 内容区（§四）。
 *
 * 菜单结构**照抄 docs/sql/seed.sql 里 sys_menu 的三棵树**
 * （内容管理 / 用户管理 / 运营管理），不是随手排的 ——
 * 这样「界面上的分组」和「角色权限树里的分组」是同一个东西，
 * 管理员在角色页勾选时看到的层级，和侧栏里看到的一模一样。
 *
 * ready: false 的项只展示不可点（业务页面还没做）。
 * P5 每做完一页就把对应项的 ready 翻成 true。
 */
import { ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { READER_WEB_URL, AUTHOR_WEB_URL } from '@/utils/auth'

const route = useRoute()
const userStore = useUserStore()

const activeMenu = computed(() => route.path)

/**
 * 菜单树。结构对应 sys_menu 的 parent_id（1 / 6 / 12 三个根）。
 *
 * ★ perm 是「看到这一项需要哪个权限点」，取值必须来自 sys_menu 表里已有的
 *   perms（见 docs/sql/seed.sql），【不要临时造一个新的】——
 *   造了它就得同时往 sys_menu 和 sys_role_menu 里加数据，
 *   否则那个菜单永远没人看得见，而且不报错。
 *
 * ★ 「作品管理」和「审核中心」是**两个不同的权限点**
 *   （content:novel:list / content:audit:list）：
 *   能看作品列表不等于能审。分工不同 —— 运营看列表，
 *   审核员才动状态机。所以它们是并列的两项，不是同一页的两个 tab。
 *
 * ★ 「章节管理」**没有**独立的菜单项，也没有独立的权限点：
 *   sys_menu 里没有 content:chapter:*，章节本来就是作品的下级视图
 *   （一章属于某本书，接口也是按 novelId 进去的）。
 *   章节的审核入口在「审核中心」里某一行的「审章节」按钮上 ——
 *   因为管理员的真实动作是「审完这本书的全部章节」，
 *   而不是「在一堆散落的章节里挑着审」。
 *   为一个从作品进去的子页面单开权限点，只会让角色配置更碎。
 *
 * ★ 「违禁审核」是第三期 E1 的（AI 自动过滤），先列出来占位。
 */
const menus = ref([
  { path: '/', title: '工作台', icon: '📊', ready: true },

  {
    title: '内容管理',
    icon: '📚',
    children: [
      { path: '/novel', title: '作品管理', icon: '📖', ready: true, perm: 'content:novel:list' },
      { path: '/audit', title: '审核中心', icon: '✅', ready: true, perm: 'content:audit:list' },
      { path: '/comment', title: '评论管理', icon: '💬', ready: true, perm: 'content:comment:list' },
      { path: '/category', title: '分类管理', icon: '🏷️', ready: true, perm: 'content:category:list' },
    ],
  },

  {
    title: '用户管理',
    icon: '👥',
    children: [
      { path: '/user', title: '用户列表', icon: '👤', ready: true, perm: 'system:user:list' },
      { path: '/author-apply', title: '作者申请', icon: '📝', ready: true, perm: 'system:author:apply' },
      { path: '/role', title: '角色管理', icon: '🔑', ready: true, perm: 'system:role:list' },
      { path: '/menu', title: '菜单管理', icon: '🗂️', ready: true, perm: 'system:menu:list' },
      { path: '/ai', title: 'AI 配置', icon: '🤖', ready: false, perm: 'system:ai:config' },
    ],
  },

  {
    title: '运营管理',
    icon: '📈',
    children: [
      { path: '/notice', title: '公告管理', icon: '📢', ready: true, perm: 'ops:notice:list' },
      { path: '/log', title: '审核日志', icon: '📋', ready: true, perm: 'ops:auditlog:list' },
      { path: '/ai-audit', title: '违禁审核', icon: '⚠️', ready: false, perm: 'ops:aiaudit:list' },
    ],
  },
])

/**
 * 按权限过滤后的菜单。没有 perm 的项（工作台）对所有人可见。
 *
 * ★★ 分组的可见性是【算出来的】，不是写死的：一个分组只要还有
 *   一个子项可见就保留。写死「内容管理永远可见」的话，
 *   一个只被授了「用户列表」的角色，左侧会挂着一个点开全是空的
 *   「内容管理」—— 看起来像加载失败。
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
 * ★ P5 的角色管理页改了角色之后会**主动踢对方下线**（tokenStore.removeAll），
 *   所以这个「要重新登录」不再是一个需要口头解释的事 —— 对方被迫重新登，
 *   登录时就拿到新权限了。这一条见 docs/P5-管理后台.md。
 */
const visibleMenus = computed(() =>
  menus.value
    .map((m) => {
      if (!m.children) {
        return !m.perm || userStore.hasPerm(m.perm) ? m : null
      }
      const children = m.children.filter((c) => !c.perm || userStore.hasPerm(c.perm))
      return children.length ? { ...m, children } : null
    })
    .filter(Boolean),
)

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
        <template v-for="m in visibleMenus" :key="m.path || m.title">
          <!-- 单项（工作台） -->
          <el-menu-item
            v-if="!m.children"
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

          <!-- 分组 -->
          <el-sub-menu v-else :index="m.title">
            <template #title>
              <span class="icon">{{ m.icon }}</span>
              <span>{{ m.title }}</span>
            </template>
            <el-menu-item
              v-for="c in m.children"
              :key="c.path"
              :index="c.path"
              :disabled="!c.ready"
              @click="c.ready && $router.push(c.path)"
            >
              <span class="icon">{{ c.icon }}</span>
              <span>{{ c.title }}</span>
              <el-tag v-if="!c.ready" size="small" type="info" effect="plain" class="pending">
                待开发
              </el-tag>
            </el-menu-item>
          </el-sub-menu>
        </template>
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
.menu :deep(.el-menu-item),
.menu :deep(.el-sub-menu__title) {
  color: #bfcbd9;
}

.menu :deep(.el-menu-item:hover),
.menu :deep(.el-sub-menu__title:hover) {
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

/*
 * ★ 分组展开后的子项要**再深一档**，否则二级项和父级标题同色，
 *   缩进又不明显，看起来是一排平级的菜单。
 */
.menu :deep(.el-sub-menu .el-menu) {
  background: #1f2d3d;
}

.menu :deep(.el-sub-menu .el-menu-item) {
  background: #1f2d3d;
  min-width: auto;
}

.menu :deep(.el-sub-menu .el-menu-item:hover),
.menu :deep(.el-sub-menu .el-menu-item.is-active) {
  background: #17212e;
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
