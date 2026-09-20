package com.novel.modules.system.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 登录成功的返回值。
 *
 * <pre>
 * {
 *   "code": 200,
 *   "message": "操作成功",
 *   "data": {
 *     "token": "eyJhbGciOiJIUzI1NiJ9...",
 *     "tokenType": "Bearer",
 *     "expiresIn": 604800,
 *     "user": { "id":1, "username":"admin", "role":"ADMIN", ... }
 *   }
 * }
 * </pre>
 *
 * <p><b>顺带把用户信息一起返回，而不是让前端再调一次 {@code /me}：</b>
 * 前端登录后必须立刻知道「我是谁、我是什么角色」才能决定跳去哪个端。
 * 分成两个请求的话，中间那一小段时间里用户处于「有 token 但不知道身份」
 * 的状态，路由守卫会闪一下或者误判。一次给全最省事。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "登录结果")
public class LoginVO {

    @Schema(description = "JWT，前端存进 localStorage，之后每次请求放在 Authorization 头里")
    private String token;

    /**
     * 固定是 {@code "Bearer"}。
     *
     * <p>这是 OAuth 2.0 里定义的 token 类型标识，前端拼接时要写成
     * {@code Authorization: Bearer <token>}（中间一个空格）。
     * 返回它而不是让前端写死，是为了将来换认证方案时前端不用改。
     * 后端对应的常量是 {@code CommonConstant.TOKEN_PREFIX}。
     */
    @Schema(description = "token 类型，固定 Bearer", example = "Bearer")
    private String tokenType = "Bearer";

    /**
     * 有效期，单位<b>秒</b>（不是毫秒）。
     *
     * <p>用秒是 OAuth 2.0 的惯例（{@code expires_in} 就是这么定义的）。
     * 前端可以拿它做个「还剩 10 分钟时提示续期」之类的功能 ——
     * 不过 P1 没有 refresh token，到期只能重新登录。
     */
    @Schema(description = "有效期（秒）", example = "604800")
    private Long expiresIn;

    @Schema(description = "当前登录用户的信息（不含密码）")
    private UserVO user;
}
