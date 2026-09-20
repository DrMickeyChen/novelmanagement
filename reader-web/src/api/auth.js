/**
 * 认证接口 —— 对应后端的 AuthController。
 *
 * ★ 本文件在三个前端里【必须逐字节相同】，改的时候三个一起改。
 *   原因见 utils/auth.js 的说明：三个应用是三个独立应用，
 *   唯一的连接点是 localStorage，接口路径写歪一个就会出现
 *   「阅读端登录了，创作中心还是未登录」这类问题。
 *
 * 目录约定（§四）：每个业务模块一个文件，组件里不直接写 axios，
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
 * request.js 的拦截器会弹出提示并 reject —— 调用方不用自己判 code。
 *
 * ★ 注意后端对「用户名不存在」和「密码错误」返回的是同一个 1002，
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
 *
 * P1 阶段还没有注册页面，这个函数先备着，页面后续再补。
 */
export function register(data) {
  return request.post('/auth/register', data)
}

/**
 * 退出登录。
 *
 * ★ 只让【当前这台设备】下线，同一账号在别的设备上不受影响。
 *   实现方式是后端 Redis 里的 key 带了 jti（每个 token 一个编号），
 *   登出只删自己那条记录。详见后端 LoginUser.jti 的说明。
 */
export function logout() {
  return request.post('/auth/logout')
}

/**
 * 取当前登录用户。
 *
 * 暂时没有页面用到（登录接口已经顺带返回了用户信息），
 * 留着是因为刷新页面后想「用服务端的最新数据校验一下本地缓存」时会用到 ——
 * 比如角色被管理员改了，本地 localStorage 里还是旧的。
 */
export function getMe() {
  return request.get('/auth/me')
}
