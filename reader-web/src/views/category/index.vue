<script setup>
/**
 * 分类页 —— 某个分类下的作品列表，带排序和完结状态筛选。
 *
 * ★ 分类 id 来自路由（/category/:id），不是组件内部的状态。
 *   理由：这个页面的地址是【可以分享、可以收藏】的，
 *   把 id 藏在组件状态里就意味着「发给朋友的链接打开是别的分类」
 *   或者干脆打不开。
 *
 * ★ 换分类（在同一路由上从 /category/1 走到 /category/2）时
 *   必须 reload()，而且必须回到第 1 页 —— 见下面 watch 那段。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getCategoryList } from '@/api/category'
import { getNovelList } from '@/api/novel'
import { usePagedList } from '@/composables/usePagedList'
import NovelCard from '@/components/NovelCard.vue'

const route = useRoute()

/** 分类 id。★ route.params.id 是字符串，后端会转，这里不强制 Number */
const categoryId = computed(() => route.params.id)

/**
 * ★ 排序选项的 key 必须和【后端 applyReaderSort 的白名单】一致。
 *   这里写错一个字母不会报错 —— 后端对不认识的 sort 会静默按 latest 处理，
 *   现象是「点了『字数最多』但顺序没变」，排查时很容易怀疑到后端。
 *   所以这些值是照着后端注释抄的，改后端时这里要跟着改。
 */
const SORT_OPTIONS = [
  { key: 'latest', label: '最近更新' },
  { key: 'new', label: '最新上架' },
  { key: 'words', label: '字数最多' },
]

/**
 * ★ 「全部」用的是空字符串，不是 null。
 *   el-radio-group 的 v-model 在没选中时是空串，用 null 会导致
 *   「一开始没有任何一项是选中的」。空串在这里只是【界面上的一个档位】，
 *   往下传给接口时会转成 undefined（见 params），axios 会把它整个省掉。
 */
const FINISH_OPTIONS = [
  { key: '', label: '全部' },
  { key: '0', label: '连载中' },
  { key: '1', label: '已完结' },
]

const sort = ref('latest')
const finish = ref('')

/** 分类名。★ 后端没有「查单个分类」的接口，从分类列表里挑一个 */
const categoryName = ref('')

const {
  list,
  total,
  page,
  size,
  loading,
  loaded,
  isEmpty,
  failed,
  load,
  changePage,
  changeSize,
  reload,
} = usePagedList((params) => getNovelList(params), {
  pageSize: 12,
  /*
   * ★ params 传函数而不是对象：每次请求前现取一次。
   *   传对象的话拿到的是组件创建那一刻的快照，之后用户改了排序
   *   也还是用旧值发请求 —— 而页面上的下拉框已经显示了新值。
   */
  params: () => ({
    categoryId: categoryId.value,
    sort: sort.value,
    // ★ 空串 → undefined，axios 会把这个参数整个省掉（不是传一个空值）
    isFinish: finish.value === '' ? undefined : Number(finish.value),
  }),
})

onMounted(() => {
  loadCategoryName()
  load()
})

/**
 * 失败态里那个「重试」。
 *
 * ★★ 必须在这里兜住 reject：`request.js` 的响应拦截器**提示完照样
 *   把 Promise 拒出去**，模板里裸写 `@click="reload"` 就是一条
 *   unhandledrejection —— 控制台一片红，接了上报工具会被当成真错误。
 *   （评论区 / 搜索页各有一份同样的 `retry()`，三处保持一致。）
 */
function retry() {
  reload().catch(() => {})
}

/**
 * ★★ 筛选条件变了要 reload()，【不能】直接 load()。
 *
 * load() 会沿用当前页码：用户在「玄幻」的第 5 页切到「悬疑」，
 * 而「悬疑」只有 2 页 → 空白列表但总数显示 20 条，看起来像后端分页算错了。
 * reload() 会把页码重置回 1 再查，见 composables/usePagedList.js 的说明。
 */
watch([sort, finish], () => reload())

/**
 * ★ 换分类也要 reload()，同样是为了回到第 1 页。
 *
 * ★★ 这个 watch 是必须的，不是保险：从 /category/1 走到 /category/2 时
 *   路由【变了但组件被复用】（同一个路由记录、只是参数不同），
 *   onMounted 不会再跑一次。少了这个 watch，页面标题变成了新分类，
 *   列表却还停在上一个分类的数据上 —— 而且因为 URL 是对的，
 *   刷新一下又正常了，看起来像灵异事件。
 */
watch(categoryId, () => {
  loadCategoryName()
  reload()
})

/**
 * 取分类名。
 *
 * ★ 失败【不弹提示】：它只是页面标题上的三个字，拿不到就退回「分类」。
 *   为它弹一个错误框，会盖在正常加载出来的列表上面，主次颠倒了。
 *   （api 层没带 silent，所以这里自己 catch —— 拦截器弹过了就算了，
 *     真要严格静默得给 request 传 silent，但那样连「后端挂了」都看不见。）
 */
async function loadCategoryName() {
  try {
    const list = (await getCategoryList()) || []
    categoryName.value = list.find((c) => String(c.id) === String(categoryId.value))?.name || ''
  } catch {
    categoryName.value = ''
  }
}
</script>

<template>
  <div class="category">
    <div class="page-head">
      <h2 class="title">{{ categoryName || '分类' }}</h2>
      <span v-if="loaded" class="count">共 {{ total }} 本</span>
    </div>

    <div class="filters">
      <el-radio-group v-model="sort" size="small">
        <el-radio-button v-for="item in SORT_OPTIONS" :key="item.key" :value="item.key">
          {{ item.label }}
        </el-radio-button>
      </el-radio-group>

      <el-radio-group v-model="finish" size="small">
        <el-radio-button v-for="item in FINISH_OPTIONS" :key="item.key" :value="item.key">
          {{ item.label }}
        </el-radio-button>
      </el-radio-group>
    </div>

    <div v-loading="loading" class="grid">
      <NovelCard v-for="novel in list" :key="novel.id" :novel="novel" />
    </div>

    <!--
      ★ loaded 才显示空态，否则首屏会出现「加载动画 + 暂无数据」同时显示

      ★★ M5：失败态单独一支。M5 之前失败和空态共用这一行，于是
        停掉 content 之后读者看到的是【「这个分类下还没有作品」】——
        而真相是作品服务挂了。它看起来完全正常，所以没人会去报这个 bug。
        `isEmpty` 里含 `!failed`，两支由构造互斥（见 usePagedList 文件头）。
    -->
    <el-empty v-if="isEmpty" description="这个分类下还没有作品" />
    <el-empty v-else-if="failed" description="作品暂时加载失败">
      <!-- ★ 拦截器弹的那条提示是瞬时的，关掉之后页面上得有个能再试一次的地方 -->
      <el-button type="primary" plain @click="retry">重试</el-button>
    </el-empty>

    <div v-if="total > size" class="pager">
      <el-pagination
        layout="total, sizes, prev, pager, next"
        background
        :current-page="page"
        :page-size="size"
        :page-sizes="[12, 24, 48]"
        :total="total"
        @current-change="changePage"
        @size-change="changeSize"
      />
    </div>
  </div>
</template>

<style scoped>
.category {
  padding: 20px 0;
}

.page-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 14px;
}

.title {
  margin: 0;
  font-size: 20px;
}

.count {
  font-size: 13px;
  color: #909399;
}

.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 16px;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
  /* 给 v-loading 的遮罩留高度，否则内容为空时遮罩没有高度、看不见 */
  min-height: 80px;
}

.pager {
  display: flex;
  justify-content: center;
  margin-top: 20px;
}
</style>
