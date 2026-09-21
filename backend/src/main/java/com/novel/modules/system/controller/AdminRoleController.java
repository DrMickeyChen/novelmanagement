package com.novel.modules.system.controller;

import com.novel.common.result.Result;
import com.novel.modules.system.dto.RoleMenuDTO;
import com.novel.modules.system.dto.RoleSaveDTO;
import com.novel.modules.system.service.AdminRoleService;
import com.novel.modules.system.vo.RoleVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端 —— 角色管理。
 *
 * <p><b>★ {@code list()} 是 S6 就有的</b>（给用户管理页的「编辑角色」弹窗用），
 * S13 在同一个类里补上了增删改和「给角色赋菜单」—— <b>原来那个方法一行没动</b>。
 *
 * <p><b>★★ 这个类是「菜单树 + 角色」两个东西的接缝，也是全项目唯一一处
 * 「UI 操作能造成不可逆损害」的地方</b>，所以写接口里有两条额外的拒绝：
 * <ol>
 *   <li>{@code PUT /{id}/menus} 会拦「去掉自己角色上的角色管理/菜单管理」→ {@code 1020}；</li>
 *   <li>{@code DELETE /{id}} 会拦内置角色 → {@code 1018}，以及「角色下还有用户」→ {@code 1019}。</li>
 * </ol>
 * 理由写在 {@code AdminRoleServiceImpl} 的类注释和 {@code ResultCode} 上。
 *
 * <p><b>★★ 三个写接口都会【强制相关用户下线】</b>（{@code tokenStore.removeAll}）。
 * 角色和权限是登录时算出来写进 Redis 的，之后每请求只读 Redis ——
 * 不踢的话，被改的人界面看起来毫无变化，管理员会以为没保存成功。
 * 前端每次操作后必须告诉管理员这件事。
 *
 * <p><b>★ 访问控制由 URL 前缀兜住</b>（{@code /api/admin/**} → {@code hasRole("ADMIN")}），
 * 这个类里没有 {@code @PreAuthorize}，也不用在 {@code SecurityConfig} 里登记。
 *
 * @author MickeyChen
 */
@Tag(name = "25. 管理端 · 角色管理", description = "角色列表 / 增删改 / 给角色赋菜单。★ 需要 ADMIN 角色")
@RestController
@RequestMapping("/api/admin/roles")
@RequiredArgsConstructor
public class AdminRoleController {

    private final AdminRoleService adminRoleService;

    @Operation(
            summary = "角色列表（不分页，含已停用）",
            description = """
                    返回全部角色，**不分页**（角色是个位数量级的字典表）。

                    ★ **包含已停用的角色**，不要按 `status = 1` 过滤前端的结果 ——
                    某个用户可能还挂着一个已停用的角色，编辑弹窗如果不显示它，
                    管理员一保存就会把这个关联悄悄删掉。

                    ★ 顺序按 id 升序，于是内置的 `ADMIN / AUTHOR / READER`
                    总是排在最前面。

                    ★ 每行带 `builtin`（是否内置角色）和 `userCount`（该角色下的用户数）。
                    这两个字段是给前端**预判**用的：内置角色不能删、用户数不为 0 不能删，
                    界面上直接把删除按钮置灰，比点了之后再看红字友好。
                    **但真正的拦截在后端** —— 前端那层只是少一次注定失败的点击。""")
    @GetMapping
    public Result<List<RoleVO>> list() {
        return Result.success(adminRoleService.list());
    }

    @Operation(
            summary = "新建角色",
            description = """
                    `{"roleCode": "EDITOR", "roleName": "责任编辑", "remark": "...", "status": 1}`

                    ★ `roleCode` 必须**大写字母开头，只含大写字母/数字/下划线**（否则 400）。
                    这不是形式主义：角色码在代码里被逐字比较
                    （`hasRole("ADMIN")`、`CommonConstant.ROLE_AUTHOR`），
                    一个 `admin`（小写）或带空格的码**永远匹配不上任何判断**，
                    症状是「这个角色配好了权限，但那个人进来什么都没有」，且不报错。

                    ★ 新建的角色**没有任何菜单**（`sys_role_menu` 里没有它的行），
                    所以挂上它的用户在界面上只会看到「工作台」。
                    建完记得去 `PUT /{id}/menus` 勾权限。

                    ★ 角色标识重复返回 `1017`。注意这里有一个反直觉的情况：
                    **一个已删除的角色仍然占着它的码**（`uk_role_code` 唯一索引不含 `deleted`），
                    所以「查不到同名角色」不等于「能建」，撞键时同样返回 `1017`。

                    ★ 返回新角色的 id。""")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody RoleSaveDTO dto) {
        return Result.success("角色已创建，别忘了给它勾选菜单", adminRoleService.create(dto));
    }

    @Operation(
            summary = "编辑角色",
            description = """
                    全部字段覆盖式提交（不是部分更新）。`remark` 传 null / 空串表示**清空备注**。

                    ★ **内置角色（ADMIN / AUTHOR / READER）只能改「角色名」和「备注」**，
                    改标识或状态一律拒绝（`1018`）。理由：删/停 ADMIN 会让所有管理员
                    同时失去管理端权限，改掉它的 `role_code` 会让代码里所有
                    `ROLE_ADMIN` 的判断同时落空 —— 三者都是全局性故障，且不可逆。

                    ★ **改了标识或状态会把该角色下的所有用户踢下线**，
                    他们重新登录后才拿到新身份。只改名字/备注不动在线会话。

                    ★ 角色不存在 `1011`，标识重复 `1017`。""")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody RoleSaveDTO dto) {
        adminRoleService.update(id, dto);
        return Result.success("角色已更新；若改了标识或状态，该角色下的用户已被强制下线，需重新登录", null);
    }

    @Operation(
            summary = "删除角色",
            description = """
                    逻辑删除，同时**物理清掉**该角色的 `sys_role_menu` 关联行
                    （那张表没有 `deleted` 列，见 `SysRoleMenuMapper`）。

                    ★ **内置角色不能删**（`1018`）。除了会造成全局性故障，
                    还有一个数据库层面的原因：`uk_role_code` 唯一索引
                    **不含 `deleted`** —— 逻辑删掉 `ADMIN` 之后，这个码再也建不回来。

                    ★ **角色下还有用户时拒绝删除**（`1019`），不会级联清掉那些
                    `sys_user_role` 行。级联看起来方便，实际是把一次误操作放大成事故：
                    那些用户会变成「登录成功但什么都进不去」，而且界面上看不出原因。
                    （和 `sys_role_menu` 的处理相反 —— 菜单关联没了无非是权限少了，
                    用户的角色没了是「这个人被废掉」，后果不同处理就该不同。）

                    ★ 角色不存在 `1011`。""")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        adminRoleService.delete(id);
        return Result.success("角色已删除", null);
    }

    @Operation(
            summary = "某角色已勾选的菜单 id",
            description = """
                    给它已有的菜单 id 列表，前端拿去当 `el-tree` 的默认选中项
                    （`setCheckedKeys`）。

                    ★ 返回的是**原始关联行**，不 join 菜单表做过滤。所以如果某个 menuId
                    指向的菜单已经被删了，它仍然会出现在这里 —— 前端的树里
                    本来就没有那个节点，多出来的 key 会被忽略。
                    **反过来「顺手过滤一下」会出问题**：树里没有它、
                    列表里也没有它，下一次保存时那条关联就被静默丢掉了。

                    ★ 角色不存在返回 `1011`，**不返回空数组** ——
                    空数组会让前端渲染出一棵「一个都没勾的树」，
                    和「这个角色确实还没有权限」完全一样。""")
    @GetMapping("/{id}/menus")
    public Result<List<Long>> menuIds(@PathVariable Long id) {
        return Result.success(adminRoleService.menuIdsOf(id));
    }

    @Operation(
            summary = "给角色赋菜单（覆盖式）",
            description = """
                    `{"menuIds": [1, 2, 3]}` —— 传该角色**最终应勾选的全部菜单 id**。
                    **是覆盖，不是追加**：传 `[2]` 之后这个角色就只剩菜单 2 了。

                    ★ **允许传空数组**（这个角色暂时没有任何权限），
                    这一点和「给用户赋角色」的 `@NotEmpty` 相反 —— 那边清空等于
                    「把这个人废掉」，这边只是「权限还没配」。理由见 `RoleMenuDTO`。

                    ★ 前端必须用 `el-tree` 的 **`check-strictly = true`**（父子不联动）。
                    联动会让「只想给一个叶子」变成「顺带授了整个父目录」，
                    而目录行本身没有权限点 —— 界面显示的勾选比实际授予的多。

                    ★ **★★ 自锁防护：不能去掉你自己角色上的 `system:role:list`
                    或 `system:menu:list`**（返回 `1020`）。去掉之后保存成功、
                    你被踢下线、重新登录时左侧菜单里这两项消失 ——
                    而界面上再也没有地方能改回来，只能进 MySQL 手工 INSERT。
                    <br>判断的是「**改完之后你自己还剩哪些权限**」（会把你**别的**角色
                    一起算进来），不是「你改的是不是自己的角色」——
                    所以同时是 ADMIN 的人去掉别的角色上的这两个权限**是允许的**。

                    ★ **返回值是被强制下线的用户数**（不是登录态条数，一个人三台设备算 1）。
                    `0` 是完全正常的结果（这个角色下没有任何人在线）。
                    前端拿它写「已保存，N 名用户需要重新登录」。
                    ★ 判断「有没有把管理员自己踢掉」请在**前端**做：
                    拿这个角色的 `roleCode` 去比对当前登录用户的 `roles`
                    （登录接口返回、存在 localStorage 里）。

                    ★ 角色不存在 `1011`，菜单不存在 `1021`。""")
    @PutMapping("/{id}/menus")
    public Result<Integer> assignMenus(@PathVariable Long id,
                                      @Parameter(description = "该角色最终应勾选的全部菜单 id")
                                      @Valid @RequestBody RoleMenuDTO dto) {
        int kicked = adminRoleService.assignMenus(id, dto.getMenuIds());
        return Result.success(
                kicked > 0
                        ? "菜单权限已更新，" + kicked + " 名用户的在线会话已被强制下线，需重新登录"
                        : "菜单权限已更新（该角色下当前无人在线）",
                kicked);
    }
}
