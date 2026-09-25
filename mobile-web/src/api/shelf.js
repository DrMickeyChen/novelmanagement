/**
 * 书架接口 —— 对应后端的 ReaderShelfController（`/api/reader/shelf`）。
 *
 * ★ 路径与 reader-web/src/api/shelf.js 逐字相同。
 * ★★ 四个接口**全部需要登录**（`/api/reader/**` 落在
 *    `anyRequest().authenticated()` 上）。未登录时后端返回
 *    业务码 401 → `request.js` 清登录态并跳登录页 —— 移动端也是这套。
 *
 * ★ 注意：**移出书架是物理删除**（`user_shelf` 没有 `deleted` 列），
 *   没有撤销。所以页面上的「移除」必须先问一句（`utils/confirm.js`）。
 */
import request from '@/utils/request'

/**
 * 我的书架（分页）。
 *
 * @param {object} params
 * @param {number} [params.page=1]
 * @param {number} [params.size=12]
 * @returns {Promise<{records: object[], total: number}>}
 *
 * ★★ 每一项是 `ShelfItemVO`，它**和 `NovelVO` 不是一回事**：
 *   · 主键叫 `novelId`（**没有 `id`**）
 *   · 没有 `isFinish` / `wordCount` / `viewCount`
 *   · 多了 `available`（这本书还在不在架上）、
 *     `readChapterId` / `readChapterNo` / `readChapterTitle`（我读到哪）、
 *     `lastChapterId`（最新章节）、`progress`（百分比）
 *   · ★ `progress` 的 `0` 是**一个真实的进度**（打开过第一章、一个字没滚），
 *     判空要写 `!= null`，不能写真值判断
 *
 * ★ 所以书架卡片**不能复用 NovelCard** —— 那个组件无条件渲染
 *   「连载 · N 章 · N 字 · N 阅读」，拿 ShelfItemVO 喂它会显示
 *   「连载 · 0 章 · 0 字 · 0 阅读」。★ **不是缺字段，是显示了一个错的数字**，
 *   而且看起来很正常。详见 reader-web 那份的同名注释，理由一模一样。
 */
export function getShelfList(params) {
  return request.get('/reader/shelf', { params })
}

/**
 * 这本书在不在我的书架里。
 *
 * @param {number|string} novelId
 * @returns {Promise<boolean>} 直接的布尔值，不是包装对象
 */
export function getShelfStatus(novelId) {
  return request.get(`/reader/shelf/${novelId}`)
}

/**
 * 加入书架。★ **幂等** —— 已经在书架里再调一次也是成功，不会报错。
 */
export function addToShelf(novelId) {
  return request.post(`/reader/shelf/${novelId}`)
}

/**
 * 移出书架。
 *
 * ★ **物理删除，不可撤销**。
 * ★★ 服务端的移除接口**刻意不校验上架状态** —— 所以下架的书也能被移除。
 *    页面上少写那个按钮，那一行就永远删不掉了（reader-web 那份注释里
 *    专门讲了这件事，移动端照做）。
 */
export function removeFromShelf(novelId) {
  return request.delete(`/reader/shelf/${novelId}`)
}
