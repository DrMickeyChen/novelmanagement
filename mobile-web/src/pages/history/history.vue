<script setup>
/**
 * 阅读历史 —— 对应 `reader-web/src/views/history/index.vue`。
 *
 * ------------------------------------------------------------
 * ★★ 这一页读的是【服务端】的 `read_history`，不是本机的 localStorage 进度
 * ------------------------------------------------------------
 * 两者是**两份存储**，回答两个不同的问题：
 *   · `utils/readerStorage.js` 那份 localStorage 进度 —— 「**本机**读到哪」，
 *     阅读器恢复位置用，离线也有；
 *   · `/api/reader/history` 这份 —— 「我读过**哪些**书」，跨设备，这一页显示的就是它。
 *
 * ★★★ 所以「阅读器只写 localStorage、不写服务端」这个缺口，
 *   **只有在历史页上才看得见**（历史页永远是空的），
 *   在阅读器本身上一切正常。这是本期实测抓到的一个真实缺陷，
 *   修法是让阅读器两个都写（见 `pages/reader/reader.vue` 的 `flushProgress`）。
 *
 * ------------------------------------------------------------
 * ★★ 与书架页同一处结构性差异：登录判断在这一页里，不在路由守卫里
 * ------------------------------------------------------------
 * `pages.json` 只是张路由表，uni 不为它提供任何拦截钩子 ——
 * "未登录"必须由页面自己渲染（`reader-web` 那边是 `meta.requiresAuth` + 全局守卫）。
 * ★ 而且这里**不该**照搬"进不来"：这一页从「我的」点进来，
 *   进来就被弹走、或者看到一个空白列表，用户都不会知道发生了什么。
 *   正确做法是进来、如实说「登录后能看到什么」、给一个登录入口。
 *
 * ★★ 随之而来的用法：未登录时**一个请求都不该发**。
 *   发了的话后端返 401 → `request.js` 弹提示 + 跳登录页，
 *   用户只是点了一下就被弹走，而原因他完全看不到。
 *
 * ------------------------------------------------------------
 * ★★ `available === false` 是【合法状态】，必须渲染出来
 * ------------------------------------------------------------
 * 作品被下架/删除后，后端的 brief 是 null，只剩 `novelId` 还在。
 * 界面要说「已下架」并且**不可点**：
 *   · 整条不显示 → 篡改历史（我明明读过这本书）；
 *   · 点得进去 → 跳到一个必然 2001 的详情页。
 * 两条都是把「后端诚实地说了取不到」翻译成假话 —— 铁律 10 那条判别式
 * 在这里的结论是：**「我读过它」这个事实不因为书下架而消失**，
 * 所以这一格有正确的渲染方式，可以照常显示。
 * （书架页同理，两页的处理刻意一致。）
 */
import { computed, watch } from 'vue'
import { onReachBottom, onPullDownRefresh } from '@dcloudio/uni-app'
import { getHistoryList } from '@/api/reader'
import { usePagedList } from '@/composables/usePagedList'
import { useUserStore } from '@/stores/user'
import { formatDate } from '@/utils/format'
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
  loadMore,
  reload,
} = usePagedList(({ page, size }) => getHistoryList({ page, size }), {
  pageSize: 15,
  append: true,
})

/**
 * ★★ 用 watch + `immediate` 而不是 `onMounted`：用户完全可能是
 *    **在这一页上**点「去登录」→ 登录页 → 回来，那种情况下
 *    挂载钩子不会再跑，少了这个 watch 他就会看到「登录了但历史还是空的」。
 *    （和 `shelf.vue` 同一手法，两页保持一致。）
 */
watch(
  () => userStore.isLogin,
  (isLogin) => {
    if (isLogin) {
      reload().catch(() => {})
    } else {
      // ★ 退出登录时清列表：不清的话未登录的引导语会和上一次的历史同时显示
      list.value = []
      total.value = 0
    }
  },
  { immediate: true },
)

onReachBottom(() => {
  loadMore()
})

/**
 * 下拉刷新。
 *
 * ★★ `uni.stopPullDownRefresh()` 必须在 `finally` 里 ——
 *    放 `then` 里的话，请求一失败这个圈就**永远转下去**，
 *    而"转不完的刷新圈"看起来和"还在加载"一模一样。
 * ★ 前提：`pages.json` 里这一页要写 `enablePullDownRefresh: true`，
 *   不加的话这个回调根本不会被触发（**不报错**）。
 */
onPullDownRefresh(async () => {
  try {
    if (userStore.isLogin) await reload()
  } catch {
    /* 失败原因由 request.js 的拦截器提示 */
  } finally {
    uni.stopPullDownRefresh()
  }
})

/** 失败态里那个「重试」。★ 用 reload() 不用 load()：失败时列表空了，「第几页」没有意义 */
function retry() {
  reload().catch(() => {})
}

/** 「共 N 条」只在拿到数据之后显示，否则首屏会先闪一个「共 0 条」 */
const totalText = computed(() => (loaded.value ? `共 ${total.value} 条` : ''))

/**
 * 回到那本书的那一章。
 *
 * ★ 下架的条目不跳（见文件头）。
 * ★ `chapterId` 理论上可能为 null（后端 `readChapterId` 与章号章名同生共死，
 *   读过的那一章被作者撤回之后一起变 null）—— 那种情况下退到详情页，
 *   至少还能看到这本书，而不是点一个必然打不开的章节。
 * ★ 跳转写成具名函数、不在模板里直接调 `uni.navigateTo`：
 *   模板表达式的求值上下文是组件实例，`uni` 是全局对象，
 *   在模板里要靠运行时全局查找拿到，而不同编译目标下这个查找的成败**不一致**。
 */
function open(item) {
  if (item.available === false) return
  const url = item.chapterId
    ? `/pages/reader/reader?novelId=${item.novelId}&chapterId=${item.chapterId}`
    : `/pages/novel/novel?id=${item.novelId}`
  uni.navigateTo({ url })
}

function goLogin() {
  uni.navigateTo({ url: '/pages/login/login' })
}

/** ★「去逛逛」去的是首页 tab，所以必须 switchTab（navigateTo 到 tabBar 页会静默失败） */
function goHome() {
  uni.switchTab({ url: '/pages/index/index' })
}

/**
 * 「读到 N%」。
 *
 * ★★ 判 `!= null` 而不是真值判断：**0% 是一个真实的进度**
 *   （打开过、一个字没滚），说成「还没开始读」是错的 ——
 *   和书架页 `progressText` 同一条规矩。
 */
function percentText(item) {
  return item.progress != null ? `读到 ${item.progress}%` : ''
}

/** 章节文案。★ 章号取不到时只说章名；都取不到就整格空着，别显示「第 null 章」 */
function chapterText(item) {
  if (item.chapterNo != null) {
    return item.chapterTitle ? `第 ${item.chapterNo} 章 · ${item.chapterTitle}` : `第 ${item.chapterNo} 章`
  }
  return item.chapterTitle || ''
}

/** 最后阅读时间（只到天）。★ formatDate 对空值返回空串，模板里判一下 */
function timeText(item) {
  return formatDate(item.updateTime)
}
</script>

<template>
  <view class="history">
    <!-- ============ 未登录 ============ -->
    <view v-if="!userStore.isLogin" class="gate">
      <text class="gate__icon">🕘</text>
      <text class="gate__title">登录后可以查看阅读历史</text>
      <text class="gate__sub">你读过的书会留在这里，换设备也找得回来。</text>
      <button class="gate__btn" @click="goLogin">去登录</button>
    </view>

    <!-- ============ 已登录 ============ -->
    <template v-else>
      <view class="head">
        <text class="head__title">阅读历史</text>
        <text class="head__count">{{ totalText }}</text>
      </view>

      <view v-if="list.length" class="rows">
        <view
          v-for="item in list"
          :key="item.novelId"
          class="row"
          :class="{ 'row--off': item.available === false }"
          hover-class="row--hover"
          @click="open(item)"
        >
          <view class="row__head">
            <!--
              ★ 下架时 title 是 null → 用 `作品 #id` 兜底。
                留空白的话读者会以为这一行坏了，而它其实在说"这本书下架了"。
            -->
            <text class="row__title">{{ item.title || `作品 #${item.novelId}` }}</text>
            <!-- ★★ 下架徽标：这一格就是「后端说了取不到、而界面能诚实渲染」的那一格 -->
            <text v-if="item.available === false" class="row__badge">已下架</text>
          </view>

          <text v-if="chapterText(item)" class="row__chapter">{{ chapterText(item) }}</text>
          <text v-else-if="item.available === false" class="row__gone">这本书已下架或已删除</text>

          <view class="row__foot">
            <text v-if="timeText(item)" class="row__time">{{ timeText(item) }}</text>
            <text v-if="percentText(item)" class="row__pct">{{ percentText(item) }}</text>
          </view>
        </view>
      </view>

      <!-- ★★ 失败态与空态互斥（由 `usePagedList` 的构造保证），见 StateHint 的文件头 -->
      <StateHint
        :loading="loading"
        :is-empty="isEmpty"
        :failed="failed"
        empty-text="还没有阅读记录"
        failed-text="历史记录暂时加载失败"
        @retry="retry"
      >
        <!-- ★ 空态的那条出路。★ 首页是 tabBar 页，所以用 switchTab -->
        <template #empty-action>
          <text class="btn btn--primary" @click="goHome">去逛逛</text>
        </template>
      </StateHint>

      <!--
        ★★ 底部提示条 —— 与首页/书架同一段规矩：追加失败**绝不能清空**已读内容
          （`usePagedList` 的 `failed` 里含 `list.length === 0`，
           所以列表非空时的失败走的是 `loadMoreFailed` 这条独立的路）。
      -->
      <view v-if="list.length" class="foot">
        <text v-if="loadMoreFailed" class="foot__retry" @click="loadMore">加载失败，点击重试</text>
        <text v-else-if="loading" class="foot__text">加载中…</text>
        <text v-else-if="finished" class="foot__text">没有更多了</text>
      </view>
    </template>
  </view>
</template>

<style scoped>
.history {
  min-height: 100vh;
  padding: 20rpx 24rpx 40rpx;
  background: #f5f6f8;
}

/* ---------- 未登录引导（与书架页同形） ---------- */
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

/* ---------- 历史行 ---------- */
.rows {
  background: #fff;
  border-radius: 16rpx;
  overflow: hidden;
}

.row {
  padding: 24rpx 28rpx;
  border-bottom: 2rpx solid #f0f2f5;
}

.row--hover {
  background: #f5f6f8;
}

/*
 * ★ 下架：降透明度但**照常显示**。
 *   不整条藏起来 —— 这一页回答的是「我读过什么」（见文件头）。
 */
.row--off {
  opacity: 0.6;
}

.row__head {
  display: flex;
  align-items: center;
}

.row__title {
  font-size: 30rpx;
  font-weight: 600;
  color: #1f2733;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row__badge {
  flex-shrink: 0;
  margin-left: 12rpx;
  padding: 2rpx 12rpx;
  border-radius: 6rpx;
  font-size: 20rpx;
  color: #8a93a0;
  background: #f0f2f5;
}

.row__chapter {
  display: block;
  margin-top: 8rpx;
  font-size: 26rpx;
  color: #5a6472;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row__gone {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #d98b1f;
}

.row__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10rpx;
}

.row__time {
  font-size: 22rpx;
  color: #b6bdc7;
}

.row__pct {
  font-size: 22rpx;
  color: #2f6fed;
}

/* ---------- 空态的那条出路 ---------- */
.btn {
  padding: 12rpx 32rpx;
  border-radius: 999rpx;
  font-size: 26rpx;
  color: #5a6472;
  background: #f5f6f8;
}

.btn--primary {
  color: #fff;
  background: #2f6fed;
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
