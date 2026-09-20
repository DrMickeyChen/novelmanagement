<script setup>
/**
 * 创作中心全局布局：顶部导航 + 左侧作品栏。
 *
 * ============================================================
 * ★★ 左侧栏为什么是【作品列表】而不是菜单
 * ============================================================
 * 作者的工作是「围着某一本书转」的：写大纲、写正文、管章节，
 * 全都要先选定一本书。所以这一栏的主语是**作品**，不是功能。
 * 一个「作品管理 / 章节管理 / AI 助手」的菜单会让作者每做一件事
 * 都要先重新选一次书。
 *
 * ★ 默认选中当前路由里的 novelId（`/novel/:novelId/...`），
 *   这样从作品列表点进工作台时，左侧栏能自己高亮对应的书。
 *
 * ★ 加载失败【不弹提示】—— 这是布局组件，它在每一个页面上都会挂载，
 *   用默认的提示方式会在「后端挂了」的时候让作者每点一个页面都吃一次弹窗。
 *   这里静默失败 + 在栏内显示一行可点的重试。
 *
 * ============================================================
 * ★★★ 这一层【绝不能】放大纲树
 * ============================================================
 * 大纲树挂在 WorkLayout 那一层（见 router/index.js 的说明）——
 * 挂在这里的话，「我的作品」「章节管理」页也会冒出一棵树。
 * 这一层只放【跨页面共用】的东西：导航 + 书架式的作品列表。
 */
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { getMyNovels } from '@/api/novel'
import { READER_WEB_URL, ADMIN_WEB_URL } from '@/utils/auth'
import { novelStatusType } from '@/utils/status'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const novels = ref([])
const loading = ref(false)
const failed = ref(false)

/** 左侧栏折叠（作者想要更大的编辑区时会收起来） */
const collapsed = ref(false)

/**
 * 当前选中的作品 id。
 *
 * ★ 只在【工作台 / 章节管理】这类带 novelId 的路由上高亮。
 *   用 computed 而不是 watch + ref：route 变了它自己就重算了，
 *   少一份需要手动同步的状态（也就少一个「切了路由高亮没跟上」的 bug）。
 */
const activeNovelId = computed(() => {
  const id = route.params.novelId
  return id ? Number(id) : null
})

/**
 * 拉作品列表。
 *
 * ★ 一次要 100 条（后端上限就是 100）。
 *   左侧栏是「书架」，它必须**一眼看全**——翻页的书架不是书架。
 *   一个作者有超过 100 本书的时候，这个交互本身就该重新设计
 *   （那时候需要的是搜索，不是列表）。
 *
 * ★ 不过滤状态：草稿、待审核、已驳回的书都要出现在这里，
 *   否则作者会以为自己的书不见了（而这恰恰是他最需要打开来改的时候）。
 */
async function loadNovels() {
  loading.value = true
  failed.value = false
  try {
    const data = await getMyNovels({ page: 1, size: 100 })
    novels.value = data?.records ?? []
  } catch {
    // ★ 静默：见文件头那段。只把状态摆出来，让作者能点一下重试
    failed.value = true
    novels.value = []
  } finally {
    loading.value = false
  }
}

onMounted(loadNovels)

/**
 * 从别处新建了作品之后，回到这一页时列表要更新。
 *
 * ★ 用「路由回到 /works」作为刷新时机：新建完作品一定会跳回作品列表，
 *   所以这覆盖了唯一的「列表会变」的入口。
 *   更严格的做法是让新建页 emit 一个事件 / 走 store，
 *   但那个方案要维护一份全局的「列表脏了」标记 —— 为了一个页面不值得。
 */
watch(
  () => route.path,
  (path) => {
    if (path === '/works') {
      loadNovels()
    }
  },
)

/**
 * 点左侧栏的一本书 → 进它的工作台。
 *
 * ★ 只带 novelId，**不带 nodeId** —— 根节点 id 要先拉一次大纲才知道，
 *   而那正是工作台要做的事。WorkLayout 拉到树之后会把真实 id replace 进 URL。
 *   详见 router/index.js 里 `:nodeId?` 那段。
 */
function openNovel(novel) {
  router.push(`/novel/${novel.id}/work`)
}

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
 *   页面还停在创作中心，顶栏变成了「未登录」，
 *   再点任何东西都会 401 被踢走 —— 看起来很莫名其妙。
 *
 * ★ 用 location.href 而不是 router.replace('/')：
 *   这是跨应用跳转，本应用的路由器管不到阅读端（§7.5 第 2 条）。
 *
 * ★ 这里【故意不用 redirectToReaderLogin()】——
 *   那个函数会带上 ?redirect=<当前地址>，于是变成：
 *   退出 → 登录页 → 登录成功 → 跳回创作中心 → 若不是作者被拦 → 回登录页……
 *   它只适合「该登录而没登录」，不适合「主动退出」。
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
          在阅读端登录后跳过来，这里直接就是已登录。
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
      <!--
        ★ 折叠时宽度给 0 而不是把 el-aside 干掉：
          直接 v-if 掉的话，它内部的滚动位置和加载状态会跟着丢，
          展开时又要重新拉一次列表。
      -->
      <el-aside :width="collapsed ? '0px' : '232px'" class="aside">
        <div class="aside-inner">
          <div class="aside-head">
            <span class="aside-title">我的作品</span>
            <el-button
              type="primary"
              size="small"
              :icon="'Plus'"
              @click="router.push('/novel/create')"
            >
              新建
            </el-button>
          </div>

          <div v-loading="loading" class="novel-list">
            <!-- 失败态单独一档：它和「还没写过书」长得一样但意思完全不同 -->
            <div v-if="failed" class="aside-tip">
              加载失败，<el-link type="primary" @click="loadNovels">重试</el-link>
            </div>

            <el-empty
              v-else-if="!loading && !novels.length"
              :image-size="60"
              description="还没有作品"
            >
              <el-button type="primary" size="small" @click="router.push('/novel/create')">
                写第一本书
              </el-button>
            </el-empty>

            <div
              v-for="n in novels"
              v-else
              :key="n.id"
              class="novel-item"
              :class="{ active: n.id === activeNovelId }"
              @click="openNovel(n)"
            >
              <!--
                ★ 封面用 object-fit: cover 撑满一个固定宽高比的小方块。
                  不固定尺寸的话，竖版和横版的封面会把列表撑得参差不齐。
                ★ 没有封面时显示书名首字 —— 比一个灰色的占位图有信息量
              -->
              <img v-if="n.cover" :src="n.cover" class="novel-cover" alt="" />
              <div v-else class="novel-cover cover-fallback">{{ (n.title || '?')[0] }}</div>

              <div class="novel-meta">
                <div class="novel-title" :title="n.title">{{ n.title }}</div>
                <el-tag :type="novelStatusType(n.status)" size="small" effect="plain">
                  {{ n.statusDesc }}
                </el-tag>
              </div>
            </div>
          </div>
        </div>
      </el-aside>

      <!-- 折叠把手：贴在左栏右边缘的一条窄条 -->
      <div class="collapse-handle" :title="collapsed ? '展开作品栏' : '收起作品栏'" @click="collapsed = !collapsed">
        {{ collapsed ? '›' : '‹' }}
      </div>

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

/* ★ overflow: hidden 是折叠动画的关键 —— 宽度收到 0 时内容不能溢出到外面 */
.aside {
  background: #fff;
  border-right: 1px solid #e4e7ed;
  transition: width 0.2s;
  overflow: hidden;
  padding: 0;
}

.aside-inner {
  width: 232px;
  padding: 14px 12px;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.aside-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.aside-title {
  font-weight: 600;
  color: #303133;
}

.novel-list {
  flex: 1;
  overflow-y: auto;
  min-height: 120px;
}

.aside-tip {
  font-size: 13px;
  color: #909399;
  padding: 8px 2px;
}

.novel-item {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 8px;
  border-radius: 6px;
  cursor: pointer;
  margin-bottom: 4px;
}

.novel-item:hover {
  background: #f5f7fa;
}

.novel-item.active {
  background: #ecf5ff;
}

.novel-cover {
  width: 36px;
  height: 48px;
  object-fit: cover;
  border-radius: 3px;
  flex-shrink: 0;
  background: #f0f2f5;
}

.cover-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
  color: #909399;
  font-size: 18px;
}

.novel-meta {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: flex-start;
}

/* ★ min-width: 0 + text-overflow 是 flex 子项里省略号的必要条件：
   少了 min-width: 0，flex 子项的最小宽度默认是内容宽度，永远不触发省略 */
.novel-title {
  font-size: 14px;
  color: #303133;
  max-width: 150px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.collapse-handle {
  width: 10px;
  background: #f5f7fa;
  border-right: 1px solid #e4e7ed;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #c0c4cc;
  font-size: 14px;
  user-select: none;
}

.collapse-handle:hover {
  background: #e9ecf2;
  color: #606266;
}

.main {
  background: #f5f7fa;
  padding: 20px;
}
</style>
