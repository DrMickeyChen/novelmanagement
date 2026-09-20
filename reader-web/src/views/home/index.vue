<script setup>
/**
 * 首页 —— 分类入口 + 热门榜 + 最近更新。
 *
 * ★★ 这个页面替换掉了 P0 的「连通性验收页」。
 *   那个页面（三张卡片验证 应用/MySQL/Redis，外加一个演示三端登录态共享的区块）
 *   是 P0 阶段用来证明「脚手架是通的」的临时工具，它自己在注释里就写了
 *   「之后会被真正的首页替换掉」。P3 的读者端要做的是「找书 → 打开 → 读完三章」，
 *   首页必须是找书的入口。
 *   ★ 三个 /api/ping* 接口本身【没有删】，只是不再有页面专门展示它们 ——
 *     它们还在 Knife4j（/doc.html）和 P0 的回归用例里。
 *
 * ------------------------------------------------------------
 * ★ 三块数据是【三个独立请求】，互不阻塞
 * ------------------------------------------------------------
 * 分类、热门榜、最近更新来自三个不同的接口。这里刻意用「各自 loading、
 * 各自渲染」，而不是 Promise.all 等齐了再一起显示：
 * 榜单接口慢的时候，分类入口应该照常能点 —— 它们之间没有任何依赖关系。
 * 合成一个 loading 会让整个首页的可用时间等于最慢那个接口的耗时。
 */
import { onMounted, ref } from 'vue'
import { getCategoryList } from '@/api/category'
import { getNovelList, getRankList } from '@/api/novel'
import { usePagedList } from '@/composables/usePagedList'
import NovelCard from '@/components/NovelCard.vue'

/** 分类入口。★ 只有十来个，不分页 */
const categories = ref([])
const loadingCategories = ref(false)
const categoriesLoaded = ref(false)

/** 热门榜。★ 榜单接口不分页，固定取前 N 条 */
const hotList = ref([])
const loadingHot = ref(false)
const hotLoaded = ref(false)

/**
 * 最近更新 —— 这里是全站唯一用了分页的「最近更新」。
 *
 * ★ 首页为什么要分页：它是【发现页】，用户会一直往下翻。
 *   而榜单只给前 N 名（榜单排到第 50 名就没有意义了）。
 *   这两种列表的性质不同，所以一个用分页、一个不用。
 *
 * ★★ 解构出来用，不要留成 `const latest = usePagedList(...)` 再在模板里写
 *   `latest.list.value`。两个原因：
 *   ① 模板只对【顶层】的 ref 自动解包。`latest` 是个普通对象，
 *      模板里访问 `latest.list` 拿到的是 ref 对象本身，必须手写 `.value`
 *      ——而 `.value` 在模板里又长又容易被漏掉，漏了就把 ref 当数组渲染，
 *      v-for 一条都不出，还不报错。
 *   ② 解构后重命名成 latestXxx，模板里一眼能看出这个 list 属于哪一块。
 *
 *   依赖追踪不受影响：模板读 `latestList`（顶层 ref）时的取值仍然发生在
 *   渲染函数里，照样会被收集。
 */
const {
  list: latestList,
  total: latestTotal,
  page: latestPage,
  size: latestSize,
  loading: latestLoading,
  loaded: latestLoaded,
  load: loadLatest,
  changePage: changeLatestPage,
} = usePagedList(({ page, size }) => getNovelList({ page, size, sort: 'latest' }), {
  pageSize: 6,
})

/** 热门榜取 6 条：首页要的是「一眼看到最热的几本」，不是完整榜单 */
const HOT_LIMIT = 6

onMounted(() => {
  loadCategories()
  loadHot()
  loadLatest()
})

async function loadCategories() {
  loadingCategories.value = true
  try {
    categories.value = (await getCategoryList()) || []
  } catch {
    // 拦截器已经弹过提示了。这里保持空数组，页面显示空态即可，不再重复报错
    categories.value = []
  } finally {
    loadingCategories.value = false
    categoriesLoaded.value = true
  }
}

async function loadHot() {
  loadingHot.value = true
  try {
    hotList.value = (await getRankList({ type: 'hot', limit: HOT_LIMIT })) || []
  } catch {
    hotList.value = []
  } finally {
    loadingHot.value = false
    hotLoaded.value = true
  }
}
</script>

<template>
  <div class="home">
    <!-- ============ ① 分类入口 ============ -->
    <el-card shadow="never" class="section">
      <template #header>
        <div class="section-head">
          <span class="section-title">分类</span>
        </div>
      </template>

      <div v-loading="loadingCategories" class="category-grid">
        <router-link
          v-for="item in categories"
          :key="item.id"
          :to="`/category/${item.id}`"
          class="category-item"
        >
          {{ item.name }}
        </router-link>
      </div>

      <!--
        ★ loaded 的用法见 composables/usePagedList.js 开头那段：
          不加「查过了」这个条件，首屏会出现「加载动画和空态同时显示」。
      -->
      <el-empty
        v-if="categoriesLoaded && !categories.length"
        description="还没有任何分类"
        :image-size="60"
      />
    </el-card>

    <!-- ============ ② 热门榜 ============ -->
    <el-card shadow="never" class="section">
      <template #header>
        <div class="section-head">
          <span class="section-title">🔥 热门</span>
          <router-link to="/rank" class="more">完整榜单 →</router-link>
        </div>
      </template>

      <div v-loading="loadingHot" class="grid">
        <NovelCard v-for="novel in hotList" :key="novel.id" :novel="novel" />
      </div>

      <el-empty v-if="hotLoaded && !hotList.length" description="暂无作品" :image-size="60" />
    </el-card>

    <!-- ============ ③ 最近更新（分页） ============ -->
    <el-card shadow="never" class="section">
      <template #header>
        <div class="section-head">
          <span class="section-title">最近更新</span>
        </div>
      </template>

      <div v-loading="latestLoading" class="grid">
        <NovelCard v-for="novel in latestList" :key="novel.id" :novel="novel" />
      </div>

      <el-empty v-if="latestLoaded && !latestList.length" description="暂无作品" :image-size="60" />

      <!--
        ★ 总数不超过一页时不显示分页控件。
          只有 3 条数据却挂着一条分页栏，看起来像页面没做完。
          ★ v-if 用 total > size 而不是 total > 0：后者在「一共 2 条、
          每页 6 条」时也会显示一个只有 1 页的分页控件。
      -->
      <div v-if="latestTotal > latestSize" class="pager">
        <!--
          ★★ layout 里必须带 total，否则页面上【根本不会出现「共 N 条」】——
             默认的 layout 只有 prev/pager/next，是纯页码条。
             而「共 N 条」正是 S7 这一步要验证的东西（中文语言包有没有生效）：
             带 total 之后它渲染的是「共 13 条」，没装 zh-cn 语言包时是
             "Total 13"。这是全项目唯一一处能一眼看出语言包是否生效的地方 ——
             el-empty / el-pagination 的其它文案在只有一页时都不显示。
        -->
        <el-pagination
          layout="total, prev, pager, next"
          background
          :current-page="latestPage"
          :page-size="latestSize"
          :total="latestTotal"
          @current-change="changeLatestPage"
        />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.home {
  padding: 20px 0;
}

.section {
  margin-bottom: 16px;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
}

.more {
  font-size: 13px;
  color: #409eff;
}

.category-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  /* 给 v-loading 的遮罩留出高度：内容为空时遮罩没有高度会看不见 */
  min-height: 40px;
}

.category-item {
  padding: 6px 16px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  font-size: 14px;
  color: #606266;
  background: #fafafa;
}

.category-item:hover {
  border-color: #409eff;
  color: #409eff;
  background: #ecf5ff;
}

/*
 * ★ 自适应网格：一行最多 2 张卡片（首页宽度上限 960px，卡片本身信息不多，
 *   放 3 张会显得很空）。用 auto-fill + minmax 而不是写死列数，
 *   这样窄屏（手机）会自动掉成 1 列，不需要额外的媒体查询。
 */
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
  /* 同上：给 v-loading 的遮罩留高度 */
  min-height: 80px;
}

.pager {
  display: flex;
  justify-content: center;
  margin-top: 18px;
}
</style>
