/**
 * 网络请求封装（uni.request 版）。
 *
 * ★ 本文件是 reader-web/src/utils/request.js（axios 版）的移动端对应物。
 *   两边**语义必须一致**，实现必须不同 —— 因为它们要解决的问题不同：
 *   那边要跨三个独立应用统一 401 策略，这边要跑在没有 axios 的运行时里。
 *
 * ============================================================
 * ★★★ 与 axios 最大的差别：4xx / 5xx 也走 success 回调 ★★★
 * ============================================================
 *
 * axios 是按 HTTP 状态码分流的：2xx 进 then，其余进 catch。
 * uni.request 不是 —— 它【只按"请求有没有发出去"分流】：
 *
 *   连上了、服务器回了 401     → success（success 里 res.statusCode === 401）
 *   连上了、服务器回了 500     → success
 *   DNS 失败 / 断网 / 超时     → fail
 *
 * 所以【"进了 success"完全不等于"请求成功"】。照 axios 的直觉写
 * （success 里直接读 res.data.code），401 会被当成一次正常响应，
 * 页面拿着错误体当数据渲染。这就是 R5。
 *
 * 处置：success 里第一件事就是读 `res.statusCode` 自己分流一次，
 * 把 uni 的"一层"还原成 axios 的"两层"（HTTP 层 + 业务码层）——
 * 之后本文件的结构就能和桌面端那份逐条对上了。
 *
 * ============================================================
 * ★★ silent：静默请求，语义与桌面端逐条一致
 * ============================================================
 *
 * 背景（P3 的真实事故）：阅读进度是滚动时每秒上报一次的。token 过期那天，
 * 第一份上报拿到 401，默认处理是「弹提示 → 清登录态 → 600ms 后跳登录页」——
 * 【读者正在往下滚，页面突然整个跳走，读到的进度全丢】，
 * 而他做的唯一一件事就是继续看书。
 *
 * 静默请求的语义：这个请求的失败不值得打断用户。
 *   - 不弹任何提示（业务错误、HTTP 错误、超时、断网都不弹）
 *   - 401 时【什么都不做】：不清本地登录态、不跳转（见 handleUnauthorized）
 *   - 仍然会把 Promise reject 出去 —— 调用方想兜就兜，不兜也没人注意到
 *
 * ★★ 判 silent 的地方和桌面端一样是 5 处，漏一处就是「半静默」：
 *    ① 业务错误提示  ② 业务码 401 分支  ③ HTTP 401 分支
 *    ④ 其它 HTTP 错误提示  ⑤ 超时/断网提示
 *    外加 handleUnauthorized 里的 401 统一处理。
 *
 * ★ 这个判断写错成「只判成功分支」的话，表现是上报成功时安安静静、
 *   一失败就弹窗，比完全不静默更难查 —— 因为它在正常情况下看起来是对的。
 *
 * ============================================================
 * ★ BASE_URL 为什么是相对路径，以及小程序要怎么办
 * ============================================================
 *
 * 走相对路径 `/api`，由 dev-proxy（开发）/ Nginx（生产）转发到网关。
 * 不写成 http://localhost:8080/api —— 那样就是真跨域，而且会把后端地址
 * 硬编码进打包产物里。
 *
 * ★ 但小程序运行时对相对 URL 是不认的，它要求绝对地址。
 *   处置方式是【零代码】的：构建时给 VITE_API_BASE 一个绝对前缀即可。
 *
 *       VITE_API_BASE=https://域名/api npm run build:mp-weixin
 *
 *   这比 `#ifdef MP-WEIXIN` 好，理由不是"优雅"，而是【可测】：
 *   条件编译的两个分支里必然有一个在本机跑不到、也验不了，
 *   而环境变量这条路径在 H5 上是**每次构建都真的在走**的
 *   （默认值就是本文件当前生效的那条）。见 docs/E3-uni-app.md 的台账。
 */
import { getToken, clearAuth, redirectToLogin } from './auth'

/** 同一个 tick 里多个请求同时 401 时，只处理一次 */
let handlingUnauthorized = false

/** 请求超时（毫秒）。与桌面端保持一致 —— 换端不改契约。 */
const TIMEOUT = 15000

/**
 * ★★ 这个常量**必须导出**，因为 `uni.uploadFile` 是全项目唯一一条
 *    不走 `request()` 的请求通道（见 pages/profile/profile.vue）。
 *
 * 它带来的东西和 `request()` 完全不一样，那边那套封装一点都用不上：
 *   · 它自己拼 multipart，**不需要也不该**设 Content-Type
 *   · 它跑在 `request.js` 之外，所以 Authorization 头要**自己加**
 *   · 它的响应体是**字符串**（不是 uni.request 那样自动解析好的对象），
 *     要自己 `JSON.parse`
 *   · 它同样把 4xx/5xx 放进 success（R5 在它身上照样成立）
 *
 * ★ 导出的是**已经算好的值**，不是让调用方自己读 `import.meta.env` ——
 *   两处各读一次 `VITE_API_BASE` 的话，默认值 `'/api'` 就要写两遍，
 *   迟早有一处被改掉而另一处没改，症状是"普通接口通了、上传 404"。
 */
/*
 * ★★★ 默认值【按平台分叉】：H5 用相对 `'/api'`，小程序用绝对地址。
 *
 * 为什么 H5 能用相对的、小程序不能：
 *   H5 靠「同源」自动正确 —— 开发期由 dev-proxy(`:3000`) 转发、生产由 Nginx 转发。
 *   而小程序的 `wx.request` **要求完整 URL**，相对地址直接
 *   `request:fail invalid url "/api/..."` —— 表现是**界面正常、每个接口都失败**
 *   （页面壳子是本地渲染的，所以「能用」和「一个请求都没通」可以同时成立）。
 *
 * ★★★ 为什么这里必须用 `#ifdef` 写死一个默认值，而**不能**只靠环境变量：
 *   HBuilderX **不经过 `package.json` 的 npm 脚本** —— 它自己直接调
 *   `@dcloudio/vite-plugin-uni/bin/uni.js -p mp-weixin`（实测命令行的原文，
 *   `HBuilderX/plugins/uniapp-extension/static/kill.js` 就是它的启动器）。
 *   而 Vite 只加载 `.env.[mode]`，uni 的 mode 默认取 `NODE_ENV`（= `development`/`production`，
 *   **不含平台名**，见 `vite-plugin-uni/dist/cli/utils.js:103`）→
 *   所以 `mobile-web/.env.mp-weixin` **只有**在命令行显式传 `-m mp-weixin` 时才会被读到。
 *   在 HBuilderX 里点「运行到小程序模拟器」，那个文件是**永远不会生效**的。
 *   `#ifdef` 是 uni 自己的预处理器，**任何**构建入口都会执行它，与怎么启动无关。
 *
 * ★ 因此本文件是【小程序端接口地址的真源】；`mobile-web/.env.mp-weixin` 只是
 *   **命令行构建**时的覆盖手段（例如真机预览要换成局域网 IP）。
 *   在 HBuilderX 里跑要改地址，就改下面这个常量 —— 改 env 文件对它无效。
 *
 * ★ 默认值用 `localhost`：微信开发者工具的**模拟器**跑在本机上，localhost 就指本机。
 *   真机预览/真机调试要换成开发机的**局域网 IP**（本机实测 192.168.1.189）——
 *   那时 localhost 指的是**手机自己**，症状是模拟器正常、真机一个请求都不通。
 */
// #ifdef MP-WEIXIN
const DEFAULT_API_BASE = 'http://localhost:8080/api'
// #endif

/*
 * ★★★ APP（安卓 / iOS 原生运行时）也必须给【绝对地址】—— 理由与小程序不同，结论一样：
 *   原生运行时里没有 origin 这个概念，`/api` 没有解析基准，请求直接失败。
 *
 * ★★ 而这里必须用【局域网 IP】，不能用 localhost：
 *   **模拟器 / 真机上的 localhost 指的是【设备自己】，不是这台开发机。**
 *   症状是「H5 全正常、App 一个请求都不通」，且它与「后端没起」长得**一模一样** ——
 *   分辨方法：在设备的浏览器里开 http://<局域网IP>:8080/api/ping ，
 *   通 = 地址问题（就是这条），不通 = 后端 / 防火墙问题。
 *
 * ★ 192.168.1.189 是本机实测的局域网 IP（与 .env.mp-weixin 里记的是同一个值）。
 *   ★★ 换网络（换 WiFi / 换网线）它就会变 —— 写死在源码里是 **dev 专用**的便利，
 *      不是交付配置：真发 App 要走 https://域名/api（用 VITE_API_BASE 覆盖，
 *      见文件头「小程序要怎么办」那段，App 同理）。
 *   ★ 网关监听在 0.0.0.0:8080，所以这条路是通的；连不上先查
 *      ① 设备与开发机是否同网段 ② Windows 防火墙有没有放行 8080。
 *
 * ★ 为什么改在这里、而不是加一个 .env.app：与上面小程序那条同因 ——
 *   HBuilderX「运行到手机或模拟器」**不经过 npm 脚本**，而 Vite 的 mode 只取 NODE_ENV
 *   （不带平台名，`vite-plugin-uni/dist/cli/utils.js:103`），
 *   所以任何 `.env.[平台]` 在 HBuilderX 里都不会被加载。本文件的分叉才是真源。
 *
 * ★★ 顺带：这一改让 assetUrl() 也一起对了 —— 它从绝对 API_BASE_URL 里剥出 origin
 *   （asset.js 那个 IIFE 的第 ① 种情形），头像那类根相对 `/uploads/**`
 *   本来就是靠这一步补全的，不用改第二处。
 */
// #ifdef APP-PLUS
const DEFAULT_API_BASE = 'http://192.168.1.189:8080/api'
// #endif

/*
 * ★★ 用【嵌套 #ifndef】而不是 `#ifndef MP-WEIXIN || APP-PLUS`：
 *   后者的求值方向（「非 A，或 B」还是「非 (A 或 B)」）本项目从未实测过，
 *   而猜错的后果是**静默**的 —— H5 会拿到绝对地址，于是从「同源」变成「跨域」，
 *   报错长得像后端问题（实测：网关不发 CORS 头、预检 OPTIONS 直接 403）。
 *   嵌套没有任何歧义，多两行换掉一个「猜」。
 */
// #ifndef MP-WEIXIN
// #ifndef APP-PLUS
const DEFAULT_API_BASE = '/api'
// #endif
// #endif

export const API_BASE_URL = import.meta.env.VITE_API_BASE || DEFAULT_API_BASE

/** ★ 内部用的短名字。**只有这一个地方读 `import.meta.env`**，见上面那段 */
const BASE_URL = API_BASE_URL

/* ==================== silent ==================== */

/**
 * `silent` 是本工程自定义的请求标记，用法和桌面端一样：
 *
 *   request.post('/reader/history', data, { silent: true })   // POST：第三个参数
 *   request.get('/reader/history/1', { silent: true })        // GET：第二个参数
 *
 * ★ 必须放进那个 config 对象，放进 data 里没用 —— 它不是请求体的一部分，
 *   后端也不认识它。（axios 会把自定义 config 原样带到 config 上；
 *   这里是我们自己传的闭包变量，效果等价。）
 */
function isSilent(config) {
  return config?.silent === true
}

/** 统一提示。★ icon 必须是 'none' —— 默认的 'success' 会把长文案截断并显示一个对勾 */
function toast(title) {
  uni.showToast({ title, icon: 'none', duration: 2500 })
}

/* ==================== 发送 ==================== */

/**
 * uni.request 在 H5 上会把 application/json 的响应自动解析成对象，
 * 但 content-type 不标准时会把原始字符串交回来。这里兜一道，
 * 好让下面的判断可以安心假定「body 要么是对象、要么本来就不是 JSON」。
 */
function normalizeBody(data) {
  if (typeof data !== 'string') return data
  try {
    return JSON.parse(data)
  } catch {
    return data
  }
}

/**
 * 把 config.params 拼成查询串。
 *
 * ★★ 这个函数的存在理由是「api/ 目录要能照搬」：
 *    reader-web 的接口文件全是 axios 写法 ——
 *
 *        request.get('/novels', { params: { page, size, categoryId } })
 *
 *    axios 认 `params` 这个键，uni.request 不认（它只认 `data`）。
 *    没有这一步，照搬过来的每一个列表接口都会**静默丢掉全部查询条件**：
 *    请求发出去了、返回 200、列表也渲染了 —— 只是永远是第一页、
 *    永远不筛选。这是最难发现的一类错，因为它长得和成功一模一样。
 *
 * ★ 为什么自己拼而不把 params 塞进 uni.request 的 data：
 *   各端对 data 的处理规则不一样（有的平台会对数组做特殊序列化、
 *   有的不跳过 null），拼出来的串可能不同。自己拼 → 各端逐字节一致，
 *   也和桌面端 axios 的行为对齐。
 *
 * ★ 跳过 null / undefined、保留空串 —— 这是 axios 的行为，照抄。
 *   空串要不要发出去是有区别的：`?keyword=` 和「没有 keyword」在
 *   后端的判定不同（前者是"关键词是空的"，后者是"没打算筛"），
 *   而调用方写 `keyword: ''` 时的意图是前者。
 */
function buildQuery(params) {
  if (!params || typeof params !== 'object') return ''

  const parts = []
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null) continue
    parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(value)}`)
  }
  return parts.length ? `?${parts.join('&')}` : ''
}

function send(method, url, data, config) {
  const silent = isSilent(config)

  // ★ GET / DELETE 没有请求体，参数一律走查询串；其余方法的 params 也照样拼上
  const query = buildQuery(config?.params)
  const fullUrl = `${BASE_URL}${url}${query}`
  const body = method === 'GET' || method === 'DELETE' ? undefined : data

  return new Promise((resolve, reject) => {
    const header = { 'Content-Type': 'application/json' }

    const token = getToken()
    if (token) {
      // ★ 和后端 CommonConstant.TOKEN_PREFIX 必须一致：注意 Bearer 后面有个空格
      header.Authorization = `Bearer ${token}`
    }

    uni.request({
      url: fullUrl,
      method,
      data: body,
      header,
      timeout: TIMEOUT,

      success: (res) => {
        const status = res.statusCode
        const body = normalizeBody(res.data)

        /* ---------- 第一层：HTTP 状态 ---------- */

        /*
         * ★★ 这一段就是 R5 的处置。uni 把 4xx/5xx 也塞进 success，
         *    所以这里必须自己判一次 —— 漏了它，401 会被当作正常响应。
         */
        if (status < 200 || status >= 300) {
          if (status === 401) {
            handleUnauthorized(silent)
            return reject(new Error(body?.message || '登录已过期'))
          }
          // 尽量把后端给的具体原因透出来，而不是笼统的「请求失败」
          const message = body?.message || `请求失败（HTTP ${status}）`
          if (!silent) toast(message)
          return reject(new Error(message))
        }

        /* ---------- 第二层：业务码 ---------- */

        /*
         * 后端的规则（见后端 ResultHttpStatus）：
         *   code 在 400~599（401/403/404/500…）→ HTTP 状态码 = code
         *   业务码（1002 用户名或密码错误、2001 作品不存在…）→ HTTP 200
         *
         * 也就是说【两条分支都必须处理 code】—— 只写一条，就会出现
         * 「后端明明拒绝了，前端却当成功」。
         */

        // 不是标准结构（比如直接返回了文件流），原样给回去
        if (body == null || typeof body !== 'object' || !('code' in body)) {
          return resolve(res.data)
        }

        if (body.code === 200) {
          /*
           * ★ 只返回 data，不返回整个 { code, message, data } ——
           *   这样调用方拿到结果就说明成功了，不用每处都写一遍 if (res.code === 200)。
           *
           * ★ data 可能是 undefined：后端配了 non_null，Result.success() 的 body
           *   里根本没有 data 这个 key（比如 POST /api/reader/history 只回一句
           *   "已记录"）。判断「有没有」请用 `?? null` 或真值判断，
           *   别写 `=== null`，那个永远不成立。
           */
          return resolve(body.data)
        }

        if (body.code === 401) {
          handleUnauthorized(silent)
          return reject(new Error(body.message || '登录已过期'))
        }

        // 其余业务错误：弹提示 + 抛出，让调用方可以用 try-catch 兜住
        if (!silent) toast(body.message || '请求失败')
        return reject(new Error(body.message || '请求失败'))
      },

      /**
       * fail 只覆盖「请求根本没发出去 / 没等到响应」：
       * 断网、DNS 失败、超时、被 abort。HTTP 层面的错误在 success 里。
       *
       * ★ 默认不静默这个方向是安全的：多弹一次提示只是烦，
       *   漏弹一次会让真正的失败无人知晓 —— 而「失败没人知道」
       *   正是最难查的那类问题。
       */
      fail: (err) => {
        const errMsg = err?.errMsg || ''

        if (errMsg.includes('timeout')) {
          if (!silent) toast('请求超时，请检查网络或后端服务')
          return reject(new Error('请求超时'))
        }

        /*
         * 走到这里最常见的原因是后端没启动，或者 dev-proxy 配错了。
         * 这条提示要明确指向后端，不然很容易以为是前端的问题。
         */
        if (!silent) toast('连接不上后端服务，请确认 backend 已在 8080 端口启动')
        return reject(new Error(errMsg || '网络异常'))
      },
    })
  })
}

/**
 * 401 统一处理。
 *
 * ★★ 静默请求在这里【直接返回，什么都不做】，包括不 clearAuth。
 *    这一步容易写错：直觉上「顺手清掉本地登录态」看起来更负责任，但后果更糟 ——
 *      ① clearAuth() 只动本地存储，【Pinia 里的 store 不会跟着变】，
 *         于是页面上仍显示「已登录」，而所有请求都在悄悄失败，
 *         用户在两个互相矛盾的信号之间完全没法判断发生了什么；
 *      ② 何况 401 的那个 token 本来就已经是废的，清不清都不影响安全性，
 *         清它换不来任何好处。
 *    正确的分工是：【静默请求只负责「不打扰」，登录态失效由下一次非静默
 *    请求统一处理】—— 那时弹提示、跳登录页都是合适的，因为用户正在做一件
 *    他期待有反馈的事。这条路径自洽，不需要额外的同步代码。
 *
 * ★ 跳的是【移动端自己的】登录页，不是阅读端的 —— 见 auth.js 里那段
 *   「刻意不一样」的论证。
 */
function handleUnauthorized(silent) {
  if (silent) {
    return
  }
  if (handlingUnauthorized) return
  handlingUnauthorized = true

  toast('登录已过期，请重新登录')
  clearAuth()

  // 稍微延迟一下，让上面的提示能显示出来再跳走（否则页面立刻跳转，用户看不到原因）
  setTimeout(() => {
    redirectToLogin()
    handlingUnauthorized = false
  }, 600)
}

/* ==================== 对外接口 ==================== */

/**
 * ★ 方法签名刻意做成和 axios 实例【一样】的：
 *     request.get(url, config) / request.post(url, data, config)
 *   这样 api/ 目录下的那些接口文件可以从 reader-web 近乎逐行搬过来，
 *   搬迁时的改动只有 import 路径 —— 少一次"顺手改写"就少一次引入 bug 的机会。
 */
const request = {
  get: (url, config) => send('GET', url, undefined, config),
  post: (url, data, config) => send('POST', url, data, config),
  put: (url, data, config) => send('PUT', url, data, config),
  delete: (url, config) => send('DELETE', url, undefined, config),
}

export default request
