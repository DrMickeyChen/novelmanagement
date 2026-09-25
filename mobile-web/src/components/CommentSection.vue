<script setup>
/**
 * 评论区（移动端）—— **书评区（详情页）和章评区（阅读器）共用同一个组件**。
 *
 * ★ 移植自 `reader-web/src/components/CommentSection.vue`。两处的差别只有三个 ——
 *   接口路径、能不能打分、标题文案。拆成两个组件的话这块的 90%
 *   （分页、发帖、空态、登录引导、10 秒频控的提示）要写两遍，
 *   而两遍迟早会长得不一样。
 *
 * ------------------------------------------------------------
 * ★★ 怎么区分书评和章评：**只传一个可空的 `chapterId`**
 * ------------------------------------------------------------
 * 后端是用 `targetType`(1 书评/2 章评) + `targetId` 表达这件事的，
 * 但组件这里**刻意不照抄成两个 prop**：
 *   · 两个 prop 就有「传了 targetType=1 却把 targetId 填成章节 id」
 *     这种组合，而它**不报错**，只是查不出东西；
 *   · 而 `chapterId` 一个 prop 只有两种取值，`targetType` 由它推出来
 *     （`chapterId ? 2 : 1`），**不可能自相矛盾**。
 *
 * ------------------------------------------------------------
 * ★★ 与桌面端的两处**真实分歧**（不是偷懒）
 * ------------------------------------------------------------
 * ① **翻页 → 上拉加载更多**。桌面端是 `el-pagination` 跳页；
 *    手机上「翻页」意味着瞄准一个小按钮，而「继续往下滑」是手指
 *    本来就在做的动作。所以这里用 `append: true` + `loadMore()`。
 *
 * ② **`onReachBottom` 由【页面】触发，组件只暴露 `loadMore`**。
 *    这是 uni-app 的硬约束：`onReachBottom` 是**页面级**生命周期，
 *    组件里根本收不到。所以组件 `defineExpose({ loadMore })`，
 *    页面在 `onReachBottom` 里调它。
 *    ★ 页面那一侧只允许有**一个**上拉加载区 —— 详情页的目录刻意
 *      做成「点按钮展开」就是为了给评论区让出 `onReachBottom`，
 *      两个无限滚动区在同一页上会互相抢事件。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { createChapterComment, createNovelComment, getChapterComments, getNovelComments } from '@/api/comment'
import { usePagedList } from '@/composables/usePagedList'
import { useUserStore } from '@/stores/user'
import CommentItem from '@/components/CommentItem.vue'
import LoginPrompt from '@/components/LoginPrompt.vue'
import StarRate from '@/components/StarRate.vue'

const props = defineProps({
  novelId: {
    type: [Number, String],
    required: true,
  },
  /**
   * 章节 id。★ **不传 = 书评区**（评论对象是整本书），传了 = 章评区。
   * 见文件头：用「有没有 chapterId」当唯一的判据。
   */
  chapterId: {
    type: [Number, String],
    default: null,
  },
})

/**
 * ★ 由 `chapterId` 推出来的评论对象类型，**只用于显示和提交时的判断**。
 * 和后端 `NovelComment.targetType` 的取值一致（1 书评 / 2 章评）。
 */
const isChapterComment = computed(() => !!props.chapterId)

const userStore = useUserStore()

/** 和 `CommentCreateDTO` 的 `@Size(max = 500)` 对齐 */
const MAX_LENGTH = 500

/* ==================== 列表 ==================== */

/**
 * 分页。★ 用项目统一的分页钩子（`usePagedList`），不自己写一份 ——
 * 它处理过「加载中和空态打架」「快速翻页时先发的响应后到」这些事。
 *
 * ★ `append: true`：移动端是无限滚动，第 2 页往后接在后面。
 * ★ `fetcher` 里现取 `props.chapterId`：书评区和章评区是两个不同的
 *   组件实例，但就算将来同一个实例被复用了，这里拿到的也是当前值。
 */
const { list, total, loading, loaded, finished, failed, loadMoreFailed, isEmpty, error, loadMore, reload } =
  usePagedList(
    ({ page, size }) =>
      props.chapterId
        ? getChapterComments(props.novelId, props.chapterId, { page, size })
        : getNovelComments(props.novelId, { page, size }),
    { pageSize: 10, append: true },
  )

onMounted(reload)

/**
 * 重试（首屏失败 / 追加失败共用）。
 *
 * ★★ 必须在这里兜住 reject，不能在模板里裸写 `@click="reload"`：
 *   `request.js` 的响应拦截器**提示完照样把 Promise 拒出去**，
 *   裸调用就是一条 unhandledrejection —— 控制台一片红，
 *   接了上报工具会被当成真错误报上去，而它其实只是「刚才没请求到」。
 */
function retry() {
  reload().catch(() => {})
}

/** 追加失败后的重试：**不能调 reload()** —— 那会把用户已经滑过的内容清掉 */
function retryMore() {
  loadMore()?.catch(() => {})
}

/**
 * 把 `loadMore` 暴露给页面。
 *
 * ★★ 这是 uni-app 的硬约束，不是设计选择：`onReachBottom` 是**页面级**
 *   生命周期，组件内部收不到滚动到底的事件。
 *   ★ 页面侧的写法见 `pages/novel/novel.vue` 的 `onReachBottom`。
 */
defineExpose({ loadMore })

/* ==================== 发表 ==================== */

const content = ref('')
/** 评分 1~5。★ **0 表示「不打分」**，不是「打 0 分」—— 提交时不传这个字段 */
const score = ref(0)
const submitting = ref(false)

/**
 * 发表评论 / 章评。
 *
 * ★★ **评分必须按「有没有值」决定传不传，不能传 0**：
 *   后端 `CommentCreateDTO#score` 是 `@Min(1) @Max(5)`，传 0 会撞 `@Min`
 *   得到一个 **400**（不是夹到 1 分 —— 那个 DTO 的注释专门解释了为什么不夹：
 *   它会进 `novel.score` 那个平均分，夹一下就把一个错误的分数洗成了合法的分数）。
 * ★ **章评不传评分**：章评不打分，评分是对整本书的。后端的 DTO 是
 *   书评/章评共用的（所以字段在），但章评那条路服务端不看它。
 */
async function submit() {
  const text = content.value.trim()
  if (!text) {
    uni.showToast({ title: '评论内容不能为空', icon: 'none' })
    return
  }

  const payload = { content: text }
  if (!isChapterComment.value && score.value > 0) {
    payload.score = score.value
  }

  submitting.value = true
  try {
    if (isChapterComment.value) {
      await createChapterComment(props.novelId, props.chapterId, payload)
    } else {
      await createNovelComment(props.novelId, payload)
    }
    /*
     * ★★ 说「已提交」，**不说「发表成功」**：
     *   新评论是**待审核**的，读者端列表只返回 `status = 1`，
     *   所以它**不会立刻出现在下面**。只说成功的话，用户会刷新、
     *   找不到、再发一条 —— 而第二条会撞上 10 秒频控（4004），
     *   于是他看到的是「发表失败」。这条提示语是在替后端解释审核流程。
     *
     * ★ 发完**不重查列表**：重查也还是没有它（同上），
     *   却会让列表闪一下、滚动位置跳回顶部。清空输入框就够了。
     */
    uni.showToast({ title: '评论已提交，通过审核后显示', icon: 'none' })
    content.value = ''
    score.value = 0
  } catch {
    /* 失败原因由 request.js 的拦截器提示（含 4004「评论太快了」），
       这里只需要别让它变成 unhandledrejection */
  } finally {
    submitting.value = false
  }
}

/* ==================== 删掉一条之后 ==================== */

/**
 * 删掉一条之后**只从本地列表里摘掉它**，不重查。
 *
 * ★★ 为什么**不能**照抄桌面端的 `load()`：那个写法在追加模式下是**错的**。
 *   桌面端的分页是「第 N 页替换第 N-1 页」，`load()` 重取当前页、
 *   替换掉列表，正确。
 *   而追加模式下列表里是**第 1..N 页拼起来的**，`load()` 会去取第 N 页
 *   再 `concat` 到现有列表后面 —— 结果是**第 N 页出现两遍**。
 *   删一条评论，列表里多出十条重复的。
 *
 * ★★ 为什么也**不用 `reload()`**：那会回到第 1 页。用户滑到第 5 页
 *   删掉自己的一条评论，整页内容被换掉、滚动位置弹回顶部 ——
 *   而他只是删了一条。
 *
 * ★ 本地摘除在这里是**精确**的：删一级评论会连带删掉它的全部二级回复，
 *   而那些回复就渲染在这一个 item 对象里面（递归子组件），
 *   摘掉这一条 = 它的整棵子树一起没了。total 减 1（total 数的是一级评论）。
 */
function handleDeleted(id) {
  const before = list.value.length
  list.value = list.value.filter((c) => c.id !== id)
  // ★ 只有真摘掉了才减 total：找不到那个 id 说明列表已经不同步了，
  //   这时减 total 会把数字改错，不如不动（下一次 reload 会校准）
  if (list.value.length < before) {
    total.value = Math.max(0, total.value - 1)
  }
}

/* ==================== 换评论对象 ==================== */

/**
 * ★★ 换了评论对象就重查 —— **这一条不是可选的**：
 *   · 阅读器里翻到下一章时，路由参数变了但**组件不会重建**
 *     （同一个页面 `reader`，只有 options 变），`onMounted` 不会再跑；
 *   · 详情页从一个作品走到另一个作品也一样。
 *
 * 少了这个 watch 的表现是：**翻到第 3 章，评论还是第 2 章的**，
 * 而且列表看起来完全正常（有内容、有条数、能下滑），
 * 只有对着章节标题读评论内容才发现对不上。
 *
 * ★ 同时清空发表框：用户在第 2 章写到一半翻到第 3 章，那句话是写给
 *   第 2 章的 —— 留着它、再点「发表」就发到第 3 章去了。
 *   （「输入框和它作用的对象必须同生共死」。）
 *
 * ★ 用 `reload()` 而不是 `loadMore()`：换了对象要**回到第 1 页**。
 */
watch(
  () => [props.novelId, props.chapterId],
  () => {
    content.value = ''
    score.value = 0
    reload().catch(() => {})
  },
)
</script>

<template>
  <view class="section">
    <view class="section__head">
      <text class="section__title">{{ isChapterComment ? '章评' : '书评' }}</text>
      <!--
        ★ M5：失败时不显示条数。`catch` 里 total 被清成 0，
          少了这个条件就会在加载失败时写着【共 0 条】——
          和下面那个「还没有人评」是同一句谎话的两种说法。
      -->
      <text v-if="loaded && !failed" class="section__sub">共 {{ total }} 条</text>
    </view>

    <!--
      ★ 未登录时用引导条**换掉**发表框，而不是把它置灰：
        置灰的输入框点了没反应，用户分不清是坏了还是自己没权限。
        （见 LoginPrompt.vue 的四条规矩。）
    -->
    <LoginPrompt v-if="!userStore.isLogin" :text="`登录后可以发表${isChapterComment ? '章评' : '书评'}`" />

    <view v-else class="post">
      <textarea
        v-model="content"
        class="post__input"
        :maxlength="MAX_LENGTH"
        :placeholder="isChapterComment ? '说说这一章……' : '说说这本书……'"
        auto-height
      />
      <view class="post__actions">
        <!-- ★ 只有书评能打分。后端存的是整数 1~5 -->
        <template v-if="!isChapterComment">
          <StarRate v-model="score" />
          <text class="post__tip">{{ score > 0 ? `已评 ${score} 分` : '可以不打分' }}</text>
        </template>
        <view
          class="btn btn--primary"
          :class="{ 'btn--busy': submitting }"
          hover-class="btn--hover"
          @click="submit"
        >
          <text class="btn__text">{{ submitting ? '提交中…' : '发表' }}</text>
        </view>
      </view>
      <!-- ★ 桌面端用 el-input 的 show-word-limit，移动端自己写一行：
           500 字的长评在手机上很容易超，超了后端直接 400，不如提前显示 -->
      <text class="post__count">{{ content.length }}/{{ MAX_LENGTH }}</text>
    </view>

    <!-- ============ 列表 ============ -->
    <view class="list">
      <CommentItem
        v-for="item in list"
        :key="item.id"
        :comment="item"
        :novel-id="novelId"
        @deleted="handleDeleted"
      />
    </view>

    <!--
      ★★ 底部四种状态**互斥**，由 usePagedList 的构造保证：
         · 首屏失败（列表空）    → failed
         · 首屏空               → isEmpty
         · 追加失败（列表非空） → loadMoreFailed   ← ★ 移动端独有
         · 到底了               → finished
      ★ 顺序不能乱：`loadMoreFailed` 必须排在 `finished` 前面 ——
        追加失败时 finished 仍是 false，但万一将来两者同时为真，
        显示「没有更多了」比「加载失败」更糟（它是在说谎）。
    -->
    <view class="foot">
      <text v-if="failed" class="foot__text foot__text--err">评论暂时加载失败</text>
      <text v-if="failed" class="foot__retry" @click="retry">点击重试</text>

      <text v-else-if="isEmpty" class="foot__text">
        {{ isChapterComment ? '还没有人评这一章' : '还没有书评，来抢沙发' }}
      </text>

      <template v-else-if="loadMoreFailed">
        <text class="foot__text foot__text--err">加载失败</text>
        <text class="foot__retry" @click="retryMore">点击重试</text>
      </template>

      <text v-else-if="loading" class="foot__text">加载中…</text>
      <text v-else-if="finished" class="foot__text">没有更多了</text>
    </view>
  </view>
</template>

<style scoped>
.section {
  background-color: #ffffff;
  border-radius: 16rpx;
  padding: 24rpx;
}

.section__head {
  display: flex;
  align-items: baseline;
  margin-bottom: 16rpx;
}

.section__title {
  font-size: 32rpx;
  font-weight: 600;
  color: #1f2329;
}

.section__sub {
  margin-left: 12rpx;
  font-size: 24rpx;
  color: #9aa3b0;
}

.post__input {
  width: 100%;
  box-sizing: border-box;
  min-height: 140rpx;
  padding: 16rpx;
  font-size: 28rpx;
  background-color: #f6f7f9;
  border-radius: 12rpx;
}

.post__actions {
  display: flex;
  align-items: center;
  margin-top: 14rpx;
}

.post__tip {
  margin-left: 12rpx;
  font-size: 24rpx;
  color: #9aa3b0;
}

.post__count {
  display: block;
  margin-top: 6rpx;
  font-size: 22rpx;
  color: #b6bcc6;
  text-align: right;
}

.btn {
  margin-left: auto;
  padding: 12rpx 40rpx;
  border-radius: 999rpx;
}

.btn--primary {
  background-color: #2f6fed;
}

.btn--hover {
  opacity: 0.85;
}

/* ★ 提交中不拦点击（拦截交给 submitting 守卫），但要看得出来，
   否则用户连点、第二次撞上 4004 频控，提示说"评论太快了"，
   而他以为自己只点了一下 */
.btn--busy {
  opacity: 0.7;
}

.btn__text {
  font-size: 28rpx;
  color: #ffffff;
}

.list {
  margin-top: 8rpx;
}

.foot {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24rpx 0 8rpx;
}

.foot__text {
  font-size: 24rpx;
  color: #9aa3b0;
}

.foot__text--err {
  color: #e5484d;
}

.foot__retry {
  margin-left: 16rpx;
  font-size: 24rpx;
  color: #2f6fed;
}
</style>
