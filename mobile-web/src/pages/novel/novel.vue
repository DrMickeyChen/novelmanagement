<script setup>
/**
 * 作品详情（移动端）—— 简介 + 目录 + 书评。
 *
 * ★ 对应 `reader-web/src/views/novel/index.vue`。三块内容一样，
 *   但**目录的组织方式不同**，那是这一页唯一需要动脑的地方（见下）。
 *
 * ============================================================
 * ★★ 目录：为什么是「先给 10 章 + 点按钮展开」而不是上拉加载
 * ============================================================
 * 这一页上**同时有两个可以无限增长的区域**：目录和书评。
 * 而 uni-app 的 `onReachBottom` 是**页面级**事件，一页只有一个 ——
 * 两个区域都去抢它，就会变成「滑到底，目录和书评各加载一页」，
 * 而且顺序取决于谁先注册，改起来是隐形的。
 *
 * 所以这里划一条硬边界：
 *   · **书评**用 `onReachBottom`（它是页面最底部的区域，滑到底就是想看更多评论）
 *   · **目录**用**显式按钮**展开（「展开更多章节」），一次一页
 *
 * ★ 这个取舍的代价是目录多一次点击。相对于「两个无限滚动区互相抢事件、
 *   而且不报错」，这个代价是划算的。
 *
 * ============================================================
 * ★ 并发：详情和目录第一页一起发，不串行
 * ============================================================
 * 串行的话要等详情回来才发目录，首屏多一个 RTT。
 * ★ 任何一路失败都算整页失败（而不是「详情有、目录空」）：
 *   两个请求的错误码本来就一样（不存在/未上架都是 2001），
 *   分开处理只会得到一个「有书名、目录永远空着」的残页 ——
 *   那看起来像「这本书没有章节」，是错的。
 */
import NovelCover from '@/components/NovelCover.vue'
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onReachBottom } from '@dcloudio/uni-app'
import { getNovelCatalog, getNovelChapters, getNovelDetail, reportNovelView } from '@/api/novel'
import { addToShelf, getShelfStatus, removeFromShelf } from '@/api/shelf'
import { loadProgress } from '@/utils/readerStorage'
import { formatAuthorName, formatDate, formatWordCount } from '@/utils/format'
import { useUserStore } from '@/stores/user'
import CommentSection from '@/components/CommentSection.vue'
import StateHint from '@/components/StateHint.vue'

/** 目录一次取多少章。★ 首屏给 10 章就够翻一屏，再多会把书评挤到很下面 */
const CHAPTER_PAGE_SIZE = 10

const userStore = useUserStore()

const novelId = ref(null)

/*
 * ★★ 评论组件的 ref。
 *   uni-app 的 `onReachBottom` 是**页面级**生命周期，组件里收不到 ——
 *   所以 CommentSection 把 `loadMore` 暴露出来，由这一页在滑到底时调它。
 *   （见 CommentSection.vue 的 `defineExpose`。）
 */
const commentRef = ref(null)

/* ==================== 作品 ==================== */

const detail = ref(null)
const loading = ref(false)
const failed = ref(false)

/** 评分。★ 判 `> 0`：`novel.score` 的列是 `NOT NULL DEFAULT 0.0`，语义是「还没有人评过分」 */
const scoreValue = computed(() => Number(detail.value?.score) || 0)

/** 标签是逗号分隔的一个字符串，不是数组 */
const tagList = computed(() => (detail.value?.tags || '').split(',').filter(Boolean))

/* ==================== 目录（累加式） ==================== */

/**
 * ★★ 和桌面端的**真实分歧**：桌面端是「翻页」，第 N 页**替换**第 N-1 页；
 *   移动端是**累加** —— 「展开更多」把下一页接在后面，已经看过的章不消失。
 *   所以这里自己维护 `chapters` 和 `chapterPage`，没有用 `usePagedList`
 *   （那个钩子的 append 模式是给"滑到底自动加载"设计的，
 *    而这里要的是"点一下加载一页"，用按钮驱动，语义不同）。
 */
const chapters = ref([])
const chapterTotal = ref(0)
const chapterPage = ref(0)
const chapterLoading = ref(false)

/** 还有没展开的章节 */
const hasMoreChapters = computed(() => chapters.value.length < chapterTotal.value)

/** 加载目录的下一页（`first = true` 时从第 1 页开始，用于首屏和下拉刷新） */
async function loadChapterPage(first) {
  const next = first ? 1 : chapterPage.value + 1
  chapterLoading.value = true
  try {
    const res = await getNovelChapters(novelId.value, { page: next, size: CHAPTER_PAGE_SIZE })
    const rows = res?.records ?? []
    // ★ 第 1 页是替换（下拉刷新要能重置），往后是追加
    chapters.value = first ? rows : chapters.value.concat(rows)
    chapterTotal.value = Number(res?.total) || 0
    chapterPage.value = next
  } finally {
    chapterLoading.value = false
  }
}

/** 点「展开更多章节」。★ 失败由 request.js 的拦截器提示，这里只兜住 reject */
function expandChapters() {
  if (chapterLoading.value) return
  loadChapterPage(false).catch(() => {})
}

/* ==================== 加入书架（三态） ==================== */

/**
 * ★★ 三态，`null` = 「不知道」：
 *   true  —— 在书架里
 *   false —— 不在书架里（服务端明确这么说的，或者根本没登录）
 *   null  —— **问不到**（接口失败了）
 *
 * ★★ 为什么必须分出第三态：写成「查不到就当不在书架」的话，
 *   一本**明明在书架上**的书，按钮会显示【「加入书架」】——
 *   用户会以为自己的收藏丢了，然后去点它，再失败一次。
 *   这比不显示更糟：它不只是缺信息，它是**错的**。
 *
 * ★ 不变量：**`null` 只有一个来源 —— 一次失败的查询。**
 *   初始值刻意留 `false`（那时页面整体在加载中，按钮不可见），
 *   只有 `catch` 会把状态置成 `null`，所以「还没查」和「查不到」
 *   在界面上不可能混淆。
 */
const onShelf = ref(false)
const shelfBusy = ref(false)

/** 状态**问不到**。为 true 时按钮禁用、文案显示 `—` */
const shelfUnknown = computed(() => userStore.isLogin && onShelf.value === null)

/** 按钮文案。★ 三态各给一句，**没有兜底分支**：`null` 时写 `—` 而不是回落成「加入书架」 */
const shelfText = computed(() => {
  if (shelfUnknown.value) return '—'
  return onShelf.value ? '已在书架' : '加入书架'
})

/**
 * 查书架状态。**只有登录时才发** —— 这个接口是 `/api/reader/**`，
 * 匿名调必然 401（而它没带 silent，会顺手把页面踢去登录页）。
 */
async function loadShelfStatus() {
  if (!userStore.isLogin) {
    onShelf.value = false
    return
  }
  try {
    const res = await getShelfStatus(novelId.value)
    // ★ `?? null` 而不是 `?? false`：key 消失和请求失败是**同一件事** —— 我们不知道
    onShelf.value = res?.onShelf ?? null
  } catch {
    onShelf.value = null
  }
}

/**
 * 加入 / 移出。
 *
 * ★ 按当前状态决定调哪个接口，而不是一个 toggle 接口 ——
 *   后端刻意没做 toggle（它既不幂等也不可重试）。
 * ★★ 这一处**不弹确认框**（书架页的「移除」弹）：两者的误点成本不一样。
 *   这里的按钮本身写着当前状态，点错了**再点一次就回来**，
 *   且成功提示立刻写着「已移出书架」→ 不弹。
 */
async function toggleShelf() {
  if (!userStore.isLogin) {
    // ★ 未登录时**只提示，不跳转**：跳转永远由用户点登录入口触发
    uni.showToast({ title: '登录后可以加入书架', icon: 'none' })
    return
  }
  if (shelfBusy.value) return

  shelfBusy.value = true
  try {
    const wasOnShelf = onShelf.value
    const res = wasOnShelf
      ? await removeFromShelf(novelId.value)
      : await addToShelf(novelId.value)
    // ★ 兜底用「这次动作的目标状态」，而不是把本地值翻转 —— 翻转是在猜
    onShelf.value = res?.onShelf ?? !wasOnShelf
    // ★ 提示语跟着**动作**走，不跟着状态走 —— 状态可能是 null，那时「已移出」会说反
    uni.showToast({ title: wasOnShelf ? '已移出书架' : '已加入书架', icon: 'none' })
  } catch {
    /* 失败原因由 request.js 的拦截器提示，这里只需要别变成 unhandledrejection */
  } finally {
    shelfBusy.value = false
  }
}

/* ==================== 进度 / 续读 ==================== */

/** 本机进度（数组里只有 novelId 这一个字段是关键的，形状见 readerStorage） */
const localProgress = ref(null)

/**
 * 本机进度指向的那一章**现在还在不在目录里**。查不到就是 null。
 *
 * ★★ 为什么为了这一个判断还要发一次请求（而且只在本机有进度时才发）：
 *   目录是分页的，「本机记录的这一章还在不在」**没法从当前这一页回答** ——
 *   它可能在别的页上。而这一条不能省：章节被作者删掉 / 被驳回下架之后，
 *   本机记录指向一个不存在的 id，直接跳过去读者看到的是「章节不存在」。
 *   `getNovelCatalog` 每条只有 4 个字段（几十字节），比整份 ChapterVO 便宜得多，
 *   而且**游客和第一次来的读者一次都不会调到**。
 *
 * ★ 拿它顺便解决了另一个问题：提示语里的「第 N 章」需要 chapterNo，
 *   而本机进度里只有 chapterId。
 */
const progressChapter = ref(null)

/**
 * 续读目标章节 id。★ 三级回退，顺序不能换：
 *   ① 本机有记录、且那一章还在目录里 → 用它
 *   ② 没有记录（或记录已失效）→ `detail.firstChapterId`（后端给的第一章）
 *   ③ 一章都没发布 → null，按钮置灰
 *
 * ★ ②用的是 `firstChapterId` 而不是「目录第一页的第一个」：
 *   分页之后第一页不一定从第一章开始（这个判断在服务端更可靠）。
 */
const continueChapterId = computed(
  () => progressChapter.value?.id ?? detail.value?.firstChapterId ?? null,
)

/** 有本机记录且能对上时，按钮文案才是「继续阅读」 */
const isResuming = computed(() => !!progressChapter.value)

/** 续读按钮的文案 */
const readText = computed(() => {
  if (!continueChapterId.value) return '暂无章节'
  return isResuming.value ? `继续阅读 第 ${progressChapter.value.chapterNo} 章` : '开始阅读'
})

/** 目录里标记「读到这里」的那一章 */
const readToChapterId = computed(() => (isResuming.value ? localProgress.value.chapterId : null))

async function resolveProgressChapter() {
  progressChapter.value = null
  if (!localProgress.value) return
  try {
    const catalog = await getNovelCatalog(novelId.value)
    progressChapter.value = catalog.find((c) => c.id === localProgress.value.chapterId) ?? null
  } catch {
    /* 请求失败就当作「对不上」—— 这时按钮回退成「开始阅读」跳到第一章，
       比跳到一个读不了的章节好 */
  }
}

/* ==================== 加载 ==================== */

async function load() {
  loading.value = true
  failed.value = false
  try {
    const [d] = await Promise.all([getNovelDetail(novelId.value), loadChapterPage(true)])
    detail.value = d
    localProgress.value = loadProgress(novelId.value)
    await resolveProgressChapter()
    /*
     * ★ 书架状态**放在这里而不是 onLoad**：作品不存在时不该再去问
     *   「它在不在我的书架」—— 那是一次注定没意义的往返。
     * ★ 不 await：它是按钮上一个角标级别的信息，不该拖住整页的 loading。
     */
    loadShelfStatus()
  } catch {
    failed.value = true
    detail.value = null
    chapters.value = []
    chapterTotal.value = 0
  } finally {
    loading.value = false
  }
}

/* ==================== 生命周期 ==================== */

onLoad((options) => {
  novelId.value = options?.id ?? null
  if (!novelId.value) {
    // ★ 没有 id 就直接当"没找到"，不去发一个注定 400 的请求
    failed.value = true
    return
  }
  load()
  /*
   * ★ 埋点是「打开详情页就记一次」，和读没读正文无关。
   *   它是 silent 的（见 api/novel.js）：不弹提示、401 不跳登录页。
   *
   * ★★ 但 silent 【不等于】不会 reject —— 拦截器照样把 Promise 拒出去，
   *   只是拒得悄无声息。所以这里必须自己兜住，否则控制台一片红，
   *   接了上报工具会被当成真错误报上去，而它其实只是「阅读量少记了一次」。
   */
  reportNovelView(novelId.value).catch(() => {})
})

/**
 * 滑到底 → 书评加载更多。
 *
 * ★★ 这一页**只有书评**用 onReachBottom，目录用按钮（见文件头）。
 * ★ `?.` 不能省：首屏加载失败时 CommentSection 没挂载，ref 是 null。
 */
onReachBottom(() => {
  commentRef.value?.loadMore()
})

/**
 * 下拉刷新：整页重来。
 *
 * ★★ `uni.stopPullDownRefresh()` 必须放在 `finally` 里 ——
 *   放在 `then` 里的话，请求一失败这个圈就永远转下去了。
 */
async function onRefresh() {
  try {
    await load()
  } finally {
    uni.stopPullDownRefresh()
  }
}

/* ==================== 跳转 ==================== */

function openReader(chapterId) {
  if (!chapterId) return
  uni.navigateTo({ url: `/pages/reader/reader?novelId=${novelId.value}&chapterId=${chapterId}` })
}

function openContinue() {
  openReader(continueChapterId.value)
}

function openCategory() {
  const id = detail.value?.categoryId
  if (!id) return
  const name = encodeURIComponent(detail.value?.categoryName || '')
  uni.navigateTo({ url: `/pages/category/category?id=${id}&name=${name}` })
}

function goHome() {
  uni.switchTab({ url: '/pages/index/index' })
}
</script>

<template>
  <view class="page">
    <StateHint v-if="failed" text="没有找到这本书，可能已下架" empty-text="哦豁">
      <template #empty-action>
        <view class="btn btn--primary" hover-class="btn--hover" @click="goHome">
          <text class="btn__text">回到首页</text>
        </view>
      </template>
    </StateHint>

    <template v-else-if="detail">
      <!-- ============ 头部 ============ -->
      <view class="hero">
        <NovelCover :cover="detail.cover" :title="detail.title" class="detail-cover" />
        <text class="hero__title">{{ detail.title }}</text>
        <view class="hero__tags">
          <text class="tag" :class="detail.isFinish === 1 ? 'tag--done' : 'tag--serial'">
            {{ detail.isFinish === 1 ? '已完结' : '连载中' }}
          </text>
          <text v-for="t in tagList" :key="t" class="tag tag--plain">{{ t }}</text>
        </view>

        <view class="hero__meta">
          <!-- ★ M5：`—` 而不是「佚名」—— 判据见 utils/format.js 的 formatAuthorName -->
          <text class="hero__author">{{ formatAuthorName(detail.authorName) }}</text>
          <text class="hero__dot">·</text>
          <text class="hero__link" @click="openCategory">{{ detail.categoryName || '未分类' }}</text>
        </view>

        <view class="hero__stats">
          <text class="hero__stat">{{ detail.chapterCount || 0 }} 章</text>
          <text class="hero__dot">·</text>
          <text class="hero__stat">{{ formatWordCount(detail.wordCount) }}</text>
          <text class="hero__dot">·</text>
          <text class="hero__stat">{{ detail.viewCount || 0 }} 阅读</text>
        </view>

        <view v-if="detail.lastUpdateTime" class="hero__update">
          <text class="hero__stat">更新于 {{ formatDate(detail.lastUpdateTime) }}</text>
        </view>

        <!-- ★ 评分判 > 0：0 的语义是「还没有人评过分」，显示成「0.0 分」会让人以为大家都打了 0 分 -->
        <view v-if="scoreValue > 0" class="hero__score">
          <text class="hero__score-num">{{ scoreValue.toFixed(1) }}</text>
          <text class="hero__score-unit">分</text>
        </view>
      </view>

      <!-- ============ 操作 ============ -->
      <view class="actions">
        <view
          class="btn btn--primary btn--grow"
          :class="{ 'btn--busy': !continueChapterId }"
          hover-class="btn--hover"
          @click="openContinue"
        >
          <text class="btn__text">{{ readText }}</text>
        </view>
        <view
          class="btn btn--plain"
          :class="{ 'btn--busy': shelfBusy || shelfUnknown }"
          hover-class="btn--hover"
          @click="toggleShelf"
        >
          <text class="btn__text btn__text--plain">{{ shelfText }}</text>
        </view>
      </view>

      <!-- ============ 简介 ============ -->
      <view class="card">
        <text class="card__title">简介</text>
        <!--
          ★ 用 {{ }} 而不是任何富文本渲染。简介是作者填的自由文本，
            富文本会把它当 HTML 解析 —— 轻则排版被标签冲乱，
            重则是一个 XSS 入口（作者的输入直接进所有读者的浏览器）。
            换行靠 CSS 的 white-space: pre-line，够用且安全。
        -->
        <text class="card__intro">{{ detail.intro || '作者还没有填写简介。' }}</text>
      </view>

      <!-- ============ 目录 ============ -->
      <view class="card">
        <view class="card__head">
          <text class="card__title">目录</text>
          <text class="card__sub">共 {{ chapterTotal }} 章</text>
        </view>

        <view class="chapters">
          <view
            v-for="chapter in chapters"
            :key="chapter.id"
            class="chapter"
            hover-class="chapter--hover"
            @click="openReader(chapter.id)"
          >
            <text class="chapter__no">第 {{ chapter.chapterNo }} 章</text>
            <text class="chapter__title">{{ chapter.title }}</text>
            <text v-if="chapter.id === readToChapterId" class="chapter__badge">读到这里</text>
          </view>
        </view>

        <!--
          ★★ 目录的「加载更多」是**按钮**，不是 onReachBottom ——
             这一页的 onReachBottom 留给书评。理由见文件头。
        -->
        <view
          v-if="hasMoreChapters"
          class="more"
          hover-class="more--hover"
          @click="expandChapters"
        >
          <text class="more__text">{{ chapterLoading ? '加载中…' : '展开更多章节' }}</text>
        </view>
      </view>

      <!-- ============ 书评 ============ -->
      <CommentSection ref="commentRef" :novel-id="novelId" />
    </template>
  </view>
</template>

<style scoped>
.detail-cover { margin-bottom: 20rpx; }
.page {
  padding: 20rpx;
}

/* ============ 头部 ============ */
.hero {
  padding: 28rpx 24rpx;
  background-color: #ffffff;
  border-radius: 16rpx;
}

.hero__title {
  font-size: 40rpx;
  font-weight: 600;
  color: #1f2329;
}

.hero__tags {
  display: flex;
  flex-wrap: wrap;
  margin-top: 14rpx;
}

.tag {
  margin: 0 12rpx 8rpx 0;
  padding: 4rpx 14rpx;
  font-size: 22rpx;
  border-radius: 6rpx;
}

.tag--serial {
  color: #b26a00;
  background-color: #fff5e6;
}

.tag--done {
  color: #1a7f4b;
  background-color: #e8f7ef;
}

.tag--plain {
  color: #5a6472;
  background-color: #f0f1f4;
}

.hero__meta {
  display: flex;
  align-items: center;
  margin-top: 16rpx;
}

.hero__author {
  font-size: 28rpx;
  color: #5a6472;
}

.hero__link {
  font-size: 28rpx;
  color: #2f6fed;
}

.hero__dot {
  margin: 0 10rpx;
  font-size: 24rpx;
  color: #c2c8d2;
}

.hero__stats {
  display: flex;
  align-items: center;
  margin-top: 10rpx;
}

.hero__update {
  margin-top: 6rpx;
}

.hero__stat {
  font-size: 24rpx;
  color: #9aa3b0;
}

.hero__score {
  display: flex;
  align-items: baseline;
  margin-top: 12rpx;
}

.hero__score-num {
  font-size: 40rpx;
  font-weight: 600;
  color: #ff9f1c;
}

.hero__score-unit {
  margin-left: 4rpx;
  font-size: 24rpx;
  color: #9aa3b0;
}

/* ============ 操作按钮 ============ */
.actions {
  display: flex;
  margin-top: 16rpx;
}

.btn {
  padding: 22rpx 32rpx;
  border-radius: 12rpx;
  display: flex;
  align-items: center;
  justify-content: center;
}

.btn--grow {
  flex: 1;
  min-width: 0;
}

.btn--primary {
  background-color: #2f6fed;
}

.btn--plain {
  margin-left: 16rpx;
  background-color: #ffffff;
}

.btn--hover {
  opacity: 0.85;
}

/* ★ 不可用时只降透明度、**不拦点击**：拦的理由各不相同
   （未登录要提示去登录、问不到书架状态要维持禁用观感），
   真正的守卫在 JS 里，这里只负责"看起来不能点" */
.btn--busy {
  opacity: 0.6;
}

.btn__text {
  font-size: 30rpx;
  color: #ffffff;
}

.btn__text--plain {
  color: #5a6472;
}

/* ============ 卡片 ============ */
.card {
  margin-top: 16rpx;
  padding: 24rpx;
  background-color: #ffffff;
  border-radius: 16rpx;
}

.card__head {
  display: flex;
  align-items: baseline;
}

.card__title {
  font-size: 32rpx;
  font-weight: 600;
  color: #1f2329;
}

.card__sub {
  margin-left: 12rpx;
  font-size: 24rpx;
  color: #9aa3b0;
}

.card__intro {
  display: block;
  margin-top: 14rpx;
  font-size: 28rpx;
  color: #3d4450;
  line-height: 1.7;
  /* ★ 作者敲的换行要保留（见模板里不用富文本那条） */
  white-space: pre-line;
}

/* ============ 目录 ============ */
.chapters {
  margin-top: 8rpx;
}

.chapter {
  display: flex;
  align-items: center;
  padding: 22rpx 0;
  border-bottom: 1rpx solid #f2f3f6;
}

.chapter--hover {
  background-color: #fafbfc;
}

.chapter__no {
  flex-shrink: 0;
  font-size: 26rpx;
  color: #9aa3b0;
}

.chapter__title {
  flex: 1;
  min-width: 0;
  margin-left: 16rpx;
  font-size: 28rpx;
  color: #1f2329;
  /* ★ 长标题截断：不加的话一章的标题会把整行撑开、把徽标挤出去 */
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.chapter__badge {
  flex-shrink: 0;
  margin-left: 12rpx;
  padding: 2rpx 10rpx;
  font-size: 20rpx;
  color: #2f6fed;
  background-color: #e8eefc;
  border-radius: 4rpx;
}

.more {
  margin-top: 16rpx;
  padding: 18rpx;
  text-align: center;
  background-color: #f6f7f9;
  border-radius: 12rpx;
}

.more--hover {
  opacity: 0.85;
}

.more__text {
  font-size: 26rpx;
  color: #2f6fed;
}
</style>
