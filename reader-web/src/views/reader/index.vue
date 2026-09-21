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
 * ★ P6 加的章评：**它是个抽屉，绝不能放进 `<main>` 里**
 * ============================================================
 * 「本章说」按钮在工具条上，点开是页底的一个抽屉（`el-drawer`）。
 * ★ 为什么不把评论区直接接在正文下面 —— 那会同时打破上面那两条：
 *   ① 评论列表进了滚动容器，`scrollHeight` 立刻变大，
 *      「刚打开这一章」就显示读了 30%，滚到正文末尾也到不了 100%；
 *   ② `.chapter-nav`（下一章）会被挤到屏幕外面，读完一章找不到出口。
 *   抽屉是 `position: fixed`，**不改变 `<main>` 的任何尺寸**，两条一起消失。
 *   ★ 完整论证写在模板里那一段，那里离代码更近。

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
import { getChapterContent, getNovelCatalog, getNovelDetail } from '@/api/novel'
import { getProgress, reportProgress } from '@/api/reader'
import { useReaderStore } from '@/stores/reader'
import { useUserStore } from '@/stores/user'
import { loadProgress, saveProgress } from '@/utils/readerStorage'
import CommentSection from '@/components/CommentSection.vue'

const route = useRoute()
const router = useRouter()
const readerStore = useReaderStore()
const userStore = useUserStore()

/** 滚动停止多久之后上报。1 秒是「够安静」和「不丢太多」之间的取舍 */
const REPORT_DEBOUNCE_MS = 1000

const novelId = computed(() => route.params.novelId)
const chapterId = computed(() => route.params.chapterId)

/**
 * ★★ 阅读器「最后在哪一章」的快照。**只增不减**，路由离开时不动它。
 *
 * ------------------------------------------------------------
 * ★ 为什么不能直接用上面那两个 computed 来上报进度
 * ------------------------------------------------------------
 * 它们读的是 `route.params`，而 **`route` 是「现在」的路由，
 * 不是「我离开时」的路由**。组件卸载发生在路由**已经切走之后**：
 * 从 `/read/1059/101271` 点「返回」去 `/novel/1059`，等
 * `onBeforeUnmount` 跑的时候，`route.params` 里是 `{ id: '1059' }` ——
 * **novelId 和 chapterId 双双是 `undefined`**。
 *
 * 于是 `Number(undefined)` → `NaN` → `JSON.stringify` 把它写成 `null`
 * → 后端 `HistoryReportDTO` 的 `@NotNull` 直接 400。而 `reportProgress`
 * 带着 `silent: true`，**一点提示都不会有，静默丢掉**。
 *
 * ★ 症状：**离开阅读器时那一次上报永远是丢的**，服务端于是停在你
 *   【上一次换章】的位置，而不是你真正读到的最后一章 ——
 *   换章时那次上报是在路由还合法的时候发的，所以它是好的，
 *   两件事叠在一起正好是「切到下一章再返回，历史还显示上一章」。
 *
 * ★ 这个 bug **P3 就存在**。它一直没被发现，是因为在 S11 之前
 *   没有任何界面显示服务端的 `chapter_id`：详情页的「继续阅读」
 *   读的是**本地**记录，而本地的每一次写入都发生在路由还合法的时候。
 *   书架页 / 历史页是第一个把它显示出来的地方。
 *
 * ★ 顺带修掉的第二个毛病：以前 `saveProgress(novelId.value, ...)` 在
 *   离开时会拿 `undefined` 当 key，往 localStorage 里写一个
 *   `novel_reader_progress_undefined` 垃圾键。
 *
 * ★ `immediate: true` 是为了首次进入就建立快照；之后只有两个参数
 *   都在的时候才更新 —— 路由离开时它们变成 `undefined`，快照保持不动。
 */
const position = ref({ novelId: null, chapterId: null })

watch(
  [novelId, chapterId],
  ([nid, cid]) => {
    if (nid != null && cid != null) {
      position.value = { novelId: nid, chapterId: cid }
    }
  },
  { immediate: true },
)

/** 滚动容器。★ 进度全靠它，不能用 window —— 理由见文件头 */
const scrollerRef = ref(null)

/** 作品详情。★ P6 起它【不带目录】了，这里只用它的书名（顶栏和 document.title） */
const novel = ref(null)

/**
 * 整本书的目录（全量精简：id / chapterNo / title）—— 上一章/下一章和目录抽屉都要它。
 *
 * ★★ P6 起目录【不再跟着详情回来】，单独走 `GET /novels/{id}/catalog`。
 *   阅读器要的是「我在第几章、前后各是谁」，这需要**整条有序列表**，
 *   分页给不了（详情页用的那个分页接口，下一页的第一条不一定紧邻当前章）。
 *   ★ 所以这里【不是】忘了改成分页 —— 阅读器就该拿全量，
 *   只是字段从 ChapterVO 缩成了 ChapterBriefVO，几百章也就几十 KB。
 */
const chapters = ref([])

/** 当前章的正文 */
const chapter = ref(null)
const loading = ref(false)
const failed = ref(false)

const catalogVisible = ref(false)

/**
 * 章评抽屉的开关。
 *
 * ★ 它和 `catalogVisible` 是**两个独立的抽屉**，不是一个变量存「当前开的是哪个」：
 *   两个抽屉互斥（打开一个就关另一个）是 el-drawer 自己的行为，
 *   用一个变量表达反而要自己维护「从目录切到评论」的中间态。
 */
const commentVisible = ref(false)

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
     * ★ 详情拿不到【不阻断阅读】：正文接口自己会校验，能读到就正常显示。
     *   只是顶栏没有书名。
     *   这里刻意不置 failed —— 为了一份详情把整章正文拦下来，主次颠倒。
     */
    novel.value = null
  }

  try {
    chapters.value = (await getNovelCatalog(novelId.value)) ?? []
  } catch {
    /*
     * ★ 目录同样【不阻断阅读】，只是「上一章 / 下一章」和目录抽屉会是空的。
     *   翻转按钮靠 chapters 判断有没有邻居，空数组就是两个按钮都置灰 ——
     *   读者仍能读正文，符合「目录挂了不该让人读不了书」。
     */
    chapters.value = []
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

  /*
   * ★★ 位置一律取自 `position` 快照，**不读 `novelId` / `chapterId`**。
   *   理由见 `position` 的声明处：这两个 computed 在离开阅读器时
   *   已经是 undefined 了，读它们等于把这次上报白白扔掉。
   *
   * ★ nid 为 null 只在「快照还没建立」时出现（immediate 之后实际不会），
   *   这时直接放弃 —— **宁可少报一次，也不要报一个 novelId: null 上去**
   *   换来一个 400。
   */
  const { novelId: nid, chapterId: here } = position.value
  if (nid == null) return

  const cid = forChapterId ?? here
  saveProgress(nid, cid, scroller.scrollTop)

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
      novelId: Number(nid),
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

/* ==================== 左右滑动翻章（P6 S15） ==================== */

/**
 * 触摸滑动的起点。`null` 表示这一轮触摸不算数（多指 / 起点没记上）。
 * ★ 存 `{x, y}` 两个数就够 —— 不需要 touchmove 里的实时跟踪：
 *   「翻不翻章」只看**起点到终点**的方向和距离，
 *   而 touchmove 是每帧都触发的，跟着它算只会让判断抖动。
 */
let touchStart = null

/** 横向滑多远才算翻章。低于这个数一律当成手指抖动 */
const SWIPE_MIN = 50

/**
 * ★★ 滑动翻章的三个判断，每一个都对应一种「手机上正常操作被误判」：
 *
 * ① **必须区分横竖**：`|dx| > |dy|`。手机上读小说最主要的动作就是上下滚，
 *    不判方向的话，每滚一次屏就翻一章 —— 而且**看起来像页面自己乱跳**，
 *    因为手指抬起来才生效，用户完全不知道是自己滚出来的。
 *
 * ② **还要有一个距离下限**（50px）：方向判断挡不住「点一下」——
 *    手指点链接/按钮时会有一两像素的位移，垂直方向恰好为 0（`|dx| > |dy|` 成立），
 *    于是每次点击都可能顺带翻一章。这个下限就是为点击设的。
 *
 * ③ **划词选中时不算**：手机上选一段文字是「长按再拖」，那串动作天然满足
 *    前两条（拖的是水平方向、距离也够）。不判的话，用户想复制一句话，
 *    结果文字被选中、页面同时翻到了下一章。
 *
 * ★ 多指触摸（捏合缩放）直接放弃：`touchend` 时 `changedTouches` 里
 *   可能只剩一根手指，起点却是两指的，算出来的位移没有意义。
 *
 * ★★ 翻章必须调 `gotoChapter`，**不能另写一套**：
 *   键盘 `←/→`（`handleKeydown`）、顶栏按钮、这一条路径要落在同一个函数上，
 *   否则「翻章」这件事就有了三份实现 —— 将来加个「翻章时上报进度」之类的改动，
 *   只会改到其中一两个。
 */
function onTouchStart(e) {
  if (e.touches.length > 1) {
    touchStart = null
    return
  }
  const t = e.touches[0]
  touchStart = { x: t.clientX, y: t.clientY }
}

function onTouchEnd(e) {
  const start = touchStart
  touchStart = null
  if (!start) return

  // ★ touchend 的坐标在 changedTouches 里，e.touches 这时已经是空的了
  const t = e.changedTouches[0]
  if (!t) return

  const dx = t.clientX - start.x
  const dy = t.clientY - start.y

  if (Math.abs(dx) < SWIPE_MIN) return
  if (Math.abs(dx) <= Math.abs(dy)) return
  if (window.getSelection()?.toString()) return

  /*
   * ★ 方向语义：手指【向左】划 = 往后翻（dx < 0 → 下一章）。
   *   这是「把当前页往左推走、下一页从右边进来」的直觉，
   *   和纸质书翻下一页的方向一致。
   */
  const target = dx < 0 ? nextChapter.value : prevChapter.value
  // ★ 没有邻居时【不阻止也不提示】：滑到最后一章再划一下什么都不发生，
  //   和顶栏那个按钮的置灰是同一个意思
  if (target) gotoChapter(target.id)
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
  /*
   * ★★ `newId` 为空 = **我们离开了阅读器**，不是「换章」。
   *   路由一走，`route.params.chapterId` 就变成 `undefined`，这个 watch
   *   照样会触发。不拦住的话，卸载过程中会对一个不存在的章节发一串
   *   注定失败的请求，还会把 `novel` / `chapters` 清空、`failed` 置 true ——
   *   虽然马上要卸载、用户看不见，但它会**抢在 onBeforeUnmount 之前
   *   把状态搅乱**，让收尾那次上报的时机变得不可预期。
   *   （离开时的收尾由 onBeforeUnmount 负责，那里用的是 position 快照。）
   */
  if (!newId) return

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
        <!--
          ★ 窄屏这两个会隐藏（`.nav-arrow`，见样式里那段 @media）：
            正文末尾的 `.chapter-nav` 已经有同样的一组「上一章/下一章」大按钮，
            顶栏再挤一份只会把「目录 / 本章说 / 设置」挤到屏幕外。
            ★ 是【隐藏】不是 v-if —— 手机上窗口旋转、桌面端拖窗口都会实时跨越断点，
              v-if 每次都要销毁重建按钮，而这里藏起来的代价是零。
        -->
        <el-button
          class="nav-arrow"
          link
          :disabled="!prevChapter"
          @click="gotoChapter(prevChapter?.id)"
        >
          上一章
        </el-button>
        <el-button
          class="nav-arrow"
          link
          :disabled="!nextChapter"
          @click="gotoChapter(nextChapter?.id)"
        >
          下一章
        </el-button>
        <el-button link @click="catalogVisible = true">目录</el-button>
        <!-- ★ 章评的入口。放在「目录」旁边：这两个都是「打开一个面板」，同一类东西 -->
        <el-button link @click="commentVisible = true">本章说</el-button>

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
    <!--
      ★ 左右滑动翻章绑在【滚动容器】上，不是根节点 `div.reader`：
        滑动是「在正文上划」的手势，绑在根上会把顶栏也圈进来
        （在工具条上划一下也会翻章，而那里没有「正文」这个语境）。
      ★ `touchstart` 用 `.passive`：它只记一个坐标，不阻止任何默认行为。
        写成非 passive 的话，浏览器为了等我们的处理结果，
        **每一次触摸开头都不能立刻开始滚动** —— 手机上表现为「滚动起步发涩」。
        而 `touchend` 不需要 passive（那时滚动早就结束了）。
    -->
    <main
      ref="scrollerRef"
      v-loading="loading"
      class="scroller"
      @scroll.passive="onScroll"
      @touchstart.passive="onTouchStart"
      @touchend="onTouchEnd"
    >
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

    <!--
      ★★ 章评抽屉（P6 加）。**必须理解为什么是抽屉、而不是放进正文下面** ——
         P3 定死的两条会被它同时打破：

      ① **进度百分比**。`currentPercent()` 的分母是
         `scroller.scrollHeight - scroller.clientHeight`（见那个函数的注释）。
         评论列表一旦渲染在 `<main>` **里面**，scrollHeight 立刻变大一大截，
         于是「刚打开这一章、还没滚」就已经显示读了 30%，
         滚到正文末尾也到不了 100%。
         ★★ 更隐蔽的是：它**不会报错**，只是「继续阅读」每次跳的位置
         都比上次靠后一点 —— 因为进度被一点点记大了。
      ② **正文变成了不是最后一屏**。`.chapter-nav` 的「下一章」是读完一章
         的落点，评论列表插在它后面的话，那个按钮就跑到屏幕外面去了。

      → 抽屉是 `position: fixed` + 遮罩，**不改变 `<main>` 的任何尺寸**，
        这两个问题一起消失。而且它和上面「目录」是同一个模式（`el-drawer`），
        读者不需要学两种交互。

      ★ `chapter-id` 传的是路由上的那一章 —— 翻章时这个 prop 变，
        `CommentSection` 内部 watch 到之后自己重查，本页不用管。
      ★ 抽屉内容在**第一次打开时**才挂载（el-drawer 的行为），
        所以不打开就不发请求 —— 每翻一章都拉一次评论是纯浪费。
    -->
    <el-drawer v-model="commentVisible" title="本章说" size="60%" direction="btt">
      <CommentSection :novel-id="novelId" :chapter-id="chapterId" />
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

/* ==================== 窄屏（P6 S15） ==================== */

/*
 * ★★ 断点 900px 与 `composables/useMediaQuery.js` 的 `NARROW` 是同一个数
 *   （那里解释了这个数字是怎么量出来的）。CSS 读不到那个常量，
 *   改的时候两边一起改。
 *
 * ★★ 这一段修的是一个**比它看起来严重得多**的问题。
 *   改之前，375px 下这一页横向溢出 134px —— 而溢出的后果不只是「有条横向滚动条」：
 *
 *   在手机的移动端模式下，`<meta name="viewport" content="width=device-width">`
 *   给出的**布局视口会跟着内容一起被撑宽**（Chrome 的 initial containing block
 *   按内容宽度算）。于是 `window.innerWidth` 变成 509 而不是 375，
 *   而 `position: fixed` 的元素是相对【布局视口】定位的 ——
 *   目录抽屉、设置气泡、确认框全部**整体偏到屏幕右边去**，
 *   看起来像「抽屉的定位写错了」。
 *
 *   ★ 这一点值得记下来：那几个浮层的 bug 是**症状不是病因**。
 *     单独去调抽屉的 `right`/`transform` 能「修好」它在 375 下的样子，
 *     但换个宽度又错，而且真正的问题（顶栏溢出）一直还在。
 */
@media (max-width: 900px) {
  /*
   * ★ 顶栏从 `padding: 0 20px` 收到 10px，并允许左边被压缩。
   *   `.left` 里是「← 返回」+ 书名，书名是 `max-width: 320px` 的死宽 ——
   *   375px 的屏幕上，光它加上右边那排按钮就已经放不下了。
   */
  .toolbar {
    padding: 0 10px;
    gap: 8px;
  }

  .left {
    /* ★ `min-width: 0` 是让 flex 子项【真的能被压到比内容窄】的唯一办法：
       flex 项的默认 `min-width` 是 `auto`（= 内容的宽度），
       不写这行，下面 `.book-title` 的省略号永远不会出现，只会一直往外顶。 */
    min-width: 0;
    flex: 1 1 auto;
  }

  /*
   * ★ 书名从「最多 320px」改成「能占多少占多少、不够就省略」。
   *   320px 是宽屏下「别把按钮挤走」的上限，窄屏没有这个余量可留。
   */
  .book-title {
    max-width: none;
    min-width: 0;
  }

  /* ★ 正文两侧留白收窄，把宽度还给文字（手机上每行能多放两个字） */
  .scroller {
    padding: 20px 12px 48px;
  }

  /*
   * ★★ 顶栏这两个「上一章 / 下一章」收掉 —— 正文末尾的 `.chapter-nav`
   *   已经有一组同样功能的按钮，而且那一组更大、更好点。
   *   留着它们会把「目录 / 本章说 / 设置」挤到屏幕外面去，
   *   而那两个是**只有这里才有**的入口：挤没了就真的没有别的路进去了。
   */
  .nav-arrow {
    display: none;
  }
}
</style>
