/**
 * 作品接口 —— 对应后端的 ReaderNovelController（`/api/novels`）。
 *
 * ★ 路径与 reader-web/src/api/novel.js 【逐字相同】。
 *   这个文件的存在方式就是"照搬"：函数名、参数、返回结构一个都不改，
 *   这样两端的页面代码可以互相参照，改一边时能立刻看出另一边要改什么。
 *
 * ★ 唯一需要注意的搬运细节：桌面端用的是 axios 的 `{ params }` 选项，
 *   而移动端的 request.js 也认这个键（见那边的 buildQuery）——
 *   这不是巧合，是**特意做的兼容**，为的就是让这一层能原样搬过来。
 *   往这个文件里加新接口时请继续用 `{ params }`，别改成手拼查询串。
 */
import request from '@/utils/request'

/**
 * 作品列表（分页）。
 *
 * @param {object} params
 * @param {number} [params.page=1]
 * @param {number} [params.size=10]  上限 100
 * @param {number} [params.categoryId] 分类 id，不传=全部
 * @param {string} [params.keyword]  书名关键词
 * @param {number} [params.isFinish] 0 连载 / 1 完结
 * @param {string} [params.sort]     latest（默认）/ new / words
 * @returns {Promise<{records: object[], total: number}>}
 *
 * ★ 只返回已上架的作品，这个筛选在服务端做，前端不用管。
 * ★ sort 传不认识的值不会报错，服务端按 latest 处理。
 */
export function getNovelList(params) {
  return request.get('/novels', { params })
}

/**
 * 榜单。**不分页** —— 榜单就是「前 N 名」。
 *
 * @param {object} [params]
 * @param {'hot'|'new'|'finished'} [params.type=hot] hot=阅读量 / new=最新创建 / finished=已完结
 * @param {number} [params.limit=10] 上限 50
 * @returns {Promise<object[]>} 作品数组，不是分页结构
 *
 * ★ ★★ 注意它返回的是**裸数组**，不是 { records, total }。
 *   所以榜单页【不该用 usePagedList】—— 那个 composable 是按分页结构
 *   写的，套上去会得到"永远空"的列表（`data?.records` 是 undefined）。
 *   阅读端就是这么做的，这里保持一致。
 */
export function getRankList(params) {
  return request.get('/novels/rank', { params })
}

/**
 * 作品详情（**不含目录**）。
 *
 * @param {number|string} id
 * @returns {Promise<object>} 作品元信息 + `firstChapterId`，**没有 `chapters`**
 *
 * ★★ 目录不再跟着详情回来，要用目录请调下面两个之一：
 *   - `getNovelChapters` —— 详情页的目录卡片（分页、每行带状态和字数）
 *   - `getNovelCatalog`  —— 阅读器的上一章/下一章和目录抽屉（全量、只有三个字段）
 *   理由见 `docs/P6-书架评论.md`：详情页是**匿名高频**入口，
 *   几百章的书每打开一次就拖回整本目录。
 *
 * ★ `firstChapterId` 是「开始阅读」的目标，**一章都没发布时它不存在**
 *   （全局 json 配了 non_null，为 null 的字段整个消失）→ 前端写
 *   `detail.firstChapterId ?? null`，为 null 时把按钮置灰。
 *   ★ 不要改回「取 chapters[0]」—— 目录已经不在这个响应里了。
 *
 * ★ 作品不存在和未上架返回的是同一个 2001，前端不要去区分。
 */
export function getNovelDetail(id) {
  return request.get(`/novels/${id}`)
}

/**
 * 作品目录（分页）—— 详情页的目录卡片用这个。
 *
 * @param {number|string} novelId
 * @param {object} [params]
 * @param {number} [params.page=1]
 * @param {number} [params.size=20] 上限 100
 * @returns {Promise<{records: object[], total: number}>}
 *
 * ★ 只含**已发布**章节，草稿/待审/被驳回的章不会出现（服务端过滤）。
 * ★ **`total` 就是「共 N 章」的那个 N**，不要另外找接口要章节总数。
 */
export function getNovelChapters(novelId, params) {
  return request.get(`/novels/${novelId}/chapters`, { params })
}

/**
 * 作品目录（**全量精简**）—— 阅读器专用。
 *
 * @param {number|string} novelId
 * @returns {Promise<Array<{id: number, novelId: number, chapterNo: number, title: string}>>}
 *
 * ★★ **它和 getNovelChapters 不是重复的，别合并**：阅读器要回答的是
 *   「我在第几章、上一章/下一章各是谁」—— 这需要**整条有序列表**，
 *   分页给不了（下一页的第一条不一定紧邻当前章）。
 * ★ 只有三个字段（没有 status / wordCount / 正文），几百章也就几十 KB。
 */
export function getNovelCatalog(novelId) {
  return request.get(`/novels/${novelId}/catalog`)
}

/**
 * 章节正文 —— 阅读器的核心接口。
 *
 * @param {number|string} novelId
 * @param {number|string} chapterId
 * @returns {Promise<{id: number, title: string, content: string, ...}>}
 *
 * ★ 两个 id 都必须传，而且服务端会校验这一章确实属于这本书 ——
 *   只传 chapterId 是拿不到正文的（防止用一本已上架的书读到别的书的章节）。
 */
export function getChapterContent(novelId, chapterId) {
  return request.get(`/novels/${novelId}/chapters/${chapterId}`)
}

/**
 * 搜索作品：**书名 OR 作者昵称**，跨全站。
 *
 * @param {object} params
 * @param {string} params.keyword 关键词，空白则服务端直接返回空列表
 * @param {number} [params.page=1]
 * @param {number} [params.size=10]
 * @returns {Promise<{records: object[], total: number}>}
 *
 * ★★ 它和上面的 getNovelList 长得像，但【不是一回事】，不要合并：
 *   getNovelList 的 keyword 是「在当前分类里筛书名」，
 *   这里的 keyword 是「跨全站搜书名或作者名」。
 *   合并的话，分类页的筛选框在用户输入作者名时会返回一堆不相关的书。
 */
export function searchNovels(params) {
  return request.get('/search', { params })
}

/**
 * 阅读量埋点。读者打开某本书时调一次。
 *
 * ★ **永远返回成功**，id 不存在也一样 —— 调用方不需要写任何错误处理。
 * ★ 阅读量是**近实时**的（M3 起）：后端只发一条异步消息就返回，
 *   由内容服务消费后批量聚合、每 5 秒落库一次。
 *
 * ★ 带 silent：这是个纯埋点，失败了不该弹任何提示打断读者。
 *   虽然后端保证不会返回错误，但网络断了 / 后端没起的时候，
 *   不带 silent 就会弹一句「连接不上后端服务」—— 而读者只是打开了本书。
 *   ★★ 在移动端这一条更要紧：uni.showToast 是盖在页面上的浮层，
 *      弹一次会挡住正文。
 */
export function reportNovelView(novelId) {
  return request.post(`/novels/${novelId}/view`, null, { silent: true })
}
