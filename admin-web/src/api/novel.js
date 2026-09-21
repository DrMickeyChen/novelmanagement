/**
 * 作品相关接口 —— 对应后端 AdminNovelController（/api/admin/novels）。
 *
 * 目录约定（§四）：每个业务模块一个文件，接口路径只在这里出现一次。
 *
 * ★ 这些接口挂在 /api/admin/** 下，后端 SecurityConfig 那一条
 *   （hasRole("ADMIN")）**自动覆盖它们** —— P5 加的管理端接口
 *   一条都不用在 SecurityConfig 里登记。这是 P1 就设计好的，
 *   别以为漏了而去补一条（补了会让人以为每条路径都要单独配）。
 *
 * ★ request.js 的响应拦截器已经把 Result 剥成 data 了：
 *   拿到 200 就直接是业务数据（PageResult / 数组 / id），不用再 .data.data。
 *
 * ★★ 这里【没有】接收 status 的编辑接口，也【没有】审核接口之外的改状态方式。
 *   后端刻意把「审核」和「上下架」分成两个接口（都到 PUBLISHED，
 *   但日志里分别记成 APPROVE / REPUBLISH）—— 前端也要跟着分：
 *   审核中心调 auditNovel，作品管理页调 changeNovelStatus。
 *   两者混用会让日志里再也说不清「这本书是自己上架的还是被审出来的」。
 */
import request from '@/utils/request'

/**
 * 作品列表（审核队列）。
 *
 * ★ **不过滤状态** —— 含草稿、待审核、已驳回、已下架，全部能查到。
 *   最常用的用法是 `?status=1` 拉出待审核队列。
 *   结果按 `update_time` 倒序，刚提交审核的书一定在最前面。
 *
 * @param {object} params
 * @param {number} [params.status] 0草稿 1待审核 2已上架 3已下架 4已驳回。不传=全部
 * @param {number} [params.categoryId]
 * @param {string} [params.keyword] 书名模糊匹配
 * @param {number} [params.page]
 * @param {number} [params.size] 上限 100（后端 PaginationInnerInterceptor 夹的）
 */
export function getNovelPage(params) {
  return request.get('/admin/novels', { params })
}

/**
 * 作品详情（审核用）。**不含目录**，目录走下面的 `getNovelCatalog`。
 *
 * ★ 比作者端多两样、少一样：
 *   多 → `intro` / `tags`（审核员要读完简介才能判断）
 *       以及 `auditRemark`（历史驳回理由 —— 审核员据此判断
 *        「上次为什么没过、这次改了没有」）
 *   少 → 归属校验（管理员审的就是别人的书，加归属校验等于审核功能不存在）
 *
 * ★★ **P6 起 `chapters` 不再跟着详情回来**（读者端、作者端、管理端三处一起改的，
 *   是接口分工：读者端那个是匿名高频入口，本来就不该拖整本目录）。
 *   要看目录必须**显式再调一次 `getNovelCatalog`**，
 *   而 `NovelPreview` 要的正是这两样，所以三个调用它的页面都要各接一次。
 *
 * ★ **仍然不要为列表里每一行都调详情**（N+1：一页 10 本书就是 11 个请求）。
 *   列表页只在点开某一本时才调。
 */
export function getNovelDetail(id) {
  return request.get(`/admin/novels/${id}`)
}

/**
 * 作品目录（**全量、含全部状态**）—— 审核用。
 *
 * @param {number|string} id 作品 id
 * @returns {Promise<object[]>} `ChapterVO` 数组，按 `chapterNo` 正序，**不分页**
 *
 * ★★ **审核员必须看到全部状态**（草稿/待审/已驳回/已发布），所以它不能复用
 *   `getChapterPage`（那个虽然也能按 novelId 筛，但是**分页**的、一页最多 100 条，
 *   几百章的书会**静默截断**，而「共 N 章」显示 100 是错的）。
 *
 * ★★ **`status` 是这个接口的重点，不是附赠**：「这本书能不能过」很大程度取决于
 *   它的章节审得怎么样了 —— 一本章节全是「草稿」的书，作品审核通过后
 *   读者进来只有一个空目录，「上架了等于没上架」。
 *
 * ★ 和详情接口挨着调（两个请求），原因是接口分工，不是性能问题 ——
 *   管理端详情是低频的登录后操作，和读者端那个匿名高频入口不是一回事。
 */
export function getNovelCatalog(id) {
  return request.get(`/admin/novels/${id}/catalog`)
}

/**
 * 审核作品：通过 / 驳回。
 *
 * ★ 只接受**待审核**的作品，其余状态一律返回 `2003`。
 *   所以审核中心只应该对 `status === 1` 的行显示「审核」按钮 ——
 *   对已上架的书显示审核按钮，点下去必然报错。
 *
 * ★ 通过后作品变**已上架**，读者立刻能搜到。
 *
 * @param {number} id
 * @param {{pass: boolean, remark?: string}} data
 *   `pass` 是**业务意图**（通过/驳回），不是目标状态 ——
 *   后端刻意不收 `status`，否则前端传个 0 就能一次「审核」把书打回草稿。
 */
export function auditNovel(id, data) {
  return request.put(`/admin/novels/${id}/audit`, data)
}

/**
 * 上架 / 下架。
 *
 * ★★ `status` 传**数字**（2 上架 / 3 下架），不要传 `"PUBLISHED"` 这类枚举名。
 *   实测传字符串会得到 HTTP 400（后端 NovelStatusDTO 的注释里有完整说明：
 *   `@JsonValue` 在枚举上是**双向生效**的，出参入参都是数字）。
 *   所以把列表接口返回的 `status` 原样回传即可，不用做任何映射。
 *   这里只放行 2 和 3 —— 传别的（含 0 草稿、1 待审核）后端返回 400。
 *
 * ★★ **下架会连带影响读者端：作品一下架，它的所有章节正文也立刻读不到了**
 *   （`GET /api/novels/{id}/chapters/{cid}` 返回 2001），整本书从读者端消失。
 *   这是它和「驳回某一章」的区别 —— 章节不能被单独下架，只能跟着作品走。
 *   前端弹确认框时必须把这句话说给管理员听。
 *
 * @param {number} id
 * @param {{status: 2|3, remark?: string}} data
 */
export function changeNovelStatus(id, data) {
  return request.put(`/admin/novels/${id}/status`, data)
}
