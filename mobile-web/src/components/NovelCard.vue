<script setup>
/**
 * 作品卡片（移动端）—— 首页 / 分类 / 榜单 / 搜索四处共用的列表单元。
 *
 * ★ 与 reader-web/src/components/NovelCard.vue 展示的信息**完全一致**
 *   （书名、连载状态、作者、分类、章数、字数、阅读量），但结构必须重写：
 *   桌面端用 `div` + Element Plus 的 `el-tag` / `el-divider`，
 *   而 uni-app 的模板里**不能出现 div** —— 小程序端只认 `view` / `text`
 *   这一套内置组件，写 div 在 H5 上照样能跑（H5 会把它渲染成 div），
 *   但在小程序里直接不识别。**这正是"只发 H5 就验不出跨端问题"的典型**：
 *   判据必须落在源码结构上（见 docs/E3-uni-app.md 判断 4）。
 *
 * ★ 分隔符不再用 el-divider，改成 `text` 里直接写「·」——
 *   多一个组件实例只为了画一根竖线，在手机上不划算。
 *
 * ★ 卡片里【没有封面图】：novel 表没有封面字段对应的种子数据，
 *   硬留一个占位图会让整个列表显得很空。
 */
import { computed } from 'vue'
import { formatAuthorName, formatWordCount } from '@/utils/format'

const props = defineProps({
  /** 一个 NovelVO。字段见后端 NovelVO */
  novel: {
    type: Object,
    required: true,
  },
})

/** 连载 / 完结。★ isFinish 是 0/1 的数字，不是布尔 */
const isFinished = computed(() => Number(props.novel.isFinish) === 1)

/**
 * 字数格式化。★ 规则本身在 utils/format.js 里 ——
 * 详情页也要显示同一个数字，抄一份到这里就会出现「改了列表页忘了改详情页」。
 */
const wordCountText = computed(() => formatWordCount(props.novel.wordCount))

/** 打开详情页。★ 用 navigateTo（保留返回栈），不是 redirectTo */
function open() {
  uni.navigateTo({ url: `/pages/novel/novel?id=${props.novel.id}` })
}
</script>

<template>
  <view class="novel-card" hover-class="novel-card--active" :hover-stay-time="80" @click="open">
    <view class="head">
      <text class="title">{{ novel.title }}</text>
      <!--
        ★ 用两个并列的 text + v-if，而不是「同一个节点换 class」：
           后者在 uni 的编译里会因为 class 是动态的而生成一段运行期逻辑，
           而这里只有两个互斥的静态分支，编译结果更简单。
      -->
      <text v-if="isFinished" class="badge badge--done">完结</text>
      <text v-else class="badge badge--ongoing">连载</text>
    </view>

    <!--
      ★★ authorName 和 categoryName 的兜底【故意不一样】，理由见
         utils/format.js 里 formatAuthorName 的完整论证：
           authorName 跨服务（auth 挂了就整个消失 → 显示 `—`），
           categoryName 是本库 JOIN（取不到就是真被删了 → 显示「未分类」）。
         判据是「这个字段会不会因为别人挂了而消失」，不是「它重不重要」。
    -->
    <view class="meta">
      <text class="author">{{ formatAuthorName(novel.authorName) }}</text>
      <text class="dot">·</text>
      <text>{{ novel.categoryName || '未分类' }}</text>
    </view>

    <view class="stats">
      <text>{{ novel.chapterCount || 0 }} 章</text>
      <text class="dot">·</text>
      <text>{{ wordCountText }}</text>
      <text class="dot">·</text>
      <text>{{ novel.viewCount || 0 }} 阅读</text>
    </view>
  </view>
</template>

<style scoped>
.novel-card {
  padding: 24rpx 28rpx;
  background: #fff;
  border-radius: 16rpx;
  /*
   * ★ 触屏上没有 hover，`hover-class` 才是"按下去了"的反馈 ——
   *   它由 uni 在 touchstart/touchend 时切换，是小程序里的标准做法。
   *   （桌面端的 `.novel-card:hover` 那条在手机上是永远不触发的死样式。）
   */
  transition: background-color 0.15s;
}

.novel-card--active {
  background: #f2f5fa;
}

.head {
  display: flex;
  align-items: center;
  margin-bottom: 10rpx;
}

.title {
  font-size: 32rpx;
  font-weight: 600;
  color: #1f2733;
  /*
   * ★ 书名可能很长，超出部分省略。不处理的话卡片高度会被撑高一行，
   *   列表里就参差不齐了。
   * ★★ flex 子项默认 min-width:auto —— 不加 min-width:0 的话，
   *   `overflow:hidden` + `text-overflow:ellipsis` 在 flex 容器里
   *   **不会生效**（元素会被内容撑宽而不是被压缩）。这是 flex 布局里
   *   最经典的一个坑，桌面端那份不用处理是因为它的标题不在 flex 里独占。
   */
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.badge {
  flex: none;
  margin-left: 12rpx;
  padding: 2rpx 12rpx;
  border-radius: 6rpx;
  font-size: 20rpx;
}

.badge--done {
  color: #2f9e6b;
  background: #e8f7ef;
}

.badge--ongoing {
  color: #d98b1f;
  background: #fdf3e2;
}

.meta,
.stats {
  display: flex;
  align-items: center;
  font-size: 24rpx;
  color: #8a93a0;
}

.meta {
  margin-bottom: 8rpx;
}

.author {
  color: #5a6472;
}

.dot {
  margin: 0 10rpx;
  color: #c8ced8;
}
</style>
