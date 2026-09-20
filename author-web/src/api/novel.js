/**
 * 作品相关接口 —— 对应后端 AuthorNovelController（/api/author/novels）。
 *
 * 目录约定（§四）：每个业务模块一个文件，接口路径只在这里出现一次。
 *
 * ★ 这些接口全部需要 AUTHOR 或 ADMIN 角色（后端 SecurityConfig 的
 *   /api/author/** 那一条），而且「能不能改这本书」还要过归属校验 ——
 *   不是自己的书会拿到 code 2002。
 *
 * ★ request.js 的响应拦截器已经把 Result 剥成 data 了：
 *   拿到 200 就直接是业务数据（PageResult / 数组 / id），不用再 .data.data。
 *
 * ★ 这里【没有】接收 status 的写接口。改状态只有 submit / withdraw 两个
 *   业务动作（后端刻意不提供 PUT /{id} {"status": 2}），
 *   否则「直接改状态」会绕过整套审核流程。
 */
import request from '@/utils/request'

/**
 * 我的作品列表（含草稿 / 待审核 / 已驳回）。
 *
 * @param {object} params
 * @param {number} [params.status] 0草稿 1待审核 2已上架 3已下架 4已驳回。不传=全部
 * @param {number} [params.page]
 * @param {number} [params.size]
 */
export function getMyNovels(params) {
  return request.get('/author/novels', { params })
}

/**
 * 新建作品。
 *
 * ★ 新建的**永远是草稿**，`status` 和 `authorId` 传了也没用（DTO 上没这两个字段）。
 * @returns {Promise<number>} 新作品的 id
 */
export function createNovel(data) {
  return request.post('/author/novels', data)
}

/** 作品详情（作者视角，目录含全部状态的章节） */
export function getNovelDetail(id) {
  return request.get(`/author/novels/${id}`)
}

/**
 * 编辑作品信息。
 *
 * ★ 状态不允许编辑时后端返回 2003：待审核期间不能改（否则审核员看的是 A 版、
 *   通过的是 B 版），已上架也不能直接改（要改先下架）。
 *   能编辑的只有 0 草稿 / 3 已下架 / 4 已驳回。
 *   → 调用方要在进页面之前就用 status 判断，别让作者填完一整页表单才被拒绝。
 */
export function updateNovel(id, data) {
  return request.put(`/author/novels/${id}`, data)
}

/**
 * 删除作品。**名下有章节时返回 2005**，请先删章节。
 *
 * ★ 只是想暂停对外应该用「下架」，不是删除 —— 提示语里要说清这一点。
 */
export function deleteNovel(id) {
  return request.delete(`/author/novels/${id}`)
}

/** 提交审核：草稿 / 已驳回 / 已下架 → 待审核 */
export function submitNovel(id) {
  return request.post(`/author/novels/${id}/submit`)
}

/**
 * 撤回提交：待审核 → 草稿（P4 补的）。
 *
 * ★ 它**不绕过审核**：撤回后还要重新提交、重新审。
 *   「待审核期间不能编辑内容」那条限制依然生效。
 */
export function withdrawNovel(id) {
  return request.post(`/author/novels/${id}/withdraw`)
}
