<script setup>
/**
 * 阅读器（移动端）—— 滚读 + 进度 + 字号/行距/背景 + 章评。
 *
 * ★ 对应 `reader-web/src/views/reader/index.vue`。桌面端那一堆
 *   **在 uni-app 里没有对应物**的 API，是这一期改动最集中的地方：
 *   `scrollTop/scrollHeight/clientHeight` → `<scroll-view>` 的 `scroll-top` /
 *   `@scroll` / `@scrolltolower`；`document.fonts.ready`、`keydown`、
 *   `beforeunload`、`window.getSelection()` 全部删掉（前三个没有对应物，
 *   最后一个在触屏上无意义 —— `text` 组件不长按选中）。
 *
 * ============================================================
 * ★★★ 三个必须知道的 scroll-view 事实（每一个都会静默失败）
 * ============================================================
 * ① **`scroll-view` 必须有一个确定的高度**，否则它不滚动 ——
 *    而"不滚动"的表现是「内容超出屏幕但滑不动」，同时**没有任何报错**。
 *    这里用 flex 布局把它撑成整屏高（`.reader__scroll { flex: 1; height: 0 }`，
 *    `height: 0` 是让 flex 收缩生效的关键）。
 *
 * ② ★★ **`scroll-top` 赋同一个值不会触发滚动。** 它是一个 prop，
 *    只有"变化"才会传给原生滚动容器。所以「恢复到 500」时如果当前
 *    已经是 500，那行赋值等于什么都没做 —— 而症状是
 *    「有时候能恢复、有时候不能」，取决于当前正好在哪。
 *    这里的处置：**先赋 0，等一帧，再赋目标值**（见 `restoreScroll`）。
 *
 * ③ ★ **恢复必须在内容渲染之后**。内容还没撑开高度时，
 *    滚到 800 会被原生容器夹回能滚动的最大值（通常是 0）——
 *    症状是「进度永远回到本章开头」，而它只在慢设备上出现。
 *    所以 restoreScroll 里要 `await nextTick()`。
 *
 * ============================================================
 * ★★ 章评的「加载更多」不能靠 onReachBottom
 * ============================================================
 * 详情页用的是**页面级** `onReachBottom`。这一页不行：
 * 整屏是一个 `scroll-view`，**页面本身不滚动** →
 * `onReachBottom` 永远不会触发，而它不触发的时候**什么都不报**。
 * 所以这里用 `scroll-view` 自己的 `@scrolltolower`。
 * （同一个需求在两种页面结构下要用两个不同的钩子。）
 *
 * ============================================================
 * ★★ 翻章用 `redirectTo` 而不是 `navigateTo`
 * ============================================================
 * 读 100 章就会往页面栈里压 100 个页面，而小程序/App 的页面栈
 * 是**有上限的**（超了 navigateTo 直接失败）。`redirectTo` 替换当前页，
 * 返回时直接回到详情页 —— 这也正是读者期望的。
 */
import { computed, nextTick, ref } from 'vue'
import { onLoad, onUnload } from '@dcloudio/uni-app'
import { getChapterContent, getNovelCatalog } from '@/api/novel'
import { reportProgress } from '@/api/reader'
import { useUserStore } from '@/stores/user'
import {
  FONT_SIZES,
  LINE_HEIGHTS,
  THEMES,
  loadProgress,
  loadSettings,
  saveProgress,
  saveSettings,
} from '@/utils/readerStorage'
import CommentSection from '@/components/CommentSection.vue'

const userStore = useUserStore()

const novelId = ref(null)
const chapterId = ref(null)

/** 评论组件：这一页的「加载更多」由 scroll-view 的 scrolltolower 驱动 */
const commentRef = ref(null)
const scrollRef = ref(null)

/* ==================== 章节内容 ==================== */

const chapter = ref(null)
const loading = ref(false)
const failed = ref(false)

/** 目录（全量精简），只为了算「上一章/下一章」和显示章节序号 */
const catalog = ref([])

const chapterIndex = computed(() => catalog.value.findIndex((c) => String(c.id) === String(chapterId.value)))

/** 上一章 / 下一章。★ 返回 null 表示到头了，按钮据此置灰 */
const prevChapterId = computed(() => {
  const i = chapterIndex.value
  return i > 0 ? catalog.value[i - 1].id : null
})
const nextChapterId = computed(() => {
  const i = chapterIndex.value
  return i >= 0 && i < catalog.value.length - 1 ? catalog.value[i + 1].id : null
})

/** 「第 N 章」里的 N。★ 优先用目录里的 chapterNo，目录没加载出来时回退到接口返回值 */
const chapterNo = computed(() => catalog.value[chapterIndex.value]?.chapterNo ?? chapter.value?.chapterNo ?? null)

/* ==================== 阅读设置 ==================== */

const settings = ref(loadSettings())
const settingsOpen = ref(false)

/**
 * ★ 三档设置映射成 CSS 值。
 *   ★★ **值要从三张表里查，不能在模板里写死** —— 表是和桌面端逐字相同的
 *      （`FONT_SIZES` / `LINE_HEIGHTS` / `THEMES`），桌面端加了一档，
 *      这里不查表就会对不上。查不到时回退到第一档，绝不产出 undefined
 *      （undefined 塞进 CSS 变量 = 设置静默失效）。
 */
const fontSizeValue = computed(
  () => (FONT_SIZES.find((o) => o.key === settings.value.fontSize) ?? FONT_SIZES[1]).value,
)
const lineHeightValue = computed(
  () => (LINE_HEIGHTS.find((o) => o.key === settings.value.lineHeight) ?? LINE_HEIGHTS[1]).value,
)
const themeValue = computed(() => THEMES.find((o) => o.key === settings.value.theme) ?? THEMES[0])

/** 正文区域的配色。★ 前景和背景**成对**给，只给背景会在夜间主题下看不清字 */
const paperStyle = computed(() => ({
  backgroundColor: themeValue.value.bg,
  color: themeValue.value.fg,
}))

/** 改一项设置并落盘。★ 逐项写，不整份覆盖 —— 见 readerStorage 的 saveSettings */
function pickFontSize(key) {
  settings.value = { ...settings.value, fontSize: key }
  saveSettings(settings.value)
}
function pickLineHeight(key) {
  settings.value = { ...settings.value, lineHeight: key }
  saveSettings(settings.value)
}
function pickTheme(key) {
  settings.value = { ...settings.value, theme: key }
  saveSettings(settings.value)
}

/* ==================== 滚动 / 进度 ==================== */

/**
 * 传给 `<scroll-view>` 的 `scroll-top`。
 *
 * ★★ 它只用于**程序触发的滚动**（恢复进度、换章回到顶部）。
 *    用户手指滚动时**不要**把它同步回来 —— 那会让 scroll-view
 *    在每一帧都收到一个和当前一致的新值，滚动变得一顿一顿的
 *    （而它看起来像"性能问题"，不像 bug）。
 */
const scrollTop = ref(0)

/** 当前滚动位置。★ 用户滚动时只更新这个，不动 `scrollTop` */
let currentOffset = 0

/** 节流用的定时器。★ 见 `onScroll` */
let saveTimer = null

/**
 * 节流写进度。
 *
 * ★★ 为什么必须节流：`@scroll` 一秒钟能触发几十次，
 *    每次都 `JSON.stringify` + 写 localStorage，在中低端机上
 *    会明显掉帧。而进度这种东西，丢了最后 500ms 完全无所谓。
 */
function onScroll(e) {
  currentOffset = Number(e?.detail?.scrollTop) || 0
  if (saveTimer) return
  saveTimer = setTimeout(() => {
    saveTimer = null
    flushProgress()
  }, 500)
}

/** 立刻把当前位置写进本地进度。★ 换章 / 退出页面时调，别等节流 */
function flushProgress() {
  if (!novelId.value || !chapterId.value) return
  /*
   * ★★ 传 `currentOffset` 这个**数字**，绝不把事件对象整个传进来。
   *   `Number({detail:{...}})` 是 NaN，而 saveProgress 的校验会拦下它 ——
   *   拦下是对的，但那意味着进度一次都没写成功，而表现只是
   *   「进度记不住」，没有任何报错。见 readerStorage.js 的 saveProgress。
   */
  saveProgress(novelId.value, chapterId.value, currentOffset)
  reportToServer()
}

/* ==================== 服务端进度上报 ==================== */

/**
 * 章内百分比（0~100，整数）。
 *
 * ★ 分母是 `scrollHeight - clientHeight`（能滚的距离），不是 `scrollHeight` ——
 *   用后者的话滚到底也只有 90% 出头，**永远到不了 100%**。
 * ★★ `max <= 0`（整章都在一屏里）返回 **100 而不是 0**：
 *   不能滚动 = 这一章已经看完了。返回 0 的话，
 *   一本每章都很短的书会永远显示「读到 0%」。
 *   （这条和桌面端 `reader-web/src/views/reader/index.vue#currentPercent` 一字不差。）
 */
function percentFrom(scrollTop, scrollHeight, clientHeight) {
  const max = scrollHeight - clientHeight
  if (max <= 0) return 100
  return Math.max(0, Math.min(100, Math.round((scrollTop / max) * 100)))
}

/**
 * 把进度上报到**服务端**（和上面那份 localStorage 进度是两回事）。
 *
 * ★★ 为什么两个都要写：
 *   · localStorage 是**本机**的，阅读器自己恢复位置用；
 *   · 服务端那份是**历史页/书架页**读的（`read_history` 表）。
 *     只写 localStorage 的表现是：**历史页永远是空的** ——
 *     而阅读器本身一切正常，所以这个缺口在阅读器上是看不出来的。
 *
 * ★★ 用 `createSelectorQuery` 拿几何量，因为 uni-app 里**没有 DOM ref**。
 *   实测（不是照文档写的）：`.boundingClientRect()` 给 `height`、
 *   `.scrollOffset()` 给 `{scrollTop, scrollHeight}`，两个合起来才是分母。
 *   ★ 顺带一提：`@scroll` 的 `e.detail` 里**没有** `clientHeight`，
 *     所以不能只靠事件参数算出来。
 *
 * ★★ **游客不发请求**：那是一次注定 401 的往返，而读者端的主体是游客。
 *   进度已经写进 localStorage 了，游客一样「记得住」。
 *
 * ★ 不 await、也不向上抛：上报失败最多是少记一次进度，不该影响任何界面行为。
 *   （`silent: true` 保证它连 401 都不会弹窗/跳登录页。）
 */
function reportToServer() {
  if (!userStore.isLogin) return
  const nid = Number(novelId.value)
  const cid = Number(chapterId.value)
  /*
   * ★★ `Number()` 不能省：这两个值来自 URL query，是**字符串**。
   *   后端 `HistoryReportDTO` 的字段是数字类型，直接传字符串会 400 ——
   *   而 400 在这里是 silent 的，表现只是「历史页没有记录」。
   */
  if (!Number.isFinite(nid) || !Number.isFinite(cid)) return

  try {
    uni
      .createSelectorQuery()
      .select('.reader__scroll')
      .boundingClientRect()
      .select('.reader__scroll')
      .scrollOffset()
      .exec((res) => {
        const rect = res?.[0]
        const off = res?.[1]
        if (!rect || !off) return
        reportProgress({
          novelId: nid,
          chapterId: cid,
          progress: percentFrom(off.scrollTop, off.scrollHeight, rect.height),
        }).catch(() => {})
      })
  } catch {
    /*
     * ★ onUnload 里也会走到这里，而那时页面正在拆 —— 查询可能直接抛。
     *   这是**预期内**的：那一次上报本来就可有可无，
     *   而让它冒泡出去会把「关掉阅读器」变成一个报错。
     */
  }
}

/**
 * 把滚动位置恢复到 `offset`。
 *
 * ★★ 这里的 `scrollTop.value = 0` → `nextTick` → 赋目标值，三步都不能省，
 *    两个原因叠加：
 *    ① 赋**同一个值**不会触发滚动（见文件头事实②）；
 *    ② 内容还没渲染完时滚不到目标位置（事实③）。
 *    只写最后那行赋值的版本，在"上次读到 0"和"网慢"两种情况下
 *    表现完全不同 —— 这正是那种"本地测一百次都对、用户第一次就错"的 bug。
 */
async function restoreScroll(offset) {
  scrollTop.value = 0
  await nextTick()
  if (offset > 0) {
    scrollTop.value = offset
    // ★ 记下来：程序滚动之后 currentOffset 要跟着走，
    //   否则下一次 @scroll 没触发时会把 0 写回去
    currentOffset = offset
  }
}

/* ==================== 加载 ==================== */

async function load() {
  loading.value = true
  failed.value = false
  try {
    /*
     * ★ 目录和正文并发。目录在换章之间是**不变**的，理论上可以缓存，
     *   但这一页每次换章都是 redirectTo（整页重建），
     *   缓存要落到 storage 才有效 —— 那是额外的复杂度，先不引入。
     */
    const [c, list] = await Promise.all([
      getChapterContent(novelId.value, chapterId.value),
      catalog.value.length ? Promise.resolve(catalog.value) : getNovelCatalog(novelId.value),
    ])
    chapter.value = c
    catalog.value = list ?? []
  } catch {
    /*
     * ★ 失败原因不在这里区分：request.js 的拦截器已经把后端给的
     *   message 提示过了（章节不存在是 3001）。页面只给一个
     *   「本章读不了」+ 返回，具体原因看那条瞬时提示。
     */
    failed.value = true
    chapter.value = null
  } finally {
    loading.value = false
  }
}

/**
 * 进页面：加载正文 + 恢复进度。
 *
 * ★★ 恢复的那一步要判 `chapterId` 是否**就是**进度记着的那一章：
 *   进度是"每本书一条"，里面记的是"N 章 + 章内偏移"。
 *   不判的话，从详情页跳到第 5 章时会把第 3 章的偏移应用上去 ——
 *   而症状是「第 5 章一打开就停在中间」。
 */
async function init(options) {
  novelId.value = options?.novelId ?? null
  chapterId.value = options?.chapterId ?? null
  if (!novelId.value || !chapterId.value) {
    failed.value = true
    return
  }

  await load()
  if (failed.value) return

  const progress = loadProgress(novelId.value)
  const sameChapter = progress && String(progress.chapterId) === String(chapterId.value)
  await restoreScroll(sameChapter ? progress.offset : 0)
}

onLoad((options) => {
  init(options)
})

/**
 * 离开页面时把进度落盘。
 *
 * ★★ 必须连同 `clearTimeout` 一起写：不清的话，那个已经排队的节流回调
 *    会在页面销毁之后再执行一次，写的还是同一个位置 ——
 *    现在无害，但将来 saveProgress 若加了副作用（比如上报），
 *    它就会在一个已经没了的页面上跑。
 */
onUnload(() => {
  if (saveTimer) {
    clearTimeout(saveTimer)
    saveTimer = null
  }
  flushProgress()
})

/* ==================== 翻章 ==================== */

/**
 * 跳到某一章。
 *
 * ★★ `redirectTo` 而不是 `navigateTo`：见文件头。
 * ★ 跳之前先把当前进度落盘 —— 换页之后 `chapterId` 就变了，
 *   那时再存就把"新章的开头"写成了新章的进度（其实也对），
 *   但**旧章读到哪**就永远丢了。
 */
function goChapter(id) {
  if (!id) return
  flushProgress()
  uni.redirectTo({ url: `/pages/reader/reader?novelId=${novelId.value}&chapterId=${id}` })
}

function goPrev() {
  goChapter(prevChapterId.value)
}

function goNext() {
  goChapter(nextChapterId.value)
}

function goBack() {
  // ★ 详情页是 navigateTo 进来的，所以 back 能回去；
  //   直接打开这一页（分享链接）时栈里只有它，navigateBack 会失败 → 回落首页
  const pages = getCurrentPages()
  if (pages.length > 1) {
    uni.navigateBack()
  } else {
    uni.switchTab({ url: '/pages/index/index' })
  }
}

/* ==================== 左右滑动翻章 ==================== */

let touchStartX = 0
let touchStartY = 0

/** 判定为"横向滑动"的最小位移。★ 太小会把纵向滚动误判成翻章 */
const SWIPE_MIN = 60

function onTouchStart(e) {
  const t = e?.touches?.[0] ?? e?.changedTouches?.[0]
  touchStartX = Number(t?.clientX) || 0
  touchStartY = Number(t?.clientY) || 0
}

/**
 * 抬手时判断是不是翻章手势。
 *
 * ★★ **必须同时要求 `|dx| > |dy|`**：只判 `|dx| > 60` 的话，
 *    用户在屏幕上斜着划一下（很常见）就会翻章 ——
 *    而他以为自己在滚动。这是触屏上最容易做错的一个判断。
 *
 * ★ 桌面端那套「用户选中了文字就别当滑动」（`window.getSelection()`）
 *   在移动端**刻意不做**：`text` 组件不长按选中，
 *   那个判断在这里永远是 false，写了就是一段永远不生效的代码。
 */
function onTouchEnd(e) {
  const t = e?.changedTouches?.[0]
  if (!t) return
  const dx = (Number(t.clientX) || 0) - touchStartX
  const dy = (Number(t.clientY) || 0) - touchStartY
  if (Math.abs(dx) < SWIPE_MIN || Math.abs(dx) <= Math.abs(dy)) return

  if (dx < 0) {
    goNext()
  } else {
    goPrev()
  }
}

/* ==================== 章评「加载更多」 ==================== */

/**
 * ★★ 用 `scroll-view` 的 `scrolltolower`，**不是**页面的 `onReachBottom`。
 *   这一页整屏是 scroll-view，页面本身不滚动 —— 见文件头。
 */
function onScrollToLower() {
  commentRef.value?.loadMore()
}
</script>

<template>
  <view class="reader" :style="paperStyle">
    <scroll-view
      ref="scrollRef"
      class="reader__scroll"
      scroll-y
      :scroll-top="scrollTop"
      :lower-threshold="120"
      @scroll="onScroll"
      @scrolltolower="onScrollToLower"
      @touchstart="onTouchStart"
      @touchend="onTouchEnd"
    >
      <view class="paper">
        <view v-if="loading" class="hint">
          <text class="hint__text">加载中…</text>
        </view>

        <view v-else-if="failed" class="hint">
          <text class="hint__text">这一章读不了，可能已被删除或下架</text>
          <view class="hint__btn" hover-class="hint__btn--hover" @click="goBack">
            <text class="hint__btn-text">返回</text>
          </view>
        </view>

        <template v-else-if="chapter">
          <text class="paper__title">{{ chapter.title }}</text>
          <text
            class="paper__content"
            :style="{ fontSize: fontSizeValue, lineHeight: lineHeightValue }"
            >{{ chapter.content }}</text
          >

          <!-- ============ 翻章 ============ -->
          <view class="nav">
            <view class="nav__btn" :class="{ 'nav__btn--off': !prevChapterId }" @click="goPrev">
              <text class="nav__text">上一章</text>
            </view>
            <view class="nav__btn" @click="goBack">
              <text class="nav__text">目录</text>
            </view>
            <view class="nav__btn" :class="{ 'nav__btn--off': !nextChapterId }" @click="goNext">
              <text class="nav__text">下一章</text>
            </view>
          </view>
        </template>

        <!-- ============ 章评 ============ -->
        <view class="comments">
          <CommentSection ref="commentRef" :novel-id="novelId" :chapter-id="chapterId" />
        </view>
      </view>
    </scroll-view>

    <!-- ============ 底栏 ============ -->
    <view class="bar">
      <text class="bar__chapter">
        第 {{ chapterNo ?? '—' }} 章
      </text>
      <view class="bar__btn" hover-class="bar__btn--hover" @click="settingsOpen = !settingsOpen">
        <text class="bar__btn-text">设置</text>
      </view>
    </view>

    <!-- ============ 设置面板 ============ -->
    <!--
      ★ 点遮罩关闭。★ 遮罩用 @click.stop 之外还要在面板上 .stop，
        否则点面板内部也会关掉 —— 那是一点就消失、没法调设置。
    -->
    <view v-if="settingsOpen" class="mask" @click="settingsOpen = false">
      <view class="sheet" @click.stop>
        <view class="sheet__row">
          <text class="sheet__label">字号</text>
          <view class="chips">
            <text
              v-for="o in FONT_SIZES"
              :key="o.key"
              class="chip"
              :class="{ 'chip--on': settings.fontSize === o.key }"
              @click="pickFontSize(o.key)"
              >{{ o.label }}</text
            >
          </view>
        </view>

        <view class="sheet__row">
          <text class="sheet__label">行距</text>
          <view class="chips">
            <text
              v-for="o in LINE_HEIGHTS"
              :key="o.key"
              class="chip"
              :class="{ 'chip--on': settings.lineHeight === o.key }"
              @click="pickLineHeight(o.key)"
              >{{ o.label }}</text
            >
          </view>
        </view>

        <view class="sheet__row">
          <text class="sheet__label">背景</text>
          <view class="chips">
            <text
              v-for="o in THEMES"
              :key="o.key"
              class="chip"
              :class="{ 'chip--on': settings.theme === o.key }"
              @click="pickTheme(o.key)"
              >{{ o.label }}</text
            >
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
/*
 * ★★ 整屏 flex 布局，`.reader__scroll` 用 `height: 0` + `flex: 1`
 *    撑出**确定的高度** —— 这是 scroll-view 能滚动的**前提**。
 *    没有高度的 scroll-view 不滚动，而且不报错（见文件头事实①）。
 */
.reader {
  display: flex;
  flex-direction: column;
  height: 100vh;
}

.reader__scroll {
  flex: 1;
  height: 0;
}

.paper {
  padding: 32rpx 32rpx 60rpx;
}

.paper__title {
  display: block;
  font-size: 36rpx;
  font-weight: 600;
  margin-bottom: 28rpx;
}

.paper__content {
  display: block;
  /* ★ 字号和行高走行内样式（用户可调）；颜色继承 .reader 的主题色 */
  word-break: break-word;
  /* ★ 和评论一样：正文里的换行要保留 */
  white-space: pre-line;
}

.nav {
  display: flex;
  margin-top: 48rpx;
}

.nav__btn {
  flex: 1;
  padding: 22rpx 0;
  margin: 0 8rpx;
  text-align: center;
  background-color: rgba(127, 127, 127, 0.12);
  border-radius: 12rpx;
}

/* ★ 到头了：只降透明度、点击仍然进 goChapter(null) 然后 return。
   置灰不等于禁用，真正的守卫在 JS 里 */
.nav__btn--off {
  opacity: 0.4;
}

.nav__text {
  font-size: 28rpx;
}

.comments {
  margin-top: 40rpx;
}

.hint {
  padding: 120rpx 0;
  text-align: center;
}

.hint__text {
  font-size: 28rpx;
  color: #9aa3b0;
}

.hint__btn {
  display: inline-block;
  margin-top: 24rpx;
  padding: 14rpx 44rpx;
  background-color: #2f6fed;
  border-radius: 999rpx;
}

.hint__btn--hover {
  opacity: 0.85;
}

.hint__btn-text {
  font-size: 28rpx;
  color: #ffffff;
}

/* ============ 底栏 ============ */
.bar {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16rpx 32rpx;
  /* ★ 底栏要在安全区之上（全面屏底部那条横条） */
  padding-bottom: calc(16rpx + constant(safe-area-inset-bottom));
  padding-bottom: calc(16rpx + env(safe-area-inset-bottom));
  background-color: #ffffff;
  border-top: 1rpx solid #eef0f4;
}

.bar__chapter {
  font-size: 26rpx;
  color: #5a6472;
}

.bar__btn {
  padding: 10rpx 32rpx;
  background-color: #f0f1f4;
  border-radius: 999rpx;
}

.bar__btn--hover {
  opacity: 0.85;
}

.bar__btn-text {
  font-size: 26rpx;
  color: #1f2329;
}

/* ============ 设置面板 ============ */
.mask {
  position: fixed;
  left: 0;
  right: 0;
  top: 0;
  bottom: 0;
  background-color: rgba(0, 0, 0, 0.4);
  z-index: 100;
  display: flex;
  align-items: flex-end;
}

.sheet {
  width: 100%;
  padding: 32rpx;
  padding-bottom: calc(32rpx + constant(safe-area-inset-bottom));
  padding-bottom: calc(32rpx + env(safe-area-inset-bottom));
  background-color: #ffffff;
  border-radius: 24rpx 24rpx 0 0;
}

.sheet__row {
  display: flex;
  align-items: center;
  margin-bottom: 28rpx;
}

.sheet__label {
  flex-shrink: 0;
  width: 90rpx;
  font-size: 28rpx;
  color: #5a6472;
}

.chips {
  flex: 1;
  display: flex;
  flex-wrap: wrap;
}

.chip {
  margin: 0 16rpx 12rpx 0;
  padding: 10rpx 28rpx;
  font-size: 26rpx;
  color: #1f2329;
  background-color: #f0f1f4;
  border-radius: 999rpx;
}

.chip--on {
  color: #ffffff;
  background-color: #2f6fed;
}
</style>
