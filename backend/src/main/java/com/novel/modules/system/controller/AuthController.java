package com.novel.modules.system.controller;

import com.novel.common.result.Result;
import com.novel.modules.system.dto.LoginDTO;
import com.novel.modules.system.dto.RegisterDTO;
import com.novel.modules.system.service.AuthService;
import com.novel.modules.system.vo.LoginVO;
import com.novel.modules.system.vo.UserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。
 *
 * <p>对应大纲 §1258 的 P1 验收：
 * 「Postman 能登录拿到 token，带 token 能访问 {@code /api/auth/me}，
 * 不带 token 返回 401」。
 *
 * <p><b>四个端点里，哪两个不需要登录：</b>
 * <pre>
 *   POST /api/auth/login     匿名可访问（在 SecurityConfig.WHITELIST 里）
 *   POST /api/auth/register  匿名可访问
 *   POST /api/auth/logout    需要登录 —— 没登录谈何退出
 *   GET  /api/auth/me        需要登录
 * </pre>
 * 白名单只加了前两个。这样设计是默认安全的：
 * <b>新写的接口默认要求登录</b>，想开放得显式去改白名单。
 * 反过来（默认全开放、需要保护的才加限制）就很容易漏，
 * 而且漏了不报错，只表现为「这个接口谁都能调」。
 *
 * @author MickeyChen
 */
@Slf4j
@Tag(name = "01. 认证", description = "注册 / 登录 / 登出 / 获取当前用户")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 登录。
     *
     * <p>{@code @Valid} 不能省 —— 少了它，{@link LoginDTO} 上那些
     * {@code @NotBlank} 会被静默忽略，空用户名会一路传到 SQL 查询里。
     * 这类「注解写了但没生效」的问题排查起来很费时间。
     */
    @Operation(summary = "登录",
            description = "成功返回 token 和用户信息。用户名不存在和密码错误都返回 1002（防用户名枚举）")
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.success(authService.login(dto));
    }

    @Operation(summary = "注册",
            description = "新用户固定授予 READER 角色。角色不能由前端指定")
    @PostMapping("/register")
    public Result<UserVO> register(@Valid @RequestBody RegisterDTO dto) {
        return Result.success(authService.register(dto));
    }

    /**
     * 退出登录 —— 只退出当前设备。
     *
     * <p>用 POST 而不是 GET：GET 是「读取」语义，可以被浏览器预取、
     * 被爬虫跟、被放进 {@code <img src>} 里触发。
     * 登出是有副作用的操作，必须是 POST。
     */
    @Operation(summary = "退出登录", description = "只让当前这台设备下线，其他设备的登录态不受影响")
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.success();
    }

    /**
     * 取当前登录用户。
     *
     * <p>不带 token 调这个接口会返回 {@code {"code":401,...}}（HTTP 状态是 200，
     * 见 {@code Result} 的说明）—— 前端 {@code utils/request.js} 认出 401 后
     * 会清掉本地登录态并跳回登录页。
     */
    @Operation(summary = "当前登录用户", description = "带 token 访问，返回里不含密码")
    @GetMapping("/me")
    public Result<UserVO> me() {
        return Result.success(authService.currentUser());
    }
}
