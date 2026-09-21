/**
 * 公告接口 —— 对应后端的 ReaderNoticeController（`/api/notices`）。
 *
 * ★★ 这是全项目【极少数匿名可访问】的接口之一，别把管理端那个
 *   `/api/admin/notices` 混进来：
 *   · 读者端（本文件）：只返回**已发布**的，**不需要登录**，参数是 `limit`
 *   · 管理端：返回全部（含未发布的），需要 ADMIN，是标准分页
 *   两者是不同的 Controller，也是不同的 `api/*.js` 文件。
 *
 * ------------------------------------------------------------
 * ★★ 为什么这个接口不需要 token，以及它为什么安全
 * ------------------------------------------------------------
 * 首页横幅要在**没登录**的时候就能看见 —— 一个「请先登录才能看公告」
 * 的公告栏是没有意义的。所以 `SecurityConfig` 里给它开了一条白名单。
 * 那条白名单是 **GET 限定**的（`HttpMethod.GET`），
 * 所以「匿名可读」不会顺带变成「匿名可写」。
 *
 * ★ 它安全的另一个原因是**这个接口只会读 status = 1 的行** ——
 *   未发布的公告不会从这里泄露出去。这条保证在服务端，
 *   前端不需要（也无法）做任何过滤。
 *
 * ★ 没有分页：横幅只渲染前几条，做成分页的话前端要维护 page/size
 *   两个状态，而第 2 页的数据请求出来了也没人看。
 */
import request from '@/utils/request'

/**
 * 已发布的公告，按发布时间倒序（最新的在前）。
 *
 * @param {number} [limit=5] 最多几条，服务端会夹到 1~20
 * @returns {Promise<object[]>} `[{ id, title, content, type, publishTime }]`
 *
 * ★ 返回的每条都带 `content`（正文全文）—— 公告**没有详情页**，
 *   横幅上的那句话就是它的全部。所以后端在读者侧的 VO 里保留了 content。
 *
 * ★ 读者侧**不返回** `status`（恒为 1，没有信息量）和
 *   `createTime` / `updateTime`（运营关心的字段）。
 *   不要拿管理端列表里的字段名去猜读者侧的返回结构。
 */
export function getNoticeList(limit = 5) {
  return request.get('/notices', { params: { limit } })
}
