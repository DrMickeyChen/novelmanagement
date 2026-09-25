/**
 * 确认框的统一入口（移动端）。
 *
 * 对应桌面端的 `admin-web/src/utils/confirm.js`，两条理由**逐条平移**过来，
 * 只是换了实现：那里是 `ElMessageBox`，这里是 `uni.showModal`。
 *
 * ------------------------------------------------------------
 * ★★ 理由一：按钮文字必须手写，不能靠默认值
 * ------------------------------------------------------------
 * 桌面端那边的坑是「`ElMessageBox` 是命令式 API，读不到 Element 的语言包，
 * 不写就是英文的 OK / Cancel」。
 *
 * 移动端有一个**形状相同、来源不同**的坑：`uni.showModal` 的默认按钮文字
 * 由**宿主环境**决定 —— H5 由 uni 自己给（中文），但小程序 / App 里
 * 跟着**手机系统的语言**走。一个英文系统的手机装了这个 App，
 * 弹出来就是 "OK" / "Cancel"，而我们整个界面是中文的。
 *
 * ★ 所以两个文案一律显式传，从不依赖默认值。
 * ★★ 而且小程序端 `confirmText` / `cancelText` **最多 4 个汉字**，
 *    超了会被**静默截断**（不是报错）。所以下面的默认值是「确定」/「取消」
 *    这种两个字的安全值，调用点传动词时也要守住 4 个字
 *    （「确定移除」正好 4 个，再长就危险了）。
 *
 * ------------------------------------------------------------
 * ★★ 理由二：取消不是失败，返回值必须是布尔而不是异常
 * ------------------------------------------------------------
 * `uni.showModal` 本身不抛异常（它走 success 回调），所以这一条在移动端
 * 比桌面端**轻**。但还有一个真实的分支要处理：**fail**。
 * 它会在「已经有一个模态框在显示时又弹一个」这类情况下触发 ——
 * 比如用户手快连点了两下「移除」。
 *
 * ★ 那时应该返回 `false`（当作"用户没确认"），**不能**让 Promise 悬着
 *   —— 悬着的话调用点的 `await` 永远不返回，按钮上的 loading 转到天荒地老，
 *   而用户只会觉得"这个 App 卡了"。
 *
 * ★ 统一的是**形状**：`await confirm(...)` → `true` / `false`，
 *   调用点只写 `if (!ok) return`。散着写 `uni.showModal` 的话，
 *   每个调用点都要自己处理 success/fail 两个回调，迟早有一处把
 *   fail 也当成成功（`res.confirm` 在 fail 时是 undefined → falsy，
 *   这一处侥幸对了，下一处判 `!res.cancel` 就错了）。
 *
 * ★ 约定：**确认框一律走这里，不要在页面里直接调 `uni.showModal`。**
 */

/** 默认按钮文案。★ 改这里就是改全局 */
const OK_TEXT = '确定'
const CANCEL_TEXT = '取消'

/**
 * 确认框。**永不抛异常**，用户取消返回 false。
 *
 * @param {string} content 正文。★ 要说清「点了会发生什么」，尤其是不可逆的操作
 * @param {string} [title] 标题
 * @param {object} [options]
 * @param {string} [options.confirmText] 确认按钮文案。★ 用动词，不要用「确定」
 *   ——「确定移除」比「确定」多告诉用户一件事，而且能防误点。
 *   ★★ **最多 4 个汉字**，超了小程序端会静默截断。
 * @param {string} [options.cancelText] ★ 同上，4 个汉字上限
 * @param {boolean} [options.showCancel=true] 传 false 就是一个只有「确定」的提示框
 * @returns {Promise<boolean>} true = 用户确认，false = 用户取消 / 弹不出来
 */
export function confirm(content, title, options = {}) {
  const {
    confirmText = OK_TEXT,
    cancelText = CANCEL_TEXT,
    showCancel = true,
  } = options

  return new Promise((resolve) => {
    uni.showModal({
      title: title || '',
      content,
      showCancel,
      confirmText,
      cancelText,
      // ★ 这两个是 uni 的语义：confirm=true 是点了确认
      success: (res) => resolve(!!res.confirm),
      /*
       * ★★ 弹不出来时返回 false，**不是** reject、也不是不 resolve。
       *   见文件头最后一段：让 Promise 悬着的代价是按钮永远转圈。
       */
      fail: () => resolve(false),
    })
  })
}

/**
 * 危险操作确认（移除书架、删除评论）。
 *
 * ★ 和 `confirm` 的唯一区别是确认按钮的**默认**文案 ——
 *   但真正该做的是**每个调用点传自己的动词**：
 *     confirmDanger(msg, '移除书架', '确定移除')
 *   默认值只是个兜底，不要依赖它。
 *
 * @returns {Promise<boolean>}
 */
export function confirmDanger(content, title, confirmText = '确定删除', options = {}) {
  return confirm(content, title, { ...options, confirmText })
}

/**
 * 纯提示框（只有一个「确定」）。
 *
 * ★ 移动端**优先用 `uni.showToast`**（页面里到处都在用）——
 *   它不打断操作、自动消失，适合"成功了""已复制"这类瞬时反馈。
 * ★ 只有「必须让用户读完并确认」的内容才用这个：
 *   需要用户明确知道发生了什么的说明、或者一段比较长的文案
 *   （toast 在小程序端**最多显示 7 个汉字左右**，超了看不全，
 *   而且 1.5 秒就消失 —— 这正是 desktop 端用 ElMessageBox.alert 的那些场景）。
 *
 * @returns {Promise<boolean>} 恒为 true。★ 保留布尔是为了调用点写法统一
 */
export function alert(content, title, options = {}) {
  return confirm(content, title, { ...options, showCancel: false })
}
