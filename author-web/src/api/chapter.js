/**
 * 章节相关接口 —— 对应后端 AuthorChapterController。
 *
 * ★ URL 有两段前缀，不是笔误：
 *   /author/novels/{novelId}/chapters   列表和新建（要带上书 —— 章节属于某本书）
 *   /author/chapters/{id}               详情 / 保存 / 提交 / 删除（已经在操作具体章节）
 *   后端那边也是这么分的，两边保持一致。
 */
import request from '@/utils/request'

/**
 * 某本书的章节列表（含全部状态，**不含正文**）。
 *
 * @param {number} novelId
 * @param {object} [params]
 * @param {number} [params.status] 0草稿 1待审核 2已发布 4已驳回。★ 没有 3。不传=全部
 * @returns {Promise<Array>} 已按章节序号升序排好，前端直接渲染
 */
export function getChapters(novelId, params) {
  return request.get(`/author/novels/${novelId}/chapters`, { params })
}

/**
 * 新建章节。
 *
 * ★ 章节序号由后端分配（MAX+1），请求体里传不了。
 * ★ `content` 可以不传 —— 允许「先建好章节占位，正文回头再写」。
 * @returns {Promise<number>} 新章节的 id
 */
export function createChapter(novelId, data) {
  return request.post(`/author/novels/${novelId}/chapters`, data)
}

/** 章节详情（**含正文**，打开编辑器时才调） */
export function getChapterDetail(id) {
  return request.get(`/author/chapters/${id}`)
}

/**
 * 保存章节（标题 + 正文全量覆盖）。
 *
 * ★ 已发布的章节改正文会被后端**自动退回草稿**（状态机不允许已发布直接改内容，
 *   否则读者正在看的正文会被静默改掉）。调用方保存后要重新拉一次详情，
 *   否则界面上显示的还是「已发布」。
 *
 * ★ 待审核期间保存会返回 2003 —— 审核员正看着 A 版、作者换成 B 版，
 *   审核就被绕过了。
 */
export function saveChapter(id, data) {
  return request.put(`/author/chapters/${id}`, data)
}

/** 提交审核：草稿 / 已驳回 → 待审核。★ 正文为空时返回 3005 */
export function submitChapter(id) {
  return request.post(`/author/chapters/${id}/submit`)
}

/** 撤回提交：待审核 → 草稿（P4 补的） */
export function withdrawChapter(id) {
  return request.post(`/author/chapters/${id}/withdraw`)
}

/**
 * 删除章节。**任何状态都能直接删，没有「先下架」这一说**（章节没有下架状态），
 * 已发布的也能删 —— 删掉之后作品的章节数 / 字数会跟着重算。
 *
 * ★ 逻辑删除，同时把 chapter_no 改成 -id 把坑位让出来
 *   （负数段是删除标记专用的，所以正常序号永远拿不到负数）。
 * ★ 序号是 MAX(现存序号)+1，所以：
 *     删掉**最后一章**再建 → 复用那个序号（好，目录不会跳号）
 *     删掉**中间某章**再建   → 序号是 max+1，中间那个号就空着了
 *   这是刻意的 —— 「最后一章 +1」才会跳号，而跳号比空号显眼得多。
 * ★ 正文（chapter_content）**不删**，留着可追溯。
 */
export function deleteChapter(id) {
  return request.delete(`/author/chapters/${id}`)
}
