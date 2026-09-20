/**
 * 展示用的格式化函数。
 *
 * ★ 抽出来的理由和 NovelCard 是一样的：同一个数字在列表卡片和详情页
 *   都要显示，各写一份的话，「万字」的规则改一次要改两处 ——
 *   而漏掉的那一处不报错，只是在某个页面上显示成另一种写法。
 *
 * ★ 这个文件【只有阅读端有】，不用复制到另外两个前端：
 *   它是展示层的约定，而三个端的展示层本来就是各写各的。
 *   （utils/auth.js / request.js 是基础设施，那两份才要求三端逐字节一致。）
 */

/**
 * 字数格式化：一万以下显原数，一万以上换算成「万字」。
 *
 * ★ 为什么上万要换算：卡片和详情页里的数字都是并排显示的，
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
