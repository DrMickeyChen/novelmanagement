<script setup>
/**
 * 作品卡片 —— 首页 / 分类 / 榜单 / 搜索四处共用的列表单元。
 *
 * ★ 抽出来的理由很直接：这四个页面的列表项长得一模一样。
 *   各写一份的话，改一次样式要改四处，而且一定会漏一处 ——
 *   漏的那处不报错，只是在某个页面上长得不一样，很难被发现。
 *
 * ★ 卡片里【没有封面图】：novel 表没有 cover 字段对应的种子数据
 *   （封面是 P4 作者端的事），硬留一个占位图会让整个列表显得很空。
 *   等封面真正有了再往这个组件里加，四处一起生效 —— 这正是抽组件的好处。
 */
import { computed } from 'vue'
import { formatWordCount } from '@/utils/format'

const props = defineProps({
  /** 一个 NovelVO。字段见后端 NovelVO */
  novel: {
    type: Object,
    required: true,
  },
})

/** 详情页地址。★ 用 computed 而不是在模板里拼字符串，方便将来改路由 */
const detailPath = computed(() => `/novel/${props.novel.id}`)

/**
 * 字数格式化。
 *
 * ★ 规则本身在 utils/format.js 里 —— 详情页也要显示同一个数字，
 *   抄一份到这里就会出现「改了列表页忘了改详情页」。
 */
const wordCountText = computed(() => formatWordCount(props.novel.wordCount))

/** 连载 / 完结。★ isFinish 是 0/1 的数字，不是布尔 */
const isFinished = computed(() => Number(props.novel.isFinish) === 1)
</script>

<template>
  <router-link :to="detailPath" class="novel-card">
    <div class="card-head">
      <span class="title">{{ novel.title }}</span>
      <el-tag v-if="isFinished" size="small" type="success" effect="plain">完结</el-tag>
      <el-tag v-else size="small" type="warning" effect="plain">连载</el-tag>
    </div>

    <div class="meta">
      <!--
        ★ authorName 和 categoryName 都是后端 JOIN 出来的（NovelVO 里带的），
          前端不用再查一次 —— 后端为此加了 ContentUserLookup 做批量取昵称，
          就是不想让前端拿到一堆 authorId 之后自己一个个去查。
          它可能为 null（作者已注销 / 分类被删），所以要有兜底文案。
      -->
      <span class="author">{{ novel.authorName || '佚名' }}</span>
      <el-divider direction="vertical" />
      <span>{{ novel.categoryName || '未分类' }}</span>
    </div>

    <div class="stats">
      <span>{{ novel.chapterCount || 0 }} 章</span>
      <el-divider direction="vertical" />
      <span>{{ wordCountText }}</span>
      <el-divider direction="vertical" />
      <span>{{ novel.viewCount || 0 }} 阅读</span>
    </div>
  </router-link>
</template>

<style scoped>
.novel-card {
  display: block;
  padding: 14px 16px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  /* 悬停时给一点位移 + 阴影，让「这是可以点的」这件事不用靠鼠标指针才知道 */
  transition:
    border-color 0.15s,
    box-shadow 0.15s;
}

.novel-card:hover {
  border-color: #c6e2ff;
  box-shadow: 0 2px 8px rgb(64 158 255 / 12%);
}

.card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
  /*
   * ★ 书名可能很长（种子数据里没有，但真实数据一定会有），
   *   超出部分省略。不处理的话卡片高度会被撑高一行，网格里就错位了。
   */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.meta,
.stats {
  display: flex;
  align-items: center;
  font-size: 13px;
  color: #909399;
}

.meta {
  margin-bottom: 6px;
}

.author {
  color: #606266;
}
</style>
