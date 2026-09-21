/**
 * 分类相关接口 —— 对应后端 AdminCategoryController（/api/admin/categories）。
 *
 * ★★ 分类接口**没有分页**，`list()` 一次返回全部。
 *   这是刻意的：分类是个位数量级的字典表，读者端的筛选栏要把它们
 *   全列出来（十几二十个），分页反而没人用。
 *   所以这个文件里没有 `page`/`size` 参数 —— 不是漏了。
 *
 * ★★ 和读者端的 `GET /api/categories` 是**两个接口**，不要混用：
 *   · 读者端：只返回 `status = 1` 的（停用的分类不该出现在筛选栏里），**匿名可访问**
 *   · 管理端：返回全部（含停用的，否则停用之后就再也启用不回来了），**需 ADMIN**
 *   管理端的下拉筛选要用这一个 —— 用读者端那个的话，
 *   一本书如果挂在已停用的分类下，用读者端分类列表就筛不出它，看起来像丢了书。
 */
import request from '@/utils/request'

/**
 * 分类列表（全量，含已停用）。
 * @returns {Promise<Array<{id:number,name:string,sort:number,status:number,createTime:string}>>}
 */
export function getCategoryList() {
  return request.get('/admin/categories')
}

/**
 * 新建分类。
 * @param {{name:string, sort?:number, status?:number}} data
 *   `sort` 越小越靠前，默认 0；`status` 1 启用 / 0 停用，默认 1
 * @returns {Promise<number>} 新分类的 id
 */
export function createCategory(data) {
  return request.post('/admin/categories', data)
}

/**
 * 编辑分类。
 *
 * ★★ **改名会影响已经挂在这个分类下的所有作品**（读者端筛选栏、
 *   作品详情页的分类名都是实时 join 出来的，不是存快照）。
 *   这是我们要的：改一次全局生效。但管理员改之前应该知道。
 *
 * @param {number} id
 * @param {{name:string, sort?:number, status?:number}} data
 */
export function updateCategory(id, data) {
  return request.put(`/admin/categories/${id}`, data)
}

/**
 * 删除分类。
 *
 * ★★ **分类下还有作品时删不掉** —— 后端会返回错误而不是级联删除。
 *   级联删除意味着「删一个分类顺手删掉几十本书」，而管理员点删除时
 *   想的是「这个分类不用了」，两者差得很远。
 *   所以调用这个接口前必须先把该分类下的作品挪走或删掉，
 *   前端要把这句话写进确认框里。
 *
 * @param {number} id
 */
export function deleteCategory(id) {
  return request.delete(`/admin/categories/${id}`)
}
