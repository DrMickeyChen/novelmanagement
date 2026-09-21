package com.novel.modules.system.controller;

import com.novel.common.result.Result;
import com.novel.modules.system.service.AdminMenuService;
import com.novel.modules.system.vo.MenuVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端 —— 菜单管理。
 *
 * <p><b>★ 这个类只有一个 GET，是有意的：P5 不做菜单的增删改。</b>
 * 菜单树是系统的「权限清单」，它由 {@code docs/sql/seed.sql} 定义，
 * 改它等于改代码（每加一个权限点，前端 {@code AdminLayout} 里那份菜单表
 * 也要同步加一项）。做成界面可编辑只会让人以为改完就生效了 ——
 * 而实际上新菜单指向的前端路由可能根本不存在，点进去是 404。
 * <p>管理员真正需要的能力是「<b>给角色勾选</b>已有的菜单」，
 * 那个在 {@code AdminRoleController} 里。
 *
 * <p><b>★ 访问控制由 URL 前缀兜住</b>（{@code /api/admin/**} → {@code hasRole("ADMIN")}），
 * 这个类里没有 {@code @PreAuthorize}，也不用在 {@code SecurityConfig} 里登记。
 *
 * @author MickeyChen
 */
@Tag(name = "26. 管理端 · 菜单管理", description = "菜单树（只读）。★ 需要 ADMIN 角色。P5 不做菜单增删改")
@RestController
@RequestMapping("/api/admin/menus")
@RequiredArgsConstructor
public class AdminMenuController {

    private final AdminMenuService adminMenuService;

    @Operation(
            summary = "整棵菜单树（嵌套）",
            description = """
                    一次返回三层嵌套结构（目录 → 菜单 → 按钮），
                    直接可以喂给前端的 `el-tree`。

                    ★ 节点按 `sort` 升序、同序按 `id` 升序 ——
                    `sort` 上没有唯一约束，只按它排的话顺序会不稳定。

                    ★ **包含已停用的菜单**（`status = 0`）。停用的菜单在权限树里
                    必须看得见：否则「某个角色为什么会有这个权限」在界面上
                    就变成了一个无法回答的问题。前端把停用的节点标灰即可。

                    ★ **叶子节点没有 `children` 字段**（响应体是 `non_null` 的，
                    null 字段直接消失）。前端要写 `node.children?.length`，
                    不要写 `node.children.length`。

                    ★ 目录行（`type = 1`）的 `perms` 是 **null** ——
                    目录本身不是权限点，前端不要把它显示成空白，显示成「—」。""")
    @GetMapping
    public Result<List<MenuVO>> tree() {
        return Result.success(adminMenuService.tree());
    }
}
