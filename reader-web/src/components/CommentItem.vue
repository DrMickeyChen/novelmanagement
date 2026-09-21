<script setup>
/**
 * 一条评论 —— 一级评论和二级回复**共用一个组件**（靠 `isReply` 区分）。
 *
 * ★ 为什么两级共用一个组件而不是写两个：它们的 90% 是一样的
 *   （昵称/时间/正文/点赞/删除），差的是「回复按钮」「展开子回复」
 *   「缩进」。写两个组件的话，改一次点赞样式要改两处，而漏掉的那处
 *   不报错、只是长得不一样。
 *
 * ------------------------------------------------------------
 * ★★ 三个必须知道的后端事实（决定了这个组件为什么这么写）
 * ------------------------------------------------------------
 * ① **回复【只能两级】**（`4006`）。所以「回复」按钮**只长在一级评论上**，
 *    二级回复上没有 —— 挂上去的话那个按钮点了必然失败。
 *    但**点赞两级都有**。
 * ② **新发的评论/回复是「待审核」**，读者端列表只返回 `status = 1`，
 *    所以**发完不会出现在列表里**。提示语必须说清这一点，
 *    否则用户以为丢了、再发一条，第二条撞上 10 秒频控（`4004`）。
 * ③ **删除只删自己的**（后端 `4002`）。所以删除按钮的渲染条件是
 *    `mine === true`，而 `mine` 是**后端算好给的**（前端算不出来：
 *    `userId` 不给读者端，昵称又会重名）。★ 匿名时 `mine` 是 null
 *    （JSON 里 key 消失），所以要写 `?? false`，不能写 `=== null`。
 */
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteComment, getReplies, likeComment, replyComment, unlikeComment } from '@/api/comment'
import { formatDateTime } from '@/utils/format'
import { useUserStore } from '@/stores/user'

const props = defineProps({
  /** 一个 CommentVO（读者端字段见 `api/comment.js` 的 `getNovelComments`） */
  comment: {
    type: Object,
    required: true,
  },
  /** ★ 展开子回复的接口路径里有它（后端不用它查数据，但路径要求） */
  novelId: {
    type: [Number, String],
    required: true,
  },
  /** 是不是二级回复。★ 决定「缩进 / 隐藏回复按钮 / 不展开子回复」 */
  isReply: {
    type: Boolean,
    default: false,
  },
})

/** 删掉之后让父组件刷新列表（父组件那一页的 total 也变了） */
const emit = defineEmits(['deleted'])

/**
 * ★ 登录态**必须从 store 读**，不能看 `mine` / `liked` 是不是 null ——
 *   那两个字段说的是「这条评论和你有没有关系」，
 *   而「你有没有登录」是另一个问题：一个已登录用户看别人的评论时，
 *   `mine` 也是 `false`（不是 null），拿它反推登录态会把
 *   **已登录用户当成游客**，于是他的点赞/回复全被拦下来。
 */
const userStore = useUserStore()

/** 和 `CommentCreateDTO` 的 `@Size(max = 500)` 对齐。★ 靠它拦住长文，别等后端 400 */
const MAX_LENGTH = 500

/** 每次「加载更多」取多少条回复。★ 20 是够一屏的数，翻到底再点一次 */
const REPLY_PAGE_SIZE = 20

/* ==================== 显示 ==================== */

/**
 * 昵称。★ `nickname` 为空时**整个 key 消失**（后端 json 配了 non_null），
 * 所以用 `||`，**不能写 `=== null`** —— 那个判断永远不成立。
 * 空的原因是评论人已注销（`ContentUserLookup` 查不到）。
 */
const nickname = computed(() => props.comment.nickname || '已注销用户')

/**
 * 头像占位：昵称首字。
 *
 * ★ 评论接口**不返回评论人的头像**（`CommentVO` 里没有这个字段）——
 *   要显示真头像就得给每条评论多查一次用户表，或者让
 *   `ContentUserLookup` 连头像一起带出来。P6 不做，用首字占位。
 *   ★ 这是**有意的取舍**，不是漏了：评论区里一排彩色首字
 *   反而是很多站点的做法，等真需要真头像时改 `ContentUserLookup`
 *   一处即可（它已经在批量查昵称了）。
 */
const avatarText = computed(() => nickname.value.slice(0, 1))

/** 是不是当前用户自己写的。★ 匿名时 mine 是 null → 不是（他也不登录，删不了） */
const isMine = computed(() => props.comment.mine ?? false)

/** 已赞态。★ 同理，匿名时 liked 是 null */
const liked = computed(() => props.comment.liked ?? false)

/** 时间。★ 精确到分，理由见 `utils/format.js#formatDateTime` */
const timeText = computed(() => formatDateTime(props.comment.createTime))

/* ==================== 点赞 / 取消 ==================== */

const liking = ref(false)

/**
 * 点赞 / 取消点赞。
 *
 * ★★ **两个接口的路径只差一个 `/like` 后缀**：
 *   `POST /comments/{id}/like` 是点赞，`DELETE /comments/{id}` 是**删评论**。
 *   少写一个后缀就会**真的把评论删掉**，而且一样返回成功。
 *   所以这里刻意写成 `if (liked) unlikeComment(id) else likeComment(id)`，
 *   两个函数名都来自 `api/comment.js`，**不在组件里拼路径**。
 *
 * ★ 用服务端返回的**新总数**更新界面，不是本地 ±1：
 *   连点、多标签页、换设备时「我到底赞没赞过」是不确定的，
 *   本地 ±1 会和服务端越差越远。
 *
 * ★ 成功后**直接改 `comment.liked` / `comment.likeCount`**：
 *   这个对象就是父组件列表里的那一个（同一个引用），emit 回去
 *   父组件还得 `find` + patch 一遍，多一层还要处理「找不到」。
 *   ★ 只在**请求成功后**改 —— 先改再发请求（乐观更新）的话，
 *   请求失败时界面会停在一个假状态上，而拦截器已经弹过错误提示了，
 *   用户看到的是「提示失败了但按钮还亮着」。
 */
async function toggleLike() {
  if (!ensureLogin('登录后可以点赞')) return
  if (liking.value) return
  liking.value = true
  try {
    const count = liked.value
      ? await unlikeComment(props.comment.id)
      : await likeComment(props.comment.id)
    props.comment.liked = !liked.value
    props.comment.likeCount = count
  } catch {
    /* 失败原因由 request.js 的拦截器提示，这里只需要别变成 unhandledrejection */
  } finally {
    liking.value = false
  }
}

/* ==================== 展开子回复 ==================== */

/**
 * 展开后拿到的**完整**回复列表；`null` = 还没展开过。
 *
 * ★★ 未展开时用列表接口内联带回来的 `comment.replies`
 *   （**最多 3 条**，见后端 `INLINE_REPLY_LIMIT`），
 *   展开时**重新请求** `GET /novels/{id}/comments/{cid}/replies`。
 *   ★ 不能在已经分页的接口上假装全都拿回来了 —— 那会让列表接口
 *   按照回复数膨胀（一条热门评论挂 200 条回复，列表页就拖 200 行）。
 */
const expanded = ref(null)
const replyTotal = ref(0)
const replyPage = ref(0)
const loadingReplies = ref(false)

/** 现在要渲染的回复：展开过就用服务端给的完整列表，否则用内联的那几条 */
const shownReplies = computed(() => expanded.value ?? props.comment.replies ?? [])

/**
 * 还有多少条没显示。
 *
 * ★★ **不能用 `replies.length` 当总数**：它是截断过的（最多 3 条），
 *   拿它算的话「展开」按钮**永远不会出现**（3 - 3 = 0）。
 *   总数要么来自列表接口的 `replyCount`，要么来自展开接口的 `total`。
 */
const replyRemaining = computed(() =>
  Math.max(0, (expanded.value ? replyTotal.value : props.comment.replyCount ?? 0) - shownReplies.value.length),
)

/** 二级回复本身没有子回复（两级封顶），所以这个按钮只在**一级**上出现 */
const canExpand = computed(() => !props.isReply && replyRemaining.value > 0)

/** 展开按钮的文案。★ 「展开全部」和「加载更多」是同一个按钮的两种状态 */
const expandText = computed(() =>
  expanded.value ? `加载更多回复（还有 ${replyRemaining.value} 条）` : `展开全部 ${props.comment.replyCount} 条回复`,
)

/**
 * 取一页回复。
 *
 * ★ `more = true` 时**追加**而不是替换 —— 否则点「加载更多」会把
 *   已经看到的那 20 条冲掉，看起来像「点了一下反而变少了」。
 */
async function loadReplies(more) {
  if (loadingReplies.value) return
  loadingReplies.value = true
  try {
    const next = more ? replyPage.value + 1 : 1
    const res = await getReplies(props.novelId, props.comment.id, { page: next, size: REPLY_PAGE_SIZE })
    const rows = res?.records ?? []
    expanded.value = more ? [...(expanded.value ?? []), ...rows] : rows
    replyTotal.value = Number(res?.total) || rows.length
    replyPage.value = next
  } catch {
    /* 见 toggleLike */
  } finally {
    loadingReplies.value = false
  }
}

/* ==================== 回复 ==================== */

const replyBoxOpen = ref(false)
const replyText = ref('')
const submittingReply = ref(false)

function openReplyBox() {
  if (!ensureLogin('登录后可以回复')) return
  replyBoxOpen.value = true
}

async function submitReply() {
  const content = replyText.value.trim()
  if (!content) {
    ElMessage.warning('回复内容不能为空')
    return
  }
  submittingReply.value = true
  try {
    await replyComment(props.comment.id, { content })
    /*
     * ★★ 说「已提交」而不是「回复成功」：回复和评论一样是**待审核**的，
     *   不会立刻出现在下面。只说成功的话用户会刷新、找不到、再发一条，
     *   而第二条会撞上 10 秒频控（4004）—— 于是他看到的是「回复失败」。
     */
    ElMessage.success('回复已提交，通过审核后显示')
    replyText.value = ''
    replyBoxOpen.value = false
  } catch {
    /* 见 toggleLike */
  } finally {
    submittingReply.value = false
  }
}

/* ==================== 删除 ==================== */

async function handleDelete() {
  /*
   * ★ 确认框的三个约定（`author-web` 里已经踩过一遍，这里是第二处消费者）：
   *   ① `ElMessageBox.confirm` 是**命令式** API，读不到 Element 的语言包 ——
   *      按钮文字必须**手写「确定」/「取消」**，不写就是英文的 OK/Cancel
   *   ② `confirm()` 会 **reject**（不是返回 false），所以必须 try/catch，
   *      点「取消」走的是 catch 分支 —— 不接住就是一个未处理异常
   *   ③ 它还需要 `element-plus/es/components/message-box/style/css`
   *      这一行样式，`unplugin-vue-components` 扫不到（它只在 JS 里被调用）
   *      → 不引就是个**没有遮罩、按钮错位的裸框**。见 `main.js`。
   */
  try {
    await ElMessageBox.confirm('删除后不可恢复，确定删除这条评论吗？', '删除评论', {
      type: 'warning',
      confirmButtonText: '确定',
      cancelButtonText: '取消',
    })
  } catch {
    return // 用户点了取消
  }

  try {
    await deleteComment(props.comment.id)
    ElMessage.success('已删除')
    // ★ 让父组件重查这一页：删一级评论会**连带删掉它的全部二级回复**，
    //   而哪些是它的子回复只有服务端知道（前端手里的内联列表是截断过的）
    emit('deleted', props.comment.id)
  } catch {
    /* 见 toggleLike */
  }
}

/* ==================== 未登录 ==================== */

/**
 * 需要登录的操作的统一拦截。**返回 true 表示可以继续。**
 *
 * ★ 这里【不跳转】，只提示一句：跳转永远由用户点 `LoginPrompt` 的按钮触发。
 *   读到一半写回复被弹去登录页，回来时输入框已经没了。
 *   ★ 三处的说法要一致（见 `LoginPrompt.vue` 的规矩 ③）：
 *     每个需要登录的**区域**顶部有引导条（带按钮），
 *     区域**内的单个操作**只在被点击时提示一句。
 */
function ensureLogin(tip) {
  if (userStore.isLogin) return true
  ElMessage.warning(tip)
  return false
}
</script>

<template>
  <div class="comment" :class="{ 'is-reply': isReply }">
    <div class="avatar">{{ avatarText }}</div>

    <div class="body">
      <div class="head">
        <span class="nickname">{{ nickname }}</span>
        <!-- ★ 只有书评有评分（章评不打分）。没有评分时 score 的 key 会消失 → 用 ?? null -->
        <el-rate v-if="comment.score" :model-value="comment.score" disabled size="small" />
        <span class="time">{{ timeText }}</span>
      </div>

      <!--
        ★ 用 {{ }} 而不是 v-html：评论是用户输入的自由文本，
          v-html 就是一个 XSS 入口。换行靠 CSS 的 white-space: pre-line。
          （详情页的简介用的是同一条规矩。）
      -->
      <p class="content">{{ comment.content }}</p>

      <div class="ops">
        <el-button
          type="primary"
          link
          size="small"
          :disabled="liking"
          :class="{ active: liked }"
          @click="toggleLike"
        >
          {{ liked ? '已赞' : '点赞' }}{{ comment.likeCount ? ` ${comment.likeCount}` : '' }}
        </el-button>

        <!-- ★ 只在一级评论上：回复一条回复是 4006（两级封顶），挂上去必然失败 -->
        <el-button v-if="!isReply" type="primary" link size="small" @click="openReplyBox">
          回复
        </el-button>

        <!-- ★ 只在自己的评论上：后端对别人的评论返回 4002，先显示再报错等于挂一个必然失败的按钮 -->
        <el-button v-if="isMine" type="danger" link size="small" @click="handleDelete">删除</el-button>
      </div>

      <!-- ============ 回复输入框 ============ -->
      <div v-if="replyBoxOpen" class="reply-box">
        <el-input
          v-model="replyText"
          type="textarea"
          :rows="2"
          :maxlength="MAX_LENGTH"
          show-word-limit
          :placeholder="`回复 @${nickname}`"
        />
        <div class="reply-actions">
          <el-button size="small" @click="replyBoxOpen = false">取消</el-button>
          <el-button type="primary" size="small" :loading="submittingReply" @click="submitReply">
            发表回复
          </el-button>
        </div>
      </div>

      <!-- ============ 二级回复 ============ -->
      <div v-if="shownReplies.length" class="replies">
        <CommentItem
          v-for="reply in shownReplies"
          :key="reply.id"
          :comment="reply"
          :novel-id="novelId"
          is-reply
          @deleted="emit('deleted', $event)"
        />
      </div>

      <el-button v-if="canExpand" type="primary" link size="small" :loading="loadingReplies" @click="loadReplies(!!expanded)">
        {{ expandText }}
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.comment {
  display: flex;
  gap: 10px;
  padding: 12px 0;
  border-bottom: 1px solid #f2f3f5;
}

.comment:last-child {
  border-bottom: none;
}

/*
 * ★ 二级回复缩进 —— 两级封顶，所以只有这一层缩进。
 *   缩进 + 左边框一起用：只缩进的话，回复多了会和一级评论糊在一起
 *   分不清哪条回复哪条。
 */
.comment.is-reply {
  padding-left: 12px;
  margin-left: 6px;
  border-left: 2px solid #f2f3f5;
}

.avatar {
  flex: none;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: #ecf5ff;
  color: #409eff;
  font-size: 15px;
  line-height: 34px;
  text-align: center;
  /* ★ 评论人已注销时首字是「已」——用户名字可能带空格，不换行才不会把圆圈撑破 */
  white-space: nowrap;
  overflow: hidden;
}

.body {
  flex: 1;
  min-width: 0; /* ★ 少了它，长 URL 会把 flex 子项撑破，整页出现横向滚动条 */
}

.head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.nickname {
  font-size: 14px;
  color: #303133;
}

.time {
  margin-left: auto;
  font-size: 12px;
  color: #c0c4cc;
}

.content {
  margin: 6px 0 8px;
  font-size: 14px;
  line-height: 1.7;
  color: #303133;
  /* ★ 保留用户敲的换行，同时不解析任何 HTML */
  white-space: pre-line;
  /* 超长连续字符（比如一串没有空格的英文）强制折行 */
  word-break: break-word;
}

.ops {
  display: flex;
  align-items: center;
  gap: 12px;
}

/* 「已赞」要有明显的视觉差，否则用户分不清自己赞没赞过 */
.active {
  font-weight: 600;
}

.reply-box {
  margin-top: 10px;
}

.reply-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}

.replies {
  margin-top: 6px;
}
</style>
