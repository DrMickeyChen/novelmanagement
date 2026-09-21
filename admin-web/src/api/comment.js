/**
 * 评论管理接口 —— 对应后端 AdminCommentController（/api/admin/comments）。
 *
 * ------------------------------------------------------------
 * ★★ 管理端【没有】删除评论的接口，这不是漏了
 * ------------------------------------------------------------
 * 管理员处置违规评论的手段是**隐藏**（`status = 2`），不是删除：
 *   · 隐藏会留痕（`auditUserId` / `auditTime` / `auditRemark`）—— 谁在什么时候
 *     因为什么把它藏了，查得到；删除什么都不留
 *   · 隐藏**可逆**（已隐藏 → 已显示是合法流转），删除只能进 MySQL 改
 *   · 读者侧确实有删除（`DELETE /api/comments/{id}`），但那是**作者删自己的评论**，
 *     和管理员处置违规内容是两回事
 * 所以这一页的按钮只有「通过 / 隐藏」，没有「删除」。
 * 别因为「列表页通常有个删除按钮」就去后端补一个。
 *
 * ------------------------------------------------------------
 * ★★ 这一页会看到「已删除」的评论，那是刻意的
 * ------------------------------------------------------------
 * `GET /admin/comments` 是全项目**唯一**一个不滤 `deleted = 0` 的查询
 * （见后端 `NovelCommentMapper#selectAdminPage`）。理由是「读者的某条评论
 * 不见了」是一类真实诉求 —— 有人投诉一条评论，管理员打开列表却找不到它，
 * 因为评论人自己先删了。「读者看不见了」和「无从查证」是两件事。
 *
 * 但**它们的 `status` 是改不了的**：后端审核走 `selectById`（带 `deleted = 0`），
 * 对已删除的评论返回 `4001 评论不存在`。所以页面上那一行的「审核」按钮
 * 必须是禁用的，并说明原因 —— 否则管理员会对一条已经不存在的内容反复点隐藏，
 * 每次都拿到 4001，却看不出为什么。
 */
import request from '@/utils/request'

/**
 * 评论列表（分页）。
 *
 * @param {object} params
 * @param {number} [params.status] 0 待审核 / 1 已显示 / 2 已隐藏。★ 不传 = 全部
 *   （★ 传 `null` 而不是空串。axios 会跳过 null/undefined 参数，
 *     正好对上后端「不传 = 全部」；传空串会变成 `?status=`，
 *     后端把空串转 Integer 会失败）
 * @param {number} [params.novelId] 只看某本书的评论。★ 只筛**书评**
 *   （后端在 SQL 里带了 `target_type = 1`）—— 章评 P5 没有写入路径
 * @param {string} [params.keyword] 正文模糊匹配
 * @param {number} [params.page]
 * @param {number} [params.size] 上限 100（后端 PaginationInnerInterceptor 夹的）
 * @returns {Promise<{records: Array, total: number}>}
 *   ★ 每条记录带 `status`（数字，用来判断）和 `statusDesc`（中文，用来显示）
 *     —— 和作品/章节列表同一条规矩：**判断用数字，显示用 Desc**。
 *   ★ 带 `deleted`（布尔）。**这一列的渲染不能省** ——
 *     不显示的话，已删除的评论和正常评论看起来一模一样。
 *   ★ `nickname` 为空时**整个 key 会消失**（全局 non_null），
 *     写 `row.nickname ?? '已注销用户'`，不要写 `=== null`。
 */
export function getCommentPage(params) {
  return request.get('/admin/comments', { params })
}

/**
 * 审核评论：通过（→ 已显示）或隐藏（→ 已隐藏）。
 *
 * ★★ 路径是 `/{id}/status`，body 只有 `{ status, remark }` ——
 *   **没有 `pass: true/false`**。作品/章节的审核接口用的是布尔 `pass`，
 *   评论用的是目标状态码，两个形状不一样，别照着 `api/novel.js` 抄。
 *
 * ★ 只接受 `1` / `2`：
 *   · 传 `0`（改回待审核）会被后端明确拒绝并给出专门的文案 ——
 *     状态机里没有任何一条边指向「待审核」，评论从读者眼前消失、
 *     又在待审队列里多出一条，是纯粹的脏数据
 *   · 传 `3` 会得到 400（**本项目没有「已删除」这个状态**。
 *     早先的方案里写过「0待审 → 1通过 / 2驳回 / 3删除」，
 *     那是 `CommentStatus` 定稿之前的说法）
 *
 * ★ **重复审核同一条返回 `4005`，不是静默成功。** 所以界面上
 *   「当前状态已经是目标状态」的那个按钮要被禁用（见页面里的
 *   `passDisabled` / `rejectDisabled`）—— 让用户点到一个必然报错的按钮，
 *   他会以为系统坏了。
 *
 * ★ `remark` 可选（≤255 字）。和章节驳回**不同**，这里不强制：
 *   评论人看不到审核意见（P5 没有任何反馈通道），强制填写只会得到
 *   一堆「违规」这种填了等于没填的内容，而它看起来像一条有效记录。
 *   它的定位是**管理员自己的备注**：同一批违规评论连续处理时，
 *   它是「上次为什么隐藏这一条」的唯一记录。
 *
 * ★ 审核人会记进 `audit_log`（`targetType = 3 评论`），
 *   在「审核日志」页查得到，标题显示的是**评论正文的前 30 字**。
 *
 * @param {number} id 评论 id
 * @param {1|2} status 目标状态：1 已显示 / 2 已隐藏
 * @param {string} [remark] 备注，可选
 */
export function auditComment(id, status, remark) {
  return request.put(`/admin/comments/${id}/status`, { status, remark })
}
