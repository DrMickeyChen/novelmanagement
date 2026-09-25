/**
 * 登录态的本地存储读写（移动端）。
 *
 * ============================================================
 * ★★★ 两个 key 必须和桌面端逐字相同，这是「登录态互通」的全部机制 ★★★
 * ============================================================
 *
 * 见 reader-web/src/utils/auth.js 的完整论证（那份是源头，这里只记结论）。
 * 要点复述：
 *
 *   - localStorage 按【origin = 协议 + 主机 + 端口】隔离，端口也参与隔离；
 *   - 开发期四个前端都收在 http://localhost:3000 下的四个路径 → 同源 → 共享；
 *   - 桌面端三个应用之间免登录，靠的就是「同源 + 同一个 key」，
 *     **没有任何代码共享**。
 *
 * 移动端接进来之后，这条链上多了一个成员，规则一个字没变：
 * 沿用 `novel_token` / `novel_user` → 在 /reader/ 登录后打开 /mobile/ 就是已登录。
 *
 * ★★ 但移动端和桌面端有一个**结构性的差别**，它决定了本文件为什么不能
 *    直接复制桌面端那份：
 *
 *   桌面端走 localStorage，移动端走 uni.*StorageSync。
 *   在 H5 上这两者底下的确是同一个 localStorage（见 storage.js 的实测），
 *   但 uni 的写侧会给非字符串包一层外壳、读侧又认得出那个外壳 ——
 *   于是「能不能读对」在两端**不是同一个条件**。
 *   所以本文件不直接碰 localStorage，一律走 storage.js 的 setRaw/getRaw：
 *   **把"只写字符串"这条约束收在一个地方强制**，而不是在四个文件里各自记得。
 *
 * ★ 本文件【不依赖 Pinia】，理由和桌面端一样：
 *   request.js 在 Vue 组件树外面执行，那里调 useUserStore() 有 Pinia
 *   尚未初始化/脱离上下文的风险。纯函数谁都能调，最稳。
 */

import { getRaw, setRaw, removeRaw } from './storage'

/** ★ 四个前端共用。token 存这里 */
export const TOKEN_KEY = 'novel_token'

/** ★ 四个前端共用。用户信息（含角色、权限点）存这里 */
export const USER_KEY = 'novel_user'

/* ==================== 各端地址 ==================== */

/**
 * 网关地址 —— 各端的共同入口。
 *
 * 开发期是 dev-proxy 的 :3000；上线后换成真实域名即可。
 * ★ 移动端只在「跨应用跳转」和「分享链接」里用到它 ——
 *   接口请求不走这里，走 request.js 的 BASE_URL（见那边的说明）。
 */
export const GATEWAY_URL = import.meta.env.VITE_GATEWAY_URL || 'http://localhost:3000'

/**
 * ★★★ 下面三个地址必须以【斜杠结尾】★★★
 *
 *   http://localhost:3000/reader    ← 404（被当成文件找）
 *   http://localhost:3000/reader/   ← 正常（被当成目录）
 *
 * 拼接时同理：`${READER_WEB_URL}login` 而不是 `${READER_WEB_URL}/login`。
 * （详细论证在 reader-web/src/utils/auth.js，这里不重复。）
 */
export const READER_WEB_URL = import.meta.env.VITE_READER_WEB_URL || `${GATEWAY_URL}/reader/`

/** 创作中心地址（以 / 结尾），用于跨应用跳转 */
export const AUTHOR_WEB_URL = import.meta.env.VITE_AUTHOR_WEB_URL || `${GATEWAY_URL}/author/`

/** 管理端地址（以 / 结尾），用于跨应用跳转 */
export const ADMIN_WEB_URL = import.meta.env.VITE_ADMIN_WEB_URL || `${GATEWAY_URL}/admin/`

/**
 * 移动端自己的登录页。
 *
 * ★ 用 uni 的页面路径（pages.json 里的路径），不是 URL ——
 *   uni.navigateTo 认的是这个。
 */
export const MOBILE_LOGIN_PATH = '/pages/login/login'

/* ==================== token ==================== */

export function getToken() {
  return getRaw(TOKEN_KEY)
}

/**
 * 写 token。
 *
 * ★ token 本来就是字符串，不存在被包装的风险 —— 但这里仍然走 setRaw，
 *   不图省事直接调 uni.setStorageSync：**这个工程里应该只有一处**
 *   碰 uni 的存储 API，否则「只写字符串」这条约束就有了后门，
 *   而后门的存在方式永远是"就这一次，它本来就安全"。
 */
export function setToken(token) {
  setRaw(TOKEN_KEY, token)
}

/* ==================== 用户信息 ==================== */

/**
 * 读用户信息。
 *
 * ★ try-catch 的理由和桌面端一样，而且在这一端更实在：
 *   存储里的字节可能是【别的端】写的（这是本期唯一一个多端共写的 key）。
 *   桌面端如果曾经用对象形态写过一次，移动端这里读到的会是解包后的对象、
 *   序列化后**看起来正常** —— 但更常见的坏法是被人手动改坏、或旧版本
 *   写了别的格式，那时 JSON.parse 抛异常。
 *   而它通常在启动时/路由判断里被调用，一抛就白屏，报错栈指不到这里。
 */
export function getUser() {
  const raw = getRaw(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw)
  } catch {
    // 数据坏了就清掉，当作未登录处理，比白屏好
    removeRaw(USER_KEY)
    return null
  }
}

/*
 * ★ 上面【故意不做字段校验】（比如"没有 nickname 就当未登录"）。
 *   两个理由：
 *   ① 那会和桌面端产生分歧 —— 桌面端只判"能不能解析"，
 *      移动端多判一条，就多出一类"移动端掉登录、桌面端不掉"的情形，
 *      而这类分歧恰好长在最不该出分歧的登录态上。
 *   ② 对象形态写坏的那种情况【不需要在这一侧救】：uni 的读侧会解包，
 *      storage.js 再序列化回来，移动端读到的其实是对的
 *      （详见 storage.js 的论证）。真正坏的是桌面端 —— 而它救不了，
 *      只能靠 setRaw 不让那种写入发生。
 *   换句话说：修在写侧，别修在读侧。
 */

/**
 * 写用户信息。
 *
 * ★★ 必须 JSON.stringify。这是 R1 的唯一入口 ——
 *    传对象进去会被 storage.js 的 setRaw 直接抛出来。
 */
export function setUser(user) {
  setRaw(USER_KEY, JSON.stringify(user))
}

/* ==================== 清理与跳转 ==================== */

/** 退出登录 / token 失效时调用 */
export function clearAuth() {
  removeRaw(TOKEN_KEY)
  removeRaw(USER_KEY)
}

/**
 * 跳转到登录页。
 *
 * ============================================================
 * ★★ 这里和桌面端【刻意不一样】，是本工程唯一一处有意的行为分歧
 * ============================================================
 *
 * 桌面端三个应用 401 时统一跳【阅读端】的登录页（项目大纲 §7.5 第 3 条），
 * 为的是「用户只看到一个登录入口」。移动端不这么做，跳自己的登录页，理由：
 *
 *   ① 桌面端的那个做法靠 `window.location.href = '/reader/login'` 实现，
 *      它是【浏览器导航】。移动端要出小程序/App，那些运行时里
 *      根本没有 "/reader/login" 这个地址，`window` 对象本身也不存在 ——
 *      照抄等于在交付形态之外写死一行永远跑不到、也永远测不了的代码。
 *   ② 登录态本来就靠同一份 localStorage 共用（这是本文件存在的意义），
 *      所以【用户不需要被送去阅读端登录】—— 在哪一端登，结果都一样。
 *      跳自己的登录页反而少一次跨应用跳转、体验更顺。
 *
 * ★ 代价要记下来：登录入口从三个变成了四个。
 *   缓解因素是移动端在 H5 里只能从 :3000/mobile/ 进，
 *   它不是读者会自然走到的路径 —— 但**这确实是不一致**，
 *   已记进 docs/E3-uni-app.md 的「已知不一致」一节。
 *
 * ★ 用 reLaunch 而不是 navigateTo：登录是「把人踢出去」，
 *   应该清空页面栈 —— 否则用户登录成功后按返回键会退回刚才那个
 *   登录失败前的页面，看起来像"登录没生效"。
 *
 * ★ 绝对不要把 token 放进 URL（§7.5 明确否掉了那个方案）——
 *   URL 会留在浏览器历史记录、服务器访问日志、Referer 头里。
 *   redirect 传的是【页面路径】，不是凭证。
 */
export function redirectToLogin() {
  let redirect = ''
  try {
    // ★ getCurrentPages 在 app 挂载前返回空数组 —— 那时没有"当前页"可回
    const pages = getCurrentPages()
    const current = pages?.[pages.length - 1]
    if (current?.route) redirect = `/${current.route}`
  } catch {
    /* 拿不到就算了：登录后回首页，比跳转失败强 */
  }

  const query = redirect ? `?redirect=${encodeURIComponent(redirect)}` : ''
  uni.reLaunch({ url: `${MOBILE_LOGIN_PATH}${query}` })
}
