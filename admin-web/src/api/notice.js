/**
 * 公告接口 —— 对应后端 AdminNoticeController（`/api/admin/notices`）。
 *
 * ★★ 读者端那个 `GET /api/notices` 是**另一个接口**，在
 *   `reader-web/src/api/notice.js`，不要混用：
 *   · 读者端：只返回已发布的，**匿名可访问**，参数是 `limit` 不是分页
 *   · 管理端：返回全部（含未发布的），**需 ADMIN**，是标准分页
 *
 * ------------------------------------------------------------
 * ★★ 这个模块最重要的一条：`publishTime` 不由前端决定
 * ------------------------------------------------------------
 * 入参里**没有** `publishTime`（后端 `NoticeSaveDTO` 里根本没这个字段）。
 * 它的值由后端根据 `status` 的**变化**算出来：
 *
 * | 这次的操作        | publishTime                            |
 * |-------------------|----------------------------------------|
 * | 新建时 status=1   | 盖当前时间                             |
 * | 新建时 status=0   | 保持空（草稿）                         |
 * | 草稿 → 发布       | 盖当前时间                             |
 * | 发布 → 下线       | **保留**原时间，不清空                 |
 * | 下线 → 再发布     | **重新**盖当前时间（公告重新置顶）     |
 * | 已发布 → 只改字   | **不动**（不会把旧公告顶到首页最上面） |
 *
 * 所以「发布 / 下线」不需要单独的接口 —— 就是 `updateNotice()`
 * 把 `status` 翻一下，其余字段原样带上（和分类的「启用 / 停用」同一套）。
 */
import request from '@/utils/request'

/**
 * 公告分页列表。
 *
 * @param {{status?:number, keyword?:string, page?:number, size?:number}} params
 *   `status`：1 发布 / 0 下线，不传 = 全部
 *   `keyword`：**标题**模糊匹配（不搜正文）
 * @returns {Promise<{records:object[], total:number, current:number, size:number, pages:number}>}
 *
 * ★ 排序是**状态升序 + id 倒序**：未发布的在最前面（这是个待办队列），
 *   同状态内最新建的在最前。前端不要自己再排一次。
 *
 * ★ 每条记录带 `content`（正文全文）—— 这个列表就是编辑弹窗的数据源，
 *   项目里没有「查单条公告」的接口。
 *
 * ★ 未发布的记录里 **`publishTime` 这个 key 会整个消失**
 *   （后端全局 `default-property-inclusion: non_null`），
 *   判空写 `row.publishTime` 就行，不要写 `row.publishTime === null`。
 */
export function getNoticePage(params) {
  return request.get('/admin/notices', { params })
}

/**
 * 新建公告。
 *
 * @param {{title:string, content:string, type?:number, status?:number}} data
 *   `type`：1 公告 / 2 通知，默认 1
 *   `status`：1 直接发布 / 0 存草稿，默认 1
 *
 * ★★ `type` 和 `status` **要显式传**，虽然它们有默认值：
 *   JSON 里写 `"status": null` 会**覆盖掉**后端那个字段默认值，
 *   而 `NoticeSaveDTO` 上挂了 `@NotNull` → 直接 400。
 *   后端这么严是有原因的：`status` 为 null 时 MP 会跳过这一列、
 *   由数据库默认值变成 1，而发布时间不会被盖上 ——
 *   库里就会出现一条「已发布但没有发布时间」的公告，
 *   它在读者端按发布时间倒序时**沉在最后**，两个界面看起来都正常。
 *
 * ★ 标题**允许重复**（「系统维护通知」一年要发好几次），
 *   所以没有「已存在」这类错误码。
 *
 * @returns {Promise<number>} 新公告的 id
 */
export function createNotice(data) {
  return request.post('/admin/notices', data)
}

/**
 * 编辑公告 —— **同时承担「发布 / 下线」**。
 *
 * ★★ 这是**全量覆盖**接口，`title` / `content` 都是必填。
 *   只传 `{ status: 0 }` 会得到 400「公告标题不能为空」。
 *   所以行内那个「发布 / 下线」按钮必须把原有字段一起带上 ——
 *   这正是本文件最容易写错的一处，见 `views/notice/index.vue#toggleStatus`。
 *
 * @param {number} id
 * @param {{title:string, content:string, type:number, status:number}} data
 */
export function updateNotice(id, data) {
  return request.put(`/admin/notices/${id}`, data)
}

/**
 * 删除公告（逻辑删除）。
 *
 * ★★ **「只想让它暂时不出现」应该用「下线」，不是删除**：
 *   下线保留数据、还能再发布（而且会重新置顶）；删掉就得重建一条。
 *   这句话必须写进确认框里，否则管理员一律用删除。
 *
 * ★ 删一条**已经不存在**的公告（别人先删了、你的列表是旧的）
 *   会返回 `7001`「公告不存在」而不是静默成功 —— 这是想要的，
 *   它告诉你「你看到的列表不是最新的」。
 *
 * @param {number} id
 */
export function deleteNotice(id) {
  return request.delete(`/admin/notices/${id}`)
}
