/**
 * 用户管理接口 —— 对应后端 AdminUserController（/api/admin/users）。
 *
 * ------------------------------------------------------------
 * ★★ 三个写接口都会「强制对方下线」，前端必须把这句话说出来
 * ------------------------------------------------------------
 * 封禁 / 改角色 / 重置密码在后端都会调 `tokenStore.removeAll(userId)`，
 * 把那个人的**全部在线会话**清掉（他会被踢到登录页）。
 *
 * 这是必须的 —— 身份是每请求从 Redis 读的，不踢的话：
 *   · 封禁后对方还能继续用 7 天（token 过期为止）
 *   · 改角色后对方界面毫无变化，管理员以为没保存成功
 *   · 重置密码后盗号者手里的 token 继续有效（而这正是重置密码的目的）
 *
 * 但**管理员在界面上看不到这件事**。所以每个操作成功后的提示里
 * 都要带上这一句，否则管理员看到对方浏览器里还开着旧页面，
 * 会怀疑「到底生效了没有」。
 *
 * ------------------------------------------------------------
 * ★ 这里【没有】新增用户的接口，后端也没有
 * ------------------------------------------------------------
 * 注册是读者自己的事（`POST /api/auth/register`）。管理端只处置已有账号。
 * 不要因为「列表页缺个新增按钮」就去后端加一个 —— 见
 * AdminUserController 的类注释里那两条理由。
 */
import request from '@/utils/request'

/**
 * 用户列表（分页）。
 *
 * @param {object} params
 * @param {number} [params.status] 1 正常 / 0 已封禁。不传=全部
 * @param {string} [params.roleCode] 角色码，如 `AUTHOR`。★ 传的是**码**不是 id
 * @param {string} [params.keyword] 用户名或昵称，模糊匹配
 * @param {number} [params.page]
 * @param {number} [params.size] 上限 100（后端 PaginationInnerInterceptor 夹的）
 * @returns {Promise<{records: Array, total: number}>}
 *   ★ 每条记录带 `roles`（角色码，展示用）和 `roleIds`（角色 id，编辑回显用）。
 *     两个都要，别想用一个代替另一个 —— 理由见后端 AdminUserVO 的注释。
 *   ★ **没有 password**，后端转 VO 时就没带。别指望能拿到哈希。
 */
export function getUserPage(params) {
  return request.get('/admin/users', { params })
}

/**
 * 封禁 / 解封。
 *
 * ★★ 这个是**两件事**：改 `status` + 清掉对方全部在线会话。
 *   所以封禁**立刻生效** —— 不用等 token 过期，也不用对方重新登录。
 *
 * ★ **不能封禁自己**（后端返回 `1012`）。封禁自己 = 再也登不回来，
 *   只能进 MySQL 改 status。前端也应该把那一行的「封禁」按钮禁掉，
 *   但**不能只靠前端**：真正的拦截在后端。
 *
 * @param {number} id
 * @param {0|1} status 0 封禁，1 解封
 */
export function changeUserStatus(id, status) {
  return request.put(`/admin/users/${id}/status`, { status })
}

/**
 * 修改用户角色（**覆盖式**）。
 *
 * ★★ 传的是该用户**最终应有的全部角色 id**，不是「要加的那个」。
 *   传 `[2]` 之后这个人就只剩角色 2 了 —— 前端必须把多选框的
 *   **完整选中集合**发出去。这一点最容易写错：把「点了一下」的
 *   那个 id 单独发出去，会把人家原有的角色全部清掉，而且返回成功。
 *
 * ★ 不能传空数组（后端 400）。一个没有角色的人能登录但什么都干不了，
 *   且界面上看不出原因 —— 要让某人不能用，请用封禁。
 *
 * ★ 改完对方会被强制下线，重新登录后才拿到新角色。
 *
 * @param {number} id
 * @param {number[]} roleIds 最终的角色 id 全量列表
 */
export function changeUserRoles(id, roleIds) {
  return request.put(`/admin/users/${id}/roles`, { roleIds })
}

/**
 * 重置密码。
 *
 * ★ 不校验原密码 —— 管理员本来就不知道用户的密码。
 * ★ 重置后对方**全部设备被强制下线**，新密码才生效。
 * ★ 新密码只走这一次请求，后端不会记进日志，前端也不该留在任何地方
 *   （别写进 localStorage、别拼进 URL）。
 *
 * @param {number} id
 * @param {string} newPassword 6~32 位，规则和注册一致
 */
export function resetUserPassword(id, newPassword) {
  return request.put(`/admin/users/${id}/reset-password`, { newPassword })
}
