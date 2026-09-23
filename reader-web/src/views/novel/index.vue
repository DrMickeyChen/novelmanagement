<script setup>
/**
 * 作品详情页 —— 简介 + 目录 + 评分 + 书评区 + 「开始/继续阅读」。
 *
 * ★★ P6 起目录**不再跟着详情回来**，改成分页接口 `GET /novels/{id}/chapters`。
 *   理由：详情页是**匿名高频**入口，几百章的书每打开一次就拖回整本目录，
 *   而绝大多数访问只是「看一眼简介」。
 *   这是对 P3「一次性全量返回」的**有意反转**，见 `docs/P6-书架评论.md`。
 *
 * ★ 书评区是 [`CommentSection`](@/components/CommentSection.vue)，
 *   和阅读器里的章评区**同一个组件**（靠 `chapterId` 有没有值区分）。
 *   它自己拉数据、自己分页 —— 本页**不需要**为它维护任何状态。
 *
 * ★★ P6 加的「加入书架」按钮，有一个刻意的取舍：
 *   **本页不显示收藏数**。`NovelVO`（详情接口的父类）里根本没有
 *   `collectCount` —— 收藏数是书架域的东西，只在 `NovelBriefVO` 和
 *   `ShelfStatusVO` 上有。要显示就得从 `/reader/shelf/{id}` 拿，
 *   而那个接口**需要登录** → 同一个详情页，登录的人看到「87 收藏」、
 *   游客看不到这个数字。**同一页按登录态显示不同的事实**比不显示更糟：
 *   它会让「收藏数」看起来像是个只有会员才有的东西。
 *   （按钮的「在不在书架」是另一回事 —— 那个本来就是「我」的状态。）
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getNovelCatalog, getNovelChapters, getNovelDetail, reportNovelView } from '@/api/novel'
import { addToShelf, getShelfStatus, removeFromShelf } from '@/api/shelf'
import { formatAuthorName, formatDate, formatWordCount } from '@/utils/format'
import { loadProgress } from '@/utils/readerStorage'
import { useUserStore } from '@/stores/user'
import CommentSection from '@/components/CommentSection.vue'

const route = useRoute()
const userStore = useUserStore()

const novelId = computed(() => route.params.id)

const detail = ref(null)
const loading = ref(false)
/** 加载失败。★ 只有 true / false，不区分原因（见下面 load 的说明） */
const failed = ref(false)

/** 当前这一页的目录。★ 加载中给空数组，模板里的 v-for 就不用再判空 */
const chapters = ref([])
/** 已发布章节总数。★ 就是「共 N 章」的那个 N，由目录接口的 total 给 */
const chapterTotal = ref(0)
const page = ref(1)
const PAGE_SIZE = 20

/**
 * 本机记录的阅读进度（只有 novelId 对得上才有）。
 *
 * ★ 这是「继续阅读」按钮【唯一】正确的数据来源。
 *   ★★ 不能用 detail.value.lastChapterId：那个字段的语义是
 *   「最新章节」（由后端 syncNovelStat 在章节状态流转时维护），
 *   不是「读者读到哪一章」—— 拿它当续读目标，用户会被扔到最新一章，
 *   也就是【剧透】。而且它可能为 null。
 *   NovelDetailVO 里甚至根本没暴露这个字段，就是为了防这个误用。
 */
const localProgress = ref(null)

/**
 * 本机进度指向的那一章，**现在还在不在目录里**。查不到就是 {@code null}。
 *
 * ★★ 为什么为了这一个判断还要发一次请求（而且只在本机有进度时才发）：
 *   目录分页之后，「本机记录的这一章还在不在」**没法从当前这一页回答** ——
 *   它可能在别的页上。而 P3 就定死了不能省这个判断：
 *   章节被作者删掉 / 被驳回下架之后，本机记录指向一个不存在的 id，
 *   直接跳过去读者看到的是「章节不存在」。
 *   `getNovelCatalog` 每条只有 4 个字段（几十字节），比整份 `ChapterVO` 便宜得多，
 *   而且**游客和第一次来的读者一次都不会调到**。
 *
 * ★ 拿它顺便解决了另一个问题：提示语里的「第 N 章」需要 chapterNo，
 *   而本机进度里只有 chapterId（`readerStorage` 的存法，P3 定的，不动）。
 */
const progressChapter = ref(null)

/**
 * 续读目标章节 id。
 *
 * ★ 三级回退，顺序不能换：
 *   ① 本机有记录、且那一章还在目录里 → 用它
 *   ② 没有记录（或记录已失效）→ `detail.firstChapterId`（后端给的第一章）
 *   ③ 一章都没发布 → null，按钮置灰
 *
 * ★ ②用的是 `firstChapterId` 而不是「目录第一页的第一个」：分页之后
 *   第一页不一定从第一章开始（这个判断在服务端更可靠）。
 */
const continueChapterId = computed(
  () => progressChapter.value?.id ?? detail.value?.firstChapterId ?? null,
)

/** 有本机记录且能对上时，按钮文案才是「继续阅读」 */
const isResuming = computed(() => !!progressChapter.value)

/** 阅读地址。★ 目标为 null 时返回 null，按钮据此置灰 */
const readPath = computed(() =>
  continueChapterId.value ? `/read/${novelId.value}/${continueChapterId.value}` : null,
)

/** 目录里标记「读到这里」的那一章 */
const readToChapterId = computed(() => (isResuming.value ? localProgress.value.chapterId : null))

/**
 * 评分。
 *
 * ★★ **判 `> 0`，不要判「有没有值」**：`novel.score` 的列是
 *   `NOT NULL DEFAULT 0.0`，语义是「0 = 还没有人评过分」
 *   （见后端 `NovelVO#score` 的注释）。显示成「0.0 分」的话，
 *   读者会以为大家都打了 0 分 —— 那是完全不同的一件事。
 *
 * ★ `toFixed(1)`：后端算的是 `ROUND(AVG(score), 1)`，但 JSON 里
 *   `4.0` 会变成数字 `4`，直接显示就是「4 分」。和旁边「4.3 分」
 *   排在一起宽度不齐，也不像一个评分。
 */
const scoreValue = computed(() => Number(detail.value?.score) || 0)

/* ==================== 加入书架 ==================== */

/**
 * 这本书在不在我的书架。★★ M5 起是**三态**，`null` = 「不知道」：
 *
 *   true  —— 在书架里
 *   false —— 不在书架里（服务端明确这么说的，或者根本没登录）
 *   null  —— **问不到**（接口失败了）
 *
 * ★★ 为什么必须分出第三态：M5 之前的写法是「查不到就当不在书架」
 *   （`catch` 里什么都不做，`onShelf` 停在 `false`）。于是停掉
 *   interaction 之后，一本**明明在书架上**的书，按钮显示的是
 *   【「加入书架」】—— 用户会以为自己的收藏丢了，然后去点它，
 *   再失败一次。这比不显示更糟：它不只是缺信息，它是**错的**。
 *   （这和 M5 后端那条降级判据是同一条规矩的前端版本：
 *   缺失必须**看得见**，不能拿一个「看起来正常」的值顶上。）
 *
 * ★★ 不变量：**`null` 只有一个来源 —— 一次失败的查询。**
 *   初始值刻意留 `false`（因为那时页面整体在 `v-loading` 下面，
 *   按钮根本不可见），查询进行中由 `shelfLoading` 转圈挡住，
 *   只有 `catch` 会把状态置成 `null`。所以「还没查」和
 *   「查不到」在界面上**不可能混淆**。
 *
 * ★ 状态**只由服务端给**，前端不猜：
 *   点击之后用的是响应里的 `onShelf`，而不是把本地的翻转一下。
 *   两个接口都是幂等的 —— 对一本已在书架里的书再点「加入」，
 *   返回的仍然是 `onShelf: true`。自己翻转的话，这种情况下
 *   按钮会显示成「未加入」而书其实在书架里。
 *
 * ★ 匿名时是 false（不是 null）：没登录就没有「我的书架」这回事，
 *   这是一个**确定**的答案。
 *   （按钮仍然显示，点了提示去登录 —— 见 `toggleShelf`。）
 */
const onShelf = ref(false)
const shelfBusy = ref(false)

/** 状态查询是否在路上。★ 只用来转圈 —— 它和 `shelfBusy`（点击动作）是两件事 */
const shelfLoading = ref(false)

/**
 * ★ 状态**问不到**。为 true 时按钮禁用、文案显示 `—`、旁边给一条重试。
 *
 * ★ 必须带 `isLogin`：未登录时 `onShelf` 是 false，但万一将来
 *   有人改了上面那行初始值，未登录的游客也不该看到一个禁用的按钮 ——
 *   他要的是「点了提示去登录」，不是「这块坏了」。
 */
const shelfUnknown = computed(() => userStore.isLogin && onShelf.value === null)

/**
 * 按钮文案。
 *
 * ★ 三态各给一句不同的话，**没有兜底分支**：`null` 时写 `—` 而不是
 *   回落成「加入书架」—— 那个回落正是 M5 要修的东西。
 */
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
  shelfLoading.value = true
  try {
    const res = await getShelfStatus(novelId.value)
    /*
     * ★ M5：`?? false` 改成 `?? null`。
     *   `onShelf` 是包装类型，全局 `non_null` 会让整个 key 消失 ——
     *   而「key 消失」和「请求失败」是**同一件事**：我们不知道。
     *   写成 `?? false` 就等于把「不知道」说成「不在书架」，
     *   和下面 `catch` 里那个 bug 一模一样，只是换了条路径进来。
     *   （后端目前保证它不会消失，但那是一条**承诺**，不是机制 ——
     *    前端按「可能消失」写，成本是一个字。）
     */
    onShelf.value = res?.onShelf ?? null
  } catch {
    /*
     * ★★ M5：这里原来是空的（注释写着「查不到就当不在书架」），
     *   而那正是这句谎话的来源。现在显式置 `null` ——
     *   让「查不到」在界面上有自己的样子。
     *   拦截器已经弹过提示了，这里不重复弹。
     */
    onShelf.value = null
  } finally {
    shelfLoading.value = false
  }
}

/**
 * 加入 / 移出。
 *
 * ★ 按当前状态决定调哪个接口，而不是一个 toggle 接口 ——
 *   后端刻意没做 toggle（它既不幂等也不可重试，理由见 `ReaderShelfController`）。
 *
 * ★★ 这一处**不弹确认框**，而书架页的「移除」弹。
 *   看着不一致，但是有意的，两者的「误点成本」不一样：
 *     · 书架页的「移除」：用户是来整理书架的，点错那一行就消失了，
 *       而且他很可能不记得刚删的是哪本 → 弹一句。
 *     · 这里的「已在书架」：按钮本身写着当前状态，点错了**再点一次就回来**，
 *       且成功提示就写着「已移出书架」，用户立刻看得到 → 不弹。
 *   给一个一眼能看懂、一下能撤销的按钮加确认框，只会让「取消收藏」
 *   这件事变得很烦。
 *
 * ★ M5：`onShelf === null`（问不到）时**故意不在这里加分支** ——
 *   那种情况下按钮是 `:disabled` 的，这个函数根本进不来。
 *   写一个永远进不去的 `if (onShelf.value === null) return` 会让人
 *   以为「这条路径被处理过了」，而它其实只会在将来某次改动
 *   把禁用去掉之后才第一次被执行 —— 到那时没人记得它。
 *   （和网关里那条刻意没写的 `headers.remove` 是同一种取舍。）
 */
async function toggleShelf() {
  if (!userStore.isLogin) {
    /*
     * ★ 未登录时**只提示，不跳转**：跳转永远由用户点登录页的入口触发。
     *   正在看简介被弹去登录页，回来时页面位置全没了。
     *   （`CommentItem.vue` 的 `ensureLogin` 是这条规矩的第一处，
     *   全站保持一致。）
     */
    ElMessage.warning('登录后可以加入书架')
    return
  }
  if (shelfBusy.value) return

  shelfBusy.value = true
  try {
    const wasOnShelf = onShelf.value
    const res = wasOnShelf
      ? await removeFromShelf(novelId.value)
      : await addToShelf(novelId.value)
    /*
     * ★ M5：兜底从「把本地值翻转一下」改成「这次动作的目标状态」。
     *   正常路径上两者结果相同（服务端一定回 `onShelf`），但
     *   翻转**是在猜**——而上面那段类注释刚说过「前端不猜」。
     *   既然要写兜底就写个语义对的：add 之后是「在架上」，
     *   remove 之后是「不在架上」，与本地原来是什么无关。
     */
    onShelf.value = res?.onShelf ?? !wasOnShelf
    // ★ 提示语跟着**动作**走，不跟着状态走 —— 状态可能是 null，
    //   而「已移出书架」这句话在 null 时会说反
    ElMessage.success(wasOnShelf ? '已移出书架' : '已加入书架')
  } catch {
    /* 失败原因由 request.js 的拦截器提示（含 2001「作品不存在或已下架」），
       这里只需要别让它变成 unhandledrejection */
  } finally {
    shelfBusy.value = false
  }
}

/**
 * 退出登录时把按钮状态清掉。
 *
 * ★ 必须清：阅读端的退出**不跳转**（首页是公开页，停在原地正好让用户
 *   看到导航栏变了，见 `BasicLayout` 的注释）。所以用户完全可能在
 *   详情页上点「退出」，这时按钮还写着「已在书架」——
 *   而它已经不属于任何人了，再点会走未登录分支提示「登录后可以加入书架」，
 *   看着像 bug。清成 false，按钮回到「加入书架」，语义立刻正确。
 */
watch(
  () => userStore.isLogin,
  (isLogin) => {
    if (isLogin) {
      loadShelfStatus()
    } else {
      onShelf.value = false
    }
  },
)

onMounted(() => {
  load()
  /*
   * ★ 埋点是「打开详情页就记一次」，和读没读正文无关。
   *   它是 silent 的（见 api/novel.js）：不弹提示、401 不跳登录页。
   *
   * ★★ 但 silent 【不等于】不会 reject —— 拦截器照样把 Promise 拒出去，
   *   只是拒得悄无声息。所以这里必须自己兜住：
   *   `void` 一个裸 Promise 会变成 unhandledrejection，控制台一片红，
   *   接了 Sentry 之类的上报工具就会被当成真错误报上去，
   *   而它其实只是「阅读量少记了一次」这种可以完全忽略的事。
   *   （阅读器里上报进度那处也是这么兜的，两处保持一致。）
   */
  reportNovelView(novelId.value).catch(() => {})
})

async function load() {
  loading.value = true
  failed.value = false
  try {
    /*
     * ★ 详情和目录第一页【并发】发，不串行。
     *   串行的话要等详情回来才发目录，首屏就多一个 RTT。
     *
     * ★ 任何一路失败都算整页失败（而不是「详情有、目录空」）：
     *   两个请求的错误码本来就一样（不存在/未上架都是 2001），
     *   分开处理只会得到一个「有书名、目录永远空着」的残页 ——
     *   那看起来像「这本书没有章节」，是错的。
     */
    const [d] = await Promise.all([getNovelDetail(novelId.value), loadChapterPage(1)])
    detail.value = d
    localProgress.value = loadProgress(novelId.value)
    await resolveProgressChapter()
    /*
     * ★ 书架状态**放在这里而不是 onMounted**：作品不存在时不该再去问
     *   「它在不在我的书架」—— 那是一次注定没意义的往返。
     * ★ 不 await：它是按钮上一个角标级别的信息，不该拖住整页的
     *   `loading`（详情还没出来就已经在转圈了，再等它没有意义）。
     *   它自己兜住了异常，不会变成 unhandledrejection。
     */
    loadShelfStatus()
  } catch {
    /*
     * ★ 这里【不区分失败原因】，因为拿不到区分所需的依据：
     *   request.js 的响应拦截器把失败统一成 `new Error(后端给的 message)`，
     *   业务码（2001 作品不存在）和网络错误在调用方看来长得一模一样。
     *   所以页面只显示一句中性的「没找到这本书」+ 回首页，
     *   具体原因由拦截器弹出的那条提示告诉用户（比如「连接不上后端服务」）。
     *
     * ★ 也可以改 request.js 让 reject 出来的 Error 带上 code，
     *   但那个文件三端逐字节相同，动它要三个一起改、还要重跑 P2 验收 ——
     *   为一个错误页的文案不值得。记在遗留里。
     */
    failed.value = true
    detail.value = null
    chapters.value = []
    chapterTotal.value = 0
  } finally {
    loading.value = false
  }
}

/**
 * 拉某一页目录。
 *
 * ★ 翻页只换目录，**不重新拉详情** —— 详情和页码无关，
 *   重拉会让书名/简介闪一下，而且白费一个请求。
 */
async function loadChapterPage(target) {
  const res = await getNovelChapters(novelId.value, { page: target, size: PAGE_SIZE })
  chapters.value = res.records ?? []
  chapterTotal.value = res.total ?? 0
  // ★ 用服务端回的 current，不是 target：页码越界时服务端会纠正，
  //   拿 target 当当前页会让分页条停在一个没有内容的页码上
  page.value = res.current ?? target
}

/** 翻页。★ 失败由 request.js 的拦截器提示，这里只需要别让它变成 unhandledrejection */
function changePage(target) {
  loadChapterPage(target).catch(() => {})
}

/**
 * 查本机进度指向的那一章是否还在目录里。**只有本机有进度时才会发请求。**
 *
 * ★ 请求失败就当作「对不上」（progressChapter 保持 null）：
 *   这时按钮回退成「开始阅读」跳到第一章 —— 比跳到一个读不了的章节好。
 */
async function resolveProgressChapter() {
  progressChapter.value = null
  if (!localProgress.value) return
  try {
    const catalog = await getNovelCatalog(novelId.value)
    progressChapter.value = catalog.find((c) => c.id === localProgress.value.chapterId) ?? null
  } catch {
    /* 见上 */
  }
}
</script>

<template>
  <div v-loading="loading" class="novel-detail">
    <el-empty v-if="failed" description="没有找到这本书，可能已下架">
      <router-link to="/">
        <el-button type="primary">回到首页</el-button>
      </router-link>
    </el-empty>

    <template v-else-if="detail">
      <!-- ============ 头部：书名 / 元信息 / 操作按钮 ============ -->
      <el-card shadow="never" class="head">
        <div class="title-row">
          <h1 class="title">{{ detail.title }}</h1>
          <el-tag :type="detail.isFinish === 1 ? 'success' : 'warning'" effect="plain" size="small">
            {{ detail.isFinish === 1 ? '已完结' : '连载中' }}
          </el-tag>
        </div>

        <div class="meta">
          <!-- ★ M5：`—` 而不是「佚名」—— 判据见 utils/format.js 的 formatAuthorName -->
          <span class="author">{{ formatAuthorName(detail.authorName) }}</span>
          <el-divider direction="vertical" />
          <router-link :to="`/category/${detail.categoryId}`" class="link">
            {{ detail.categoryName || '未分类' }}
          </router-link>
          <el-divider direction="vertical" />
          <span>{{ detail.chapterCount || 0 }} 章</span>
          <el-divider direction="vertical" />
          <span>{{ formatWordCount(detail.wordCount) }}</span>
          <el-divider direction="vertical" />
          <span>{{ detail.viewCount || 0 }} 阅读</span>
          <template v-if="detail.lastUpdateTime">
            <el-divider direction="vertical" />
            <span>更新于 {{ formatDate(detail.lastUpdateTime) }}</span>
          </template>
        </div>

        <!--
          ★ 标签：后端给的是逗号分隔的一个字符串（"热血,升级流"），
            不是数组，所以这里 split 一下。加 filter(Boolean) 是因为
            结尾多一个逗号会 split 出一个空串，渲染成一个空白的小标签。
        -->
        <div v-if="detail.tags" class="tags">
          <el-tag v-for="tag in detail.tags.split(',').filter(Boolean)" :key="tag" size="small" type="info" effect="plain">
            {{ tag }}
          </el-tag>
        </div>

        <!--
          ★★ 评分（P6 加）。放在元信息【下面单独一行】而不是塞进那行里：
             el-rate 是块级高度的控件，混在一行小字里会把那行撑歪。
          ★ 星星的刻度和颜色都用 Element 默认的 —— 这里没有自定义主题，
             和 el-tag / el-button 一样交给组件库。
        -->
        <div class="score">
          <template v-if="scoreValue > 0">
            <!-- allow-half：均分算出来是 4.3 这种小数，不给半星就只能四舍五入到整数 -->
            <el-rate :model-value="scoreValue" disabled allow-half />
            <span class="score-value">{{ scoreValue.toFixed(1) }}</span>
            <span class="score-unit">分</span>
          </template>
          <span v-else class="score-empty">暂无评分</span>
        </div>

        <div class="actions">
          <router-link v-if="readPath" :to="readPath">
            <el-button type="primary" size="large">
              {{ isResuming ? '继续阅读' : '开始阅读' }}
            </el-button>
          </router-link>
          <el-button v-else size="large" disabled>暂无章节</el-button>

          <!--
            ★★ 加入书架（P6 加）。**未登录也显示**，点了提示去登录 ——
               不藏起来是因为「有没有书架」是读者端的一个基本功能，
               游客看不到就永远不知道它存在。
               （`CommentItem.vue` 定的规矩：需要登录的**区域**顶部放引导条，
               区域**内的单个操作**只在被点击时提示一句。这里是后者。）

            ★ 按钮文案跟着 `onShelf` 走，而不是「加入书架 / 取消收藏」两个
               意思相反的动词 —— 用户扫一眼就知道**现在是什么状态**，
               不需要靠推断自己刚才点没点过。

            ★★ M5：状态**问不到**时按钮禁用、文案 `—`，旁边给一条重试。
               为什么不是「禁用就完了」：禁用只回答了「不能点」，
               没回答「怎么了」，也没给用户任何出路 ——
               他会以为这个功能坏了，而不是「后端暂时不通」。
               （`shelfLoading` 只负责转圈：查询在路上的那一小段
               按钮文案还是 `false` 对应的「加入书架」，
               但上面盖着 loading，看不到 —— 所以它不构成一句谎话。）
          -->
          <el-button
            size="large"
            :loading="shelfBusy || shelfLoading"
            :disabled="shelfUnknown"
            @click="toggleShelf"
          >
            {{ shelfText }}
          </el-button>
          <el-button v-if="shelfUnknown" link type="primary" @click="loadShelfStatus">
            重试
          </el-button>

          <span v-if="isResuming" class="resume-hint">
            上次读到 第 {{ progressChapter.chapterNo }} 章
          </span>
        </div>
      </el-card>

      <!-- ============ 简介 ============ -->
      <el-card shadow="never" class="section">
        <template #header><span class="section-title">简介</span></template>
        <!--
          ★ 用 {{ }} 而不是 v-html。简介是作者填的自由文本，
            v-html 会把它当 HTML 解析 —— 轻则排版被标签冲乱，
            重则是一个 XSS 入口（作者的输入直接进所有读者的浏览器）。
            换行靠 CSS 的 white-space: pre-line，够用且安全。
        -->
        <p class="intro">{{ detail.intro || '作者还没有填写简介。' }}</p>
      </el-card>

      <!-- ============ 目录 ============ -->
      <el-card shadow="never" class="section">
        <template #header>
          <span class="section-title">目录</span>
          <span class="section-sub">共 {{ chapterTotal }} 章</span>
        </template>

        <div class="chapters">
          <router-link
            v-for="chapter in chapters"
            :key="chapter.id"
            :to="`/read/${novelId}/${chapter.id}`"
            class="chapter"
            :class="{ current: chapter.id === readToChapterId }"
          >
            <span class="chapter-no">第 {{ chapter.chapterNo }} 章</span>
            <span class="chapter-title">{{ chapter.title }}</span>
            <span v-if="chapter.id === readToChapterId" class="badge">读到这里</span>
          </router-link>
        </div>

        <el-empty v-if="!chapters.length" description="还没有已发布的章节" :image-size="60" />

        <!--
          ★ 只有一页时不显示分页条（`chapterTotal > PAGE_SIZE`）——
            一个「1」孤零零地待在目录下面，比没有更难看。
        -->
        <el-pagination
          v-if="chapterTotal > PAGE_SIZE"
          class="chapter-pager"
          layout="prev, pager, next"
          background
          :current-page="page"
          :page-size="PAGE_SIZE"
          :total="chapterTotal"
          @current-change="changePage"
        />
      </el-card>

      <!--
        ★★ 书评区（P6 加）。**不传 `chapterId`** 就是这个组件判定
           「评论对象是整本书」的唯一依据 —— 章评区（阅读器里）才传。
           见 `components/CommentSection.vue` 的文件头。

        ★ 它自己发请求、自己管分页，所以【不跟着 `loading`】：
           详情还在转圈时它也已经在拉书评了，两件事互不等待。
           搁在 `v-else-if="detail"` 里面是为了让「书不存在」时
           不会对着一个不存在的 id 拉评论。
      -->
      <CommentSection :novel-id="novelId" />
    </template>
  </div>
</template>

<style scoped>
.novel-detail {
  padding: 20px 0;
}

.head {
  margin-bottom: 16px;
}

.title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.title {
  margin: 0;
  font-size: 24px;
  line-height: 1.3;
}

.meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  font-size: 13px;
  color: #909399;
}

.author {
  color: #606266;
}

.link {
  color: #409eff;
}

.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 12px;
}

/*
 * ★ 评分单独一行。`min-height` 是为了让「暂无评分」和「五颗星」
 *   这两种状态占一样的高度 —— 不然有评分的书和没评分的书，
 *   下面「开始阅读」按钮的纵向位置会差十几像素，列表里翻着看很明显。
 */
.score {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 12px;
  min-height: 24px;
}

.score-value {
  font-size: 18px;
  font-weight: 600;
  color: #ff9900;
}

.score-unit {
  font-size: 13px;
  color: #909399;
}

.score-empty {
  font-size: 13px;
  color: #c0c4cc;
}

.actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 18px;
}

.resume-hint {
  font-size: 13px;
  color: #909399;
}

.section {
  margin-bottom: 16px;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
}

.section-sub {
  margin-left: 8px;
  font-size: 13px;
  color: #909399;
}

.intro {
  margin: 0;
  line-height: 1.9;
  color: #303133;
  /* ★ 保留作者写的换行，同时不解析任何 HTML */
  white-space: pre-line;
}

.chapters {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 8px;
}

.chapter {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 4px;
  font-size: 14px;
  color: #606266;
}

.chapter:hover {
  background: #f5f7fa;
  color: #409eff;
}

.chapter-pager {
  margin-top: 14px;
  justify-content: center;
}

/* 「读到这里」的那一章：左侧一条竖线，扫一眼就能找到 */
.chapter.current {
  background: #ecf5ff;
  color: #409eff;
  font-weight: 600;
}

.chapter-no {
  flex: none;
  font-size: 13px;
  color: #909399;
}

.chapter.current .chapter-no {
  color: #409eff;
}

.chapter-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.badge {
  flex: none;
  margin-left: auto;
  font-size: 12px;
  color: #67c23a;
}
</style>
