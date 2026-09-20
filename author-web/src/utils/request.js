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
   * HTTP 2xx —— 注意这里【不代表业务成功】。
   *
   * 后端约定业务错误也返回 HTTP 200，把错误码放在 body 的 code 里
   * （见后端 Result.java 的说明）。所以这里要再判一次 code。
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
       */
      return res.data
    }

    if (res.code === 401) {
      handleUnauthorized()
      return Promise.reject(new Error(res.message || '登录已过期'))
    }

    // 其余业务错误：弹提示 + 抛出，让调用方可以用 try-catch 兜住
    ElMessage.error(res.message || '请求失败')
    return Promise.reject(new Error(res.message || '请求失败'))
  },

  /** HTTP 非 2xx，或者压根没连上（后端没启动、断网、超时） */
  (error) => {
    const res = error.response

    if (res) {
      if (res.status === 401) {
        handleUnauthorized()
        return Promise.reject(error)
      }

      // 尽量把后端给的具体原因透出来，而不是笼统的「网络错误」
      const message = res.data?.message || `请求失败（HTTP ${res.status}）`
      ElMessage.error(message)
      return Promise.reject(new Error(message))
    }

    if (error.code === 'ECONNABORTED') {
      ElMessage.error('请求超时，请检查网络或后端服务')
      return Promise.reject(error)
    }

    /*
     * 没有 response 对象，说明请求根本没发出去 ——
     * 最常见的原因是后端没启动，或者 Vite 代理配错了。
     * 这条提示要明确指向后端，不然很容易以为是前端的问题。
     */
    ElMessage.error('连接不上后端服务，请确认 backend 已在 8080 端口启动')
    return Promise.reject(error)
  },
)

/**
 * 401 统一处理。
 *
 * ★ 两个应用（author-web / admin-web）里跳的是【阅读端】的登录页，
 *   不是自己的 —— 见 §7.5 第 3 条约定。
 */
function handleUnauthorized() {
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
