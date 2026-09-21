/**
 * 数据大盘接口 —— 对应后端 AdminDashboardController（/api/admin/dashboard）。
 *
 * ============================================================
 * ★★ 三个接口要发【三个请求】，不要合成一个
 * ============================================================
 * 后端刻意拆成了三个 GET，前端也必须分开调（不要在这里包一个
 * `getDashboard()` 一次性拿全）：
 *
 *   首屏要的是【卡片上的八个数字】，图表可以晚半秒。
 *   合在一起的话，趋势那个 GROUP BY 扫表慢一点，
 *   连卡片数字都跟着出不来 —— 用户看到的是整页空白，
 *   而不是「卡片已经好了，图在转圈」。
 *
 * 所以页面里三块各自有 loading 状态，各自到货各自渲染。
 *
 * ============================================================
 * ★★ 后端配了 `default-property-inclusion: non_null`
 * ============================================================
 * 值为 null 的字段**在 JSON 里整个不存在**，不是 `null`。
 * 所以取值一律用 `?? 0` / `?? []` 兜底，
 * **不要**写 `stats.novelTotal === null` 那种判断（那个永远不成立）。
 *
 * 这一条在 stats 上尤其要紧：将来某个指标查不出来时它会是 undefined，
 * 直接塞进模板会显示成空白，而不是 0 —— 空白和「真的是 0」
 * 在界面上长得一模一样，管理员会以为「一本作品都没有」。
 */
import request from '@/utils/request'

/**
 * 顶部卡片的八个数字（**同一条 SQL 查出来的同一瞬间快照**）。
 *
 * @returns {Promise<object>} 形如
 *   `{ novelTotal, novelPending, chapterPending, userTotal, userToday,
 *      commentTotal, commentPending, authorApplyPending }`
 *   ★ 八个都是数字，不会为 null。但按上面那条约定，取值时仍用 `?? 0`。
 *   ★ 全部**不含已删除的行**（后端每个子查询都自己写了 `deleted = 0`）。
 */
export function getDashboardStats() {
  return request.get('/admin/dashboard/stats')
}

/**
 * 最近 N 天的新增趋势（**已零填充**）。
 *
 * @param {number} [days=30] 天数。★ 后端会夹到 **1~90**，
 *   超限不报错（按 90 返回）。
 * @returns {Promise<{days: string[], novels: number[], users: number[], comments: number[]}>}
 *   ★★ `days` 是**连续无缺口**的日期轴，三个序列与它**等长**，
 *     按下标一一对应。**没有数据的那天是 0，不是被跳过** ——
 *     所以前端【不要】自己按日期去匹配，也【不要】做任何补零，
 *     后端已经补好了。再补一次只会把真实数据盖掉。
 *   ★ 因为已经补零了，**不要**给 series 加 `connectNulls` ——
 *     那是给「真的缺数据」用的，这里缺的是 0，加了一旦将来后端漏填
 *     反而会把断档连成斜线，正是零填充要防的那件事。
 */
export function getDashboardTrend(days = 30) {
  return request.get('/admin/dashboard/trend', { params: { days } })
}

/**
 * 作品 / 章节的状态分布（**已零填充**）。
 *
 * @returns {Promise<{novels: Array<{status: number, label: string, count: number}>,
 *                    chapters: Array<{status: number, label: string, count: number}>}>}
 *   ★★ 某种状态一条都没有时，仍然有一条 `count: 0`。
 *     所以**不要**在前端过滤 `count === 0` ——
 *     过滤掉之后，饼图图例里那个分类会**彻底消失**，
 *     于是「已下架 0 本」和「系统里没有这个状态」长得一模一样。
 *   ★ 两个数组长度**不同**（作品 5 项、章节 4 项），这是对的：
 *     **章节没有「已下架」**（章节不能单独下架）。
 *   ★ `label` 是后端枚举给的文案，前端**不要**再维护一份 0→'草稿' 的映射。
 */
export function getDashboardStatusDist() {
  return request.get('/admin/dashboard/status-dist')
}
