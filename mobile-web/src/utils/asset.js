/**
 * 后端返回的资源路径 → 可以直接交给 `<image src>` 的**绝对 URL**。
 *
 * ============================================================
 * ★★★ 为什么相对路径在这里是错的（一次真实踩坑的全过程）
 * ============================================================
 *
 * 后端 `sys_user.avatar` 存的是 `/uploads/avatar/2026/09/25/xxx.png` ——
 * 一个**【根相对】路径**（`/uploads/**` 不在 `/api` 前缀下，是网关的另一条路由）。
 *
 * ★ 直觉上它应该能用：根相对路径由浏览器按 origin 解析，
 *   `/uploads/x.png` → `http://站点/uploads/x.png`。桌面端就是这么用的。
 *   **这个直觉在 uni-app 的 H5 上是错的。**
 *
 * ★★ uni-app 的 H5 运行时**会把根相对路径按 `router.base` 重写一遍**。
 *    源码在 `@dcloudio/uni-h5` 的 `getRealPath()`：
 *
 *        if (filePath.indexOf('/') === 0) {
 *          if (filePath.indexOf('//') === 0) { filePath = 'https:' + filePath }
 *          else { return addBase(filePath.slice(1)) }   // ← 就是这一行
 *        }
 *
 *    而 `addBase` 实现就是 `router.base + 路径`。本工程 `base = '/mobile/'`，
 *    于是 `/uploads/x.png` 被改写成 `/mobile/uploads/x.png` → **404**。
 *
 * ★★ 这个 404 的症状极具误导性，是整个排查里最贵的一段：
 *    `@error` 把 `avatarBroken` 置真 → `v-if` 换回首字占位 →
 *    **`uni-image` 元素被从 DOM 里整个摘掉**。
 *    于是探针看到的是「页面里 `uni-image` 数为 0」——
 *    读起来像"图根本没渲染"，真相是"渲染了、加载失败了、被替换掉了"。
 *    这两件事的修法完全不同。分清它们靠的是**浏览器自己的资源账本**
 *    （`performance.getEntriesByType('resource')` 里那条
 *     `.../mobile/uploads/...` 的请求），**不是页面代码的自我报告** ——
 *    页面代码会把自己的失败渲染成"另一种正常状态"，看不出破绽。
 *
 * ============================================================
 * ★ 所以规则是：**凡是要交给 `<image>` 的后端路径，先补成绝对地址。**
 * ============================================================
 *
 * ★ 为什么不能简单写成 `'/' + path`：`API_BASE_URL` 的默认值是 `'/api'` 这个
 *   **相对**值（见 request.js 里「为什么 BASE_URL 是相对路径」那段），
 *   去掉 `/api` 后缀之后剩下的是**空串** —— 补出来的还是相对路径，等于没补。
 *   这正是这一版之前那个写法（`API_BASE_URL.replace(/\/api\/?$/, '')`）的错处：
 *   它在「配了绝对地址」时是对的，在**默认配置下悄悄退化成了空串**，
 *   而两种配置的产物在页面上看不出区别（都要等到图片 404 才发现）。
 *   所以相对配置下**必须去问浏览器要 origin**。
 *
 * ★ 为什么用运行时判断而不是 `#ifdef H5`：与 `stores/user.js` 里那条同源 ——
 *   小程序 / App 运行时里没有 `window`，而这段代码在任何端都是**合法的**，
 *   只是取不到 origin。用运行时判断，"零条件编译"才是个可验收的结论。
 *
 * ★★ 取不到 origin 时**原样返回**，这不是兜底、是**如实的失败**：
 *   非 H5 端必须靠 `VITE_API_BASE` 配成绝对地址
 *   （`VITE_API_BASE=https://域名/api npm run build:mp-weixin`），
 *   否则这里返回的仍是一个小程序认不了的相对路径 —— 那就让它认不了，
 *   **不要假装成功**（铁律 10：宁可暴露，不要把"拿不到"渲染成一个看着正常的值）。
 */
import { API_BASE_URL } from './request'

/**
 * 带协议头的地址（`http:` / `https:` / `blob:` / `data:` / `file:` …），
 * 以及协议相对地址（`//host/x`）—— 这两类一律原样放过。
 *
 * ★ 尤其是 `blob:`：选完图片还没上传时，`uni.chooseImage` 给的就是
 *   一个 `blob:` 地址（H5 上 `tempFiles[i].path` 是 `URL.createObjectURL`
 *   的产物）。给它补 origin 会**直接毁掉**这个地址。
 */
const HAS_SCHEME_RE = /^(?:[a-z][a-z0-9+.-]*:|\/\/)/i

/**
 * 资源地址的 origin。模块加载时算一次。
 *
 * 三种情形，按可靠程度从高到低：
 *   ① `VITE_API_BASE` 配的是绝对地址（小程序 / App 的构建方式）→ 去掉 `/api` 就是 origin
 *   ② H5 + 相对配置（开发与生产的默认形态）→ 只有浏览器知道 origin
 *   ③ 非 H5 + 相对配置 → 没有正确答案，返回空串（见文件头最后一段）
 */
const ORIGIN = (() => {
  const stripped = API_BASE_URL.replace(/\/api\/?$/, '')
  if (/^https?:\/\//i.test(stripped)) return stripped
  if (typeof window !== 'undefined' && window.location) return window.location.origin
  return stripped
})()

/**
 * 把后端返回的资源路径补成绝对 URL。
 *
 * ★ 补的是**同一站点**的地址：`/uploads/**` 由 dev-proxy（开发）/ Nginx（生产）
 *   与 `/api` 一起转发，所以 origin 直接取当前站点即可，不需要另配一个
 *   「文件服务器地址」——多一个配置项就多一个能配错的地方。
 *   哪天真把文件挪到对象存储（那就是另一个域名了），改这一个函数即可。
 *
 * @param {string} path 后端给的路径，例如 `/uploads/avatar/2026/09/25/xxx.png`
 * @returns {string} 绝对 URL；入参为空时返回 `''`（让 `v-if` 能直接判空）
 */
export function assetUrl(path) {
  if (!path) return ''
  const raw = String(path)
  if (HAS_SCHEME_RE.test(raw)) return raw
  return `${ORIGIN}${raw.startsWith('/') ? '' : '/'}${raw}`
}
