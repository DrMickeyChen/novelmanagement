<script setup>
/**
 * 排行榜（tab 2）—— 热门 / 新书 / 完结三个榜。
 *
 * ------------------------------------------------------------
 * ★★ 这一页【刻意不用 usePagedList】，理由要记住
 * ------------------------------------------------------------
 * `GET /api/novels/rank` 返回的是**裸数组**，不是 `{ records, total }`。
 * 榜单的语义就是「前 N 名」—— 排到第 60 名的东西不叫榜，它不需要分页。
 *
 * 套 usePagedList 会怎样：那个 composable 读的是 `data?.records`，
 * 拿到 `undefined` → 空列表 → 页面显示「榜单上还没有作品」。
 * **接口 200、数据也在，但页面是空的**，而且不报任何错。
 *
 * ------------------------------------------------------------
 * ★★ 与桌面端的一处形态差异：Tab 状态存在本地 ref，不在 URL 里
 * ------------------------------------------------------------
 * reader-web 那份把当前榜【从 route.query 推导】出来，理由是
 * 「URL 是唯一的状态来源」（分享链接、后退键都能正确还原）。
 *
 * 移动端做不到，也不该做：这是 **tabBar 页面**，而
 * `uni.switchTab` **不接受 query 参数**，tabBar 页的 `onLoad` 拿不到 options。
 * 一个存不进 URL 的状态，做成"从 URL 推导"只会得到一个永远等于默认值的 computed。
 *
 * ★ 代价如实记账：移动端**不支持**"把某个榜的链接发给别人"。
 *   这在手机上本来也不是常见的动作（没有地址栏），所以这个代价是可接受的 ——
 *   但它是真实存在的，不是"桌面端那套没必要"。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { getRankList } from '@/api/novel'
import NovelCard from '@/components/NovelCard.vue'
import StateHint from '@/components/StateHint.vue'

/**
 * ★ type 的取值必须和后端 NovelServiceImpl.rank 的 switch 分支一致
 *   （hot / new / finished）。写错了不报错 —— 后端默认走 hot，
 *   现象是「点了新书榜，顺序没变」。
 */
const RANK_TABS = [
  { key: 'hot', label: '热门榜', desc: '按阅读量排序' },
  { key: 'new', label: '新书榜', desc: '按上架时间排序' },
  { key: 'finished', label: '完结榜', desc: '已完结作品，按阅读量排序' },
]

/** ★ 取满上限 50：榜单不需要翻页，但需要"够长" */
const RANK_LIMIT = 50

const activeTab = ref('hot')

const list = ref([])
const loading = ref(false)
const loaded = ref(false)

/**
 * ★★ 本次加载是不是**失败**了。
 *
 * ★ 本页不用 usePagedList（见文件头），所以拿不到它那对
 *   `isEmpty` / `failed` —— 这里自己存一个，语义与它完全一致，
 *   连 `loaded` 的用法都照抄：区分「还没查」和「查过但为空」。
 *
 * ★ M5 之前 catch 里只做 `list = []`，于是停掉 content 之后
 *   榜单页显示的是【「榜单上还没有作品」】—— 而真相是服务挂了。
 *   而且榜单页尤其像真的：一个「还没有作品」的排行榜，
 *   听起来完全可能是「站里还没数据」。
 */
const failed = ref(false)

const activeDesc = computed(() => RANK_TABS.find((t) => t.key === activeTab.value).desc)

onMounted(load)

/**
 * ★ watch 的是 activeTab。这里没有桌面端那个「认不出的 type」的问题
 *   （Tab 只能通过点击切换，值一定是三个 key 之一），
 *   所以不需要"认不出就回落 hot"那一段。
 */
watch(activeTab, load)

/**
 * ★★ 从别的页面切回来时静默刷新一次。
 *
 * 起因：读完一本书回到排行榜，阅读量可能已经变了（M3 起阅读量是
 * 异步聚合的，5 秒落一次库），但页面上还是切走之前那份数据 ——
 * **看起来像"阅读量根本没涨"**。
 *
 * ★ 这里**不置 loading**（所以不会闪），失败也不进失败态：
 *   它只是"顺手更新一下"，失败了屏幕上那份旧数据仍然是好的，
 *   把它换成「暂时加载失败」是把一次后台刷新变成了故障。
 * ★★ 这个区别正是 `load()` 和 `refreshQuietly()` 分成两个函数的原因 ——
 *    合成一个就必须传参控制，而那种参数迟早会被传错。
 */
onShow(() => {
  // 首次进入由 onMounted 负责（那时 onShow 也会触发一次，会重复请求）——
  // ★ loaded 就是那个"已经查过一次了"的标志
  if (loaded.value) refreshQuietly()
})

onPullDownRefresh(async () => {
  try {
    await load()
  } finally {
    uni.stopPullDownRefresh()
  }
})

async function load() {
  loading.value = true
  // ★ 和 usePagedList 的 load() 一样：每次开查先清掉上一次的失败，
  //   否则重试成功之后页面还停在失败态上
  failed.value = false
  try {
    list.value = (await getRankList({ type: activeTab.value, limit: RANK_LIMIT })) || []
  } catch {
    // 拦截器已经弹过提示了。★ 但「弹过提示」不等于页面可以说谎 ——
    // 下面那一行才是让失败在界面上有自己样子的东西
    list.value = []
    failed.value = true
  } finally {
    loading.value = false
    loaded.value = true
  }
}

/** 静默刷新：不动 loading、不进失败态。见 onShow 上面那段 */
async function refreshQuietly() {
  try {
    list.value = (await getRankList({ type: activeTab.value, limit: RANK_LIMIT })) || []
  } catch {
    // 保持屏幕上那份旧数据。★ 这里【故意】什么都不做
  }
}

/** 失败态里的「重试」。★ 必须兜住 reject，理由见各列表页同名的那个函数 */
function retry() {
  load().catch(() => {})
}

function switchTab(key) {
  if (key === activeTab.value) return
  activeTab.value = key
}
</script>

<template>
  <view class="rank">
    <!-- ============ 榜单切换 ============ -->
    <view class="tabs">
      <view
        v-for="tab in RANK_TABS"
        :key="tab.key"
        class="tab"
        :class="tab.key === activeTab ? 'tab--on' : ''"
        @click="switchTab(tab.key)"
      >
        <text class="tab__text">{{ tab.label }}</text>
      </view>
    </view>

    <text class="desc">{{ activeDesc }}</text>

    <!-- ============ 榜单 ============ -->
    <view v-if="list.length" class="board">
      <view v-for="(novel, index) in list" :key="novel.id" class="row">
        <!--
          ★ 名次徽章。前三名单独配色 —— 这是榜单页唯一比列表页多的信息，
            没有它这一页就只是「按某个字段排过序的列表」。
          ★ index + 1：榜单没有分页，所以下标就是名次。
            如果哪天榜单要分页了，这里必须改成 (page - 1) * size + index + 1，
            否则第二页会从「第 1 名」重新开始 —— 那看起来非常荒唐。
        -->
        <text class="rank-no" :class="index < 3 ? 'rank-no--top' : ''">{{ index + 1 }}</text>
        <view class="row__card">
          <NovelCard :novel="novel" />
        </view>
      </view>
    </view>

    <!--
      ★★ 失败态单独一支。这一页尤其像真的 ——
        「榜单上还没有作品」听起来完全可能是「站里还没数据」，
        所以它比别的页面更难被发现是谎话。
        ★ 判据和 usePagedList 的 isEmpty 一致：`!failed` 那半个条件不能少，
          因为 catch 里 list 也被清空了。
    -->
    <StateHint
      :loading="loading"
      :is-empty="loaded && !failed && !list.length"
      :failed="failed"
      empty-text="榜单上还没有作品"
      failed-text="榜单暂时加载失败"
      @retry="retry"
    />
  </view>
</template>

<style scoped>
.rank {
  min-height: 100vh;
  padding: 20rpx 24rpx 40rpx;
  background: #f5f6f8;
}

/* ---------- 切换条 ---------- */
.tabs {
  display: flex;
  padding: 6rpx;
  background: #fff;
  border-radius: 12rpx;
}

.tab {
  flex: 1;
  padding: 14rpx 0;
  text-align: center;
  border-radius: 8rpx;
}

.tab--on {
  background: #2f6fed;
}

.tab__text {
  font-size: 28rpx;
  color: #5a6472;
}

.tab--on .tab__text {
  color: #fff;
}

.desc {
  display: block;
  padding: 16rpx 8rpx 20rpx;
  font-size: 24rpx;
  color: #9aa3b0;
}

/* ---------- 榜单行 ---------- */
.board {
  display: flex;
  flex-direction: column;
}

.row {
  display: flex;
  align-items: center;
  margin-bottom: 16rpx;
}

/*
 * ★ 名次徽章固定宽度 + flex:none，卡片占满剩下的一行。
 *   `flex: none` 不能少 —— 不加的话徽章会被压成一条窄缝，
 *   两位数名次（10~50）会挤成竖排。
 */
.rank-no {
  flex: none;
  width: 56rpx;
  text-align: center;
  font-size: 30rpx;
  font-weight: 600;
  color: #b6bdc7;
}

/* 前三名 */
.rank-no--top {
  color: #e6801f;
}

.row__card {
  flex: 1;
  /*
   * ★★ min-width: 0 —— flex 子项默认 min-width:auto，不加这一条的话
   *   卡片里那个 `text-overflow: ellipsis` 的书名会把整行撑宽，
   *   而不是被省略号截断。这条和 NovelCard 里那条是同一个原因的两端。
   */
  min-width: 0;
}
</style>
