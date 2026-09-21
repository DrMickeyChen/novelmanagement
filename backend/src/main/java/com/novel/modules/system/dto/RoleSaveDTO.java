package com.novel.modules.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增 / 编辑角色的入参。
 *
 * <p><b>★ 一个 DTO 同时给 {@code POST} 和 {@code PUT} 用</b>，
 * 字段完全一样（编辑时前端把整个表单都传上来，不做部分更新）。
 * 分成两个类的话，两边迟早会出现「一边加了字段另一边忘了」的偏差。
 *
 * <p><b>★★ {@code roleCode} 的格式校验不是形式主义。</b>
 * 角色码在代码里被<b>逐字比较</b>：{@code SecurityConfig} 里的
 * {@code hasRole("ADMIN")}、{@code CommonConstant.ROLE_AUTHOR}、
 * {@code SysUserMapper} 注册时按 {@code "READER"} 查角色 id。
 * 管理员在界面上建一个 {@code admin}（小写）或 {@code 管理员 }（带空格）
 * 完全存得进去 —— 唯一索引只保证「不重复」，不保证「像样」。
 * 而它<b>永远不会匹配上任何一处判断</b>，症状是「这个角色看起来配好了
 * 权限，但那个人进来什么菜单都没有」，且不报任何错。
 *
 * <p>所以在入口处就把格式钉死：<b>大写字母开头，只含大写字母/数字/下划线</b>。
 * 这和 {@code register} 接口限制用户名的思路一致 ——
 * 标识符的格式属于「进去之前就该对」的东西，进去之后再纠正成本高得多。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "角色新增 / 编辑入参")
public class RoleSaveDTO {

    @Schema(description = "角色标识（大写字母开头，只含大写字母/数字/下划线），例 AUTHOR",
            example = "EDITOR", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "角色标识不能为空")
    /*
     * ★ 长度上限写在正则的 {0,49} 里，不再单挂一个 @Size(max = 50)。
     *   两个约束都会对「超过 50 个字符」这一种输入报错，于是校验框架
     *   可能一次返回两条消息（或者换一条），前端就得处理两条。
     *   一条规则一个来源。
     */
    @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,49}$",
            message = "角色标识必须以大写字母开头，只包含大写字母、数字和下划线，最长 50 位")
    private String roleCode;

    @Schema(description = "角色名（给人看的）", example = "责任编辑",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "角色名不能为空")
    @Size(max = 50, message = "角色名最长 50 个字符")
    private String roleName;

    @Schema(description = "备注，说明这个角色是干什么的")
    @Size(max = 255, message = "备注最长 255 个字符")
    private String remark;

    /*
     * ★ status 用 @NotNull 而不是给默认值。
     *   默认值会让「前端漏传」和「前端明确传了 1」变成同一件事，
     *   而编辑接口里这两者的后果完全不同：漏传 = 把角色状态悄悄改成 1
     *   （一个被停用的角色会突然生效）。宁可 400。
     */
    @Schema(description = "状态：1 启用，0 停用", example = "1",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "请选择启用状态")
    private Integer status;
}
