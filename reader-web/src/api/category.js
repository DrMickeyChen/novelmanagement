/**
 * 分类接口 —— 对应后端的 ReaderCategoryController（`/api/categories`）。
 *
 * ★ 只有阅读端有这个文件。管理端的分类管理在 `/api/admin/categories`，
 *   是另一个 Controller（增删改查），不要把两边的函数混进一个文件。
 */
import request from '@/utils/request'

/**
 * 分类列表（**已启用的**，按 sort 升序）。
 *
 * @returns {Promise<object[]>} `[{ id, name, sort, status, createTime }]`
 *
 * ★ 返回的是数组不是分页结构 —— 分类总共就十来个，分页没有意义。
 * ★ 后端只返回 status = 1（启用）的分类，所以这个接口不需要传参。
 * ★ **没有 novelCount 字段**（每个分类下有几本书）：
 *   那需要在分类表上 JOIN 作品表做聚合，而首页的分类入口只是导航，
 *   不显示数量。P3 不为它加一次聚合查询。
 */
export function getCategoryList() {
  return request.get('/categories')
}
