/**
 * 书架接口 —— 对应后端的 ReaderShelfController（`/api/reader/shelf`）。
 *
 * ★★ 四个接口都【需要登录】。和 api/reader.js 的定位完全一样
 *   （`/api/reader/**` 是「会落用户数据的接口」的约定前缀），
 *   单独开一个文件只是因为书架自成一块，不是因为权限口径不同。
 *
 * ------------------------------------------------------------
 * ★★ 加入和移出是【两个函数、两条路径】，不是「切换」
 * ------------------------------------------------------------
 * 你可能会想用一个 `toggleShelf(novelId)`：反正详情页那个按钮
 * 「加入/移出」就是一个动作。**但后端刻意没做成 toggle**，
 * 理由（写在 ReaderShelfController 的类注释里）值得在前端也留一份：
 *
 *   toggle 既不幂等也不可重试 —— 用户点「加入」，响应超时
 *   （服务端其实成功了），客户端重试，第二次把书【移出】了，
 *   而两次响应都是 200。用户看到的是「加不进去」。
 *
 * 所以这里是「调用方知道自己在做什么」的形态：详情页按钮根据
 * 当前 `onShelf` 决定调哪个，书架页的「移除」永远调 removeFromShelf。
 *
 * ★ 两个接口都是**幂等**的：已经在架上的再 add、不在架上的再 remove，
 *   都返回成功，且返回体里的状态是【真实状态】而不是「翻转了一下」。
 *   所以前端**不需要自己猜**操作后的状态 —— 服务端让你不用猜。
 */
import request from '@/utils/request'

/**
 * 我的书架（分页）。
 *
 * @param {{page?: number, size?: number}} params
 * @returns {Promise<{records: Array, total: number, current: number, size: number}>}
 *
 * ★ 排序是**加入时间倒序**（同秒按 id 兜底）。
 *   注意它和阅读历史页不同：这里排的是「什么时候收藏的」，
 *   重读一本书**不会**把它顶到最上面（后端取的是第一次加入的时间）。
 *
 * ★★ 每一行都要接住 `available === false` 这个**合法状态**：
 *   书架里那本书被作者下架 / 被管理员驳回时，`title` / `cover` /
 *   `authorName` 等字段会**整个 key 消失**（全局 `non_null`），
 *   只剩 `novelId` / `createTime` / 阅读进度。
 *   这时**不能把这一行过滤掉**：读者会以为自己的书架丢了东西，
 *   而真实原因只是那本书下架了。渲染成「已下架」占位卡，
 *   ★ 并且**仍然要给「移除」按钮** —— 移除接口刻意不校验上架状态，
 *   就是为了让这一行能被清理掉（否则它永远删不掉）。
 */
export function getShelfList(params) {
  return request.get('/reader/shelf', { params })
}

/**
 * 这本书在不在我的书架 + 当前收藏数。详情页那个按钮的初始态用它。
 *
 * @returns {Promise<{novelId: number, onShelf: boolean, collectCount?: number}>}
 *
 * ★ **不复用 `GET /novels/{id}`**：那个接口是匿名的（游客也要看详情页），
 *   把「我」的状态混进去等于要求每个游客也有一个 userId。
 *
 * ★ `onShelf` 是包装类型 → 理论上可能整个 key 消失。后端注释说明了
 *   它不会发生（这个类没有「不知道」的情况），但前端仍按
 *   `?? false` 读 —— 和 `CommentVO.liked` 同一个写法，
 *   免得将来漏 set 时界面上出现一个「既不是已加入也不是未加入」的按钮。
 */
export function getShelfStatus(novelId) {
  return request.get(`/reader/shelf/${novelId}`)
}

/**
 * 加入书架。**幂等** —— 本来就在书架里也返回成功，`collect_count` 不会重复加。
 *
 * @returns {Promise<{novelId: number, onShelf: boolean, collectCount?: number}>}
 *
 * ★ 作品必须**存在且已上架**，否则业务码 `2001`。
 *   （和移出接口的口径刻意不同，理由见 removeFromShelf。）
 *
 * ★ 返回值里的 `collectCount` 是**重算后**的收藏数，直接用，
 *   不要再为了刷新这个数字去调一次详情接口。
 */
export function addToShelf(novelId) {
  return request.post(`/reader/shelf/${novelId}`)
}

/**
 * 移出书架。**幂等** —— 本来就不在书架里也返回成功。
 *
 * ★★ **刻意不校验作品是否上架**（和加入接口不对称，这是有意的）：
 *   书架里完全可能有一本已经下架的书，如果这里也要求「作品已上架」，
 *   那一行就**永远移除不掉** —— 读者点「移除」拿到 2001，
 *   书还在书架上，再点还是 2001。
 *   既然列表把下架的书显示成占位卡，就必须给出清掉它的路。
 *
 * ★ 是**物理删除**（`user_shelf` 这张表没有 `deleted` 列）：
 *   它的语义是「现在在不在架」，留着历史行没有意义。
 *   所以移出之后【没有撤销】—— 调用方该在删之前问一句。
 */
export function removeFromShelf(novelId) {
  return request.delete(`/reader/shelf/${novelId}`)
}
