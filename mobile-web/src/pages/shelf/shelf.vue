<script setup>
/**
 * 我的书架（tab 3）—— 收藏过的书 + 每本书读到哪。
 *
 * ------------------------------------------------------------
 * ★★ 与桌面端的一处结构性差异：登录判断在这一页里，不在路由守卫里
 * ------------------------------------------------------------
 * reader-web 用 `router/index.js` 的 `meta.requiresAuth` + 全局守卫，
 * 未登录时**根本进不来**这一页，所以那边敢写「不写『请先登录』的分支」。
 *
 * 移动端没有路由守卫这个东西 —— `pages.json` 只是张路由表，
 * uni 不为它提供任何拦截钩子。所以"未登录"必须由**页面自己**渲染。
 *
 * ★ 而且移动端**不该**照搬"进不来"这个做法：tabBar 上的四个 tab
 *   是常驻的，把其中两个变成"点了没反应"（或者一进去就被弹到登录页）
 *   是很糟的体验 —— 用户会以为 App 坏了。正确做法是进来、
 *   如实说明"登录后能看到什么"、并给一个登录入口。
 *
 * ★ 这一条改变了 `usePagedList` 的用法：未登录时**一个请求都不该发**。
 *   发了的话（后端返回 401 → request.js 弹提示 + 跳登录页），
 *   用户只是点了一下 tab 就被弹走，而原因他完全看不到。
 *   所以 onMounted 里先判 `userStore.isLogin`。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { onReachBottom, onShow } from '@dcloudio/uni-app'
import { getShelfList, removeFromShelf } from '@/api/shelf'
import { usePagedList } from '@/composables/usePagedList'
import { useUserStore } from '@/stores/user'
import { confirm } from '@/utils/confirm'
import { formatAuthorName, formatDate } from '@/utils/format'
import StateHint from '@/components/StateHint.vue'

const userStore = useUserStore()

const {
  list,
  total,
  loaded,
  loading,
  isEmpty,
  failed,
  loadMoreFailed,
  finished,
  load,
  loadMore,
  reload,
} = usePagedList(({ page, size }) => getShelfList({ page, size }), {
  pageSize: 12,
  append: true,
})

/**
 * ★ 只在已登录时才拉数据。
 *
 * ★★ 用 watch 而不是只判 onMounted：用户完全可能是**在这一页上**
 *    点「去登录」→ 登录页 → 回来。那种情况下 onMounted 不会再跑，
 *    少了这个 watch 他就会看到一个"登录了但书架还是空的"页面。
 *   `immediate: true` 让首次进入也走同一条路 —— 一份逻辑，
 *    两个入口，不会出现"首屏的判法和登录回来的判法不一致"。
 */
watch(
  () => userStore.isLogin,
  (isLogin) => {
    if (isLogin) {
      reload()
    } else {
      // ★ 退出登录时把列表清掉：不清的话下面那支"未登录"的引导语
      //   会和上一次的收藏列表同时显示
      list.value = []
      total.value = 0
    }
  },
  { immediate: true },
)

/**
 * ★★ 从别的页面切回来时静默刷新。
 *
 * 场景：在详情页点了「加入书架」→ 切回书架 tab —— 少了这一段
 * 新加的那本书**不会出现**，用户会以为没加上，然后再点一次。
 * （不加 loading、失败也不进失败态：屏幕上那份旧数据仍是好的。）
 */
onShow(() => {
  if (userStore.isLogin && loaded.value) reload().catch(() => {})
})

onReachBottom(() => {
  loadMore()
})

/**
 * 失败态里那个「重试」。
 * ★ 用 `reload()` 而不是 `load()`：失败时列表被清空、total 是 0，
 *   此刻「第几页」没有意义。
 * ★★ 必须兜住 reject：`request.js` 提示完照样把 Promise 拒出去，
 *   模板里裸写 `@click="reload"` 就是一条 unhandledrejection。
 */
function retry() {
  reload().catch(() => {})
}

/**
 * 续读目标章节。
 *
 * ★★ **优先 `readChapterId`，没有才退到 `lastChapterId`** —— 顺序不能反：
 *   · `readChapterId` 是「我读到哪」，是这一页真正想要的东西；
 *   · `lastChapterId` 是**最新章节**（后端在章节状态流转时维护的），
 *     拿它当续读目标，用户会被扔到最新一章，也就是**剧透**。
 *
 * ★ `readChapterId` 会和 `readChapterNo` / `readChapterTitle` **同生共死**：
 *   读过的那一章被作者撤回之后三个一起变 null。这时退到 `lastChapterId`
 *   是对的 —— 至少还能读，而不是点一个必然打不开的章节（后端 `3001`）。
 */
function continueChapterId(item) {
  return item.readChapterId ?? item.lastChapterId ?? null
}

/** 续读地址。★ null 表示这本书现在没有可读的章节，按钮据此置灰 */
function readPath(item) {
  const chapterId = continueChapterId(item)
  return chapterId ? `/pages/reader/reader?novelId=${item.novelId}&chapterId=${chapterId}` : null
}

/**
 * 进度文案。
 *
 * ★ 三种情况的说法不一样，不能合成一句：
 *   · 有章号 → 「读到 第 3 章 · 已读 60%」
 *   · 有进度但章节信息没了 → 只说「已读 60%」（章名取不到就别显示章号）
 *   · 完全没读过 → 「还没开始读」
 *
 * ★ `progress` 判的是 `!= null` 而不是真值判断：**0% 是一个真实的进度**
 *   （打开过第一章、一个字没滚），显示成「还没开始读」是错的。
 */
function progressText(item) {
  const percent = item.progress != null ? `已读 ${item.progress}%` : ''
  if (item.readChapterNo != null) {
    return percent ? `读到 第 ${item.readChapterNo} 章 · ${percent}` : `读到 第 ${item.readChapterNo} 章`
  }
  return percent || '还没开始读'
}

/** 加入时间（只到天）。★ `formatDate` 对空值返回空串，所以模板里要判一下 */
function joinedText(item) {
  const date = formatDate(item.createTime)
  return date ? `加入于 ${date}` : ''
}

const removing = ref(null)

/**
 * 移出书架。
 *
 * ★ **必须问一句**：`user_shelf` 没有 `deleted` 列，移出是**物理删除**，
 *   没有撤销。而且用户很容易点错 —— 这一页上「继续阅读」和「移除」
 *   就在同一行。
 *
 * ★★ 走的 `confirm()` 是**永不抛异常、返回布尔**的那个（见 utils/confirm.js）。
 *    直接调 `uni.showModal` 的话每个调用点都要自己处理 success/fail
 *    两个回调，迟早有一处把 fail 也当成成功。
 * ★ 「确定移除」正好 4 个汉字 —— 小程序端 confirmText 的上限就是 4 个，
 *   再多会被**静默截断**。
 */
async function handleRemove(item) {
  const name = item.title || '这本书'
  const ok = await confirm(`确定把《${name}》移出书架吗？`, '移出书架', { confirmText: '确定移除' })
  if (!ok) return

  removing.value = item.novelId
  try {
    await removeFromShelf(item.novelId)
    uni.showToast({ title: '已移出书架', icon: 'none' })
    /*
     * ★ 重查**当前页**（load()），不是 reload() —— 后者会跳回第 1 页，
     *   而用户可能正在第 3 页清书架。
     * ★★ 追加模式下这里有个坑：`load()` 拿到的是**当前页那一页的 records**，
     *    而 `list` 里累积的是前几页 —— 直接赋值会把前面几页抹掉。
     *    所以拿到之后要**接着**已有的内容（用长度判断是不是第一页）。
     */
    const before = list.value.length
    await load()
    if (before > list.value.length) {
      // load() 把列表替换成了"当前页那一页"，长度反而变小了 —— 说明是追加模式
      // ★ 这种情况直接 reload()：清书架时跳回第 1 页是可以接受的，
      //   而被抹掉一整页内容不可接受
      await reload()
    }
  } catch {
    /* 失败原因由 request.js 的拦截器提示，这里只需要别变成 unhandledrejection */
  } finally {
    removing.value = null
  }
}

/** 「共 N 本」只在拿到数据之后显示，否则首屏会先闪一个「共 0 本」 */
const totalText = computed(() => (loaded.value ? `共 ${total.value} 本` : ''))

function goLogin() {
  uni.navigateTo({ url: '/pages/login/login' })
}

/** ★「去逛逛」去的是首页 tab，所以必须 switchTab（navigateTo 到 tabBar 页会静默失败） */
function goHome() {
  uni.switchTab({ url: '/pages/index/index' })
}

/**
 * 打开作品详情。
 *
 * ★★ 这些跳转都写成**具名函数**，不在模板里直接调 `uni.navigateTo(...)` ——
 *    模板表达式的求值上下文是组件实例，`uni` 是个全局对象，
 *    写在模板里要靠"运行时的全局查找"才能拿到，而在不同编译目标下
 *    这个查找的成功与否**不一致**。写进 script 里是零风险的。
 *    （这条在 H5 上大概率能跑通 —— 而"大概率能跑通"正是最该避开的那种。）
 */
function openNovel(item) {
  uni.navigateTo({ url: `/pages/novel/novel?id=${item.novelId}` })
}

/** 继续阅读 */
function openReader(item) {
  const url = readPath(item)
  if (!url) return
  uni.navigateTo({ url })
}
</script>

<template>
  <view class="shelf">
    <!-- ============ 未登录 ============ -->
    <view v-if="!userStore.isLogin" class="gate">
      <text class="gate__icon">📚</text>
      <text class="gate__title">登录后可以收藏作品</text>
      <text class="gate__sub">书架里的书会记住你读到第几章，换设备也接着读。</text>
      <button class="gate__btn" @click="goLogin">去登录</button>
    </view>

    <!-- ============ 已登录 ============ -->
    <template v-else>
      <view class="head">
        <text class="head__title">我的书架</text>
        <text class="head__count">{{ totalText }}</text>
      </view>

      <view v-if="list.length" class="cards">
        <view v-for="item in list" :key="item.novelId" class="card">
          <!--
            ★★ 下架的书【不能整行过滤掉】：读者会以为自己的书架丢了东西，
              而真实原因只是那本书下架了。渲染成占位卡，并且照样给「移除」。
          -->
          <template v-if="item.available">
            <text class="card__title" @click="openNovel(item)">{{ item.title }}</text>
            <view class="card__meta">
              <!-- ★ M5：`—` 而不是「佚名」—— 判据见 utils/format.js 的 formatAuthorName -->
              <text>{{ formatAuthorName(item.authorName) }}</text>
              <text class="dot">·</text>
              <text>{{ item.categoryName || '未分类' }}</text>
              <text class="dot">·</text>
              <text>{{ item.chapterCount || 0 }} 章</text>
            </view>
          </template>

          <template v-else>
            <text class="card__title card__title--gone">{{ item.title || '已下架' }}</text>
            <text class="card__gone">这本书已下架或已删除</text>
          </template>

          <!-- ★ 进度独立于 available 显示：上架状态没了，但我读到哪这件事还在 -->
          <text class="card__progress">{{ progressText(item) }}</text>

          <view class="card__ops">
            <text
              v-if="item.available && readPath(item)"
              class="btn btn--primary"
              @click="openReader(item)"
            >
              继续阅读
            </text>
            <!-- ★ 已上架但没有可读章节（作者还没发正文）→ 置灰，不要给一个点了就报错的按钮 -->
            <text v-else-if="item.available" class="btn btn--off">暂无可读章节</text>

            <!--
              ★ 下架的书【也要】有移除按钮 —— 服务端的移除接口刻意不校验上架状态，
                就是为了让这一行能被清理掉。少写这个按钮，那一行就永远删不掉了。
            -->
            <text
              v-if="removing !== item.novelId"
              class="btn"
              @click="handleRemove(item)"
            >
              移除
            </text>
            <text v-else class="btn btn--off">移除中…</text>
          </view>

          <text v-if="joinedText(item)" class="card__joined">{{ joinedText(item) }}</text>
        </view>
      </view>

      <!--
        ★★ 失败态单独一支。M5 之前失败和空态共用一行，于是停掉 content 之后
          读者看到的是【「书架还是空的」】—— 而他的收藏一本都没少。
          ★ 空态那句要给一条出路（去逛逛），失败态给的是「重试」——
           两者的下一步动作不同，不能合并。
      -->
      <StateHint
        :loading="loading"
        :is-empty="isEmpty"
        :failed="failed"
        empty-text="书架还是空的"
        failed-text="书架暂时加载失败"
        @retry="retry"
      >
        <!-- ★ 空态的那条出路。★「去逛逛」去的是首页 tab，所以用 switchTab -->
        <template #empty-action>
          <text class="btn btn--primary" @click="goHome">去逛逛</text>
        </template>
      </StateHint>


      <!-- ★★ 底部提示条 —— 见首页同一段注释：追加失败绝不能清空已读内容 -->
      <view v-if="list.length" class="foot">
        <text v-if="loadMoreFailed" class="foot__retry" @click="loadMore">加载失败，点击重试</text>
        <text v-else-if="loading" class="foot__text">加载中…</text>
        <text v-else-if="finished" class="foot__text">没有更多了</text>
      </view>
    </template>
  </view>
</template>

<style scoped>
.shelf {
  min-height: 100vh;
  padding: 20rpx 24rpx 40rpx;
  background: #f5f6f8;
}

/* ---------- 未登录引导 ---------- */
.gate {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 140rpx 48rpx;
  background: #fff;
  border-radius: 16rpx;
}

.gate__icon {
  font-size: 96rpx;
  line-height: 1;
}

.gate__title {
  margin-top: 24rpx;
  font-size: 32rpx;
  font-weight: 600;
  color: #1f2733;
}

.gate__sub {
  margin-top: 12rpx;
  font-size: 26rpx;
  color: #9aa3b0;
  text-align: center;
  line-height: 1.6;
}

.gate__btn {
  margin-top: 40rpx;
  width: 100%;
  height: 84rpx;
  line-height: 84rpx;
  border-radius: 12rpx;
  background: #2f6fed;
  color: #fff;
  font-size: 30rpx;
}

/* ---------- 头部 ---------- */
.head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 8rpx 8rpx 20rpx;
}

.head__title {
  font-size: 36rpx;
  font-weight: 600;
  color: #1f2733;
}

.head__count {
  font-size: 24rpx;
  color: #9aa3b0;
}

/* ---------- 书架卡片 ---------- */
.cards {
  display: flex;
  flex-direction: column;
}

.card {
  padding: 24rpx 28rpx;
  margin-bottom: 16rpx;
  background: #fff;
  border-radius: 16rpx;
}

.card__title {
  display: block;
  font-size: 32rpx;
  font-weight: 600;
  color: #1f2733;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card__title--gone {
  color: #b6bdc7;
}

.card__gone {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #d98b1f;
}

.card__meta {
  display: flex;
  align-items: center;
  margin-top: 10rpx;
  font-size: 24rpx;
  color: #8a93a0;
}

.dot {
  margin: 0 10rpx;
  color: #c8ced8;
}

.card__progress {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #5a6472;
}

.card__ops {
  display: flex;
  align-items: center;
  margin-top: 20rpx;
}

.btn {
  padding: 12rpx 32rpx;
  margin-right: 16rpx;
  border-radius: 999rpx;
  font-size: 26rpx;
  color: #5a6472;
  background: #f5f6f8;
}

.btn--primary {
  color: #fff;
  background: #2f6fed;
}

/* ★ 置灰态用颜色 + 不可点，不是 disabled 属性（text 组件没有 disabled） */
.btn--off {
  color: #b6bdc7;
  background: #f0f2f5;
}

.card__joined {
  display: block;
  margin-top: 16rpx;
  font-size: 22rpx;
  color: #b6bdc7;
}

/* ---------- 底部提示条 ---------- */
.foot {
  display: flex;
  justify-content: center;
  padding: 24rpx 0 8rpx;
}

.foot__text {
  font-size: 24rpx;
  color: #9aa3b0;
}

.foot__retry {
  font-size: 24rpx;
  color: #2f6fed;
}
</style>
