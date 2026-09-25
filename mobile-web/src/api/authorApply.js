/**
 * 作者申请 —— 对应后端 `ReaderAuthorApplyController`
 * （`/api/reader/author-apply`），移动端移植自 `reader-web/src/api/authorApply.js`。
 *
 * ★★ 两条都要登录。前缀族同 `reader.js`/`shelf.js`/`profile.js`。
 *
 * ★★ 后端路径**不是** `/api/author/**`，别照着「作者申请」四个字猜：
 *   这个接口的使用者**恰恰还不是作者**，归到 `/api/author/**` 下面
 *   将来会被 `hasAnyRole("AUTHOR")` 拦掉，变成「非作者不能申请成为作者」。
 */
import request from '@/utils/request'

/**
 * 提交申请。
 *
 * @param {{penName: string, intro?: string}} data
 *        `penName` **必填**（≤50 字）；`intro` 选填（≤500 字）
 * @returns {Promise<number>} 新申请的 id
 *
 * ★ **入参里没有 userId** —— 申请人从登录态取。让它成为参数就等于给了
 *   「替别人申请」的能力，而通过会给**那个人**发 AUTHOR 角色。
 *
 * ★ 两种业务失败（都是 **HTTP 200 + code**，别用状态码判）：
 *   `1009` 已经有一份在审核中（不会产生第二条记录）、`1015` 你已经是作者了。
 *   前端提交前用 `userStore.isAuthor` 挡一道，但**必须仍然接住这两个码** ——
 *   本地角色是登录那一刻写下的，管理员可能刚手工给过权限。
 *
 * ★ **被驳回之后可以重新提交**（那正是驳回的用途），交的是一份**新记录**，
 *   旧的留着作为历史 —— 所以这里没有「修改申请」，只有「再交一份」。
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
 * ★★ **从来没申请过时返回 `null`**（key 会整个消失 → 实际是 undefined）。
 *   取 `?? null`，**别写 `=== null`**。
 *
 * ★★ **`auditRemark` 是【给申请人看的驳回理由】**，必须显示出来。
 *   它和评论的 `auditRemark`（管理员自留备注、读者看不到）**正好相反** ——
 *   照抄评论那边的写法就会把这个字段藏起来，
 *   而那正是「驳回」这个功能存在的意义。
 */
export function getMyAuthorApply() {
  return request.get('/reader/author-apply/mine')
}
