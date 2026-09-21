/**
 * ECharts 的**按需引入**注册。
 *
 * ============================================================
 * ★★ 全项目只有这一个文件 import echarts
 * ============================================================
 * 组件（components/EChart.vue）从这里拿的是**已经注册好、可以直接 init 的**
 * echarts 对象。这么做是为了让「打包进多少东西」这个决定只出现在一个地方 ——
 * 散在各个组件里 `import * as echarts from 'echarts'` 的话，
 * 半年后没人说得清包里那一兆是从哪来的。
 *
 * ============================================================
 * ★★ 为什么不用 `import * as echarts from 'echarts'`
 * ============================================================
 * 那是**全量引入**：所有图表类型（地图、桑基图、树图、K 线、
 * 关系图……十几个）+ 所有组件 + 两套渲染器，压缩后 ~1MB。
 * 本项目只用两种图，剩下的连一次都不会执行到。
 *
 * ★ 代价是**新加一种图必须回来补一行 `use([...])`**，
 *   不补的话表现是：
 *     控制台一条警告 → 图上什么都不显示（不是报错、不是白屏，
 *     就是那个图的位置空着）。而且开发期热更新时有时候是对的
 *     （别的 chunk 恰好把那个图的实现带进来了），刷新一下又没了。
 *   所以下面的清单里每一项都写了「谁在用」。
 *
 * ============================================================
 * ★★ 注册清单和用途一一对应，用完就能看出哪一行可以删
 * ============================================================
 */
import * as echarts from 'echarts/core'

/* 图表类型 */
import { LineChart, PieChart } from 'echarts/charts'
/*
 * ★ 只引这两种：
 *     LineChart —— 大盘的 30 天新增趋势折线图
 *     PieChart  —— 作品 / 章节两个状态分布饼图
 *
 * ★ 刻意【没有】BarChart：柱状图在这个项目里一次都没用到。
 *   如果哪天要加「按分类的作品数」柱状图，回来补 `BarChart` 这一项。
 */

/* 组件（坐标轴、tooltip 这些不是图表类型，是组件） */
import {
  GridComponent, // 直角坐标系。★ 折线图没它的话图整个不显示，且只有一条警告
  TooltipComponent, // 鼠标悬停的高亮提示
  LegendComponent, // 图例。★ 饼图的图例也在这一项里
} from 'echarts/components'

/* 渲染器 */
import { CanvasRenderer } from 'echarts/renderers'
/*
 * ★ Canvas 而不是 SVG：
 *   本项目最复杂的图是 30 个点的折线 + 两个五扇区的饼，canvas 足够，
 *   而且 canvas 在数据点多（趋势图切到 90 天）时优势更大。
 *   SVG 的好处是能拿 CSS 改样式、能选中文字，这里用不上。
 */

/*
 * ★ 刻意【没有】引 TitleComponent：
 *   图表的标题用外层的 <el-card> 的 #header 写，比 echarts 自己画的
 *   title 更好排版（能用 HTML、能放按钮）。
 *   在 option 里写 `title: {...}` 而没注册这个组件的话，
 *   标题【静默不显示】—— 图本身是好的，只是没标题。
 */

echarts.use([
  LineChart,
  PieChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  CanvasRenderer,
])

export default echarts
