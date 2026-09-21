/**
 * 章节相关接口 —— 对应后端 AdminChapterController（/api/admin/chapters）。
 *
 * 目录约定（§四）：每个业务模块一个文件，接口路径只在这里出现一次。
 *
 * ★★ 章节审核和作品审核有一个**真实的不对称**，前端也要跟着区别对待：
 *   作品审核 → 后端必须先判「当前是不是待审核」（因为状态机允许 已下架 → 已上架，
 *              那是「重新上架」走另一个接口，光靠状态机挡不住
 *              「对一本已下架的书点审核通过」）
 *   章节审核 → 后端不用判（章节没有「已下架」状态，状态机本身就限死了）
 *   → 结果是一样的：**审核按钮只对 status === 1 的行显示**。
 *     作品那边是后端兜底，章节那边是状态机兜底，但前端都不该给出
 *     一个「点了必然报错」的按钮。
 *
 * ★★ 这里【没有】「下架章节」的接口，而且不该有。
 *   章节不能单独下架，只能跟着作品一起下架 —— 因为读者是顺着作品进来的，
 *   作品在架上、某一章却不见了，读者看到的是「目录里有一章点不开」。
 *   要撤掉某一章的内容用**驳回**；要把整本书撤下来用作品的上下架接口。
 *   前端不要为了「方便」自己拼一个删章节的调用。
 */
import request from '@/utils/request'

/**
 * 章节列表（审核队列）。
 *
 * ★ **跨作品**，含全部状态。每一行都带 `novelTitle`（书名）——
 *   列表里混着好几本书的章节，不显示书名审核员根本不知道该审的是哪本。
 *   结果按 `update_time` 倒序。
 *
 * ★ **不返回正文** —— 正文只在 `getChapterDetail` 里返回（点开某一章时才调）。
 *   审核列表一页 10 条，带上正文就是一页几百 KB，而审核员一次只看一章。
 *
 * @param {object} params
 * @param {number} [params.novelId] 只看某一本书的章节
 * @param {number} [params.status] 0草稿 1待审核 2已发布 4已驳回。★ 没有 3
 * @param {number} [params.page]
 * @param {number} [params.size]
 */
export function getChapterPage(params) {
  return request.get('/admin/chapters', { params })
}

/**
 * 章节详情（含正文）。
 *
 * ★★ **审核员必须先看到正文才能审** —— 只看标题点通过等于没审。
 *   这一条决定了审核弹窗里必须有一块正文预览区，不能只列标题。
 *
 * ★ 同时也带 `auditRemark`（历史驳回理由）。
 */
export function getChapterDetail(id) {
  return request.get(`/admin/chapters/${id}`)
}

/**
 * 审核章节：通过 / 驳回。
 *
 * ★ 通过后章节变**已发布**，读者立刻能在目录里看到、能读正文。
 *   通过时后端会在**同一个事务内**重算作品的章节数和总字数 ——
 *   所以审核通过的瞬间，作品列表里那本书的「共 12 章 / 25.6 万字」就变了。
 *   **每审完一章，作品列表那一行的数字会变，这是正常的。**
 *
 * @param {number} id
 * @param {{pass: boolean, remark?: string}} data
 */
export function auditChapter(id, data) {
  return request.put(`/admin/chapters/${id}/audit`, data)
}
