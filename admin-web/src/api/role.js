/**
 * 角色接口 —— 对应后端 AdminRoleController（/api/admin/roles）。
 *
 * ------------------------------------------------------------
 * ★ S6 只有 getRoleList 一个查询，S14 补上其余五个
 * ------------------------------------------------------------
 * S6 加它，是因为用户管理页的「编辑角色」弹窗需要一份角色清单来渲染多选框。
 * 没有它前端就只能把 `ADMIN / AUTHOR / READER` 三个角色写死在页面里，
 * 而那样管理员将来自己建的角色**永远选不到，且不报错**。
 *
 * S13 把后端的增删改和「给角色赋菜单」补齐了，S14 在这里补上调用它们的函数。
 * **getRoleList 一行没动** —— 用户管理页和角色管理页共用这一份清单
 * （两个页面必须看到同一份数据，各查各的迟早会出现「这页有那个角色、那页没有」）。
 *
 * ------------------------------------------------------------
 * ★★ 四个写接口都会「强制相关用户下线」，前端必须把这件事说出来
 * ------------------------------------------------------------
 * 角色/权限是**登录时**从库里查出来写进 Redis 的，之后每请求只读 Redis
 * （见 AuthServiceImpl 的注释）。所以改了角色不踢下线的话，
 * 被改的人**界面看起来毫无变化**，管理员会以为没保存成功，然后再改一次。
 *
 * 后端因此在三处都调了 `tokenStore.removeAll`，而且**单位是「用户」不是「会话」**：
 *   · updateRole   —— 改了 roleCode 或 status 才踢（只改名字/备注不踢）
 *   · assignMenus  —— 一定踢（权限变了）
 *   · deleteRole   —— 走不到：角色下有用户时后端直接拒绝删除（1019）
 *
 * ★ `removeAll` **不区分操作者**：管理员改自己所属角色的权限，会把自己
 *   当前这个标签页一起踢掉。这件事必须在页面里主动讲明白，否则表现是
 *   「点了一下保存，闪了一下就回登录页了，一句提示都没有」——
 *   用户第一反应是「这功能有 bug」。判断方法是拿这个角色的 `roleCode`
 *   去比对当前登录用户的 `roles`（登录时返回、存在 localStorage 里）。
 *
 * ★★ 后缀是**用户数**不是会话数：一个人三台设备算 1，`0` 是完全正常的结果
 *   （这个角色下当前没人在线），不要把它当成「没生效」。
 */
import request from '@/utils/request'

/**
 * 角色列表（全量，含已停用，不分页）。
 *
 * ★★ **包含已停用的角色**。前端不要「顺手」过滤掉 `status === 0` 的：
 *   某个用户可能还挂着一个已停用的角色，编辑弹窗如果不显示它，
 *   那个角色在界面上就是「未勾选」—— 管理员一保存，这个关联被悄悄删掉，
 *   而他什么都没做，也不会有任何提示。
 *   正确做法是**显示出来并标记「已停用」**，让管理员看见后自己决定。
 *
 * ★ 每行带 `builtin`（内置角色）和 `userCount`（该角色下的用户数）——
 *   它们是给前端**预判**用的：内置角色不能删、用户数不为 0 不能删，
 *   界面上把删除按钮置灰，比点了再吃一个错误码友好。
 *   **但真正的拦截在后端**，前端那层只是「少一次注定失败的点击」。
 *
 * @returns {Promise<Array<{id:number, roleCode:string, roleName:string,
 *   remark:string, status:number, builtin:boolean, userCount:number,
 *   createTime:string}>>}
 *   顺序按 id 升序，于是内置的 ADMIN / AUTHOR / READER 总在最前面。
 */
export function getRoleList() {
  return request.get('/admin/roles')
}

/**
 * 新建角色。
 *
 * ★ `roleCode` 的格式是**硬性要求**（大写字母开头，只含大写字母/数字/下划线），
 *   不是形式主义：角色码在代码里被逐字比较（`hasRole("ADMIN")`、
 *   `CommonConstant.ROLE_AUTHOR`）。存进去一个小写的 `admin` 完全存得下 ——
 *   唯一索引只保证不重复，不保证像样 —— 而它**永远匹配不上任何判断**，
 *   症状是「这个角色配好了权限，但那个人进来什么都没有」，且不报错。
 *
 * ★ 新建的角色**没有任何菜单**，所以挂上它的用户只会看到「工作台」。
 *   建完要紧接着调 `updateRoleMenus` —— 页面里是保存成功后自动打开权限弹窗。
 *
 * @param {{roleCode:string, roleName:string, remark?:string, status:number}} payload
 * @returns {Promise<number>} 新角色的 id
 */
export function createRole(payload) {
  return request.post('/admin/roles', payload)
}

/**
 * 编辑角色（**覆盖式**，不是部分更新）。
 *
 * ★ 全部字段都要传：只传要改的那个，剩下的会被写成 null。
 * ★ `remark` 传 null 或空串 = **清空备注**（这是唯一能清空备注的途径）。
 * ★ **内置角色（ADMIN / AUTHOR / READER）只能改「角色名」和「备注」**，
 *   改标识或状态一律拒绝（`1018`）—— 页面里把这两个输入框禁掉。
 * ★ 改了标识或状态会把该角色下的**所有人**踢下线。
 *
 * @param {number} id
 * @param {{roleCode:string, roleName:string, remark?:string, status:number}} payload
 */
export function updateRole(id, payload) {
  return request.put(`/admin/roles/${id}`, payload)
}

/**
 * 删除角色（逻辑删，同时物理清掉它的菜单关联）。
 *
 * ★ **内置角色不能删**（`1018`）。除了会造成全局性故障，还有一个
 *   数据库层面的原因：`uk_role_code` 唯一索引**不含 `deleted`** ——
 *   逻辑删掉 `ADMIN` 之后，这个码再也建不回来。
 * ★ **角色下还有用户时拒绝删除**（`1019`），不会级联清掉 `sys_user_role`。
 *   级联看起来方便，实际是把一次误操作放大成事故：那些用户会变成
 *   「登录成功但什么都进不去」，而且界面上看不出原因。
 *   → 想让某人失去这个角色，正确做法是去**用户管理页**改他的角色。
 *
 * ★ 这两条前端都能用 `builtin` / `userCount` 预判，页面里据此置灰按钮。
 *
 * @param {number} id
 */
export function deleteRole(id) {
  return request.delete(`/admin/roles/${id}`)
}

/**
 * 某角色**已勾选**的菜单 id（给 el-tree 回显用）。
 *
 * ★ 返回的是**原始关联行**，不 join 菜单表做过滤：如果某个 menuId 指向的
 *   菜单已经被删了，它仍然会出现在这里。前端的树里本来就没有那个节点，
 *   多出来的 key 会被忽略。
 *   **反过来「顺手过滤一下」会出问题** —— 树里没有它、列表里也没有它，
 *   下一次保存时那条关联就被静默丢掉了。
 *
 * ★ 角色不存在返回 `1011`，**不返回空数组**：空数组会渲染出一棵
 *   「一个都没勾的树」，和「这个角色确实还没有权限」完全一样。
 *
 * @param {number} id
 * @returns {Promise<number[]>}
 */
export function getRoleMenuIds(id) {
  return request.get(`/admin/roles/${id}/menus`)
}

/**
 * 给角色赋菜单（**覆盖式**）。
 *
 * ★★ 传的是该角色**最终应勾选的全部菜单 id**，不是「这次新增的那个」。
 *   传 `[2]` 之后这个角色就只剩菜单 2 了 —— 前端必须把 el-tree 的
 *   **完整选中集合**发出去（`getCheckedKeys()`）。
 *
 * ★ 和「给用户赋角色」相反，这里**允许传空数组**（这个角色暂时没有任何权限）。
 *   那边清空等于「把这个人废掉」，这边只是「权限还没配」。
 *
 * ★★ **自锁防护**：不能去掉你自己角色上的 `system:role:list` /
 *   `system:menu:list`（后端 `1020`）。去掉之后保存成功、你被踢下线、
 *   重新登录时左侧菜单里「角色管理」「菜单管理」消失 ——
 *   而界面上再也没有地方能改回来，只能进 MySQL 手工 INSERT。
 *   <br>后端判断的是「**改完之后你自己还剩哪些权限**」（会把你**别的**角色
 *   一起算进来），所以这件事**只能在页面里提醒、不能在前端复刻一份判断** ——
 *   两份实现迟早分叉，而分叉的那天这个防护就失效了。见 views/role/index.vue。
 *
 * @param {number} id
 * @param {number[]} menuIds 最终勾选的全部菜单 id
 * @returns {Promise<number>} 被强制下线的**用户数**（0 = 该角色下没人在线，正常）
 */
export function updateRoleMenus(id, menuIds) {
  return request.put(`/admin/roles/${id}/menus`, { menuIds })
}
