package com.novel.modules.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册入参。
 *
 * <p><b>这里没有 {@code role} 字段 —— 这是刻意的。</b>
 * 注册出来的一律是普通读者（READER），角色由后端在
 * {@code AuthServiceImpl.register} 里写死授予。
 * 如果让前端传角色，那就等于「谁都能注册一个管理员账号」，
 * 是最典型也最致命的越权漏洞。
 * <b>凡是「谁能获得什么权限」的决定，永远只能在服务端做。</b>
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "注册入参")
public class RegisterDTO {

    /**
     * 用户名。
     *
     * <p>限制成「字母 / 数字 / 下划线，3~20 位」，一是防止有人用
     * emoji 或超长字符串当用户名（排版会乱、索引会变大），
     * 二是顺手避免了用户名里混入特殊字符后被用在日志或 SQL 里的风险。
     *
     * <p>{@code @Pattern} 的正则里没用 {@code \w} —— 因为 Java 的
     * {@code \w} 默认还会匹配中文，看起来差不多但行为不同，
     * 这里写明确的字符集更不容易误解。
     */
    @Schema(description = "登录用户名，3~20 位字母/数字/下划线", example = "reader01",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9_]{3,20}$", message = "用户名只能是 3~20 位的字母、数字或下划线")
    private String username;

    @Schema(description = "明文密码，6~32 位", example = "123456",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度需在 6~32 位之间")
    private String password;

    /**
     * 昵称，可不填。
     *
     * <p>不填时后端用用户名兜底，避免前端到处要写
     * {@code nickname || username} 这种判断。
     */
    @Schema(description = "昵称，不填则默认与用户名相同", example = "测试读者")
    @Size(max = 20, message = "昵称最长 20 个字符")
    private String nickname;
}
