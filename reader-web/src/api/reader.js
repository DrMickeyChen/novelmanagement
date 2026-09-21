/**
 * 读者个人数据接口 —— 对应后端的 ReaderHistoryController（`/api/reader/history`）。
 *
 * ★★ 这两个接口【需要登录】，和 api/novel.js 里那些公开接口是两回事。
 *   调用前请确认用户已登录（见下面各自的说明）。
 *
 * ★ 路径前缀 `/api/reader/**` 是约定：**会落用户数据的写接口都放这里**。
 *   免登录的 `/api/novels/**` 下只允许「不写用户数据」的接口
 *   （唯一的例外是阅读量埋点，它只动一个 Redis 计数器）。
 *
 * ★ P6 加了书架和个人资料，它们**各自开了新文件**（`api/shelf.js` /
 *   `api/profile.js`）而不是堆进这里 —— 前缀约定管的是权限口径，
 *   不是「必须写在同一个文件里」。这个文件继续只管阅读进度这一件事。
 */
import request from '@/utils/request'

/**
 * 上报阅读进度。
 *
 * @param {{novelId: number, chapterId: number, progress?: number}} data
 *        progress 是**章内百分比 0~100**，超出会被服务端夹到边界（不报错）
 *
 * ★★ **必须带 silent: true。** 这是整个 P3 前端最重要的一个细节：
 *    上报是滚动时每 1 秒防抖发一次的。不带 silent 的话，token 过期那天
 *    401 会走 request.js 的默认处理 —— 弹提示 + clearAuth +
 *    600ms 后【整页跳登录页】。读者正在往下滚，页面突然跳走，进度全丢，
 *    而他做的唯一一件事就是继续看书。
 *    带了 silent 之后，401 只会在内部悄悄 reject，页面纹丝不动。
 *    （详见 utils/request.js 里 isSilent 的注释。）
 *
 * ★ 服务端**不返回任何有用数据**（data 字段不存在），调用方不需要处理返回值。
 * ★ 同一本书反复调用只覆盖，不新增行 —— 表上有 uk_user_novel 唯一键。
 */
export function reportProgress(data) {
  return request.post('/reader/history', data, { silent: true })
}

/**
 * 查当前登录用户在某本书上的进度。
 *
 * @param {number|string} novelId
 * @returns {Promise<{novelId: number, chapterId: number, progress: number, updateTime: string}|null>}
 *
 * ★ **查不到时返回 null**（后端 data: null，而配了 non_null 之后
 *   这个 key 会整个从 JSON 里消失，所以拿到的是 undefined）。
 *   两种表现对调用方是一样的 —— 用 `?? null` 或真值判断，
 *   别写 `=== null`，那个在 undefined 时不成立。
 *
 * ★ 带 silent，而且调用方还要再判一次 isLogin，两道防线：
 *    ① 调用方判 isLogin —— 游客根本不需要发这个请求（他没有服务端进度），
 *       这是主要的一道，省掉一次注定 401 的往返；
 *    ② 这里带 silent —— 万一有人漏判了 ①，或者登录态刚好在这一刻过期，
 *       也绝不能让一个 401 把正在看书的读者踢到登录页。
 *   两道防线管的是同一件事，但失效原因不同，都留着。
 */
export function getProgress(novelId) {
  return request.get(`/reader/history/${novelId}`, { silent: true })
}

/**
 * 我的阅读历史（分页）。
 *
 * @param {{page?: number, size?: number}} params
 * @returns {Promise<{records: Array, total: number, current: number, size: number}>}
 *
 * ★★ 和上面 `getProgress` 【同源不同接口，刻意不合并】：
 *   这个返回 `ReadHistoryItemVO`（带书名/封面/章名），
 *   上面那个返回 `ReadHistoryVO`（只有 novelId + 进度）。
 *   合并的话，阅读器**每次滚动上报之后**都要为这三个字段多付一次查询，
 *   而它一个都用不上 —— 后端 `ReadHistoryVO` 的注释把这条写死了。
 *
 * ★ 排序是**最后阅读时间倒序**（同秒按 id 兜底）。
 *   和书架页不同：这里重读同一本书会把它**顶到最上面**，
 *   因为这一页回答的是「我最近在读什么」。
 *
 * ★★ `available === false` 同样是合法状态，接法见 `api/shelf.js`
 *   的 `getShelfList`（规则一字不差，那边写得全）。
 *
 * ★ 这个接口【不带 silent】—— 它是用户主动打开的一页，
 *   401 就该按默认处理（提示 + 跳登录），和滚动上报那种后台请求不是一回事。
 */
export function getHistoryList(params) {
  return request.get('/reader/history', { params })
}
