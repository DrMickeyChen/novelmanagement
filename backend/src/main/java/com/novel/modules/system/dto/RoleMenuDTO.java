package com.novel.modules.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 给角色赋菜单的入参（<b>覆盖式</b>，不是增量）。
 *
 * <p><b>★ 覆盖式的理由和 {@code UserRolesDTO} 完全一样：</b>
 * 界面上的交互是「一棵树 + 保存」，管理员看到的就是最终结果。
 * 增量的话，取消勾选要额外发一个删除请求，而管理员不知道「哪些被删了」，
 * 保存成功之后也没法确认结果对不对。
 *
 * <p><b>★★ 但这里【允许传空列表】，和 {@code UserRolesDTO} 相反 ——
 * 这不是疏忽，两者的后果不一样：</b>
 * <ul>
 *   <li>给<b>用户</b>清空角色 = 把这个人废掉（能登录但什么都进不去），
 *       而且从界面上看不出原因。要停一个人该用「封禁」，那是可逆的、
 *       看得见的。所以那边用 {@code @NotEmpty} 挡死在 400。</li>
 *   <li>给<b>角色</b>清空菜单 = 这个角色暂时没有任何权限。
 *       它是「先建角色，回头再配权限」的正常中间状态 ——
 *       管理员建完角色去查资料、或者分工给别人接着配，都是真实场景。
 *       拦住它只会逼管理员先随便勾一个自己不想要的菜单。</li>
 * </ul>
 *
 * <p><b>★ 只传 {@code menuIds}，不传 {@code roleId}：</b>
 * 角色 id 在 URL 路径上（{@code /api/admin/roles/{id}/menus}）。
 * 路径参数和 body 里各放一份的话，两者不一致时以谁为准就成了一个
 * 需要解释的问题 —— 能只有一个来源就只有一个。
 *
 * <p><b>★ 前端必须用 {@code el-tree} 的 {@code check-strictly = true}。</b>
 * 父子联动会让「只想给一个叶子权限」变成「顺带授了整个父目录」，
 * 而目录行本身没有权限点，勾了也不生效 —— 于是界面上显示的勾选状态
 * 比实际授予的多，管理员以为多给了权限。理由写在
 * {@code admin-web/src/views/role/index.vue}（S14）。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "角色赋菜单入参（覆盖式）")
public class RoleMenuDTO {

    @Schema(description = "该角色最终应勾选的全部菜单 id。★ 允许空数组，表示这个角色暂时没有任何菜单",
            example = "[1, 2, 3]", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "请提供菜单列表；一个都不给请传空数组 []，不要省略这个字段")
    private List<Long> menuIds;
}
