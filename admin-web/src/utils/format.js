/**
 * 展示用的格式化函数。
 *
 * ★ 这个文件是从 reader-web 抄过来的（作者端也有一份），三端各留一份。
 *   ★★ 但【不要】指望它们保持逐字节相同 —— 要求逐字节一致的是
 *   utils/auth.js / request.js / stores/user.js（那几份是基础设施，
 *   三端必须是同一份），而这里是展示层的约定，本来就是各写各的，
 *   将来也会各自长出对方没有的函数。
 *   把展示层也绑成「必须一致」，只会让某一端想加个函数时不敢动。
 *
 * ★ 管理端比阅读端更需要它的理由：管理端的列表页有十来个，
 *   每张表里都有「时间」和「字数」两列 —— 各写一份的话，
 *   格式改一次要改十几处，而漏掉的那一处不报错，
 *   只是在某个页面上显示成另一种写法。
 */

/**
 * 字数格式化：一万以下显原数，一万以上换算成「万字」。
 *
 * ★ 为什么上万要换算：表格里的数字都是并排显示的，
 *   "9578 字" 和 "128900 字" 宽度差一倍，右边就会参差不齐。
 * ★ 保留一位小数：1.3 万字比「1 万字」有信息量，又不像 12890 字那样占地方。
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
 * 时间取到「天」。
 *
 * ★ 后端返回的是 "2026-09-19 23:56:13"（格式见后端 JacksonConfig，
 *   不是 ISO 的带 T 格式），所以直接截前 10 位就是日期。
 *   ★ 这里【不用 new Date()】：那个字符串带空格分隔符，
 *     在 Safari 上 new Date('2026-09-19 23:56:13') 会返回 Invalid Date
 *     —— 而 Chrome 上正常。同一份代码在两种浏览器上表现不同，
 *     是前端最难查的一类 bug。截字符串没有这个问题。
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
 * ★ 管理端比阅读端更需要这一个，而且是**审核场景的硬需求**：
 *   审核日志、审核时间都只精确到天的话，「今天审了哪几本、按什么顺序」
 *   完全看不出来。同一本书被审两次时，两条记录长得一模一样。
 */
export function formatDateTime(dateTime) {
  if (!dateTime || typeof dateTime !== 'string') return ''
  return dateTime.slice(0, 16)
}
