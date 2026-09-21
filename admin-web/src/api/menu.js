/**
 * 菜单接口 —— 对应后端 AdminMenuController（/api/admin/menus）。
 *
 * ------------------------------------------------------------
 * ★★ 只有一个查询，而且是只读的 —— 这是有意的，不要加写接口
 * ------------------------------------------------------------
 * 菜单树是系统的**权限清单**：它由 `docs/sql/seed.sql` 定义，
 * 每加一个权限点，前端 `AdminLayout.vue` 里那份菜单表也要同步加一项。
 * **改它等于改代码**，做成界面可编辑只会让人以为改完就生效了 ——
 * 而新菜单指向的前端路由可能根本不存在，点进去是 404。
 *
 * 管理员真正需要的能力是「**给角色勾选**已有的菜单」，
 * 那个在 `api/role.js` 的 `updateRoleMenus` 里。
 * 后端 `AdminMenuController` 也只有一个 GET，理由写在它的类注释里。
 *
 * ------------------------------------------------------------
 * ★★ `path` 和 `perms` 是两个不同的东西，别混用
 * ------------------------------------------------------------
 *   `path`  = `/novel`          → 前端路由，**给二期动态菜单用的**
 *   `perms` = `content:novel:list` → 权限点，AdminLayout 按它过滤菜单
 *
 * ★ 今天 `path` 还没有消费者：左侧菜单走的是 AdminLayout 里那份**写死的表**，
 *   按叶子节点的 `perms` 过滤显示。S14 把这一列和真实路由对齐过一次
 *   （原来 12 个叶子里有 11 个写的是 `/x/list` —— 那是照着同行的 perms
 *   抄形状抄出来的），理由见 seed.sql 里菜单那一节。
 *   菜单管理页会把这一列显示出来，所以它必须是真值。
 *
 * ★ 目录行（`type = 1`）的 `path` 只是分组标识（`/content`、`/system`、`/ops`），
 *   不对应任何路由；它们的 `perms` 是 null。
 *
 * ------------------------------------------------------------
 * ★★ 叶子节点【没有】`children` 字段
 * ------------------------------------------------------------
 * 响应体是 `non_null` 的（`application.yml`），null 字段直接从 JSON 里消失。
 * 所以判断有没有子节点要写 `node.children?.length`，
 * 写 `node.children.length` 会在叶子上抛 TypeError。
 * （`el-tree` 和 `el-table` 的 `tree-props` 对缺失的 children 处理是对的，
 *   都当成叶子 —— 这个坑主要在页面里自己遍历的时候踩。）
 */
import request from '@/utils/request'

/**
 * 整棵菜单树（嵌套三层：目录 → 菜单 → 按钮）。
 *
 * ★ 直接可以喂给 `el-tree` 的 `data`（角色赋权弹窗）和
 *   `el-table` 的 `tree-props`（菜单管理页）。
 *   后端已经按 `sort, id` 排好序了 —— **前端不要重新排**：
 *   只按 sort 排是不稳定的（sort 上没有唯一约束），
 *   表现为「刷新两次，同级两项的顺序换了」。
 *
 * ★ 节点的 `type` 取值：1 目录 / 2 菜单 / 3 按钮。
 *   种子里只有 1 和 2，没有按钮行 —— 权限点在**菜单级**就够用了
 *   （`content:novel:list` 是「作品管理」这一项的权限，不是某个按钮的）。
 *
 * ★ **包含已停用的菜单**（`status = 0`）。停用的菜单在权限树里必须看得见：
 *   否则「某个角色为什么会有这个权限」在界面上就变成了一个无法回答的问题。
 *   页面把停用的节点标灰，但**不隐藏**。
 *
 * ★ 这个 VO 没有 `statusDesc` / `typeDesc`：`sys_menu` 是做字典的
 *   **1/0 开关**，不是有状态机的东西，所以文案在页面里写死即可 ——
 *   和用户管理页对「正常/已封禁」的处理一致。
 *   有状态机的那几个（作品 / 章节 / 评论 / 作者申请）才由后端给 `statusDesc`。
 *
 * @returns {Promise<Array<{id:number, parentId:number, name:string, type:number,
 *   path:string, perms:string, icon:string, sort:number, visible:number,
 *   status:number, children?:Array}>>}
 */
export function getMenuTree() {
  return request.get('/admin/menus')
}
