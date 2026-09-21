package com.novel.modules.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 登录入参。
 *
 * <p><b>为什么不用 {@code SysUser} 实体直接接参：</b>
 * 实体上有 {@code id}、{@code status}、{@code deleted} 这些字段。
 * 如果直接拿实体接，前端就能传 {@code {"status":1,"deleted":0}}
 * 之类的东西进来，一旦代码里用 {@code updateById(user)} 保存，
 * 这些字段就跟着被写进库了 —— 这叫<b>过度提交（mass assignment）</b>，
 * 是个很低级但很常见的漏洞。用专门的 DTO 只暴露该收的字段。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "登录入参")
public class LoginDTO {

    @Schema(description = "登录用户名", example = "admin", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "用户名不能为空")
    @Size(max = 50, message = "用户名过长")
    private String username;

    /**
     * 明文密码。
     *
     * <p><b>这里刻意没有写 {@code @Size(min = 6)}</b>（注册接口上才有）。
     * 登录接口不该校验密码的复杂度：万一有历史账号的密码就是短的，
     * 加了这个限制等于把人挡在门外，而且提示语还会反过来泄露
     * 「本站密码至少 6 位」这种策略信息。
     * 登录只做一件事 —— 拿明文去和库里的 BCrypt 哈希比对。
     */
    @Schema(description = "明文密码", example = "admin123", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "密码不能为空")
    @Size(max = 64, message = "密码过长")
    private String password;
}
