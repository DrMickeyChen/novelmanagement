/**
 * 作者申请接口 —— 对应后端的 ReaderAuthorApplyController（`/api/reader/author-apply`）。
 *
 * ★★ 两个接口都【需要登录】。前缀族同 `api/reader.js` / `api/shelf.js` /
 *   `api/profile.js`（`/api/reader/**` 是「会落用户数据的接口」的约定前缀）。
 *
 * ------------------------------------------------------------
 * ★★ 为什么这个文件只有两个函数也要单独建
 * ------------------------------------------------------------
 * 本端每个 `api/*.js` 对着一个后端 Controller，这条规矩已经用了三次
 * （`shelf.js` / `profile.js` / 现在这个）。它的价值不在「文件多整齐」，
 * 而在**将来找代码的路径是唯一的**：想知道「申请作者都调了哪些接口」，
 * 不用先猜它可能被塞进了哪个文件。
 *
 * ★ 后端那两条路径**不是** `/api/author/**`，别照着「作者申请」四个字去猜。
 *   理由写在 `ReaderAuthorApplyController` 的类注释里（一句话版：
 *   这个接口的使用者**恰恰还不是作者**，归到 `/api/author/**` 下面
 *   将来会被 `hasAnyRole("AUTHOR")` 拦掉，变成「非作者不能申请成为作者」）。
 */
import request from '@/utils/request'

/**
 * 提交申请。
 *
 * @param {{penName: string, intro?: string}} data
 *        `penName` **必填**（笔名，≤50 字）；`intro` 选填（创作说明，≤500 字）
 * @returns {Promise<number>} 新申请的 id
 *
 * ★ **入参里没有 userId** —— 申请人从登录态取。让它成为参数就等于给了
 *   「替别人申请」的能力，而通过会给**那个人**发 AUTHOR 角色。
 *
 * ★ 两种业务失败（都是 **HTTP 200 + code**，别用状态码判）：
 *   - `1009` 已经有一份在审核中 → 不会产生第二条记录
 *   - `1015` 你已经是作者了
 *   前端在提交前就用 `userStore.isAuthor` 挡了一道，但**必须仍然接住这两个码** ——
 *   本地角色是登录那一刻写下的，管理员可能刚刚手工给过权限。
 *
 * ★ **被驳回之后可以重新提交**（那正是驳回的用途），提交的是一份**新记录**，
 *   旧的那份留着作为历史 —— 所以这里没有「修改申请」，只有「再交一份」。
 */
export function submitAuthorApply(data) {
  return request.post('/reader/author-apply', data)
}

/**
 * 查我最近一次的申请。
 *
 * @returns {Promise<{id, penName, intro, status, statusDesc, createTime,
 *                    auditTime, auditRemark}|null>}
 *        `status`：`0` 待审核 / `1` 已通过 / `2` 已驳回
 *
 * ★★ **从来没申请过时返回 `null`** —— 这不是错误，前端据此显示表单。
 *   注意后端配了 `default-property-inclusion: non_null`，`data` 这个 key
 *   会**整个从 JSON 里消失**，所以拿到的是 `undefined`。取值写 `?? null`，
 *   **不要写 `=== null`**（那个在 undefined 下永远不成立）。
 *
 * ★★ **`auditRemark` 是【给申请人看的驳回理由】**，必须显示出来。
 *   注意它和评论的 `auditRemark`（管理员自留备注、读者看不到）**正好相反** ——
 *   照抄评论那边的注释就会把这个字段藏起来，而那正是驳回这个功能的意义所在。
 *
 * ★ 返回的是**最近一份**（被驳回后重新提交会产生第二条记录）。
 *   ★ **这个接口不回答「我是不是作者」** —— 那个信息在登录态的
 *   `roles` 里（`userStore.isAuthor`）。边界情况（管理员手工加了 AUTHOR、
 *   本人从没申请过）这里返回 `null`，所以页面上**先判 `isAuthor` 再判这个**。
 */
export function getMyAuthorApply() {
  return request.get('/reader/author-apply/mine')
}
