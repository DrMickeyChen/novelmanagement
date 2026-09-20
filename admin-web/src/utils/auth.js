/**
 * 登录态的本地存储读写。
 *
 * ============================================================
 * ★★★ 这个文件的核心是「三个应用必须用完全一样的存储 key」★★★
 * ============================================================
 *
 * 见 项目大纲 §7.5。三个前端是【三个独立应用】，各跑各的端口，
 * 它们之间没有任何代码共享 —— 唯一的连接点就是浏览器的 localStorage。
 *
 * ------------------------------------------------------------
 * ★ localStorage 是按【origin = 协议 + 主机 + 端口】隔离的
 * ------------------------------------------------------------
 *
 * 这一条曾经踩过坑，特意写清楚：
 *
 *   端口也参与隔离。localhost:5173 和 localhost:5174 是【两个 origin】，
 *   互相读不到对方的 localStorage。（同源策略里端口是有效成分；
 *   Cookie 不受端口限制，但 localStorage 受 —— 两者规则不一样，
 *   拿 Cookie 的直觉去套 localStorage 就会错。）
 *
 *   实测记录（在 5173 写入 novel_token，然后 from 5174 / 5175 读）：
 *       ON_5173 = WRITTEN_ON_5173
 *       ON_5174 = NULL(读不到)
 *       ON_5175 = NULL(读不到)
 *
 * 所以【不能】指望「都是 localhost 就自动共享」。要让三端免登录，
 * 必须让它们真的同源。做法是照搬生产的 Nginx 拓扑：
 *
 *   生产：https://域名/reader/  /author/  /admin/       ← 同域名同端口 → 同源 ✅
 *   开发：http://localhost:3000/reader/  /author/  /admin/
 *         （由 dev-proxy 转发到 5173 / 5174 / 5175）
 *
 * ★ 开发期务必从 http://localhost:3000/ 进入，不要直接开 5174。
 *   直接开单个端口仍然能用（各自有 /api 代理），但那是一个孤立的 origin，
 *   登录态不共享 —— 只适合单独调某一个应用时用。
 *
 * ------------------------------------------------------------
 * 结论：
 *   三个应用同源 + 读写同一个 key ⟹ 用户在阅读端登录一次，
 *   跳到创作中心和管理端就是已登录状态，不需要再登一次。
 *
 * 反过来说：只要有【一个】应用把 key 写成 'token' 而别人写 'novel_token'，
 * 就会出现「阅读端登录了，创作中心还是未登录」这种看似灵异的问题。
 * 所以这两个常量必须原样复制到另外两个应用的同名文件里。
 *
 * ★ 注意：本文件不依赖 Pinia。
 *   因为 request.js（axios 拦截器）会在 Vue 组件树【外面】执行，
 *   那里调 useUserStore() 有 Pinia 尚未初始化/脱离上下文的风险。
 *   存储读写用纯函数，谁都能调，最稳。
 *   Pinia 的 stores/user.js 只是在这些纯函数外面包一层响应式。
 */

/** ★ 三个应用共用。token 存这里 */
export const TOKEN_KEY = 'novel_token'

/** ★ 三个应用共用。用户信息（含角色、权限点）存这里 */
export const USER_KEY = 'novel_user'

/* ==================== 三个应用的地址 ==================== */

/**
 * 网关地址 —— 三个应用的共同入口。
 *
 * 开发期是 dev-proxy 的 :3000；上线后换成真实域名即可，
 * 这里读环境变量就是为了换域名时不用改代码。
 */
export const GATEWAY_URL = import.meta.env.VITE_GATEWAY_URL || 'http://localhost:3000'

/*
 * ============================================================
 * ★★★ 下面三个地址必须以【斜杠结尾】★★★
 * ============================================================
 *
 * 这不是风格问题，是踩过的坑：
 *
 *   http://localhost:3000/reader    ← 没有结尾斜杠 → 404
 *   http://localhost:3000/reader/   ← 有结尾斜杠   → 正常
 *
 * 因为三个应用都是部署在子路径下的（Vite 的 base = '/reader/'），
 * 服务器把 /reader 当成一个「文件」去找，找不到就 404；
 * 只有 /reader/ 才会被当成目录、进而返回它的 index.html。
 * （生产的 Nginx 一般会自动 301 补上这个斜杠，但那是服务器的行为，
 *   换一个服务器、换一份配置就未必了 —— 与其指望服务器宽容，
 *   不如自己把地址写对。）
 *
 * 所以拼接时也要注意：
 *   ✅ `${READER_WEB_URL}login`   → http://localhost:3000/reader/login
 *   ❌ `${READER_WEB_URL}/login`  → http://localhost:3000/reader//login
 *
 * ★ 如果要用环境变量覆盖（VITE_READER_WEB_URL 等），
 *   注意那个值也必须以斜杠结尾，三个应用要写成同一个值。
 */

/**
 * 阅读端地址（以 / 结尾）。
 *
 * 401 时统一跳这里，而不是各端跳各自的登录页 ——
 * 否则用户会看到三个登录入口，不知道该用哪个（§7.5 第 3 条约定）。
 */
export const READER_WEB_URL = import.meta.env.VITE_READER_WEB_URL || `${GATEWAY_URL}/reader/`

/** 创作中心地址（以 / 结尾），用于跨应用跳转 */
export const AUTHOR_WEB_URL = import.meta.env.VITE_AUTHOR_WEB_URL || `${GATEWAY_URL}/author/`

/** 管理端地址（以 / 结尾），用于跨应用跳转 */
export const ADMIN_WEB_URL = import.meta.env.VITE_ADMIN_WEB_URL || `${GATEWAY_URL}/admin/`

/* ==================== token ==================== */

export function getToken() {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token) {
  localStorage.setItem(TOKEN_KEY, token)
}

/* ==================== 用户信息 ==================== */

/**
 * 读用户信息。
 *
 * 这里必须 try-catch：localStorage 里存的是字符串，
 * 如果被手动改坏了（或者上个版本存的是别的格式），
 * JSON.parse 会抛异常 —— 而它通常在路由守卫里被调用，
 * 一抛就白屏，报错信息还指不到这里。
 */
export function getUser() {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw)
  } catch {
    // 数据坏了就清掉，当作未登录处理，比白屏好
    localStorage.removeItem(USER_KEY)
    return null
  }
}

export function setUser(user) {
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

/* ==================== 清理与跳转 ==================== */

/** 退出登录 / token 失效时调用 */
export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

/**
 * 跳回阅读端登录页。
 *
 * ★ 用 location.href 而不是 router.push（§7.5 第 2 条约定）。
 *   在 author-web / admin-web 里跳转到 reader-web 是【跨应用】跳转，
 *   本应用的路由器根本管不到另一个应用 —— router.push('/login')
 *   只会在当前应用里找一个不存在的路由，白屏。
 *
 * redirect 参数是为了登录成功后能跳回来，注意它传的是【地址】不是 token。
 * ★ 绝对不要把 token 放在 URL 里（§7.5 明确否掉了那个方案）——
 *   URL 会留在浏览器历史记录、服务器访问日志、Referer 头里，
 *   等于把凭证到处散播。
 */
export function redirectToReaderLogin() {
  const back = encodeURIComponent(window.location.href)
  // ★ READER_WEB_URL 自带结尾斜杠，所以这里是 `login` 而不是 `/login`
  window.location.href = `${READER_WEB_URL}login?redirect=${back}`
}
