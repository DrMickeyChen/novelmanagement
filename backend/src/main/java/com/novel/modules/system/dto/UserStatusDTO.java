package com.novel.modules.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 封禁 / 解封入参。
 *
 * <p><b>★ 只收 0 和 1 两个值，和 {@code SysUser.status} 的定义对齐。</b>
 * 用 {@code @Min/@Max} 而不是让 Service 自己判 —— 传 {@code status: 5}
 * 进来应该是一句「参数校验失败」，而不是写进库之后变成一个
 * 谁都不认识的第三种状态（那样这个账号既不算正常也不算封禁，
 * 登录时 {@code STATUS_ENABLED.equals(status)} 是 false，
 * 于是<b>登不进去，但管理端显示的状态是「正常」</b>）。
 *
 * <p><b>★★ 封禁不只是改这一列。</b>
 * 改完 {@code status} 后必须同时调 {@code tokenStore.removeAll(userId)}
 * 把对方的在线会话全部清掉 —— 因为 {@code JwtAuthenticationFilter}
 * 的身份<b>每请求只从 Redis 读，不查库</b>，
 * 不踢的话对方能继续正常使用到 token 自然过期（7 天）。
 * 界面上显示「已封禁」而对方还在用，是这一类 bug 最典型的症状。
 * 见 {@code AdminUserServiceImpl#changeStatus}。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "用户封禁/解封入参")
public class UserStatusDTO {

    @Schema(description = "1 正常（解封），0 禁用（封禁）", example = "0",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "请指明要封禁还是解封")
    @Min(value = 0, message = "状态只能是 0 或 1")
    @Max(value = 1, message = "状态只能是 0 或 1")
    private Integer status;
}
