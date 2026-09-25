/**
 * 阅读进度 / 历史 —— 对应后端的 `ReaderHistoryController`
 * （`/api/reader/history`），移动端移植自 `reader-web/src/api/reader.js`。
 *
 * ★★ 三条接口【都要登录】，和 `api/novel.js` 里那些公开接口是两回事。
 *
 * ------------------------------------------------------------
 * ★★ 移动端和桌面端在进度这件事上**有两份存储**，别混
 * ------------------------------------------------------------
 *   · `utils/readerStorage.js` 的 localStorage 进度 —— **本机**的、离线的；
 *   · 这三条接口 —— **服务端**的、跨设备的（书架页/历史页显示的就是它）。
 *   ★ 阅读器**两个都要写**：只写 localStorage 的话，换台设备进度就没了，
 *     而且**历史页永远是空的**（那一页读的是服务端的 `read_history`）。
 *     这个缺口在桌面端看不出来，因为它两边都写。
 */
import request from '@/utils/request'

/**
 * 上报阅读进度。
 *
 * @param {{novelId: number, chapterId: number, progress?: number}} data
 *        `progress` 是**章内百分比 0~100**，超出会被服务端夹到边界（不报错）
 *
 * ★★ **必须带 silent: true**（和桌面端一字不差的理由）：上报是滚动时
 *   每 500ms 一次的后台请求。不带 silent 的话，token 过期那天
 *   401 会走默认处理 —— 弹提示 + 清登录态 + 跳登录页。
 *   读者正在往下滚，页面突然跳走，进度全丢，而他只是继续看书。
 *
 * ★ 服务端不返回有用数据；同一本书反复调用只覆盖（表上有唯一键）。
 */
export function reportProgress(data) {
  return request.post('/reader/history', data, { silent: true })
}

/**
 * 查当前用户在某本书上的**服务端**进度。
 *
 * @returns {Promise<{novelId, chapterId, progress, updateTime}|null>}
 *
 * ★★ 查不到时返回 `null`，而后端配了 `non_null` → 这个 key 会整个消失，
 *   所以实际拿到的是 `undefined`。取 `?? null`，**别写 `=== null`**。
 * ★ 同 `reportProgress`：带 silent，调用方还要再判一次 isLogin（两道防线，
 *   失效原因不同，都留着）。
 *
 * ★★ **移动端的阅读器刻意不用它**：本机已经有 localStorage 进度，
 *   再读一次服务端会带来「两个来源谁优先」的问题，
 *   而两者不一致时（比如在电脑上读到第 5 章、手机上读到第 3 章）
 *   没有正确答案。移动端只用本机进度，**服务端进度只用于历史页展示**。
 *   这条分歧是有意的（判断 3 只要求「本机进度共用」，没要求「跨设备合并」）。
 */
export function getProgress(novelId) {
  return request.get(`/reader/history/${novelId}`, { silent: true })
}

/**
 * 我的阅读历史（分页，最后阅读时间倒序）。
 *
 * @param {{page?: number, size?: number}} params
 * @returns {Promise<{records: Array, total, current, size}>}
 *
 * ★ `records` 里是 `ReadHistoryItemVO`：`novelId / title / cover / authorName /
 *   categoryName / available / chapterId / chapterNo / chapterTitle /
 *   progress / updateTime`。
 *
 * ★★ `available === false` 是**合法状态**（作品下架/删除后 brief 为 null，
 *   只剩 `novelId` 还在）—— 界面要说「已下架」，**不是**整条不显示：
 *   那一页回答的是「我读过什么」，把它藏起来等于篡改历史。
 *
 * ★ 这个接口**不带 silent**：它是用户主动打开的一页，401 就该按默认处理。
 */
export function getHistoryList(params) {
  return request.get('/reader/history', { params })
}
