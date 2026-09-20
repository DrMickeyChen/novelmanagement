/**
 * 分类接口。
 *
 * ★ 只有一个查询，且**是公开接口**（`GET /api/categories` 在 SecurityConfig 白名单里）——
 *   和阅读端、管理端读的是同一个接口，没有「作者专用的分类列表」这回事。
 *   所以这个文件放在 author-web 里看着有点多余，但**不要**因此把它删掉去直接
 *   写死一份分类数组：分类是管理员能改的数据（`AdminCategoryController`），
 *   写死的那一刻起就会和后台不一致。
 *
 * ★ 管理端的增删改（POST/PUT/DELETE /api/admin/categories）**不属于创作中心**，
 *   这里不提供 —— 作者不能自己建分类。
 */
import request from '@/utils/request'

/**
 * 全部分类（按 sort 升序）。
 *
 * @returns {Promise<Array<{id:number,name:string,sort:number,status:number}>>}
 *
 * ★ 后端**只返回启用中的分类**（status = 1），停用的不会出现 ——
 *   所以前端不需要再按 status 过滤一次。
 * ★ 也不用分页：分类是几十条量级的数据，后端返回的是数组而不是 PageResult。
 */
export function getCategories() {
  return request.get('/categories')
}
