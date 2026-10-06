/**
 * 「登录 / 注册成功之后跳回原来想去的页面」—— 移动端登录页与注册页共用。
 *
 * ★ 为什么要单独一个文件：下面 `resolveAfterLoginUrl` 里那道判断是移动端版本的
 *   **开放重定向防护**，属于安全逻辑。复制成两份的代价不是「多二十行」，
 *   而是「以后出了洞只会修其中一份，另一份还是漏的」——
 *   所以宁可让两个页面都依赖这一个文件。
 *
 * ★★ `resolveAfterLoginUrl` 是**纯函数**（模块顶层不碰 `uni`）——
 *   这样它可以被 node 侧直接断言，不需要跑起整个 uni 运行时。
 *   只有 `goAfterLogin` 里才碰 `uni`。
 */

/**
 * 决定登录成功后该 reLaunch 到哪。
 *
 * ★★ 只接受以 `/pages/` 开头的路径 —— 这是移动端的开放重定向防护。
 *    桌面端那边挡的是 `startsWith('/') && !startsWith('//')`（浏览器地址），
 *    这里挡的必须是 uni 的**页面路径命名空间**：`redirect` 是个页面路径，
 *    任何不以 /pages/ 开头的东西（绝对 URL、`//evil.com`、
 *    `javascript:` 之类）都不该被 reLaunch 拿去用。
 *    ★ 少了这一道，攻击者构造 ?redirect=https://钓鱼站 就能把用户带走。
 *
 * @param {string} redirect 形如 /pages/reader/reader?id=3 的页面路径；空值走首页
 * @returns {string} 可以安全交给 uni.reLaunch 的地址
 */
export function resolveAfterLoginUrl(redirect) {
  if (typeof redirect === 'string' && redirect.startsWith('/pages/')) {
    return redirect
  }
  return '/pages/index/index'
}

/** 跳回来源页（或首页）。★ 用 reLaunch：登录/注册后不该还能“返回”到表单页 */
export function goAfterLogin(redirect) {
  uni.reLaunch({ url: resolveAfterLoginUrl(redirect) })
}

/**
 * 拼「登录 ↔ 注册」互跳的地址，并**原样带上** redirect。
 *
 * ★ 不带的话：「详情页 → 登录 → 点注册 → 注册成功」这一圈回来会落在首页，
 *   用户想做的事得自己重新找 —— 那多半就不会再做了
 *   （桌面端 `components/LoginPrompt.vue` 的四条规矩 ④ 就是这件事）。
 *
 * ★ 必须 encodeURIComponent：redirect 值里带着 `?id=3` 这样的 query，
 *   直接拼会和目标地址自己的 query 串在一起，跳回去时参数就丢了。
 *   与 `utils/auth.js` 的 redirectToLogin 是同一个手法（那边解、这边编）。
 *
 * @param {string} path 目标页，如 '/pages/login/login'
 * @param {string} redirect 当前要回跳的页面路径（可为空）
 */
export function withRedirect(path, redirect) {
  if (!redirect) {
    return path
  }
  return `${path}?redirect=${encodeURIComponent(redirect)}`
}
