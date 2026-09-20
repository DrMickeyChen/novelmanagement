<script setup>
/**
 * 阅读器 —— 全屏，独立滚动容器。
 *
 * ============================================================
 * ★★ 为什么它不套 BasicLayout（router/index.js 里的顶层路由）
 * ============================================================
 * ① 全屏。顶部一条导航栏对沉浸式阅读是纯干扰。
 *
 * ② ★【功能性】理由，不只是好看：进度是按「滚动容器」算的
 *    （scrollTop / (scrollHeight - clientHeight)）。
 *    如果外面套着 BasicLayout，那个滚动容器是 el-main，它比视口矮
 *    一个导航栏的高度 —— 用 window 算出来的百分比会永远偏小，
 *    表现为「进度记不准」。偏多少取决于导航栏高度和字体，
 *    换个分辨率就变，是那种查半天查不出所以然的偏差。
 *    这个页面自己独占一个滚动容器，问题就不存在。
 *
 * ============================================================
 * ★★ 进度记忆：本地像素 + 服务端百分比，各管一半
 * ============================================================
 * 恢复的判定顺序（顺序不能换）：
 *   ① 本机记录【且章节 id 与 URL 一致】→ 恢复像素偏移（精确到那一行）
 *   ② 本机没有 / 章节对不上 → 尝试服务端百分比（跨设备），
 *      ★ 但服务端的 chapterId 也必须和 URL 一致，否则那个百分比
 *        说的是别的章，套到这一章上只会停在一个莫名其妙的位置
 *   ③ 都不行 → 置顶
 *
 * 上报：滚动停止 1 秒后报一次 + 离开页面时再报一次。
 *   ★ 游客【只写本地】，不发请求（/api/reader/history 会 401，
 *     即使 silent 也是白跑一趟）。
 *
 * ============================================================
 * ★ 安全 / 渲染上的两条硬约束
 * ============================================================
 * ① 正文用 `{{ content }}`，【严禁 v-html】。正文是作者提交的自由文本，
 *    v-html 会让它变成 HTML 进到浏览器里 —— 那是一个 XSS 入口。
 *    换行靠 CSS 的 white-space: pre-wrap。
 * ② 正文【不做任何富文本排版】（首行缩进之类的效果靠 CSS，
 *    不靠往文本里插空格或全角空格）—— 一旦往文本里插字符，
 *    复制正文时就会带上那些垃圾字符。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getChapterContent, getNovelDetail } from '@/api/novel'
import { getProgress, reportProgress } from '@/api/reader'
import { useReaderStore } from '@/stores/reader'
import { useUserStore } from '@/stores/user'
import { loadProgress, saveProgress } from '@/utils/readerStorage'

const route = useRoute()
const router = useRouter()
const readerStore = useReaderStore()
const userStore = useUserStore()

/** 滚动停止多久之后上报。1 秒是「够安静」和「不丢太多」之间的取舍 */
const REPORT_DEBOUNCE_MS = 1000

const novelId = computed(() => route.params.novelId)
const chapterId = computed(() => route.params.chapterId)

/** 滚动容器。★ 进度全靠它，不能用 window —— 理由见文件头 */
const scrollerRef = ref(null)

/** 作品详情（这里只用它的目录：上一章/下一章/目录抽屉都要） */
const novel = ref(null)
const chapters = computed(() => novel.value?.chapters ?? [])

/** 当前章的正文 */
const chapter = ref(null)
const loading = ref(false)
const failed = ref(false)

const catalogVisible = ref(false)

/**
 * ★★ ready 是「进度恢复做完了」的闸门。
 *
 * 滚动回调在它变成 true 之前【什么都不做】。
 * 不加这个闸门会发生什么：打开一章时我们先把 scrollTop 归零，
 * 这个动作本身会触发一次 scroll 事件，1 秒后把「进度 0%」写进本地 ——
 * 而这时候恢复还没做完（在等字体加载），于是【刚恢复好的位置被覆盖成 0】。
 * 下次再打开这本书，进度就变成了开头。
 */
const ready = ref(false)

/* ==================== 章节切换 ==================== */

const currentIndex = computed(() =>
  chapters.value.findIndex((c) => String(c.id) === String(chapterId.value)),
)

/** 上一章 / 下一章。★ 目录是按 chapterNo 排的（后端保证），所以用下标找邻居 */
const prevChapter = computed(() => (currentIndex.value > 0 ? chapters.value[currentIndex.value - 1] : null))
const nextChapter = computed(() =>
  currentIndex.value >= 0 && currentIndex.value < chapters.value.length - 1
    ? chapters.value[currentIndex.value + 1]
    : null,
)

onMounted(async () => {
  window.addEventListener('keydown', handleKeydown)
  // ★ 关掉网页时再报一次。它的可靠性有限（浏览器不保证异步请求发得出去），
  //   所以它只是【补充】—— 真正兜底的是滚动时的 1 秒防抖上报。
  window.addEventListener('beforeunload', handleBeforeUnload)

  await loadNovel()
  await loadChapter()
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleKeydown)
  window.removeEventListener('beforeunload', handleBeforeUnload)
  // ★ 离开路由（比如点了「返回详情」）时要立刻报一次：
  //   这条路不触发 beforeunload，不报的话最后一段阅读位置就丢了。
  flushProgress()
  cancelPendingReport()
})

async function loadNovel() {
  try {
    novel.value = await getNovelDetail(novelId.value)
  } catch {
    /*
     * ★ 目录拿不到【不阻断阅读】：正文接口自己会校验，能读到就正常显示。
     *   只是「上一章 / 下一章」和目录抽屉会是空的。
     *   这里刻意不置 failed —— 为了一份目录把整章正文拦下来，主次颠倒。
     */
    novel.value = null
  }
}

/**
 * 加载当前章的正文。
 *
 * ★ watch 的是 chapterId（下面），所以这个方法在【切章】和【首次进入】
 *   两条路径上都会跑，不存在只走其中一条的情况。
 */
async function loadChapter() {
  loading.value = true
  failed.value = false
  ready.value = false

  try {
    chapter.value = await getChapterContent(novelId.value, chapterId.value)
    // ★ 先把滚动位置归零，再谈恢复。
    //   不归零的话，从第 5 章翻到第 6 章时，新正文会从「上一章读到的那个
    //   高度」开始显示 —— 表现为「翻页后跳到了新章节的中间」。
    if (scrollerRef.value) scrollerRef.value.scrollTop = 0
    await restoreProgress()
    // ★ 换章之后标题也跟着变，否则浏览器的标签页和前进后退历史里
    //   全都叫「阅读」，用户找不到自己在看哪一本
    document.title = `${chapter.value?.title || '阅读'} - ${novel.value?.title || '小说阅读'}`
  } catch {
    failed.value = true
    chapter.value = null
  } finally {
    loading.value = false
  }
}

/* ==================== 进度恢复 ==================== */

/**
 * 按「本地 → 服务端 → 置顶」的顺序恢复滚动位置。
 *
 * ★★ 必须等 nextTick + document.fonts.ready 之后再算偏移量。
 *   正文用的是自定义字号（--reader-font-size），字体没加载完时
 *   浏览器先用后备字体排版，scrollHeight 是【偏小】的 ——
 *   这时候按百分比算出来的位置也偏小，用户看到的就是「进度没记住，
 *   打开就停在靠前的位置」。等字体就位后再算才准。
 *   （nextTick 保证 DOM 里已经有这一章的正文了，没有它连 scrollHeight 都读不到。）
 */
async function restoreProgress() {
  const scroller = scrollerRef.value
  if (!scroller) return

  try {
    await nextTick()
    // ★ 字体没加载完时 fonts.ready 会等到加载完才 resolve；
    //   已经加载完的话它立刻 resolve。两种情况都正确。
    if (document.fonts?.ready) {
      await document.fonts.ready
    }

    const max = scroller.scrollHeight - scroller.clientHeight
    // ★ max <= 0 说明这一章短到不用滚动（整章都在视口里）。
    //   此时任何偏移都没有意义，也不能拿它去除（除零 → Infinity/NaN）。
    if (max <= 0) return

    const local = loadProgress(novelId.value)
    if (local && String(local.chapterId) === String(chapterId.value)) {
      // ① 本机记录，章节对得上 —— 最精确的一档
      scroller.scrollTop = Math.min(local.offset, max)
      return
    }

    if (!userStore.isLogin) return

    // ② 服务端百分比。★ 它自己的 chapterId 也必须和当前章一致，
    //    否则那个百分比说的是别的章（见文件头）。失败就当作没有。
    const remote = await getProgress(novelId.value)
    if (!remote || String(remote.chapterId) !== String(chapterId.value)) return

    const percent = Number(remote.progress)
    if (!Number.isFinite(percent) || percent <= 0) return
    scroller.scrollTop = (Math.min(percent, 100) / 100) * max
  } catch {
    // ★ 恢复进度失败【绝不能让阅读器崩掉或白屏】—— 停在顶部照样能读
  } finally {
    ready.value = true
  }
}

/* ==================== 进度上报 ==================== */

/** 防抖定时器 */
let reportTimer = null

/**
 * 当前进度百分比（0~100，整数）。
 *
 * ★ 分母是 (scrollHeight - clientHeight) 而不是 scrollHeight：
 *   能滚动的总距离才是分母。用 scrollHeight 的话进度永远到不了 100%
 *   —— 因为滚到底时 scrollTop 最大也只有 scrollHeight - clientHeight。
 */
function currentPercent() {
  const scroller = scrollerRef.value
  if (!scroller) return 0
  const max = scroller.scrollHeight - scroller.clientHeight
  // ★ 不能滚动 = 整章都在视口里 = 读完了。返回 0 的话，
  //   一本每章都很短的书会永远显示「读到 0%」。
  if (max <= 0) return 100
  const percent = (scroller.scrollTop / max) * 100
  return Math.max(0, Math.min(100, Math.round(percent)))
}

/**
 * 滚动回调。★ 用 @scroll.passive 绑定（见模板）——
 * 被动监听不阻塞滚动，滚动是阅读器里最高频的事件。
 */
function onScroll() {
  // ★ 恢复还没做完就先不记：理由见 ready 的说明
  if (!ready.value) return
  clearTimeout(reportTimer)
  reportTimer = setTimeout(flushProgress, REPORT_DEBOUNCE_MS)
}

/**
 * beforeunload 的专用包装。
 *
 * ★★ 必须有这个包装，【不能】把 flushProgress 直接注册成监听器。
 *   浏览器调用监听器时会把【事件对象】作为第一个实参传进去 ——
 *   而 flushProgress 的第一个形参是 forChapterId。于是
 *   `cid = forChapterId ?? chapterId.value` 里的 `??` 看到的是
 *   一个非空的 Event 对象，直接采用了它，最终
 *   `Number(event)` → NaN → JSON 序列化成 `"chapterId": null`。
 *
 *   后果不是「少记一次进度」，而是【本地记录被写坏】：
 *   utils/readerStorage.js 的 loadProgress 会因为 chapterId 不是
 *   有限正数而返回 null，于是下次打开这本书读不到任何记录 ——
 *   「记住进度」这个功能整体失效，而读一条合法记录时一切正常，
 *   所以只在刷新之后才看得出来。
 *
 *   （同理，模板上的 @scroll 也【不能】绑 flushProgress：它同样会收到
 *     事件对象。那个位置绑的是 onScroll，它没有形参，是安全的。）
 *
 * ★ 这类「监听器签名和业务函数签名撞车」的 bug，人眼极难发现 ——
 *   两边单看都完全正确。它是靠真实浏览器跑一遍刷新流程才抓出来的。
 */
function handleBeforeUnload() {
  flushProgress()
}

/**
 * 立刻把当前进度写下去（本地 + 服务端）。
 *
 * @param {string|number} [forChapterId] 要记录的章节，默认当前 URL 上的那一章。
 *        ★★ 这个参数是必须的，不是可选的方便选项 —— 见下面 watch 里的说明：
 *        切章时 watch 回调触发的那一刻，URL 上的 chapterId 【已经是新的了】，
 *        而滚动容器里还是旧章节的正文。此时不带参数地调用，就会把
 *        「新章节 id + 旧章节的滚动位置」这一组错位的值写进去。
 *        ★ 也正因为这个形参的存在，把它直接当事件监听器传会出事，
 *        见 handleBeforeUnload。
 *
 * ★ 本地是同步写的、一定成功；服务端是异步的、可能发不出去
 *   （beforeunload 里就是这样，浏览器不保证请求发得出去）。
 *   两者都做，是因为丢服务端只是「换设备看不到进度」，
 *   丢本地则是「这台设备上也没记住」—— 严重程度不一样。
 */
function flushProgress(forChapterId) {
  if (!ready.value) return
  const scroller = scrollerRef.value
  if (!scroller) return

  const cid = forChapterId ?? chapterId.value
  saveProgress(novelId.value, cid, scroller.scrollTop)

  /*
   * ★★ 游客不发请求。getProgress/reportProgress 都带了 silent，
   *   所以就算发了也不会弹窗，但那是一次注定 401 的往返 ——
   *   读者端的主体是游客，这个浪费乘以访问量并不小。
   *   进度已经在上面写进 localStorage 了，游客一样「记得住」。
   *
   * ★ 这里不 await、也不 catch 到外面：上报失败（除了已经处理过的 401）
   *   最多是少记一次进度，不该影响任何界面行为。
   */
  if (userStore.isLogin) {
    reportProgress({
      novelId: Number(novelId.value),
      chapterId: Number(cid),
      progress: currentPercent(),
    }).catch(() => {})
  }
}

function cancelPendingReport() {
  clearTimeout(reportTimer)
  reportTimer = null
}

/* ==================== 交互 ==================== */

/** 翻到指定章节。★ 走路由而不是直接 loadChapter：URL 要跟着变 */
function gotoChapter(id) {
  if (!id) return
  router.push({ name: 'reader', params: { novelId: novelId.value, chapterId: id } })
}

/**
 * 键盘快捷键：← 上一章、→ 下一章、Esc 关抽屉。
 *
 * ★★ 三个必须做的判断，少一个都会出问题：
 *   ① e.repeat —— 长按方向键时浏览器会连续触发 keydown，
 *      不判的话按住一秒能翻十几章出去，而且每一章都会发一次请求。
 *   ② 修饰键 —— Ctrl/Alt/Meta + 方向键是浏览器的快捷键
 *      （macOS 上 Cmd+← 是「回到行首」，Cmd+→ 是「跳到行尾」），
 *      抢过来会把用户的正常操作变成翻页。
 *   ③ e.preventDefault() —— 不阻止默认行为，方向键会同时滚动页面，
 *      于是「翻章」和「滚动」一起发生，落点完全不可预期。
 *
 * ★ 输入框里也要放行：用户如果在（将来的）评论框里按方向键移动光标，
 *   不该被解释成翻页。现在阅读器里没有输入框，但阅读设置里以后可能有。
 */
function handleKeydown(e) {
  if (e.repeat || e.ctrlKey || e.altKey || e.metaKey) return

  const tag = e.target?.tagName
  if (tag === 'INPUT' || tag === 'TEXTAREA' || e.target?.isContentEditable) return

  if (e.key === 'Escape') {
    catalogVisible.value = false
    return
  }

  if (e.key === 'ArrowLeft') {
    if (!prevChapter.value) return
    e.preventDefault()
    gotoChapter(prevChapter.value.id)
  } else if (e.key === 'ArrowRight') {
    if (!nextChapter.value) return
    e.preventDefault()
    gotoChapter(nextChapter.value.id)
  }
}

/* ==================== 切章 watch ==================== */

/**
 * ★★ 换章时路由变了但【组件被复用】（同一个路由记录、只是参数不同），
 *   onMounted 不会再跑。少了这个 watch，点「下一章」之后 URL 变了、
 *   正文还是上一章的 —— 而且刷新一下又对了，最难查的一类症状。
 *
 * ★★ oldId 必须接下来传给 flushProgress，这是这个回调里最关键的一处：
 *   回调触发的那一刻，URL 上的 chapterId 已经是【新章节】了，
 *   而滚动容器里装的还是旧章节的正文。
 *   不带 oldId 地调 flushProgress()，写进去的就是
 *   「新章节 id + 旧章节的滚动位置」—— 读者在下一章翻两页就会发现
 *   进度莫名其妙地停在了一个他没读过的位置。而且这个错位值
 *   一旦写进 localStorage，下次打开就是错的。
 *
 * ★ 先清挂起的上报：不清的话，切章后那个定时器会带着旧章节的信息
 *   再报一次，把刚写好的新章进度覆盖掉。
 *   这个 bug 只在「滚完 1 秒内立刻点下一章」时出现，本地很难撞上。
 *
 * ★ 顺带判断目录要不要重取：chapterId 是全局唯一的，
 *   所以 novelId 变了 chapterId 必然也变 —— 不需要再单独 watch novelId
 *   （两个 watch 会一起触发，把同一章正文请求发两遍）。
 */
watch(chapterId, async (newId, oldId) => {
  cancelPendingReport()
  // ★ 切章前先把【旧章节】的进度落一次，否则这一章的阅读位置就丢了
  flushProgress(oldId)

  // 换了本书才重取目录（同一本书内翻页时目录是不变的）
  if (String(novel.value?.id ?? '') !== String(novelId.value)) {
    await loadNovel()
  }
  loadChapter()
})
</script>

<template>
  <!-- ★ 把 4 个 CSS 变量绑在根节点上，整棵子树都能吃到（见 stores/reader.js） -->
  <div class="reader" :style="readerStore.styleVars">
    <!-- ============ 工具条 ============ -->
    <header class="toolbar">
      <div class="left">
        <el-button link @click="router.push(`/novel/${novelId}`)">← 返回</el-button>
        <span class="book-title">{{ novel?.title || '' }}</span>
      </div>

      <div class="right">
        <el-button link :disabled="!prevChapter" @click="gotoChapter(prevChapter?.id)">
          上一章
        </el-button>
        <el-button link :disabled="!nextChapter" @click="gotoChapter(nextChapter?.id)">
          下一章
        </el-button>
        <el-button link @click="catalogVisible = true">目录</el-button>

        <!--
          ★ 阅读设置用 el-popover 而不是 el-drawer：它只有三个选项、
            改完立刻能看见正文变化，用抽屉会把正文遮住一半，
            正好挡住了「我想看看这个字号合不合适」要看的东西。
        -->
        <el-popover placement="bottom-end" :width="280" trigger="click">
          <template #reference>
            <el-button link>设置</el-button>
          </template>

          <div class="settings">
            <div class="setting-row">
              <span class="setting-label">字号</span>
              <el-radio-group
                :model-value="readerStore.settings.fontSize"
                size="small"
                @update:model-value="(v) => readerStore.update('fontSize', v)"
              >
                <el-radio-button v-for="o in readerStore.FONT_SIZES" :key="o.key" :value="o.key">
                  {{ o.label }}
                </el-radio-button>
              </el-radio-group>
            </div>

            <div class="setting-row">
              <span class="setting-label">行距</span>
              <el-radio-group
                :model-value="readerStore.settings.lineHeight"
                size="small"
                @update:model-value="(v) => readerStore.update('lineHeight', v)"
              >
                <el-radio-button v-for="o in readerStore.LINE_HEIGHTS" :key="o.key" :value="o.key">
                  {{ o.label }}
                </el-radio-button>
              </el-radio-group>
            </div>

            <div class="setting-row">
              <span class="setting-label">背景</span>
              <div class="theme-list">
                <button
                  v-for="o in readerStore.THEMES"
                  :key="o.key"
                  class="theme-dot"
                  :class="{ active: readerStore.settings.theme === o.key }"
                  :style="{ background: o.bg, color: o.fg }"
                  :title="o.label"
                  @click="readerStore.update('theme', o.key)"
                >
                  {{ o.label }}
                </button>
              </div>
            </div>

            <el-button link size="small" class="reset" @click="readerStore.reset()">
              恢复默认
            </el-button>
          </div>
        </el-popover>
      </div>
    </header>

    <!-- ============ 正文（独立滚动容器） ============ -->
    <main ref="scrollerRef" v-loading="loading" class="scroller" @scroll.passive="onScroll">
      <article v-if="chapter" class="article">
        <h1 class="chapter-title">{{ chapter.title }}</h1>
        <!--
          ★ 章节号用 chapter.chapterNo（后端给的），【不要】用 currentIndex + 1。
            下标是「目录里的第几条」，而 chapterNo 是「作者编的第几章」——
            草稿章 / 待审章不列进目录，两者就会不一致。
            目录没取到时（loadNovel 失败）退化成不显示，而不是显示一个错号。
        -->
        <div class="chapter-meta">
          <template v-if="chapter.chapterNo">第 {{ chapter.chapterNo }} 章 · </template>
          {{ chapter.wordCount || 0 }} 字
        </div>

        <!--
          ★★ 必须是 {{ content }}，不能是 v-html。
             正文是作者提交的自由文本，v-html 会把它当 HTML 解析 ——
             那是一个直接的 XSS 入口（作者的输入进到所有读者的浏览器里）。
          ★ 换行靠 CSS 的 white-space: pre-wrap（见 .body），
            不靠往文本里插 <br> 或全角空格。
        -->
        <div class="body">{{ chapter.content }}</div>

        <div class="chapter-nav">
          <el-button :disabled="!prevChapter" @click="gotoChapter(prevChapter?.id)">
            上一章
          </el-button>
          <el-button type="primary" :disabled="!nextChapter" @click="gotoChapter(nextChapter?.id)">
            下一章
          </el-button>
        </div>
      </article>

      <el-empty v-else-if="failed && !loading" description="这一章不存在或已下架">
        <el-button @click="router.push(`/novel/${novelId}`)">返回详情页</el-button>
      </el-empty>
    </main>

    <!-- ============ 目录抽屉 ============ -->
    <el-drawer v-model="catalogVisible" title="目录" size="320px" direction="rtl">
      <div class="catalog">
        <router-link
          v-for="c in chapters"
          :key="c.id"
          :to="`/read/${novelId}/${c.id}`"
          class="catalog-item"
          :class="{ current: String(c.id) === String(chapterId) }"
          @click="catalogVisible = false"
        >
          <span class="catalog-no">{{ c.chapterNo }}</span>
          <span class="catalog-title">{{ c.title }}</span>
        </router-link>

        <el-empty v-if="!chapters.length" description="暂无目录" :image-size="60" />
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
/*
 * ★ 高度用 100%（不是 100vh）：全局样式里 html/body/#app 都是 height: 100%，
 *   100% 会一路继承下来。用 100vh 在移动端浏览器上会算进地址栏的高度，
 *   实际内容会比可视区域高一截、底部被裁掉。
 */
.reader {
  height: 100%;
  display: flex;
  flex-direction: column;
  /* ★ 背景色来自 CSS 变量（主题），文字色同理 —— 见 stores/reader.js */
  background: var(--reader-bg);
  color: var(--reader-fg);
  transition: background 0.2s;
}

.toolbar {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  height: 52px;
  padding: 0 20px;
  /*
   * ★ 工具条【不跟随主题变深】—— 固定成半透明白 + 下边框。
   *   跟着主题变的话，夜间模式下一整条深色栏压在正文上方，
   *   会出现一条明显的分界线（两个相近但不相同的深色）。
   */
  background: rgb(255 255 255 / 80%);
  border-bottom: 1px solid rgb(0 0 0 / 6%);
}

.left,
.right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.book-title {
  font-size: 14px;
  color: #606266;
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/*
 * ★ 正文区【居中 + 限宽】。不限宽的话，宽屏上一行能有 150 个字，
 *   眼睛从行尾找回下一行行首时很容易串行。
 *   45em 是「一行 40~50 个汉字」这个阅读舒适区。
 */
.scroller {
  flex: 1;
  overflow-y: auto;
  /* 滚动到底部时留一点空白，最后一行不会贴着屏幕边缘 */
  padding: 32px 16px 64px;
}

.article {
  max-width: 45em;
  margin: 0 auto;
}

.chapter-title {
  margin: 0 0 6px;
  font-size: 1.35em;
  line-height: 1.5;
  font-weight: 600;
}

.chapter-meta {
  margin-bottom: 28px;
  font-size: 0.8em;
  /* ★ 用 currentColor + 透明度而不是写死 #909399：
     写死的话夜间模式下这行字会又亮又刺眼，和正文的灰度接不上 */
  opacity: 0.55;
}

.body {
  font-size: var(--reader-font-size);
  line-height: var(--reader-line-height);
  /*
   * ★★ pre-wrap 是这里最要紧的一行：正文里的换行符要保留
   *   （那是作者分段的方式），同时长段落还能自动折行。
   *   用 pre 的话长段落会横向溢出、出现横向滚动条；
   *   用默认的 normal 则所有段落会挤成一大坨。
   */
  white-space: pre-wrap;
  /* 长英文单词 / URL 不至于把容器撑破 */
  overflow-wrap: break-word;
  /* 中英文混排时让字距松一点，纯中文正文更好读 */
  letter-spacing: 0.02em;
}

.chapter-nav {
  display: flex;
  justify-content: space-between;
  margin-top: 40px;
  padding-top: 20px;
  border-top: 1px solid rgb(128 128 128 / 20%);
}

/* ============ 设置面板 ============ */
.settings {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.setting-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.setting-label {
  flex: none;
  width: 32px;
  font-size: 13px;
  color: #909399;
}

.theme-list {
  display: flex;
  gap: 6px;
}

.theme-dot {
  width: 40px;
  height: 28px;
  border: 1px solid rgb(128 128 128 / 30%);
  border-radius: 4px;
  font-size: 12px;
  cursor: pointer;
}

.theme-dot.active {
  /* ★ 选中态用 outline 而不是 border：改 border 会让按钮尺寸变一点，
     四个按钮里有一个宽度不同就很难看 */
  outline: 2px solid #409eff;
  outline-offset: 1px;
}

.reset {
  align-self: flex-start;
}

/* ============ 目录 ============ */
.catalog {
  display: flex;
  flex-direction: column;
}

.catalog-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 4px;
  border-bottom: 1px solid #f2f6fc;
  font-size: 14px;
  color: #606266;
}

.catalog-item:hover {
  color: #409eff;
}

.catalog-item.current {
  color: #409eff;
  font-weight: 600;
}

.catalog-no {
  flex: none;
  width: 28px;
  font-size: 12px;
  color: #c0c4cc;
}

.catalog-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
