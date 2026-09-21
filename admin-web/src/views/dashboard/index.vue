<script setup>
/**
 * 数据大盘 —— 管理端的首页（`/admin/`）。
 *
 * ============================================================
 * ★★ 三块数据、三个请求、各自 loading
 * ============================================================
 * 卡片 / 趋势 / 分布走三个独立请求，互不等待（理由见 api/dashboard.js）。
 * 所以这里**没有**一个总的 `loading`，是 `statsLoading` / `trendLoading` /
 * `distLoading` 三个 —— 合成一个的话，慢的那个会把快的也压住，
 * 又回到了「后端为什么要拆三个接口」要避免的那件事。
 *
 * ============================================================
 * ★★「规模指标」和「待办队列」是两种东西，不能混排成一行
 * ============================================================
 * 八个数字看着像一排，其实是两类：
 *
 *   规模（4 个）：作品总数 / 用户总数 / 评论总数 / 今日新增用户
 *     —— 只会变大（除非删库）。看它是为了知道「系统里有多少东西」。
 *   待办（4 个）：待审作品 / 待审章节 / 待审评论 / 待处理作者申请
 *     —— 是**要人去处理的队列**。看它是为了知道「现在该干什么」。
 *
 * 混在一排八张卡片里，管理员扫一眼分不出「17 本作品」和「1 本待审」
 * 哪个需要他动手 —— 而这一页存在的意义就是后者。
 * 所以分成两段，待办那段在待办数 > 0 时**整体变色**，
 * 扫一眼就知道「今天有活」还是「今天没事」。
 *
 * ============================================================
 * ★★ 待办卡片点进去 = 直接去处理
 * ============================================================
 * 待办的唯一用途就是「点进去把它清掉」。所以每张待办卡片是一个链接，
 * 指向能处理它的那个页面。
 *
 * ★ 指向还不存在的页面时**不加链接**，做成不可点而不是「点了 404」。
 *   P5 的 S10（评论管理）和 S12（作者申请）做完之后，四张待办卡片
 *   就都指到真实页面了 —— 模板里那个 `v-else`「页面还没做」的兜底
 *   因此**暂时不会渲染**。留着它，是因为它表达的是「这张卡还没有可去的地方」
 *   这件事本身：下一张没页面的卡加进来时会直接命中，
 *   而不是先出现一个点了没反应的卡片，等有人报 bug 再补。
 *
 * ============================================================
 * ★★ 后端的 `default-property-inclusion: non_null` 在这里会咬人
 * ============================================================
 * 值为 null 的字段在 JSON 里**整个不存在**。所以取值一律 `?? 0`。
 * 不兜的话，将来某个指标查不出来时卡片会显示**空白**，
 * 而「空白」和「真的是 0」在界面上长得一模一样 ——
 * 管理员会以为「一本作品都没有」而不是「这个数字没查出来」。
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  getDashboardStats,
  getDashboardTrend,
  getDashboardStatusDist,
} from '@/api/dashboard'
import EChart from '@/components/EChart.vue'

const router = useRouter()

/* ==================== 三块数据 ==================== */

const stats = ref(null)
const statsLoading = ref(false)

/** ★ 初始化成空数组而不是 null：模板里直接 `.days` 不会炸，图也是空图不是报错 */
const trend = ref({ days: [], novels: [], users: [], comments: [] })
const trendLoading = ref(false)
/** 趋势天数。★ 上限 90 是后端的夹取上限，别传更大的（会被夹掉但白白让人以为生效了） */
const trendDays = ref(30)

const dist = ref({ novels: [], chapters: [] })
const distLoading = ref(false)

async function loadStats() {
  statsLoading.value = true
  try {
    stats.value = await getDashboardStats()
  } catch {
    /*
     * ★ 空 catch。失败时 request.js 已经弹过 ElMessage 了（这是非 silent 请求），
     *   这里再弹一次就是两条一样的红字。
     *   不写 catch 的话会是一个 unhandled rejection —— 控制台一片红，
     *   看起来像页面崩了，其实只是后端没起来。
     */
  } finally {
    statsLoading.value = false
    markUpdated()
  }
}

async function loadTrend() {
  trendLoading.value = true
  try {
    trend.value = await getDashboardTrend(trendDays.value)
  } catch {
    // 同上
  } finally {
    trendLoading.value = false
    markUpdated()
  }
}

async function loadDist() {
  distLoading.value = true
  try {
    dist.value = await getDashboardStatusDist()
  } catch {
    // 同上
  } finally {
    distLoading.value = false
    markUpdated()
  }
}

onMounted(() => {
  /*
   * ★ 三个都不 await：并发发出去，各自回来各自渲染。
   *   逐个 await 的话总耗时是三者之和，而且第一个慢的话后面两个
   *   连请求都还没发出去 —— 那三个接口拆开的收益就全没了。
   */
  loadStats()
  loadTrend()
  loadDist()
})

function refreshAll() {
  loadStats()
  loadTrend()
  loadDist()
}

/** 切天数。★ 只重拉趋势，不重拉卡片和饼图（它们和天数无关） */
function changeTrendDays(days) {
  trendDays.value = days
  loadTrend()
}

/* ==================== 数字格式化 ==================== */

/**
 * 千分位。
 *
 * ★ 大盘上的数字会长到四五位（评论总数、用户总数），
 *   不加分隔符的话「12345」和「1234」扫一眼分不出来。
 */
function fmtNum(n) {
  return Number(n ?? 0).toLocaleString('zh-CN')
}

/**
 * 「最后更新」时刻。
 *
 * ★ 这是快照数据，不显示时间的话没人知道页面刷没刷过 ——
 *   一个开着不动的大盘和一小时前刷过的长得一模一样。
 *
 * ★ 三块各自到货时都会刷一次，所以它显示的是「最近一次有数据回来」的时刻。
 *   这是有意的松口径：显示成「三块都成功」的时刻的话，
 *   只要有一块一直失败，时间就永远不更新，而这恰恰是更该被看见的状态。
 */
const updatedAt = ref('')

function markUpdated() {
  const d = new Date()
  const p = (v) => String(v).padStart(2, '0')
  updatedAt.value = `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/* ==================== 卡片定义 ==================== */

/**
 * 规模指标。
 *
 * ★ `to` 是「点进去看明细」的去处。指向还不存在的页面时留空 ——
 *   留空 = 卡片不可点（不是「点了 404」）。
 */
const SCALE_CARDS = computed(() => [
  { key: 'novelTotal', label: '作品总数', icon: '📖', value: stats.value?.novelTotal, to: '/novel' },
  { key: 'userTotal', label: '用户总数', icon: '👥', value: stats.value?.userTotal, to: '/user' },
  { key: 'commentTotal', label: '评论总数', icon: '💬', value: stats.value?.commentTotal, to: '/comment' },
  { key: 'userToday', label: '今日新增用户', icon: '✨', value: stats.value?.userToday, to: '/user' },
])

/**
 * 待办队列。★ 顺序 = 处理它们的优先级（作品 > 章节 > 评论 > 申请）
 */
const TODO_CARDS = computed(() => [
  { key: 'novelPending', label: '待审作品', icon: '📕', value: stats.value?.novelPending, to: '/audit' },
  { key: 'chapterPending', label: '待审章节', icon: '📄', value: stats.value?.chapterPending, to: '/audit' },
  { key: 'commentPending', label: '待审评论', icon: '🚩', value: stats.value?.commentPending, to: '/comment' },
  { key: 'authorApplyPending', label: '待处理作者申请', icon: '📝', value: stats.value?.authorApplyPending, to: '/author-apply' },
])

/** 待办总数。★ 用来决定那一段要不要「亮起来」 */
const todoTotal = computed(() =>
  TODO_CARDS.value.reduce((sum, c) => sum + Number(c.value ?? 0), 0),
)

function goto(card) {
  if (!card.to) return
  router.push(card.to)
}

/* ==================== 趋势图 ==================== */

/** 三条线的固定颜色。★ 写死而不是让 echarts 分配 —— 见下面饼图那段同样的理由 */
const SERIES = [
  { name: '新增作品', key: 'novels', color: '#409eff' },
  { name: '新增用户', key: 'users', color: '#67c23a' },
  { name: '新增评论', key: 'comments', color: '#e6a23c' },
]

const trendOption = computed(() => {
  const days = trend.value.days ?? []

  return {
    tooltip: { trigger: 'axis' },
    legend: {
      top: 0,
      data: SERIES.map((s) => s.name),
    },
    grid: { left: 8, right: 20, top: 44, bottom: 8, containLabel: true },
    xAxis: {
      type: 'category',
      // ★ 时间轴不两边留空：首尾两个点落在坐标轴端点上，符合折线图的读数习惯
      boundaryGap: false,
      data: days,
      /*
       * ★ 90 天时 90 个日期全画出来会糊成一团黑。hideOverlap 让 echarts
       *   自动抽稀（隔几个画一个），比人为设 interval 更稳 ——
       *   人为间隔在容器变窄时还是会重叠。
       */
      axisLabel: { hideOverlap: true },
    },
    yAxis: {
      type: 'value',
      /*
       * ★★ 必须设 minInterval: 1。
       *   统计的是【条数】，是整数。不设的话 echarts 会按数值范围自己挑刻度，
       *   最大值只有 2 的时候会画出 0 / 0.5 / 1 / 1.5 / 2 ——
       *   「新增 0.5 本作品」看起来像个真数字，其实毫无意义。
       */
      minInterval: 1,
    },
    series: SERIES.map((s) => ({
      name: s.name,
      type: 'line',
      color: s.color,
      data: trend.value[s.key] ?? [],
      /*
       * ★★ 数据点标记：天数少的时候【必须显示】。
       *
       * 30 天里可能只有 3 天有数据，其余 27 天是 0。折线会把这些点连起来，
       * 一个只出现一天的非零值（1 个点，左右都是 0）在图上是一个
       * **很小的尖角** —— 不画标记的话很容易被当成 0 划过去。
       *
       * ★ 90 天时反过来要关掉：270 个标记点会把图糊成一片。
       *   而且 90 天的跨度本来就只看趋势，不看单日。
       */
      showSymbol: days.length <= 31,
      symbol: 'circle',
      symbolSize: 6,
      /*
       * ★★ 不要加 `connectNulls`。
       *   那个选项是给「真的缺数据」（null）用的 —— 这里缺的是 **0**，
       *   后端已经零填充过了，不存在 null。
       *   加上它，万一将来后端漏填某天（变成 null），
       *   echarts 会把断档**连成一条直线** —— 正好是零填充要防的那件事。
       */
    })),
  }
})

/* ==================== 状态分布饼图 ==================== */

/**
 * 扇区颜色的**唯一来源**。
 *
 * ★★ 按 `status` 数字取色，**不要**按数组下标取色。
 *   按下标的话，某天某个状态变成 0 —— 但它仍然会返回（零填充），
 *   所以下标其实不会错位。真正会错位的是「后端将来加一个状态」，
 *   那时所有扇区的颜色会整体平移一格，而**没人会注意到颜色变了**。
 *   按 status 取色则永远不会错位，加新状态时也只会是那一个新扇区
 *   落到下面的兜底色上。
 *
 * ★ 取值和 utils/status.js 里 el-tag 的颜色对齐（灰/橙/绿/红），
 *   这样「大盘上那个橙色的扇区」和「列表里那个橙色的标签」
 *   说的是同一件事。两处要一起改。
 */
const NOVEL_SLICE_COLOR = {
  0: '#909399', // 草稿
  1: '#e6a23c', // 待审核
  2: '#67c23a', // 已上架
  3: '#a8abb2', // 已下架（比草稿更浅，表示「曾经上过」）
  4: '#f56c6c', // 已驳回
}

/** ★ 章节**没有 3「已下架」**（章节不能单独下架），这张表少一项是对的 */
const CHAPTER_SLICE_COLOR = {
  0: '#909399', // 草稿
  1: '#e6a23c', // 待审核
  2: '#67c23a', // 已发布
  4: '#f56c6c', // 已驳回
}

/**
 * 拼一个饼图的 option。
 *
 * ★★ 图例里**带上数量**（`已下架 0`），这是零填充能被看见的唯一位置。
 *   一个 count 为 0 的分类在饼图里**没有面积**，扇区画不出来、
 *   鼠标也 hover 不到，只能靠图例说明。
 *   这正是后端大费周章返回 `count: 0` 的原因 ——
 *   图例里少了这一条，「已下架 0 本」和「系统里没有这个状态」
 *   就长得一模一样了。
 */
function pieOption(items, sliceColor) {
  const data = items.map((it) => ({
    name: it.label,
    value: it.count,
    itemStyle: { color: sliceColor[it.status] ?? '#c0c4cc' },
  }))

  return {
    tooltip: { trigger: 'item', formatter: '{b}：{c}（{d}%）' },
    legend: {
      bottom: 0,
      // ★ 显式给 data：保证 count 为 0 的分类也占一行图例
      data: items.map((it) => it.label),
      // ★ 图例文字后面缀上数量。0 也是数量，要显示出来
      formatter: (name) => {
        const hit = items.find((it) => it.label === name)
        return hit ? `${name} ${hit.count}` : name
      },
    },
    series: [
      {
        type: 'pie',
        radius: ['42%', '66%'],
        // ★ 圆心略微上移，给底部图例让位（图例占了两三行）
        center: ['50%', '44%'],
        /*
         * ★ 不在扇区上画标签：5 个分类的外置标签在窄卡片里会互相压。
         *   数量交给图例，明细交给 tooltip —— hover 时 emphasis 会放大那一块。
         */
        label: { show: false },
        emphasis: { label: { show: false }, scaleSize: 8 },
        data,
      },
    ],
  }
}

const novelPieOption = computed(() => pieOption(dist.value.novels ?? [], NOVEL_SLICE_COLOR))
const chapterPieOption = computed(() => pieOption(dist.value.chapters ?? [], CHAPTER_SLICE_COLOR))

/** 分布的总数。★ 全为 0 时画出来是个空圆环，不如直接说「还没有数据」 */
function sumCount(items) {
  return (items ?? []).reduce((s, it) => s + Number(it.count ?? 0), 0)
}
const novelTotal = computed(() => sumCount(dist.value.novels))
const chapterTotal = computed(() => sumCount(dist.value.chapters))
</script>

<template>
  <div>
    <!-- ==================== 通用工具条 ==================== -->
    <div class="toolbar">
      <span class="toolbar-tip">
        数字来自同一条 SQL 的同一瞬间快照，不含已删除的数据。
        <template v-if="updatedAt">最后更新 {{ updatedAt }}</template>
      </span>
      <el-button :loading="statsLoading || trendLoading || distLoading" @click="refreshAll">
        刷新
      </el-button>
    </div>

    <!-- ==================== ① 规模指标 ==================== -->
    <div class="section-title">
      规模
      <span class="section-sub">系统里一共有多少东西</span>
    </div>
    <el-row :gutter="12" class="cards">
      <el-col v-for="c in SCALE_CARDS" :key="c.key" :xs="12" :sm="8" :md="6">
        <!--
          ★ 只有带 to 的卡片才可点。做成 <div> 而不是「点了没反应」的
            el-card —— 后者会让人以为页面卡了。
        -->
        <el-card
          shadow="hover"
          class="stat"
          :class="{ clickable: !!c.to }"
          @click="goto(c)"
        >
          <div class="stat-icon">{{ c.icon }}</div>
          <div class="stat-value">{{ fmtNum(c.value) }}</div>
          <div class="stat-label">{{ c.label }}</div>
          <div v-if="c.to" class="stat-more">查看明细 →</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- ==================== ② 待办队列 ==================== -->
    <div class="section-title">
      待办
      <span class="section-sub">
        需要处理的队列
        <template v-if="todoTotal > 0">
          —— 共 <b class="hot">{{ todoTotal }}</b> 项
        </template>
        <template v-else>—— 已清空</template>
      </span>
    </div>
    <el-row :gutter="12" class="cards">
      <el-col v-for="c in TODO_CARDS" :key="c.key" :xs="12" :sm="8" :md="6">
        <!--
          ★★ 待办数 > 0 时整张卡片变色（todo 类）。
             「规模」和「待办」八张卡片长得一样的话，扫一眼分不出
             哪个数字是要自己动手的 —— 而这一页的意义就是后者。
        -->
        <el-card
          shadow="hover"
          class="stat"
          :class="{ todo: Number(c.value ?? 0) > 0, clickable: !!c.to }"
          @click="goto(c)"
        >
          <div class="stat-icon">{{ c.icon }}</div>
          <div class="stat-value">{{ fmtNum(c.value) }}</div>
          <div class="stat-label">{{ c.label }}</div>
          <div v-if="c.to" class="stat-more">去处理 →</div>
          <!-- ★ 还没做页面的那两项：明说为什么点不动，别让人以为是坏的 -->
          <div v-else class="stat-more muted">页面还没做</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- ==================== ③ 趋势 ==================== -->
    <el-card shadow="never" class="block">
      <template #header>
        <div class="card-header">
          <span>新增趋势</span>
          <!--
            ★ 用 el-radio-button 而不是下拉框：只有三档，摊平了一眼能看出
              当前是哪一档，也少一次点击。
            ★ Element Plus 2.14 用 :value，不是已废弃的 :label。
          -->
          <el-radio-group
            :model-value="trendDays"
            size="small"
            @change="changeTrendDays"
          >
            <el-radio-button :value="7">近 7 天</el-radio-button>
            <el-radio-button :value="30">近 30 天</el-radio-button>
            <el-radio-button :value="90">近 90 天</el-radio-button>
          </el-radio-group>
        </div>
      </template>

      <div v-loading="trendLoading">
        <!--
          ★★ 零填充说明必须写在界面上。
            这图看起来和「每天都有人在新增」的正常折线长得一样，
            看的人不会知道中间那 20 天是 0。不写这句话，
            下次有人看到「平坦的 0 段」会以为是数据丢了来查。
        -->
        <p class="hint">
          没有新增的那天是 <b>0</b>，而且照样画在图上 ——
          不会跳过。所以线贴到 0 轴意味着<b>那天真的没有新增</b>，
          不是数据缺失。
        </p>
        <EChart :option="trendOption" height="300px" />
      </div>
    </el-card>

    <!-- ==================== ④ 状态分布 ==================== -->
    <el-row :gutter="12">
      <el-col :xs="24" :md="12">
        <el-card shadow="never" class="block">
          <template #header>
            <div class="card-header">
              <span>作品状态分布</span>
              <el-tag size="small" type="info" effect="plain">共 {{ fmtNum(novelTotal) }} 本</el-tag>
            </div>
          </template>
          <div v-loading="distLoading">
            <!-- ★ 全为 0 时画出来是个空圆环，不如直说「还没有数据」 -->
            <el-empty v-if="novelTotal === 0" description="还没有作品" :image-size="70" />
            <EChart v-else :option="novelPieOption" height="280px" />
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :md="12">
        <el-card shadow="never" class="block">
          <template #header>
            <div class="card-header">
              <span>章节状态分布</span>
              <el-tag size="small" type="info" effect="plain">共 {{ fmtNum(chapterTotal) }} 章</el-tag>
            </div>
          </template>
          <div v-loading="distLoading">
            <el-empty v-if="chapterTotal === 0" description="还没有章节" :image-size="70" />
            <EChart v-else :option="chapterPieOption" height="280px" />
          </div>
          <!--
            ★ 章节没有「已下架」这一档，图例里也就不会出现它。
              这不是漏了 —— 章节不能单独下架（下架是作品级动作）。
              不写这句的话，会有人来问「章节的下架数据去哪了」。
          -->
          <p class="hint">
            章节没有「已下架」这一档 —— 下架是<b>作品级</b>动作，
            章节跟着作品走，不能单独下线。
            <br />
            数量为 0 的状态会以「名称 0」出现在图例里，而不是消失。
          </p>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.toolbar-tip {
  font-size: 12px;
  color: #909399;
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  margin: 4px 0 10px;
}

.section-sub {
  font-weight: 400;
  font-size: 12px;
  color: #909399;
  margin-left: 6px;
}

.hot {
  color: #e6a23c;
}

.cards {
  margin-bottom: 8px;
}

/*
 * ★ el-col 自带左右 padding，垂直方向没有 —— 不加这行的话卡片会贴着
 *   下一段标题。同时 row :gutter 只横向生效，纵向间距要自己给。
 */
.cards :deep(.el-col) {
  margin-bottom: 12px;
}

.stat {
  text-align: center;
  cursor: default;
}

/* 可点的卡片给一个手型，和不可点的区分开 */
.stat.clickable {
  cursor: pointer;
}

.stat-icon {
  font-size: 22px;
  line-height: 1;
}

.stat-value {
  font-size: 26px;
  font-weight: 600;
  color: #303133;
  margin: 8px 0 2px;
  /* ★ 数字等宽：几张卡片并排时，「17」和「1,234」的宽度差会让数字对不齐 */
  font-variant-numeric: tabular-nums;
}

.stat-label {
  font-size: 13px;
  color: #606266;
}

.stat-more {
  font-size: 12px;
  color: #409eff;
  margin-top: 6px;
  height: 16px;
}

.stat-more.muted {
  color: #c0c4cc;
}

/*
 * ★★ 待办 > 0 的卡片：橙色边框 + 浅橙底。
 *   和「规模」那段区分开 —— 不区分的话八张卡片长得一样，
 *   管理员扫一眼分不出哪个数字是要自己动手的。
 */
.stat.todo {
  border-color: #f3d19e;
  background: #fdf6ec;
}

.stat.todo .stat-value {
  color: #e6a23c;
}

.block {
  margin-bottom: 12px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.hint {
  margin: 0 0 10px;
  font-size: 12px;
  color: #909399;
  line-height: 1.8;
}
</style>
