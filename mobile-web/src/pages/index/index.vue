<script setup>
/**
 * 首页（tab 1）—— 搜索入口 + 分类 + 热门 + 最近更新。
 *
 * ★ 四块内容与 reader-web/src/views/home/index.vue **逐块对应**
 *   （分类 / 热门榜 / 最近更新 / 公告横幅），四块各自发请求、各自 loading。
 *
 * ★★ 移动端把"分页"换成了"上拉加载更多"，这是与桌面端最大的一处形态差异：
 *    桌面端最近更新用 el-pagination 翻页；移动端用 onReachBottom 滚到底继续接。
 *    理由见 composables/usePagedList.js 文件头 —— 手指本来就在做"往下滑"这件事，
 *    不该让它停下来去瞄准一个页码按钮。
 *
 * ★ 顶部多了一条**搜索框**（桌面端搜索在顶栏里，移动端没有顶栏那一条）——
 *   这是移动端的形态需要，不是新增功能：搜索页本身照旧。
 */
import { onMounted, ref } from 'vue'
import { onPullDownRefresh, onReachBottom } from '@dcloudio/uni-app'
import { getCategoryList } from '@/api/category'
import { getNovelList, getRankList } from '@/api/novel'
import { usePagedList } from '@/composables/usePagedList'
import NovelCard from '@/components/NovelCard.vue'
import NoticeBanner from '@/components/NoticeBanner.vue'
import StateHint from '@/components/StateHint.vue'

/** 热门榜取 6 条：首页要的是「一眼看到最热的几本」，不是完整榜单 */
const HOT_LIMIT = 6

/** 分类入口。★ 只有十来个，不分页 */
const categories = ref([])
const loadingCategories = ref(false)
const categoriesLoaded = ref(false)
const categoriesFailed = ref(false)

/** 热门榜。★ 榜单接口不分页，固定取前 N 条 */
const hotList = ref([])
const loadingHot = ref(false)
const hotLoaded = ref(false)
const hotFailed = ref(false)

/**
 * 最近更新 —— 全站唯一用了分页的「最近更新」（这里用追加模式）。
 *
 * ★ 解构出来用，理由与桌面端同名文件里那段相同：模板只对**顶层 ref**
 *   自动解包，留成 `const latest = usePagedList(...)` 再在模板里写
 *   `latest.list` 拿到的是 ref 对象本身，v-for 一条都不出**且不报错**。
 */
const {
  list: latestList,
  loading: latestLoading,
  isEmpty: latestIsEmpty,
  failed: latestFailed,
  loadMoreFailed: latestLoadMoreFailed,
  finished: latestFinished,
  load: loadLatest,
  loadMore: loadMoreLatest,
  reload: reloadLatest,
} = usePagedList(({ page, size }) => getNovelList({ page, size, sort: 'latest' }), {
  pageSize: 6,
  append: true,
})

onMounted(() => {
  loadCategories()
  loadHot()
  loadLatest()
})

/**
 * 滚到底 → 加载下一页。
 *
 * ★ 页面级滚动才有这个事件。○ 如果哪天把首页改成 `scroll-view` 包起来，
 *   这个事件**不会再触发**（scroll-view 有它自己的 @scrolltolower），
 *   而且不会有任何报错 —— 只是列表再也加载不出下一页。
 */
onReachBottom(() => {
  loadMoreLatest()
})

/**
 * 下拉刷新 —— 移动端的标准手势，桌面端没有对应物。
 *
 * ★ 必须调 `uni.stopPullDownRefresh()`，否则那个转圈会**永远停在屏幕上**。
 *   它要放在 finally 里：请求失败时同样要停，不然一次网络抖动
 *   就把页面卡在转圈状态，用户只能杀掉页面重进。
 */
onPullDownRefresh(async () => {
  try {
    await Promise.all([loadCategories(), loadHot(), reloadLatest()])
  } finally {
    uni.stopPullDownRefresh()
  }
})

/** ★ 每块各给一条重试，理由同桌面端：三块是三个请求，可能只挂一块 */
function retryCategories() {
  loadCategories().catch(() => {})
}

function retryHot() {
  loadHot().catch(() => {})
}

async function loadCategories() {
  loadingCategories.value = true
  categoriesFailed.value = false
  try {
    categories.value = (await getCategoryList()) || []
  } catch {
    /*
     * ★ 不能停在「空数组」上 —— 那等于说「这个站没有分类」，
     *   而真相是分类接口没通。M5 实测过这个谎话，
     *   在移动端它同样成立（而且手机屏幕小，用户更不容易察觉少了什么）。
     */
    categories.value = []
    categoriesFailed.value = true
  } finally {
    loadingCategories.value = false
    categoriesLoaded.value = true
  }
}

async function loadHot() {
  loadingHot.value = true
  hotFailed.value = false
  try {
    hotList.value = (await getRankList({ type: 'hot', limit: HOT_LIMIT })) || []
  } catch {
    hotList.value = []
    hotFailed.value = true
  } finally {
    loadingHot.value = false
    hotLoaded.value = true
  }
}

/** 去分类页。★ 把名字也带上：详情页没法只凭 id 显示标题（那要再查一次分类接口） */
function openCategory(item) {
  uni.navigateTo({ url: `/pages/category/category?id=${item.id}&name=${encodeURIComponent(item.name)}` })
}

/** 去搜索页 */
function openSearch() {
  uni.navigateTo({ url: '/pages/search/search' })
}

/**
 * 去完整榜单。
 *
 * ★★ 排行页是 **tabBar 页面**，必须用 `switchTab` —— 用 navigateTo
 *    会**静默失败**（uni 不允许 navigateTo 到 tabBar 页，只抛一个
 *    控制台警告，页面上什么都不发生）。这是 uni 最容易踩的一条。
 */
function openRank() {
  uni.switchTab({ url: '/pages/rank/rank' })
}
</script>

<template>
  <view class="home">
    <!-- ============ ⓪ 搜索入口 ============ -->
    <view class="searchbar" @click="openSearch">
      <text class="searchbar__icon">🔍</text>
      <text class="searchbar__ph">搜书名、作者名</text>
    </view>

    <!--
      ============ ① 公告横幅 ============
      ★ 它**自己发请求**（组件内 onMounted），不占本页的 loading 状态 ——
        公告拉不到不该让首页别的区块跟着转圈。
      ★ 没有公告时它整个不渲染，对版面零影响（见组件文件头）。
    -->
    <NoticeBanner />

    <!-- ============ ② 分类入口 ============ -->
    <view class="section">
      <view class="section__head">
        <text class="section__title">分类</text>
      </view>

      <view v-if="categories.length" class="cats">
        <view
          v-for="item in categories"
          :key="item.id"
          class="cat"
          hover-class="cat--active"
          :hover-stay-time="80"
          @click="openCategory(item)"
        >
          <text class="cat__text">{{ item.name }}</text>
        </view>
      </view>

      <!--
        ★★ 失败态带重试、空态不带 —— 两者由 StateHint 内部的两支渲染，
           页面只负责把 usePagedList 给的判据（或这里同形的本地判据）传进去。
           ★ 本地这两块（分类 / 热门）不是 usePagedList（接口返回裸数组），
             所以 failed / isEmpty 是页面自己维护的 —— 判据必须**照抄**
             usePagedList 里那两条的定义，尤其 isEmpty 里的 `!failed`。
      -->
      <StateHint
        :loading="loadingCategories"
        :is-empty="categoriesLoaded && !categoriesFailed && !categories.length"
        :failed="categoriesFailed"
        empty-text="还没有任何分类"
        failed-text="分类暂时加载失败"
        @retry="retryCategories"
      />
    </view>

    <!-- ============ ③ 热门榜 ============ -->
    <view class="section">
      <view class="section__head">
        <text class="section__title">🔥 热门</text>
        <text class="section__more" @click="openRank">完整榜单 →</text>
      </view>

      <view v-if="hotList.length" class="cards">
        <view v-for="novel in hotList" :key="novel.id" class="cards__item">
          <NovelCard :novel="novel" />
        </view>
      </view>

      <StateHint
        :loading="loadingHot"
        :is-empty="hotLoaded && !hotFailed && !hotList.length"
        :failed="hotFailed"
        empty-text="暂无作品"
        failed-text="热门暂时加载失败"
        @retry="retryHot"
      />
    </view>

    <!-- ============ ④ 最近更新（上拉加载更多） ============ -->
    <view class="section">
      <view class="section__head">
        <text class="section__title">最近更新</text>
      </view>

      <view v-if="latestList.length" class="cards">
        <view v-for="novel in latestList" :key="novel.id" class="cards__item">
          <NovelCard :novel="novel" />
        </view>
      </view>

      <StateHint
        :loading="latestLoading"
        :is-empty="latestIsEmpty"
        :failed="latestFailed"
        empty-text="暂无作品"
        failed-text="最近更新暂时加载失败"
        @retry="reloadLatest"
      />

      <!--
        ★★ 底部提示条 —— 只在【列表已经有内容】时才可能出现这四种。
           这是追加模式特有的：滑到第 5 页时网断了，绝不能把上面
           已经读过的 30 条换成一句「暂时加载失败」（那正是
           usePagedList 里 `failed` 加了 `list.length === 0` 的原因）。
      -->
      <view v-if="latestList.length" class="foot">
        <text v-if="latestLoadMoreFailed" class="foot__retry" @click="loadMoreLatest">
          加载失败，点击重试
        </text>
        <text v-else-if="latestLoading" class="foot__text">加载中…</text>
        <text v-else-if="latestFinished" class="foot__text">没有更多了</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
.home {
  min-height: 100vh;
  padding: 20rpx 24rpx 40rpx;
  background: #f5f6f8;
}

/* ---------- 搜索入口 ---------- */
.searchbar {
  display: flex;
  align-items: center;
  height: 76rpx;
  padding: 0 24rpx;
  margin-bottom: 20rpx;
  background: #fff;
  border-radius: 999rpx;
}

.searchbar__icon {
  margin-right: 12rpx;
  font-size: 28rpx;
}

.searchbar__ph {
  font-size: 28rpx;
  color: #b6bdc7;
}

/* ---------- 区块 ---------- */
.section {
  padding: 24rpx;
  margin-bottom: 20rpx;
  background: #fff;
  border-radius: 16rpx;
}

.section__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20rpx;
}

.section__title {
  font-size: 30rpx;
  font-weight: 600;
  color: #1f2733;
}

.section__more {
  font-size: 24rpx;
  color: #2f6fed;
}

/* ---------- 分类格 ---------- */
.cats {
  display: flex;
  flex-wrap: wrap;
  /*
   * ★ 用负 margin 抵消每个格子的左右外边距，让第一列和最后一列
   *   贴齐容器边缘。不加的话两侧会各多出 8rpx 的空隙，看起来像没对齐。
   */
  margin: 0 -8rpx;
}

.cat {
  /*
   * ★ 四列：每格 25% 宽（含 padding），把内容盒压到 100%。
   *   用百分比而不是固定 rpx —— 固定宽度在大屏手机（比如 428pt）上
   *   会排不满一行，右边留一条空白。
   * ★ box-sizing 在 uni 里默认是 content-box，所以必须显式写 border-box，
   *   否则 padding 会让每个格子超过 25%，一行只能放下三个。
   */
  box-sizing: border-box;
  width: 25%;
  padding: 8rpx;
}

.cat__text {
  display: block;
  padding: 16rpx 0;
  text-align: center;
  font-size: 26rpx;
  color: #5a6472;
  background: #f5f6f8;
  border-radius: 10rpx;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cat--active .cat__text {
  background: #e4edff;
  color: #2f6fed;
}

/* ---------- 卡片列表 ---------- */
.cards {
  display: flex;
  flex-direction: column;
}

/*
 * ★ 相邻卡片之间只留间距、不加分隔线：卡片本身有白底 + 圆角，
 *   再画线就变成"表格"的样子了。
 *
 * ★★ 间距加在**外层包一层的 view** 上，而不是直接给 `.novel-card` 加：
 *    给子组件根节点写样式要依赖「Vue 3 会把父组件的 scope id 也加到
 *    子组件根元素上」这条规则 —— 它在 H5 上成立，但在小程序端
 *    经过 uni 的编译之后**不保证**，而失败方式是静默的（间距没了而已，
 *    没人会为此报错）。包一层是零依赖的写法。
 */
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
