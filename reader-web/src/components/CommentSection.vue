<script setup>
/**
 * 评论区 —— **书评区（详情页）和章评区（阅读器）共用同一个组件**。
 *
 * ★ 为什么共用：两处的差别只有三个 —— 接口路径、能不能打分、
 *   标题文案。把它们拆成两个组件的话，这块的 90%（分页、发帖、
 *   空态、登录引导、10 秒频控的提示）要写两遍，
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
 * 这正是后端 `CommentVO` 把 `novelId` 拆成两个字段时警告过的同一件事 ——
 * 「一个字段有时装这个、有时装那个」是 bug 的温床。
 * ★ 组件对外的调用仍然走 `api/comment.js` 里已经分好的两个函数，
 *   后端路径的形状没有变。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  createChapterComment,
  createNovelComment,
  getChapterComments,
  getNovelComments,
} from '@/api/comment'
import { usePagedList } from '@/composables/usePagedList'
import { useUserStore } from '@/stores/user'
import CommentItem from '@/components/CommentItem.vue'
import LoginPrompt from '@/components/LoginPrompt.vue'

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
 * 它处理过「加载中和空态打架」「快速翻页时先发的响应后到」这些事，
 * 见它的文件头注释。
 *
 * ★ `fetcher` 里现取 `props.chapterId`：书评区和章评区是两个不同的
 *   组件实例，但就算将来同一个实例被复用了，这里拿到的也是当前值。
 */
const { list, total, page, size, loading, loaded, isEmpty, failed, load, reload, changePage } =
  usePagedList(
    ({ page, size }) =>
      props.chapterId
        ? getChapterComments(props.novelId, props.chapterId, { page, size })
        : getNovelComments(props.novelId, { page, size }),
    { pageSize: 10 },
  )

onMounted(load)

/**
 * 重试。
 *
 * ★★ 必须在这里兜住 reject，不能在模板里裸写 `@click="reload"`：
 *   `request.js` 的响应拦截器**提示完照样把 Promise 拒出去**，
 *   裸调用就是一条 unhandledrejection —— 控制台一片红，
 *   接了上报工具会被当成真错误报上去，而它其实只是「刚才没请求到」。
 *   （详情页 `reportNovelView` 和 `handleDeleted` 都是同一个坑，写法保持一致。）
 */
function retry() {
  reload().catch(() => {})
}

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
    ElMessage.warning('评论内容不能为空')
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
     *   却会让列表闪一下、页码跳回第 1 页。清空输入框就够了。
     */
    ElMessage.success('评论已提交，通过审核后显示')
    content.value = ''
    score.value = 0
  } catch {
    /* 失败原因由 request.js 的拦截器提示（含 4004「评论太快了」），
       这里只需要别让它变成 unhandledrejection */
  } finally {
    submitting.value = false
  }
}

/**
 * 删掉一条之后重查**当前页**（不是 `reload()` —— 那个会跳回第 1 页，
 * 用户可能正在第 3 页删东西）。
 */
function handleDeleted() {
  load().catch(() => {})
}

/* ==================== 换评论对象 ==================== */

/**
 * ★★ 换了评论对象就重查 —— **这一条不是可选的**，两个页面都会踩：
 *   · 阅读器里翻到下一章时，路由参数变了但**组件不会重建**
 *     （同一个路由 `reader`，只有 `params` 变），`onMounted` 不会再跑；
 *   · 详情页从 `/novel/1` 走到 `/novel/2` 也一样。
 *
 * 少了这个 watch 的表现是：**翻到第 3 章，评论还是第 2 章的**，
 * 而且列表看起来完全正常（有内容、有条数、能翻页），
 * 只有对着章节标题读评论内容才发现对不上。
 *
 * ★ 同时清空发表框：用户在第 2 章写到一半翻到第 3 章，那句话是写给
 *   第 2 章的 —— 留着它、再点「发表」就发到第 3 章去了。
 *   （「输入框和它作用的对象必须同生共死」。）
 *
 * ★ 用 `reload()` 而不是 `load()`：换了对象要**回到第 1 页**。
 *   `load()` 会沿用当前页码，在第 3 章的第 5 页翻到第 4 章，
 *   查的是「第 4 章评论的第 5 页」—— 一条都没有，看起来像没人评论。
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
  <el-card shadow="never" class="section">
    <template #header>
      <span class="section-title">{{ isChapterComment ? '章评' : '书评' }}</span>
      <!--
        ★ M5：失败时不显示条数。`catch` 里 total 被清成 0，
          少了这个条件就会在加载失败时写着【共 0 条】——
          和下面那个「还没有人评」是同一句谎话的两种说法。
      -->
      <span v-if="loaded && !failed" class="section-sub">共 {{ total }} 条</span>
    </template>

    <!--
      ★ 未登录时用引导条**换掉**发表框，而不是把它置灰：
        置灰的输入框点了没反应，用户分不清是坏了还是自己没权限。
        （见 `LoginPrompt.vue` 的四条规矩。）
    -->
    <LoginPrompt v-if="!userStore.isLogin" :text="`登录后可以发表${isChapterComment ? '章评' : '书评'}`" />

    <div v-else class="post">
      <el-input
        v-model="content"
        type="textarea"
        :rows="3"
        :maxlength="MAX_LENGTH"
        show-word-limit
        :placeholder="isChapterComment ? '说说这一章……' : '说说这本书……'"
      />
      <div class="post-actions">
        <!-- ★ 只有书评能打分。不开 allow-half：后端存的是整数 1~5 -->
        <el-rate v-if="!isChapterComment" v-model="score" clearable />
        <span v-if="!isChapterComment" class="score-tip">
          {{ score > 0 ? `已评 ${score} 分` : '可以不打分' }}
        </span>
        <el-button type="primary" :loading="submitting" @click="submit">发表</el-button>
      </div>
    </div>

    <div v-loading="loading" class="list">
      <CommentItem
        v-for="item in list"
        :key="item.id"
        :comment="item"
        :novel-id="novelId"
        @deleted="handleDeleted"
      />
    </div>

    <!--
      ★★ M5：失败态和空态是两个分支，**顺序不能反**。
        它俩由 `usePagedList` 保证互斥（`isEmpty` 里带了 `!failed`），
        所以写 `v-else-if` 和写两个独立的 `v-if` 在渲染上等价 ——
        但仍然写 `v-else-if`：把「互斥」这件事**写进模板**，
        让下一个读代码的人不必回到 composable 里去确认。

      ★ 在 M5 之前这里只有下面那一个 `el-empty`，判据是
        `loaded && !list.length`。停掉 interaction 之后，
        读者看到的是【「还没有书评，来抢沙发」】——
        而真相是评论服务挂了。★ 更糟的是它看起来完全正常，
        没人会去报这个 bug。

      ★ 空态仍然必须等 `loaded`（这一点 M5 没改）—— 只用 `!list.length`
        的话，首次进入时请求还没发（loading 也是 false），
        「加载中」和「还没有评论」会同时出现，然后数据回来再闪一下。
    -->
    <el-empty
      v-if="isEmpty"
      :description="isChapterComment ? '还没有人评这一章' : '还没有书评，来抢沙发'"
      :image-size="60"
    />
    <el-empty v-else-if="failed" description="评论暂时加载失败" :image-size="60">
      <!--
        ★ 必须给重试入口：拦截器弹的那条提示是**瞬时**的，
           用户关掉它之后页面上就没有任何「能再做点什么」的地方了。
      -->
      <el-button type="primary" plain @click="retry">重试</el-button>
    </el-empty>

    <div v-if="total > size" class="pager">
      <el-pagination
        layout="prev, pager, next"
        background
        :current-page="page"
        :page-size="size"
        :total="total"
        @current-change="changePage"
      />
    </div>
  </el-card>
</template>

<style scoped>
/*
 * ★ 版式刻意和详情页的「简介」「目录」两张卡片**完全一致**
 *   （`.section` / `.section-title` / `.section-sub` 三个类名都照抄，
 *   数值也一致）—— 评论区是同一页上的第三张卡片，
 *   长得不一样会显得像从别的站搬过来的。
 *   ★ 这里是 scoped，不会和 `views/novel/index.vue` 里那几个同名类冲突。
 */
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

.post-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
}

.score-tip {
  font-size: 13px;
  color: #909399;
}

/* ★ 发表按钮靠最右：左边是评分、右边是动作，中间那段空白不要挤在一起 */
.post-actions .el-button {
  margin-left: auto;
}

/* ★ 给 v-loading 的遮罩留高度，否则列表为空时遮罩没有高度、看不见 */
.list {
  margin-top: 4px;
  min-height: 40px;
}

.pager {
  margin-top: 14px;
  display: flex;
  justify-content: center;
}
</style>
