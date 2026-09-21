package com.novel.modules.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员重置某个用户的密码。
 *
 * <p><b>★ 长度规则和 {@code RegisterDTO.password} 逐条对齐</b>（6~32 位）——
 * 两处不一致的话，会出现「注册时能用的密码，重置时用不了」这种怪事。
 *
 * <p><b>★★ 这个接口和管理员自己的「修改密码」是两回事，别合并：</b>
 * <pre>
 *   改自己的密码：要验原密码（OLD_PASSWORD_ERROR 1008），走 /api/auth/**
 *   重置别人的密码：不验原密码（管理员本来就不知道），走 /api/admin/**
 * </pre>
 * 前者是「我证明我是我」，后者是「我有权限处置这个账号」——
 * 安全性来自 {@code /api/admin/**} 那一道 {@code hasRole("ADMIN")}，不是来自原密码。
 *
 * <p><b>★ 重置之后同样要 {@code tokenStore.removeAll}：</b>
 * 不给的话，对方<b>用旧密码登录过的那些设备全部照常在线</b>——
 * 而「重置密码」这个动作在安全事件里通常正是为了把别人踢出去
 * （账号疑似被盗）。不踢就等于这个功能在最需要它的场景下完全无效。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "重置密码入参")
public class ResetPasswordDTO {

    @Schema(description = "新密码（明文，6~32 位）", example = "newpass123",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度需在 6~32 位之间")
    private String newPassword;
}
