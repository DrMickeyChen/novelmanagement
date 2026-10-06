/**
 * 「登录 / 注册成功之后跳回原来想去的页面」—— 登录页与注册页共用。
 *
 * ★ 为什么单独一个文件，而不是写在登录页里让注册页 import 那个页面：
 *   页面之间互引组件会把两页的加载绑在一起（注册页 import 登录页 = 连带
 *   把它那一整套表单依赖也拉进来）。这里要的只是一段判断，搬出来是干净的。
 *
 * ★★ 更重要的是：下面 `goAfterLogin` 里有一道**开放重定向防护**。
 *   它属于安全逻辑，复制成两份的代价不是「多三十行」，而是
 *   「以后出了洞只会修其中一份，另一份还是漏的」——
 *   所以宁可让两个页面都依赖这一个文件。
 *
 * ★ 这两个函数都不碰组件状态、不发请求，纯粹是「给定当前路由，决定跳哪」。
 */

/**
 * 登录（或注册）成功后跳回来源页。
 *
 * redirect 是 auth.js 里 401 跳转时带上的参数 ——
 * 用户在创作中心 token 过期被踢回来，重新登录后应该回到创作中心，
 * 而不是停在阅读端首页。注册页同理：从详情页点「注册」过来的，
 * 注册完应该回到那本书，而不是被丢在首页。
 *
 * ★ 这里只接受【本应用内】的相对路径：如果直接把外部 URL 拿来跳，
 *   就是个开放重定向漏洞（攻击者构造 ?redirect=https://钓鱼站 骗用户点）。
 *   所以用 startsWith('/') 卡一道，「//evil.com」这种也要一起挡掉。
 *
 * ★★ 补一个「不成形的 redirect」：`new URL()` 对 `?redirect=httpfoo`
 *   这种手写值会抛 TypeError。不接住的话这个异常会冒到调用方
 *   （`handleLogin` / `handleRegister`）的 catch 里被静默吞掉 ——
 *   用户看到「欢迎回来」，页面却停在原地不动。落到默认跳转即可。
 *
 * @param {import('vue-router').Router} router
 * @param {import('vue-router').RouteLocationNormalized} route 当前路由（读它的 query.redirect）
 */
export function goAfterLogin(router, route) {
  const redirect = route.query.redirect

  if (typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')) {
    router.replace(redirect)
    return
  }

  if (typeof redirect === 'string' && redirect.startsWith('http')) {
    // 从另外两个端跳回来的，是完整地址，且在自己的 localhost 域内
    try {
      const url = new URL(redirect)
      if (['localhost', '127.0.0.1'].includes(url.hostname)) {
        window.location.href = redirect
        return
      }
    } catch {
      // 见上面第二颗星：不成形的地址，走默认跳转
    }
  }

  router.replace('/')
}

/**
 * 拼「登录 ↔ 注册」互跳的地址，并**原样带上** redirect。
 *
 * ★ 不带的话：「详情页 → 登录 → 点注册 → 注册成功」这一圈回来会落在首页，
 *   用户想做的事得自己重新找 —— 那多半就不会再做了
 *   （`components/LoginPrompt.vue` 的四条规矩 ④ 说的就是这件事）。
 *
 * ★ 必须 encodeURIComponent：redirect 值里可能带 `?` `&` `=`，
 *   直接拼会和目标地址自己的 query 串在一起，跳回去时筛选条件就丢了。
 *   与 `LoginPrompt.vue:54` 是同一个手法。
 *
 * @param {string} path 目标页，如 '/login'
 * @param {import('vue-router').RouteLocationNormalized} route 当前路由
 */
export function withRedirect(path, route) {
  const redirect = route.query.redirect
  if (typeof redirect !== 'string' || !redirect) {
    return path
  }
  return `${path}?redirect=${encodeURIComponent(redirect)}`
}
