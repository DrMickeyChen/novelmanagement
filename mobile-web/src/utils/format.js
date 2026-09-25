/**
 * 展示用的格式化函数（移动端）。
 *
 * ★ 与 reader-web/src/utils/format.js 【同源、逐行对应】——
 *   同一个函数名在两端做同一件事，改动时才不会漏掉一端。
 *   （展示层不要求逐字节相同：auth.js / request.js 那种基础设施才要求，
 *     因为它们是"协议"；这里是"约定"。）
 */

/**
 * 字数格式化：一万以下显原数，一万以上换算成「万字」。
 *
 * ★ 为什么上万要换算：卡片和详情页里的数字都是并排显示的，
 *   "9578 字" 和 "128900 字" 宽度差一倍，右边就会参差不齐。
 *   ★ 在手机上这一条比桌面端更要紧 —— 屏幕只有 375px 宽，
 *     多出来的那几位数会把整行挤到换行。
 * ★ 用万而不是千：中文语境里「1.3 万字」是常识说法，
 *   「12.9 千字」在日常生活中没人这么说。
 *
 * @param {number|string} words
 * @returns {string} 例如 "9578 字" / "12.9 万字"
 */
export function formatWordCount(words) {
  // ★ Number(undefined) 是 NaN，NaN < 10000 是 false —— 会一路走到下面的除法，
  //   显示成 "NaN 万字"。所以先兜成 0，而不是直接拿原值比较。
  const value = Number(words) || 0
  if (value < 10000) return `${value} 字`
  return `${(value / 10000).toFixed(1)} 万字`
}

/**
 * ★★ 作者名的缺省写法：**取不到就显示 `—`，绝不回落成「佚名」。**
 *
 * 这是 M5 立下的一条判据，不是文案偏好：
 *
 *   `authorName` 是后端**跨服务**取来的（content → auth 的 ContentUserLookup）。
 *   它取不到时，整个 key 会从 JSON 里消失（后端全局配了 non_null）——
 *   而**本系统的作品必然有作者**（`novel.author_id` 是外键）。
 *   所以「佚名」在这个站里不是一个合法的状态，它是一句**假话**：
 *   把「服务器没问到」说成了「这本书没有作者」。而那正是 M1 立下的
 *   「宁可 500，也不要一个看起来正常但内容是错的页面」要挡的东西。
 *
 * ★ 为什么这条规则必须放在这里、而不是写在四个模板里：
 *   移动端有**四处**要显示作者名（卡片、详情页、书架、阅读历史）——
 *   各写一份的话，判据改一次要改四处，**而漏掉的那一处不报错**。
 *
 * ★ 顺带说清一处**刻意保留的不对称**：同一个卡片上的 `categoryName`
 *   仍然回落成「未分类」。因为分类是 content **自己库里的表**（JOIN 出来的），
 *   不跨服务 —— 它取不到只可能是「分类被删了」，那「未分类」说的就是事实。
 *   **判据是「这个字段会不会因为别人挂了而消失」，不是「它重不重要」。**
 *
 * @param {string} [name] 后端给的昵称，可能整个 key 不存在（见上）
 * @returns {string} 昵称，或 `—`
 */
export function formatAuthorName(name) {
  // ★ 用 || 而不是 ?? —— 后端可能给空串（PingController 那条老规矩：
  //   绝不能返回 null，于是有些地方会把空串当「没有」）。
  return name || '—'
}

/**
 * 时间取到「天」。
 *
 * ★ 后端返回的是 "2026-09-19 23:56:13"（后端 JacksonConfig 配的格式，
 *   不是 ISO 的带 T 格式），所以直接截前 10 位就是日期。
 *   ★ 这里【不用 new Date()】：那个字符串带空格分隔符，
 *     在 Safari 上 new Date('2026-09-19 23:56:13') 会返回 Invalid Date
 *     —— 而 Chrome 上正常。同一份代码在两种浏览器上表现不同，
 *     是前端最难查的一类 bug。截字符串没有这个问题。
 *   ★★ 移动端这条更实在：iOS 上的 WKWebView（uni-app 打包成 App 之后
 *     跑的就是它）用的正是 Safari 的 JS 引擎，桌面浏览器上测不出来的
 *     问题会在真机上出现。
 *
 * @param {string} [dateTime] 形如 "2026-09-19 23:56:13"，可能为 null
 * @returns {string} 形如 "2026-09-19"；传空则返回 ""
 */
export function formatDate(dateTime) {
  if (!dateTime || typeof dateTime !== 'string') return ''
  return dateTime.slice(0, 10)
}

/**
 * 时间取到「分」：`2026-09-19 23:56`。
 *
 * ★ 阅读端为什么需要它：**评论列表**。只精确到天的话，
 *   今天发的所有评论显示的都是今天，一屏里几行时间一模一样 ——
 *   而「谁先说的」在回复场景里是有意义的信息。
 *
 * ★ 为什么不做成「3 分钟前」这种相对时间：那个要靠**客户端时钟**算，
 *   而客户端时钟可能不准（差几小时很常见），于是一条刚发的评论
 *   会显示成「3 小时前」，且只在部分用户的机器上出现。
 *   服务端给的是绝对时间，截字符串永远不会算错。
 */
export function formatDateTime(dateTime) {
  if (!dateTime || typeof dateTime !== 'string') return ''
  return dateTime.slice(0, 16)
}
