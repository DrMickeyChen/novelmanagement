<script setup>
/**
 * 排行榜 —— 热门 / 新书 / 完结三个 Tab。
 *
 * ------------------------------------------------------------
 * ★★ 三个 Tab 是【同一个页面 + query 参数】，不是三条路由
 * ------------------------------------------------------------
 * 用 /rank?type=new 而不是 /rank/new。两个好处：
 *
 * ① 切 Tab 不会重新挂载组件。三条路由的话每次切都走一遍
 *    组件销毁 → 重建 → onMounted → 发请求，而这三个榜拿的是
 *    同一个接口的同一份数据，没有任何需要重建的状态。
 *
 * ② ★ 更重要的是【不往浏览器历史里堆记录】。
 *    用 router.push 的话，用户点三下 Tab 再按返回键，
 *    要按三次才能回到上一个页面 —— 而他心里「上一步」只有一次。
 *    这里用 router.replace，切 Tab 不进历史。
 *
 * ★ 榜单【不分页】。榜单的语义就是「前 N 名」，
 *   排到第 60 名的东西不叫榜。所以这里不用 usePagedList。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getRankList } from '@/api/novel'
import NovelCard from '@/components/NovelCard.vue'

const route = useRoute()
const router = useRouter()

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

/**
 * 榜单条数。
 *
 * ★ 直接取满上限 50。榜单不需要「翻页」但需要「够长」——
 *   前 50 名足够一个读者慢慢挑，而且 50 条数据一次性返回
 *   （每条约 200 字节，总共 10KB 出头）比做成分页轻得多。
 *   ★ 后端会把超过 50 的值夹到 50，前端不依赖这个兜底，
 *     但也不去猜一个更大的数字。
 */
const RANK_LIMIT = 50

const list = ref([])
const loading = ref(false)
const loaded = ref(false)

/**
 * ★★ M5：本次加载是不是**失败**了。
 *
 * ★ 本页刻意不用 `usePagedList`（榜单不分页，见上面那段），
 * 所以拿不到它那对 `isEmpty` / `failed` —— 这里自己存一个，
 * 语义和 `usePagedList` 里的 `failed` 完全一致。
 *
 * ★ M5 之前 `catch` 里只做了 `list = []`，于是停掉 content 之后
 *   榜单页显示的是【「榜单上还没有作品」】—— 而真相是服务挂了。
 *   而且榜单页尤其像真的：一个「还没有作品」的排行榜，
 *   听起来完全可能是「站里还没数据」。
 */
const failed = ref(false)

/**
 * 当前 Tab —— 【从 URL 推导】，不是独立的 ref。
 *
 * ★★ 这是这个页面最关键的一处：如果把 activeTab 做成 ref，
 *   就会出现两份「当前是哪个榜」的真相（URL 里的和 ref 里的），
 *   而它们一定会不同步：
 *     - 用户从分享链接进来 /rank?type=finished → ref 还是默认的 hot
 *     - 用户在页面上按浏览器【后退】→ URL 变了，ref 没变
 *   两种情况下页面高亮的 Tab 和实际显示的榜单都对不上。
 *
 * ★ 认不出的 type 一律当 hot —— 和后端「传不认识的值不报错，
 *   按 hot 处理」的约定保持一致。前后端对同一个坏输入
 *   给出同一个结果，用户就不会看到「Tab 高亮热门、内容却是新书」。
 */
const activeTab = computed(() => {
  const type = route.query.type
  return RANK_TABS.some((t) => t.key === type) ? type : 'hot'
})

const activeDesc = computed(() => RANK_TABS.find((t) => t.key === activeTab.value).desc)

onMounted(load)

/**
 * ★ watch 的是推导出来的 activeTab，不是 route.query.type。
 *   直接 watch route.query.type 的话，`?type=xxx`（认不出）和
 *   `?type=hot` 会被当成两次变化，白白重查一遍，而两次结果完全一样。
 */
watch(activeTab, load)

/**
 * 切 Tab：只改 URL，不发请求。
 *
 * ★ 请求由上面的 watch 触发 —— 这是刻意的单向数据流：
 *   URL 是唯一的状态来源，任何改 URL 的方式（点 Tab、按后退、
 *   粘贴一个链接回车）都会走到同一条加载逻辑上。
 *   在 handleTab 里直接调 load() 的话，就只有「点 Tab」这一条路能加载，
 *   按后退键时列表不会跟着变。
 */
function handleTab(key) {
  router.replace({ name: 'rank', query: { type: key } })
}

async function load() {
  loading.value = true
  // ★ 和 usePagedList 的 load() 一样：每次开查都先把上一次的失败清掉，
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
    // ★ 和 usePagedList 里的 loaded 一个道理：区分「还没查」和「查过但为空」
    loaded.value = true
  }
}

/** 失败态里那个「重试」。★ 必须兜住 reject，理由见各列表页同名的那个函数 */
function retry() {
  load().catch(() => {})
}
</script>

<template>
  <div class="rank">
    <div class="page-head">
      <h2 class="title">排行榜</h2>
      <span class="desc">{{ activeDesc }}</span>
    </div>

    <!--
      ★ 用 el-radio-group 而不是 el-tabs：这三个榜是「选一个」，
        而且右边要跟一句说明文案，el-tabs 的下划线样式在这里没有意义。
    -->
    <el-radio-group
      :model-value="activeTab"
      size="default"
      class="tabs"
      @update:model-value="handleTab"
    >
      <el-radio-button v-for="tab in RANK_TABS" :key="tab.key" :value="tab.key">
        {{ tab.label }}
      </el-radio-button>
    </el-radio-group>

    <div v-loading="loading" class="board">
      <div v-for="(novel, index) in list" :key="novel.id" class="row">
        <!--
          ★ 名次徽章。前三名单独配色 —— 这是榜单页唯一比列表页多的信息，
            没有它这一页就只是「按某个字段排过序的列表」。
          ★ index + 1：榜单没有分页，所以下标就是名次。
            如果哪天榜单要分页了，这里必须改成 (page - 1) * size + index + 1，
            否则第二页会从「第 1 名」重新开始 —— 那看起来非常荒唐。
        -->
        <span class="rank-no" :class="{ top3: index < 3 }">{{ index + 1 }}</span>
        <NovelCard class="row-card" :novel="novel" />
      </div>
    </div>

    <!--
      ★★ M5：失败态单独一支。这一页尤其像真的 ——
        「榜单上还没有作品」听起来完全可能是「站里还没数据」，
        所以它比别的页面更难被发现是谎话。
        ★ 判据和 `usePagedList` 的 `isEmpty` 一致：`!failed` 那半个
          条件不能少，因为 catch 里 list 也被清空了。
    -->
    <el-empty v-if="loaded && !failed && !list.length" description="榜单上还没有作品" />
    <el-empty v-else-if="failed" description="榜单暂时加载失败">
      <el-button type="primary" plain @click="retry">重试</el-button>
    </el-empty>
  </div>
</template>

<style scoped>
.rank {
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

.desc {
  font-size: 13px;
  color: #909399;
}

.tabs {
  margin-bottom: 16px;
}

.board {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 80px;
}

.row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.rank-no {
  /* 固定宽度：名次从 1 位变 2 位时卡片不会整体错位 */
  width: 32px;
  flex: none;
  text-align: center;
  font-size: 18px;
  font-weight: 700;
  color: #c0c4cc;
}

.rank-no.top3 {
  color: #f56c6c;
}

/*
 * ★ 卡片要能占满剩下的宽度。NovelCard 根节点是 <a>，
 *   在 flex 容器里的默认 flex-shrink 会让它被名次挤窄，
 *   所以给它 flex: 1 + min-width: 0（min-width 不加的话，
 *   长书名撑出来的最小宽度会让这一行溢出容器）。
 */
.row-card {
  flex: 1;
  min-width: 0;
}
</style>
