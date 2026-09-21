/**
 * 确认框的统一入口。
 *
 * ------------------------------------------------------------
 * ★★ 为什么要有这个文件：ElMessageBox 拿不到中文语言包
 * ------------------------------------------------------------
 * `ElMessageBox` 是**命令式 API** —— 它在 body 上临时挂一个节点，
 * 不在 `<el-config-provider>` 的插槽里，所以 `App.vue` 里那份 zh-cn
 * **传不到它**。不显式传 `confirmButtonText` / `cancelButtonText`，
 * 两个按钮就永远是英文的 "OK" / "Cancel"。
 *
 * 而管理端要弹确认框的地方**特别多**：审核通过/驳回、封禁/解封、
 * 重置密码、上下架、删除公告、删除角色、给角色赋权……
 * 十几处各写一遍 `{ confirmButtonText: '确定', cancelButtonText: '取消' }`，
 * 漏掉一处就多一处中英混排 —— 而这种「某一页的按钮是英文」
 * 只有真的点开那个页面才会发现。
 *
 * 所以：**确认框一律走这里，不要在页面里直接调 ElMessageBox。**
 * 默认值在下面两个常量里，改一次全局生效。
 *
 * ------------------------------------------------------------
 * ★★ 第二个理由：`ElMessageBox` 取消是 reject，不是 resolve
 * ------------------------------------------------------------
 * `ElMessageBox.confirm(...)` 在用户点「取消」时**抛异常**。所以每个
 * 调用点都得包一层 `try/catch`，而 `catch` 里到底该不该提示「操作失败」
 * 是个真问题 —— 用户主动取消不是失败。
 * 十几处各写一遍 try/catch，迟早有人把「取消」也弹一句红字错误。
 * 这里统一吞掉，**返回布尔值**，调用点只需要 `if (!ok) return`。
 */
import { ElMessageBox } from 'element-plus'

/** 默认按钮文案。★ 改这里就是改全局 */
const OK_TEXT = '确定'
const CANCEL_TEXT = '取消'

/**
 * 确认框。**永不抛异常**，用户取消返回 false。
 *
 * @param {string} message 正文。★ 要说清「点了会发生什么」，尤其是不可逆的操作
 * @param {string} title 标题
 * @param {object} [options]
 * @param {'warning'|'info'|'error'|'success'} [options.type] 图标类型
 * @param {string} [options.confirmText] 确认按钮文案。★ 用动词，不要用「确定」
 *   ——「确定删除」比「确定」多告诉用户一件事，而且能防误点
 * @param {string} [options.cancelText]
 * @param {boolean} [options.dangerouslyUseHTMLString] 默认 false，别开
 * @returns {Promise<boolean>} true = 用户确认，false = 用户取消
 */
export async function confirm(message, title, options = {}) {
  const { type = 'warning', confirmText = OK_TEXT, cancelText = CANCEL_TEXT } = options

  try {
    await ElMessageBox.confirm(message, title, {
      type,
      confirmButtonText: confirmText,
      cancelButtonText: cancelText,
    })
    return true
  } catch {
    /*
     * ★ 用户点了取消（或按了 ESC / 点了遮罩）—— 这是**正常结果**，不是错误。
     *   这里绝不 ElMessage.error，否则每次取消都弹一句红字。
     */
    return false
  }
}

/**
 * 危险操作确认（删除、封禁、下架）。
 *
 * ★ 和 `confirm` 的唯一区别是**确认按钮默认文案是「确定删除」**——
 *   但真正该做的是**每个调用点传自己的动词**：
 *     confirmDanger(msg, '下架作品', '确定下架')
 *     confirmDanger(msg, '封禁用户', '确定封禁')
 *   默认值只是个兜底，不要依赖它。
 *
 * @returns {Promise<boolean>}
 */
export function confirmDanger(message, title, confirmText = '确定删除', options = {}) {
  return confirm(message, title, { ...options, type: 'warning', confirmText })
}

/**
 * 单行输入框。**永不抛异常**，用户取消返回 null。
 *
 * ★ 主要给「驳回理由」这类一次性输入用。但注意：**审核弹窗不要用它** ——
 *   审核员要一边看正文一边写理由，用一个飘在上面的 prompt 会把正文挡住。
 *   那个场景用 `components/AuditDialog.vue` 里的固定文本框。
 *
 * @param {object} [options]
 * @param {string} [options.inputPlaceholder]
 * @param {(v: string) => true | string} [options.inputValidator]
 *   ★ 返回 `true` = 合法，返回**字符串** = 不合法的原因（会显示在输入框下方）。
 *   传了它之后，校验不过时**输入框保持打开**、红字贴在下面 ——
 *   比「关掉框 → 我们在外面发现是空的 → 再弹一次」好得多：
 *   后者会闪一下，而且用户刚打的字没了。
 * @returns {Promise<string|null>} null = 用户取消
 */
export async function prompt(message, title, options = {}) {
  const {
    confirmText = OK_TEXT,
    cancelText = CANCEL_TEXT,
    inputPlaceholder = '',
    inputValidator,
  } = options

  try {
    const { value } = await ElMessageBox.prompt(message, title, {
      confirmButtonText: confirmText,
      cancelButtonText: cancelText,
      inputPlaceholder,
      // 不传时 element-plus 不做任何校验（undefined 是它的默认值）
      inputValidator,
    })
    return value
  } catch {
    return null
  }
}
