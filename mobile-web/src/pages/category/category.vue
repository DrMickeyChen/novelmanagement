<script setup>
/**
 * 分类页 —— 某个分类下的作品列表，带排序和完结状态筛选。
 *
 * ★ 分类 id 来自**页面参数**（`/pages/category/category?id=3&name=玄幻`），
 *   由首页的分类格传进来 —— 与桌面端的 `/category/:id` 是同一件事的两种写法。
 *
 * ------------------------------------------------------------
 * ★★ 这一页是 `usePagedList` 的 `seq`（丢弃过期响应）最要紧的地方
 * ------------------------------------------------------------
 * 页面上有**两组筛选**（排序 3 档 × 状态 3 档），随手连点两下就会发出
 * 两个并发请求。没有 seq 的话，先发的那个晚回来就会覆盖后发的 ——
 * 用户看到的是"按字数排"的数据，而高亮在"最近更新"上。
 * 而且它**只在网络慢的时候出现**，本地开发几乎测不出来。
 *
 * ★★ 换分类时用的是 `reload()` 不是 `load()`：
 *    load() 会沿用当前页码 —— 在「玄幻」的第 5 页切到「悬疑」，
 *    而「悬疑」只有 2 页 → **空白列表但总数显示 20 条**，
 *    看起来像后端分页算错了。reload() 会把页码重置回 1。
 *
 * ------------------------------------------------------------
 * ★ 与桌面端的两处形态差异（都不是"桌面端那样没必要"）
 * ------------------------------------------------------------
 * ① 桌面端 watch(categoryId) 处理「同路由换参数、组件被复用」的情况。
 *    移动端**不需要**：uni 的 navigateTo 每次都新建页面实例，
 *    onLoad 一定会再跑一次。少一个 watch 是因为这个场景不存在，
 *    不是因为它可以省。
 * ② 分类名优先用首页传进来的 `name` 参数，省掉一次「拉全部分类」的请求。
 *    桌面端每次都拉（它只拿到 id）—— 移动端在导航时顺手带了，
 *    这个信息本来就在手上，再查一次没有意义。
 *    ★ 但没有 `name` 参数时（直接进这个页面）仍然要去查，见 onMounted。
 */
import { onMounted, ref } from 'vue'
import { onLoad, onReachBottom } from '@dcloudio/uni-app'
import { getCategoryList } from '@/api/category'
import { getNovelList } from '@/api/novel'
import { usePagedList } from '@/composables/usePagedList'
import NovelCard from '@/components/NovelCard.vue'
import StateHint from '@/components/StateHint.vue'

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
 *   空串在这里只是【界面上的一个档位】，往下传给接口时会转成 undefined，
 *   而 request.js 的 buildQuery 会把 undefined 整个省掉（不是传一个空值）。
 */
const FINISH_OPTIONS = [
  { key: '', label: '全部' },
  { key: '0', label: '连载中' },
  { key: '1', label: '已完结' },
]

const categoryId = ref('')
const categoryName = ref('')
const sort = ref('latest')
const finish = ref('')

const {
  list,
  total,
  loaded,
  loading,
  isEmpty,
  failed,
  loadMoreFailed,
  finished,
  load,
  loadMore,
  reload,
} = usePagedList((params) => getNovelList(params), {
  pageSize: 12,
  append: true,
  /*
   * ★ params 传函数而不是对象：每次请求前现取一次。
   *   传对象的话拿到的是创建那一刻的快照，之后用户改了排序
   *   也还是用旧值发请求 —— 而页面上的筛选条已经显示了新值。
   *   ★★ 这个坑在移动端**更隐蔽**：并发请求多，很容易被误判成
   *      "seq 没生效"。
   */
  params: () => ({
    categoryId: categoryId.value,
    sort: sort.value,
    // ★ 空串 → undefined（buildQuery 会把这个参数整个省掉）
    isFinish: finish.value === '' ? undefined : Number(finish.value),
  }),
})

onLoad((options) => {
  categoryId.value = options?.id || ''
  // ★ name 是首页传的，encodeURIComponent 过 —— 解回来（中文分类名）
  categoryName.value = options?.name ? decodeURIComponent(options.name) : ''
})

onMounted(() => {
  // ★ 只有没带 name 时才去查（见文件头第 ② 条）
  if (!categoryName.value) loadCategoryName()
  load()
})

onReachBottom(() => {
  loadMore()
})

/**
 * 取分类名。
 *
 * ★ 失败【不弹提示】：它只是页面标题上的三个字，拿不到就退回「分类」。
 *   为它弹一个错误框，会盖在正常加载出来的列表上面，主次颠倒了。
 */
async function loadCategoryName() {
  try {
    const all = (await getCategoryList()) || []
    categoryName.value = all.find((c) => String(c.id) === String(categoryId.value))?.name || ''
  } catch {
    categoryName.value = ''
  }
}

/**
 * ★★ 筛选条件变了要 reload()，不能 load() —— 理由见文件头。
 *
 * ★ 两个筛选各写一个函数而不是在模板里内联 `sort = k; reload()`：
 *   内联的话「改了值忘了 reload」这种错**不会报错**，
 *   只是筛选项高亮了而列表没变 —— 又一个静默的谎。
 */
function pickSort(key) {
  if (key === sort.value) return
  sort.value = key
  reload()
}

function pickFinish(key) {
  if (key === finish.value) return
  finish.value = key
  reload()
}

/**
 * 失败态里那个「重试」。
 *
 * ★★ 必须在这里兜住 reject：`request.js` 提示完照样把 Promise 拒出去，
 *   模板里裸写 `@click="reload"` 就是一条 unhandledrejection ——
 *   控制台一片红，接了上报工具会被当成真错误。
 */
function retry() {
  reload().catch(() => {})
}
</script>

<template>
  <view class="category">
    <view class="head">
      <text class="head__title">{{ categoryName || '分类' }}</text>
      <!-- ★ loaded 才显示本数，否则首屏会闪一下「共 0 本」 -->
      <text v-if="loaded" class="head__count">共 {{ total }} 本</text>
    </view>

    <!-- ============ 筛选：排序 ============ -->
    <view class="filters">
      <view class="filter-row">
        <view
          v-for="item in SORT_OPTIONS"
          :key="item.key"
          class="chip"
          :class="item.key === sort ? 'chip--on' : ''"
          @click="pickSort(item.key)"
        >
          <text class="chip__text">{{ item.label }}</text>
        </view>
      </view>

      <view class="filter-row">
        <view
          v-for="item in FINISH_OPTIONS"
          :key="item.key"
          class="chip"
          :class="item.key === finish ? 'chip--on' : ''"
          @click="pickFinish(item.key)"
        >
          <text class="chip__text">{{ item.label }}</text>
        </view>
      </view>
    </view>

    <view v-if="list.length" class="cards">
      <view v-for="novel in list" :key="novel.id" class="cards__item">
        <NovelCard :novel="novel" />
      </view>
    </view>

    <StateHint
      :loading="loading"
      :is-empty="isEmpty"
      :failed="failed"
      empty-text="这个分类下还没有作品"
      failed-text="作品暂时加载失败"
      @retry="retry"
    />

    <!-- ★★ 底部提示条 —— 见首页同一段注释：追加失败绝不能清空已读内容 -->
    <view v-if="list.length" class="foot">
      <text v-if="loadMoreFailed" class="foot__retry" @click="loadMore">加载失败，点击重试</text>
      <text v-else-if="loading" class="foot__text">加载中…</text>
      <text v-else-if="finished" class="foot__text">没有更多了</text>
    </view>
  </view>
</template>

<style scoped>
.category {
  min-height: 100vh;
  padding: 20rpx 24rpx 40rpx;
  background: #f5f6f8;
}

.head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 8rpx 8rpx 20rpx;
}

.head__title {
  font-size: 36rpx;
  font-weight: 600;
  color: #1f2733;
}

.head__count {
  font-size: 24rpx;
  color: #9aa3b0;
}

/* ---------- 筛选 ---------- */
.filters {
  padding: 16rpx 16rpx 4rpx;
  margin-bottom: 20rpx;
  background: #fff;
  border-radius: 16rpx;
}

.filter-row {
  display: flex;
  flex-wrap: wrap;
  margin-bottom: 12rpx;
}

.chip {
  padding: 10rpx 24rpx;
  margin: 0 12rpx 12rpx 0;
  background: #f5f6f8;
  border-radius: 999rpx;
}

.chip--on {
  background: #e4edff;
}

.chip__text {
  font-size: 26rpx;
  color: #5a6472;
}

.chip--on .chip__text {
  color: #2f6fed;
}

/* ---------- 卡片列表 ---------- */
.cards {
  display: flex;
  flex-direction: column;
}

.cards__item {
  margin-bottom: 16rpx;
}

/* ---------- 底部提示条 ---------- */
.foot {
  display: flex;
  justify-content: center;
  padding: 24rpx 0 8rpx;
}

.foot__text {
  font-size: 24rpx;
  color: #9aa3b0;
}

.foot__retry {
  font-size: 24rpx;
  color: #2f6fed;
}
</style>
