/**
 * axios 实例 + 拦截器。
 *
 * ★ 这个文件的内容在三个前端里【完全一致】（除了注释里提到的阅读端地址），
 *   改的时候要三个一起改。见 项目大纲 §7.6。
 */
import axios from 'axios'
import { ElMessage } from 'element-plus'
// Element Plus 按需引入时，ElMessage 是【手动调用】的 API 而不是模板里的组件，
// 插件不会自动带上它的样式。不引这一行，弹出来的提示会是个没样式的裸框。
import 'element-plus/es/components/message/style/css'

import { getToken, clearAuth, redirectToReaderLogin } from './auth'

/** 同一个 tick 里多个请求同时 401 时，只处理一次 */
let handlingUnauthorized = false

const request = axios.create({
  /**
   * 走相对路径 /api，由 Vite 代理转发到 8080（见 vite.config.js）。
   * 不要写成 http://localhost:8080/api —— 那样就是真跨域了，
   * 而且在生产环境会把后端地址硬编码进打包产物里。
   */
  baseURL: '/api',
  timeout: 15000,
})

/* ==================== silent：静默请求 ==================== */

/**
 * `silent` 是本项目自定义的 axios config 标记 —— axios 允许 config 上挂任意
 * 自定义键，它会原样带到 response.config / error.config 上，所以响应拦截器里
 * 能把它读回来。
 *
 * 用法：
 *   request.post('/reader/history', data, { silent: true })   // POST：第三个参数
 *   request.get('/reader/history/1', { silent: true })        // GET：第二个参数
 *
 * ★ 必须放进那个 config 对象，放进 data 里没用 —— 它不是请求体的一部分，
 *   后端也不认识它。
 *
 * ------------------------------------------------------------
 * 为什么需要它：这是 P3 阅读器的一个真实事故场景
 * ------------------------------------------------------------
 * 读者的阅读进度是滚动时每隔 1 秒上报一次的。token 过期那天，第一份上报拿到
 * 401，默认处理是「弹提示 → clearAuth → 600ms 后整页跳登录页」——
 * 【读者正在往下滚，页面突然整个跳走，已经读到的进度全丢】，
 * 而他做的唯一一件事就是继续看书。
 *
 * 静默请求的语义是：这个请求的失败不值得打断用户。
 *   - 不弹任何 ElMessage（业务错误、HTTP 错误、超时、断网都不弹）
 *   - 401 时【什么都不做】：不清本地登录态、不跳转（见 handleUnauthorized）
 *   - 仍然会把 Promise reject 出去 —— 调用方想兜就兜，不兜也不会有人注意到
 *
 * ★ 判 silent 的地方一共 5 处，漏一处就是「半静默」：业务错误提示、
 *   code 401 分支、HTTP 401 分支、其它 HTTP 错误提示、超时/断网提示，
 *   外加 handleUnauthorized 里的 401 统一处理。它们全都在【响应】拦截器里。
 *
 * ★ 请求拦截器不需要判（它不弹任何提示），但 silent 必须能穿过它到达响应侧 ——
 *   这就是上面「要放在 config 里」那条要求的来由。
 *
 * ★ 这个判断写错成「只判成功分支」的话，表现是上报成功时安安静静、
 *   一失败就弹窗，比完全不静默更难查 —— 因为它在正常情况下看起来是对的。
 */
function isSilent(config) {
  return config?.silent === true
}

/* ==================== 请求拦截器 ==================== */

request.interceptors.request.use(
  (config) => {
    const token = getToken()
    if (token) {
      // ★ 和后端 CommonConstant.TOKEN_PREFIX 必须一致：注意 Bearer 后面有个空格
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error),
)

/* ==================== 响应拦截器 ==================== */

request.interceptors.response.use(
  /**
   * HTTP 2xx —— 注意这里【不代表业务成功】，还得再判一次 code。
   *
   * ★ 后端的规则（见后端 ResultHttpStatus）：
   *   code 在 400~599（401/403/404/405/500…）→ HTTP 状态码 = code
   *   业务码（1002 用户名或密码错误、2001 作品不存在…）→ HTTP 200
   *
   * 也就是说：协议层的错误落到下面的 error 分支，
   * 业务错误仍然走这里。所以【两条分支都必须处理 code】——
   * 只写一条，就会出现「后端明明拒绝了，前端却当成功」。
   */
  (response) => {
    const res = response.data

    // 后端返回的不是标准结构（比如直接返回了文件流），原样给回去
    if (res == null || typeof res !== 'object' || !('code' in res)) {
      return response.data
    }

    if (res.code === 200) {
      /*
       * ★ 只返回 data，不返回整个 { code, message, data }。
       *
       * 这样调用方写的是：
       *     const info = await getPingInfo()
       *     console.log(info.status)
       * 而不是：
       *     const res = await getPingInfo()
       *     console.log(res.data.status)   // 多一层 .data
       *
       * code 已经在拦截器里判过了，调用方拿到结果就说明成功了，
       * 不该再让它关心 code —— 否则每个页面都要写一遍 if (res.code === 200)。
       *
       * ★ data 可能是 undefined：后端配了 non_null，Result.success() 的 body 里
       *   根本没有 data 这个 key（比如 POST /api/reader/history 只回一句
       *   "已记录"）。判断「有没有」请用 `?? null` 或真值判断，
       *   别写 `=== null`，那个永远不成立。
       */
      return res.data
    }

    if (res.code === 401) {
      handleUnauthorized(response.config)
      return Promise.reject(new Error(res.message || '登录已过期'))
    }

    // 其余业务错误：弹提示 + 抛出，让调用方可以用 try-catch 兜住
    if (!isSilent(response.config)) {
      ElMessage.error(res.message || '请求失败')
    }
    return Promise.reject(new Error(res.message || '请求失败'))
  },

  /** HTTP 非 2xx，或者压根没连上（后端没启动、断网、超时） */
  (error) => {
    const res = error.response
    /*
     * ★ 静默请求从 error.config 取 silent。取不到时（比如请求被拦截器提前
     *   reject、压根没走到发请求那步）isSilent 返回 false，即「默认不静默」。
     *   这个默认方向是安全的：多弹一次提示只是烦，漏弹一次会让真正的失败
     *   无人知晓 —— 而「失败没人知道」正是最难查的那类问题。
     */
    const silent = isSilent(error.config)

    if (res) {
      if (res.status === 401) {
        handleUnauthorized(error.config)
        return Promise.reject(error)
      }

      // 尽量把后端给的具体原因透出来，而不是笼统的「网络错误」
      const message = res.data?.message || `请求失败（HTTP ${res.status}）`
      if (!silent) {
        ElMessage.error(message)
      }
      return Promise.reject(new Error(message))
    }

    if (error.code === 'ECONNABORTED') {
      if (!silent) {
        ElMessage.error('请求超时，请检查网络或后端服务')
      }
      return Promise.reject(error)
    }

    /*
     * 没有 response 对象，说明请求根本没发出去 ——
     * 最常见的原因是后端没启动，或者 Vite 代理配错了。
     * 这条提示要明确指向后端，不然很容易以为是前端的问题。
     */
    if (!silent) {
      ElMessage.error('连接不上后端服务，请确认 backend 已在 8080 端口启动')
    }
    return Promise.reject(error)
  },
)

/**
 * 401 统一处理。
 *
 * ★ 三个应用里跳的都是【阅读端】的登录页，不是自己的 —— 见 §7.5 第 3 条约定。
 *
 * ★★ 静默请求在这里【直接返回，什么都不做】，包括不 clearAuth。
 *    这一步容易写错：直觉上「顺手清掉本地登录态」看起来更负责任，但后果更糟 ——
 *      ① clearAuth() 只动 localStorage，【Pinia 里的 store 不会跟着变】，
 *         于是页面上仍显示「已登录」，而所有请求都在悄悄失败，
 *         用户在两个互相矛盾的信号之间完全没法判断发生了什么；
 *      ② 何况 401 的那个 token 本来就已经是废的，清不清都不影响安全性，
 *         清它换不来任何好处。
 *    正确的分工是：【静默请求只负责「不打扰」，登录态失效由下一次非静默
 *    请求统一处理】—— 那时弹提示、跳登录页都是合适的，因为用户正在做一件
 *    他期待有反馈的事。这条路径自洽，不需要额外的同步代码。
 */
function handleUnauthorized(config) {
  if (isSilent(config)) {
    return
  }
  if (handlingUnauthorized) return
  handlingUnauthorized = true

  ElMessage.warning('登录已过期，请重新登录')
  clearAuth()

  // 稍微延迟一下，让上面的提示能显示出来再跳走（否则页面立刻跳转，用户看不到原因）
  setTimeout(() => {
    redirectToReaderLogin()
    handlingUnauthorized = false
  }, 600)
}

export default request
