/**
 * uni-app 存储的薄封装 —— 全工程【唯一】碰 uni.*StorageSync 的地方。
 *
 * ============================================================
 * ★★★ 这个文件存在的全部理由：H5 的 uni 存储【就是】localStorage ★★★
 * ============================================================
 *
 * 实测（读 `@dcloudio/uni-h5` 的产物，不是文档转述）：
 *
 *   setStorageSync(key, data):
 *       const type = typeof data
 *       const value = type === 'string' ? data : JSON.stringify({ type, data })
 *       localStorage.setItem(key, value)
 *
 * 两件事一眼可见：
 *   ① key 【原样】，没有任何 `uni_` 之类的前缀 —— 和桌面端写的是同一批 key；
 *   ② 字符串【原样存】，非字符串会被包一层 {type, data} 再序列化。
 *
 * 而 :3000 这个 origin 下，三端已经在用 `novel_token` / `novel_user`
 * （见 reader-web/src/utils/auth.js）。**同源共享登录态是本项目刻意的设计**
 * （CLAUDE.md：开发入口一律走 :3000，直连 5175 读不到另外两端的登录态）。
 * 于是移动端一进来就自动落进了同一份存储 —— 这既是本期的最大红利，
 * 也是本期的最大风险（见下面那条）。这就是 R1。
 *
 * ------------------------------------------------------------
 * ★★★ 风险：写成对象，坏的是【桌面端】，而且两边都不报错
 * ------------------------------------------------------------
 *
 * 桌面端读 user 的方式是死的：
 *
 *     JSON.parse(localStorage.getItem('novel_user'))
 *
 * 如果移动端写下的是【对象】：
 *
 *     实际字节 = {"type":"object","data":{"id":1,"nickname":"reader01"}}
 *     桌面端解出来 = { type:'object', data:{...} }      ← 结构合法，就是不对
 *     user.nickname === undefined → 桌面端显示「未登录」
 *
 * 注意它**不会抛异常** —— `JSON.parse` 成功，拿到的是一个形状完好的对象，
 * 只是没有 nickname / role / permissions。桌面端 auth.js 的 try-catch
 * 兜不住这种情况（它只兜「解析失败」）。症状是「三端突然都登不出去/登不进来」，
 * 而浏览器控制台一片安静。这是 R1 之所以排第一的原因。
 *
 * ★★ 更阴的一点：**移动端自己不会坏。**
 *   uni 的读侧（getStorageOrigin → parseValue）认得出 {type:'object',data:…}
 *   这个外壳，会把 data 解包还给你，于是移动端读到的还是那个完好的用户对象。
 *   → 同一次写入，移动端一切正常、桌面端静默失效。
 *   → 所以【判断 2 的阳性对照必须断言桌面端那一侧坏】，断言移动端是断言不出来的。
 *
 * ------------------------------------------------------------
 * 对策：只写字符串，且这一条由代码强制，不靠注释约定
 * ------------------------------------------------------------
 *
 *   ✅ setRaw(USER_KEY, JSON.stringify(user))   ← 字符串，两边读到的字节一致
 *   ❌ setRaw(USER_KEY, user)                   ← 抛异常，写不进去
 *
 * 为什么不「自动帮你 JSON.stringify」而是【抛】：
 *   自动兜底会把一次明确的错误变成一次静默的、看起来成功的写入，
 *   而它下次还会犯。这里的调用方只有 auth.js / readerStorage.js 两处，
 *   全部本来就先做了序列化 —— 抛异常不会误伤任何正常路径，
 *   只会挡住那个「顺手传个对象」的瞬间。
 *
 * ★ 判据留在注释里没用，落在代码里才有用：本文件是铁律 8 意义上的护栏，
 *   而判断 2 的阳性对照（直接绕开本封装去调 uni.setStorageSync）就是
 *   那个「能证明它会失败」的对照组。
 */

/**
 * 读一个 key 的【原始字符串】。
 *
 * @returns {string|null} 没有这个 key / 读不出来 → null
 *
 * ★ 正常情况下 uni.getStorageSync 直接还你那个字符串。
 *   但有一个非正常情况必须兜住：如果有人绕开了 setRaw、用对象形态写进去过，
 *   uni 的 H5 读侧会认外壳、把 data 解包成【对象】还给我们。
 *   那时这里重新序列化一次 —— 让本函数的返回类型恒为 `string|null`，
 *   调用方（auth.js / readerStorage.js）就不用各自再判一次类型。
 *
 * ★ 但要说清楚：这个兜底【只救得了移动端】。桌面端读的是原始字节，
 *   它没有 uni 这层解包，照样坏。所以这一支不是"容错设计"，
 *   而是"把移动端受影响的范围缩到最小"——真正的防线是 setRaw 的拒绝写入。
 */
export function getRaw(key) {
  let value
  try {
    value = uni.getStorageSync(key)
  } catch {
    // 存储不可用（隐私模式、企业策略）—— 当作没有，调用方按未登录处理
    return null
  }

  // ★ uni 对「不存在的 key」返回空串，不是 null。三种空值一起判。
  if (value === '' || value === null || value === undefined) return null
  if (typeof value === 'string') return value

  try {
    return JSON.stringify(value)
  } catch {
    // 循环引用之类，序列化不了 —— 当作没有
    return null
  }
}

/**
 * 写一个 key。
 *
 * ★ 只接受字符串。传别的直接抛，见文件头的论证。
 *
 * @throws {TypeError} value 不是字符串
 * @returns {boolean} 写成功没有（存储不可用时返回 false，不抛）
 *
 * ★ 为什么写失败【不抛】而写错类型【要抛】：
 *   两者性质完全不同。
 *   - 类型错：是【调用方的 bug】，现在改还来得及，必须立刻炸出来；
 *   - 写不进：是【运行环境的限制】（Safari 隐私模式、配额满），
 *     调用方对此无能为力，而且进度上报是在滚动回调里调的 ——
 *     那里抛出去没人接，读者会看到「滚到一半页面就不动了」。
 *     记不住进度的代价，远小于页面崩掉。
 *   （这个取舍和 reader-web 的 writeJson 是同一个，见那边的说明。）
 */
export function setRaw(key, value) {
  if (typeof value !== 'string') {
    throw new TypeError(
      `[storage] ${key} 的值必须是字符串，收到 ${typeof value}。` +
        `对象请先 JSON.stringify —— 否则会写成 uni 的 {type,data} 外壳，` +
        `把桌面端（直接 JSON.parse）读坏，且两边都不报错。`,
    )
  }

  try {
    uni.setStorageSync(key, value)
    return true
  } catch {
    return false
  }
}

/** 删一个 key。删不掉（存储不可用）也当成功 —— 调用方的诉求是「它别再在」 */
export function removeRaw(key) {
  try {
    uni.removeStorageSync(key)
  } catch {
    /* 见 writeJson / setRaw 的说明：存储不可用时静默放弃 */
  }
}

/**
 * 列出当前所有 key。
 *
 * 只有 readerStorage.js 的进度淘汰逻辑用它。
 * ★ 桌面端那边是 `Object.keys(localStorage)` —— 这里不能照抄：
 *   localStorage 是小程序里【不存在】的对象，照抄等于把跨端性写死。
 *   uni.getStorageInfoSync() 是各端都有的等价物。
 */
export function allKeys() {
  try {
    const info = uni.getStorageInfoSync()
    return Array.isArray(info?.keys) ? info.keys : []
  } catch {
    return []
  }
}
