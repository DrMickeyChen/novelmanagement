/**
 * 作品接口 —— 对应后端的 ReaderNovelController（`/api/novels`）。
 *
 * 目录约定（§四）：每个业务模块一个文件，组件里不直接写 axios，
 * 接口路径只在这一处出现。所以改后端路径时，只需要改这个文件。
 *
 * ★ 这个文件【只有阅读端有】，不要复制到另外两个前端 ——
 *   创作中心走 /api/author/**，管理端走 /api/admin/**，
 *   那是另外两个 Controller，见各自的 api 目录。
 *   （和 utils/request.js「三端逐字节相同」的约定不是一回事：
 *     那个是基础设施，这个是业务接口，各端本来就不一样。）
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
 * @returns {Promise<{records: object[], total: number, current: number, size: number, pages: number}>}
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
 * ★ 三个榜共用这一个接口，靠 type 区分。传不认识的值不报错，按 hot 处理。
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
 * ★★ **P6 起目录不再跟着详情回来**，要用目录请调下面两个之一：
 *   - `getNovelChapters` —— 详情页的目录卡片（分页、每行带状态和字数）
 *   - `getNovelCatalog`  —— 阅读器的上一章/下一章和目录抽屉（全量、只有三个字段）
 *   这是对 P3「一次性全量返回」的反转，理由见 `docs/P6-书架评论.md`：
 *   详情页是**匿名高频**入口，几百章的书每打开一次就拖回整本目录。
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
 * ★ 作品不存在 / 未上架同样是 2001（和详情接口一字不差）。
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
 *   详情页要的才是「翻页浏览」，那才用分页。
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
 * ★ 不返回 auditRemark（驳回理由是审核内部话术）。
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
 *   合并成一个接口的话，分类页的筛选框在用户输入作者名时
 *   会返回一堆不相关的书 —— 因为它本来只打算按书名筛。
 *   后端为此专门开了 /api/search，两个接口的注释互相点名。
 *
 * ★ 路径是 /search 而不是 /novels/search，所以它放在这个文件里
 *   只是因为它返回的仍然是 NovelVO —— 「接口路径只在一处出现」
 *   这条约定的单位是【文件】，不是【URL 前缀】。
 */
export function searchNovels(params) {
  return request.get('/search', { params })
}

/**
 * 阅读量埋点。读者打开某本书时调一次。
 *
 * @param {number|string} novelId
 *
 * ★ **永远返回成功**，id 不存在也一样 —— 调用方不需要写任何错误处理。
 * ★ 阅读量不是实时的：这里只对一个 Redis 计数器 +1，
 *   由后端定时任务每 30 秒批量落库。刚点完去看榜单，数字可能还没变。
 *
 * ★ 带 silent：这是个纯埋点，失败了不该弹任何提示打断读者。
 *   虽然后端保证不会返回错误，但网络断了 / 后端没起的时候，
 *   不带 silent 就会弹一句「连接不上后端服务」—— 而读者只是打开了本书。
 */
export function reportNovelView(novelId) {
  return request.post(`/novels/${novelId}/view`, null, { silent: true })
}
