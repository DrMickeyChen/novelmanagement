<script setup>
/**
 * 一条评论（移动端）—— 一级评论和二级回复**共用一个组件**（靠 `isReply` 区分）。
 *
 * ★ 移植自 `reader-web/src/components/CommentItem.vue`。两级共用而不是写两个：
 *   它们的 90% 是一样的（昵称/时间/正文/点赞/删除），差的是「回复按钮」
 *   「展开子回复」「缩进」。写两个组件的话，改一次点赞样式要改两处，
 *   而漏掉的那处不报错、只是长得不一样。
 *
 * ★★ 递归自引用：模板底部会渲染 `<CommentItem>` 自己。
 *   `<script setup>` 里**不需要 import 自己** —— 编译器按**文件名**推断组件名，
 *   同名引用会被解析成自身。★ 但这条依赖文件名，所以这个文件**不能改名**
 *   （改了之后子回复会静默变成"未注册组件"，H5 上只在控制台报一行警告）。
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
import { deleteComment, getReplies, likeComment, replyComment, unlikeComment } from '@/api/comment'
import { formatDateTime } from '@/utils/format'
import { confirm } from '@/utils/confirm'
import { useUserStore } from '@/stores/user'
import StarRate from '@/components/StarRate.vue'

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
 *   要显示真头像就得给每条评论多查一次用户表。P6 不做，用首字占位。
 *   ★ 这是**有意的取舍**，不是漏了。移动端照搬这个取舍，
 *   理由和桌面端一样（而且手机上头像更小，首字占位反而更清楚）。
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
    uni.showToast({ title: '回复内容不能为空', icon: 'none' })
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
    uni.showToast({ title: '回复已提交，通过审核后显示', icon: 'none' })
    replyText.value = ''
    replyBoxOpen.value = false
  } catch {
    /* 见 toggleLike */
  } finally {
    submittingReply.value = false
  }
}

/* ==================== 删除 ==================== */

/**
 * 删除。
 *
 * ★ 移动端这里比桌面端**少一层坑**：桌面端的 `ElMessageBox.confirm` 是
 *   命令式 API，点「取消」走的是 **reject** 分支，必须 try/catch 接住，
 *   而且读不到语言包、按钮文字要手写。
 *   `utils/confirm.js` 已经把这两件事都封平了 —— 它**永不抛异常、返回布尔**。
 *   所以这里写 `if (!(await confirm(...))) return` 就行。
 *   （那两条坑没有消失，只是被封在 confirm.js 里了，改那个文件时要小心。）
 */
async function handleDelete() {
  const ok = await confirm('删除后不可恢复，确定删除这条评论吗？', '删除评论', {
    confirmText: '删除',
  })
  if (!ok) return

  try {
    await deleteComment(props.comment.id)
    uni.showToast({ title: '已删除', icon: 'none' })
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
 *   ★ 每个需要登录的**区域**顶部有引导条（带按钮），
 *     区域**内的单个操作**只在被点击时提示一句。
 */
function ensureLogin(tip) {
  if (userStore.isLogin) return true
  uni.showToast({ title: tip, icon: 'none' })
  return false
}
</script>

<template>
  <view class="comment" :class="{ 'comment--reply': isReply }">
    <view class="comment__avatar">
      <text class="comment__avatar-text">{{ avatarText }}</text>
    </view>

    <view class="comment__body">
      <view class="comment__head">
        <text class="comment__nickname">{{ nickname }}</text>
        <!-- ★ 只有书评有评分（章评不打分）。没有评分时 score 的 key 会消失 → 用 ?? null -->
        <StarRate v-if="comment.score" :model-value="comment.score" disabled class="comment__rate" />
        <text class="comment__time">{{ timeText }}</text>
      </view>

      <!--
        ★ 用 {{ }} 而不是任何富文本渲染：评论是用户输入的自由文本，
          富文本就是一个 XSS 入口。换行靠 CSS 的 white-space: pre-line。
          （详情页的简介用的是同一条规矩。）
      -->
      <text class="comment__content">{{ comment.content }}</text>

      <view class="comment__ops">
        <text
          class="comment__op"
          :class="{ 'comment__op--active': liked }"
          @click="toggleLike"
          >{{ liked ? '已赞' : '点赞' }}{{ comment.likeCount ? ` ${comment.likeCount}` : '' }}</text
        >

        <!-- ★ 只在一级评论上：回复一条回复是 4006（两级封顶），挂上去必然失败 -->
        <text v-if="!isReply" class="comment__op" @click="openReplyBox">回复</text>

        <!-- ★ 只在自己的评论上：后端对别人的评论返回 4002，先显示再报错等于挂一个必然失败的按钮 -->
        <text v-if="isMine" class="comment__op comment__op--danger" @click="handleDelete">删除</text>
      </view>

      <!-- ============ 回复输入框 ============ -->
      <view v-if="replyBoxOpen" class="reply-box">
        <textarea
          v-model="replyText"
          class="reply-box__input"
          :maxlength="MAX_LENGTH"
          :placeholder="`回复 @${nickname}`"
          auto-height
        />
        <view class="reply-box__actions">
          <view class="btn btn--plain" hover-class="btn--hover" @click="replyBoxOpen = false">
            <text class="btn__text btn__text--plain">取消</text>
          </view>
          <view
            class="btn btn--primary"
            :class="{ 'btn--busy': submittingReply }"
            hover-class="btn--hover"
            @click="submitReply"
          >
            <text class="btn__text">{{ submittingReply ? '提交中…' : '发表回复' }}</text>
          </view>
        </view>
      </view>

      <!-- ============ 二级回复（★ 递归渲染自己） ============ -->
      <view v-if="shownReplies.length" class="replies">
        <CommentItem
          v-for="reply in shownReplies"
          :key="reply.id"
          :comment="reply"
          :novel-id="novelId"
          is-reply
          @deleted="emit('deleted', $event)"
        />
      </view>

      <text v-if="canExpand" class="expand" @click="loadReplies(!!expanded)">
        {{ loadingReplies ? '加载中…' : expandText }}
      </text>
    </view>
  </view>
</template>

<style scoped>
.comment {
  display: flex;
  padding: 20rpx 0;
  border-bottom: 1rpx solid #f0f1f4;
}

/* ★ 二级回复缩进 + 左侧竖线：不缩进的话父子两级的正文左边缘对齐，
   读起来像两条并列的一级评论 */
.comment--reply {
  padding-left: 0;
  border-bottom: none;
}

.comment__avatar {
  flex-shrink: 0;
  width: 64rpx;
  height: 64rpx;
  margin-right: 16rpx;
  border-radius: 50%;
  background-color: #e8eefc;
  display: flex;
  align-items: center;
  justify-content: center;
}

.comment__avatar-text {
  font-size: 28rpx;
  color: #2f6fed;
}

.comment__body {
  flex: 1;
  min-width: 0;
}

.comment__head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
}

.comment__nickname {
  font-size: 26rpx;
  color: #5a6472;
}

.comment__rate {
  margin-left: 10rpx;
}

.comment__time {
  margin-left: auto;
  font-size: 22rpx;
  color: #9aa3b0;
}

.comment__content {
  display: block;
  margin-top: 8rpx;
  font-size: 28rpx;
  color: #1f2329;
  line-height: 1.6;
  /* ★ 评论里用户敲的换行要保留（见模板里不用富文本那条） */
  white-space: pre-line;
  word-break: break-word;
}

.comment__ops {
  display: flex;
  align-items: center;
  margin-top: 10rpx;
}

.comment__op {
  margin-right: 32rpx;
  font-size: 24rpx;
  color: #6b7280;
}

.comment__op--active {
  color: #2f6fed;
}

.comment__op--danger {
  color: #e5484d;
}

.reply-box {
  margin-top: 12rpx;
}

.reply-box__input {
  width: 100%;
  box-sizing: border-box;
  min-height: 96rpx;
  padding: 14rpx;
  font-size: 26rpx;
  background-color: #f6f7f9;
  border-radius: 10rpx;
}

.reply-box__actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 12rpx;
}

.btn {
  margin-left: 16rpx;
  padding: 10rpx 28rpx;
  border-radius: 999rpx;
}

.btn--primary {
  background-color: #2f6fed;
}

.btn--plain {
  background-color: #f0f1f4;
}

.btn--hover {
  opacity: 0.85;
}

/* ★ 提交中不置灰、也不拦截点击（拦截交给 submittingReply 那个守卫），
   但要让用户看出来"正在提交"—— 否则他会连点，第二次撞上 4004 频控，
   看到的提示是"评论太快了"，而他以为自己只点了一下。 */
.btn--busy {
  opacity: 0.7;
}

.btn__text {
  font-size: 26rpx;
  color: #ffffff;
}

.btn__text--plain {
  color: #5a6472;
}

.replies {
  margin-top: 12rpx;
  padding-left: 20rpx;
  border-left: 4rpx solid #eef0f4;
}

.expand {
  display: block;
  margin-top: 12rpx;
  font-size: 24rpx;
  color: #2f6fed;
}
</style>
