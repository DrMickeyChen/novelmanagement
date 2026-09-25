/**
 * 认证接口 —— 对应后端的 AuthController。
 *
 * ★ 路径必须和 reader-web/src/api/auth.js 【逐字相同】。
 *   两边各写各的，路径歪一个就会出现「阅读端登录了，移动端还是未登录」
 *   这类看起来灵异、实际只是拼错一个字符的问题。
 *
 * 目录约定（§四）：每个业务模块一个文件，组件里不直接写 uni.request，
 * 接口路径只在这一处出现。
 */
import request from '@/utils/request'

/**
 * 登录。
 *
 * @param {{username: string, password: string}} data
 * @returns {Promise<{token: string, tokenType: string, expiresIn: number, user: object}>}
 *
 * 失败时（用户名或密码错误）后端返回 code 1002，
 * request.js 的封装会弹出提示并 reject —— 调用方不用自己判 code。
 *
 * ★ 后端对「用户名不存在」和「密码错误」返回的是同一个 1002，
 *   这是故意的（防止攻击者靠错误码枚举出哪些用户名存在），
 *   所以前端也不要去区分这两种情况。
 */
export function login(data) {
  return request.post('/auth/login', data)
}

/**
 * 注册。默认注册成普通读者（READER）。
 *
 * ★ 入参里【没有 role 字段】—— 谁能获得什么角色只能由服务端决定，
 *   前端传什么都不作数（后端压根不接收这个字段）。
 */
export function register(data) {
  return request.post('/auth/register', data)
}

/**
 * 退出登录。
 *
 * ★ 只让【当前这台设备】下线，同一账号在别的设备上不受影响。
 *   实现方式是后端 Redis 里的 key 带了 jti（每个 token 一个编号），
 *   登出只删自己那条记录。
 *
 * ★★ 但注意：**localStorage 里的 token 是共享的**。
 *    所以「在移动端退出」会把移动端和三个桌面端【一起】退掉 ——
 *    因为大家读的是同一个 key。这不是 bug，是共享登录态的必然结果，
 *    也正是验收表里「退出登录 → 三端 + 移动端一起退出」那一条。
 *    反过来说：四端在同一台设备上本来就是"同一个人"，
 *    服务端那条 jti 记录删掉之后，谁也用不了这个 token 了。
 */
export function logout() {
  return request.post('/auth/logout')
}

/**
 * 取当前登录用户。
 *
 * ★ 移动端【用得上】它，桌面端反而用不上：App/小程序从后台切回来时，
 *   页面不一定会重新加载，而 token 可能在别处（比如同一台手机上的
 *   浏览器里）已经被退掉了。冷启动时用它校验一次本地缓存，
 *   能避免"界面显示已登录、实际所有请求都在 401"。
 *   （这条同时是判断 2 的一个真实使用场景，所以留着。）
 */
export function getMe() {
  return request.get('/auth/me')
}
