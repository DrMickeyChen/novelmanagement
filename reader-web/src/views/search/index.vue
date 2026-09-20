<script setup>
/**
 * 搜索结果页 —— 按【书名或作者昵称】跨全站搜索。
 *
 * ★ 关键词来自 URL（/search?keyword=xxx），不是组件内部状态。
 *   和分类页的理由一样：搜索结果页要能分享、能收藏、能刷新。
 *   而且这样还有个副产品：用户按后退键回到上一次的搜索结果，
 *   页面显示的就是那一次的结果，不用重新搜。
 *
 * ★ 搜索框本身在 BasicLayout 的导航栏里，不在这个页面上 ——
 *   这样用户在任何页面都能直接搜，不用先回首页。
 *   这个页面只负责「显示结果」。
 */
import { computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { searchNovels } from '@/api/novel'
import { usePagedList } from '@/composables/usePagedList'
import NovelCard from '@/components/NovelCard.vue'

const route = useRoute()

/**
 * 关键词。
 *
 * ★ trim() 之后再用：URL 里带一个空格（/search?keyword=%20）
 *   在后端会被当成空白关键词直接返回空列表，
 *   而用户看到的现象是「搜了个空格，结果页说没找到」。
 *   这里 trim 掉之后，「有没有关键词」这个判断和前端显示就一致了。
 */
const keyword = computed(() => String(route.query.keyword || '').trim())

/**
 * ★★ 没有关键词就【不发请求】。
 *
 * 用户直接打开 /search（或者把 URL 里的 keyword 删了），
 * 空关键词的查询在后端会被短路成空列表 —— 也就是说这个请求
 * 100% 返回 0 条，纯属浪费。而且页面上会显示「没找到 xxx」，
 * x 是空的，文案很怪。这里直接显示一句引导语。
 *
 * ★ usePagedList 刻意不在内部自动请求（见它的文件头第 ① 条），
 *   正是为了留出这个判断的余地 —— 否则 composable 一挂载就
 *   先发一个空关键词的查询出去，页面还得再处理它的结果。
 */
const hasKeyword = computed(() => keyword.value.length > 0)

const { list, total, page, size, loading, loaded, load, changePage, reload } = usePagedList(
  (params) => searchNovels(params),
  {
    pageSize: 10,
    params: () => ({ keyword: keyword.value }),
  },
)

/**
 * ★ 关键词变了就重新搜，并回到第 1 页（reload 的职责）。
 *
 * 场景：用户在导航栏的搜索框里改了词再回车。
 * 此时路由从 ?keyword=A 变成 ?keyword=B，但【组件被复用】——
 * 同一个路由记录、只是 query 不同，onMounted 不会再跑。
 * 少了这个 watch，URL 变了、结果还是 A 的，而且刷新一下又对了。
 */
watch(keyword, () => {
  if (hasKeyword.value) {
    reload()
  } else {
    // ★ 关键词被清空时要把上一次的结果也清掉。
    //   留着的话页面显示空关键词，下面却列着上一次搜出来的书。
    list.value = []
    total.value = 0
  }
})

// ★ 首次进入：只在确实有关键词时查，理由见上面 hasKeyword 那段
onMounted(() => {
  if (hasKeyword.value) load()
})
</script>

<template>
  <div class="search">
    <!--
      ★ 没有关键词的引导态。刻意不用 el-empty —— 「空空如也」的插画
        配在这里像是出错了，而这个状态是完全正常的（用户还没搜）。
    -->
    <el-card v-if="!hasKeyword" shadow="never" class="hint">
      <p>在上方搜索框输入<strong>书名</strong>或<strong>作者名</strong>开始找书。</p>
      <p class="sub">搜索是跨全站的，不需要先选分类。</p>
    </el-card>

    <template v-else>
      <div class="page-head">
        <h2 class="title">
          「<span class="kw">{{ keyword }}</span>」的搜索结果
        </h2>
        <span v-if="loaded" class="count">共 {{ total }} 本</span>
      </div>

      <div v-loading="loading" class="grid">
        <NovelCard v-for="novel in list" :key="novel.id" :novel="novel" />
      </div>

      <!--
        ★ 空态文案里带上关键词。只说「暂无数据」的话，
          用户会怀疑是不是没搜出去（尤其是网络慢的时候）。
      -->
      <el-empty
        v-if="loaded && !list.length"
        :description="`没有找到与「${keyword}」有关的作品，换个书名或作者名试试`"
      />

      <div v-if="total > size" class="pager">
        <el-pagination
          layout="total, prev, pager, next"
          background
          :current-page="page"
          :page-size="size"
          :total="total"
          @current-change="changePage"
        />
      </div>
    </template>
  </div>
</template>

<style scoped>
.search {
  padding: 20px 0;
}

.hint {
  text-align: center;
  color: #606266;
}

.hint .sub {
  font-size: 13px;
  color: #909399;
}

.page-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 16px;
}

.title {
  margin: 0;
  font-size: 18px;
  font-weight: 500;
}

.kw {
  color: #409eff;
  font-weight: 600;
}

.count {
  font-size: 13px;
  color: #909399;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
  min-height: 80px;
}

.pager {
  display: flex;
  justify-content: center;
  margin-top: 20px;
}
</style>
