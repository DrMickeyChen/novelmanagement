package com.novel.modules.system.controller;

import com.novel.common.result.PageResult;
import com.novel.common.result.Result;
import com.novel.modules.system.dto.ResetPasswordDTO;
import com.novel.modules.system.dto.UserRolesDTO;
import com.novel.modules.system.dto.UserStatusDTO;
import com.novel.modules.system.service.AdminUserService;
import com.novel.modules.system.vo.AdminUserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 —— 用户管理。
 *
 * <p><b>★ 访问控制由 URL 前缀兜住</b>（{@code /api/admin/**} → {@code hasRole("ADMIN")}），
 * 这个类里没有 {@code @PreAuthorize}，也不用在 {@code SecurityConfig} 里登记。
 *
 * <p><b>★★ 这里【没有】「新增用户」接口，这是有意的。</b>
 * 注册是读者自己的事（{@code POST /api/auth/register}），
 * 管理端不建号。管理员能做的只有「处置已有的账号」：
 * 封禁 / 解封、改角色、重置密码。
 * <p>加一个「管理员建号」的接口看起来方便，实际会带来两个问题：
 * 一是它绕开了注册接口的防枚举和唯一键兜底，二是它会成为一个
 * <b>「我帮你建个管理员账号吧」</b>的入口 —— 而管理员本来就能给任意
 * 已有账号加 ADMIN 角色，多这一个入口没有任何新能力，只多一个被滥用的面。
 *
 * <p><b>★★ 三个写接口都会把目标用户的在线会话全部清掉</b>
 * （{@code tokenStore.removeAll}），理由见 {@code AdminUserService} 的类注释。
 * 前端每次操作后都必须告诉管理员<b>「该用户的在线会话已被强制下线」</b>——
 * 不说的话，管理员看到对方还在线（其实是浏览器里的旧页面）会以为没生效。
 *
 * @author MickeyChen
 */
@Tag(name = "24. 管理端 · 用户管理", description = "用户列表 / 封禁 / 改角色 / 重置密码。★ 需要 ADMIN 角色")
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    @Operation(
            summary = "用户列表（分页）",
            description = """
                    三个筛选条件都可选，可以组合：

                    - `status`：1 正常 / 0 已封禁。不传=全部
                    - `roleCode`：角色码，如 `AUTHOR` / `READER`。不传=全部
                    - `keyword`：**用户名或昵称**的模糊匹配（两个字段是 OR 关系）

                    ★ 结果按 **id 倒序** = 按注册时间倒序，最新注册的在最前面。
                    排序用 id 而不是 `create_time`，因为同一秒注册的两个用户
                    用时间排是不稳定的 —— 翻页时会看到某一条重复出现、另一条永远看不到。

                    ★ 每行都带 `roles`（角色码，用于展示）和 `roleIds`（角色 id，用于编辑回显）。
                    **不含 permissions** —— 管理端列表不需要它，
                    而一个用户一次权限点查询，一页 20 条就是 20 次额外查询。""")
    @GetMapping
    public Result<PageResult<AdminUserVO>> list(
            @Parameter(description = "状态：1 正常 0 已封禁。不传=全部")
            @RequestParam(required = false) Integer status,
            @Parameter(description = "角色码，如 AUTHOR。不传=全部")
            @RequestParam(required = false) String roleCode,
            @Parameter(description = "用户名或昵称关键词")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数，上限 100") @RequestParam(defaultValue = "10") long size) {

        return Result.success(adminUserService.page(status, roleCode, keyword, page, size));
    }

    @Operation(
            summary = "封禁 / 解封",
            description = """
                    `{"status": 0}` 封禁，`{"status": 1}` 解封。

                    **★ 封禁是两件事，这是这个接口最容易被做错的地方：**
                    1. 把 `sys_user.status` 改成 0 —— 让**新登录**被拒（返回 `1003`）
                    2. **清掉该用户的全部在线会话**

                    少了第 2 步，界面会显示「已封禁」而对方**继续正常使用 7 天**
                    （token 自然过期为止）—— 因为身份是每请求从 Redis 读的，
                    不查库。这个 bug 不报错，除非你拿旧 token 去试，否则测不出来。

                    ★ **不能封禁自己**，会返回 `1012`。理由见 `ResultCode` 里的注释。

                    ★ 用户不存在返回 `1001`。""")
    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @Valid @RequestBody UserStatusDTO dto) {
        adminUserService.changeStatus(id, dto.getStatus());
        return Result.success(dto.getStatus() == 0
                ? "已封禁，该用户的在线会话已被强制下线"
                : "已解封，该用户需要重新登录", null);
    }

    @Operation(
            summary = "修改用户角色（覆盖式）",
            description = """
                    传该用户**最终应有的全部角色 id**，例：`{"roleIds": [1, 2]}`。

                    ★ **是覆盖，不是追加** —— 传 `[1]` 之后这个人就只剩角色 1 了。
                    界面上的多选框勾成什么样，库里就是什么样。

                    ★ **不能传空数组**（返回 `400`）。一个什么角色都没有的用户
                    能登录但什么都干不了，且从界面上看不出原因 ——
                    要让某人无法使用，请用「封禁」。

                    ★ **不能把自己身上的 ADMIN 拿掉**（返回 `1013`），
                    否则左侧的「用户管理」「角色管理」会消失，界面上再也加不回来。

                    ★ **改完会强制该用户下线**，他重新登录后才拿到新角色。
                    不给的话，对方界面看起来毫无变化，管理员会以为没保存成功。

                    ★ 角色不存在返回 `1011`。""")
    @PutMapping("/{id}/roles")
    public Result<Void> assignRoles(@PathVariable Long id, @Valid @RequestBody UserRolesDTO dto) {
        adminUserService.assignRoles(id, dto.getRoleIds());
        return Result.success("角色已更新，该用户的在线会话已被强制下线，需重新登录", null);
    }

    @Operation(
            summary = "重置密码",
            description = """
                    `{"newPassword": "xxxxxx"}`，6~32 位，规则和注册接口完全一致。

                    ★ **不校验原密码** —— 管理员本来就不该知道用户的密码。
                    安全性来自 `/api/admin/**` 那一道 `hasRole("ADMIN")`，
                    而不是来自原密码。

                    ★ **重置后会强制该用户下线**。这一点在安全事件里最关键：
                    「重置密码」通常正是为了把盗号者赶出去，
                    不踢的话对方手里的 token 还能再用 7 天。

                    ★ 新密码**不会**出现在服务端日志里，只记「谁重置了谁」。""")
    @PutMapping("/{id}/reset-password")
    public Result<Void> resetPassword(@PathVariable Long id, @Valid @RequestBody ResetPasswordDTO dto) {
        adminUserService.resetPassword(id, dto.getNewPassword());
        return Result.success("密码已重置，该用户的在线会话已被强制下线", null);
    }
}
