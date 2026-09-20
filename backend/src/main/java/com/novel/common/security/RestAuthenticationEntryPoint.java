package com.novel.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.common.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * <b>认证失败</b>的出口 —— 「你是谁」没搞清楚，返回 {@code code:401}。
 *
 * <p><b>什么时候会走到这里：</b>请求打到一个需要登录的路径，但
 * {@code SecurityContext} 里还是匿名身份（没带 token / token 过期 /
 * token 是伪造的）。是 {@code ExceptionTranslationFilter} 发现
 * 「当前是匿名用户」后转过来的。
 *
 * <p><b>★ 它和 {@link com.novel.common.exception.GlobalExceptionHandler} 的分工
 * （这是本项目最容易漏的一处）：</b>
 * <pre>
 *   filter chain 里失败（URL 级）→ 请求根本没进 DispatcherServlet
 *                                → 必须由本类 / AccessDeniedHandler 处理
 *   @PreAuthorize 失败（方法级） → 已经进了 DispatcherServlet
 *                                → 由 GlobalExceptionHandler 处理
 * </pre>
 * <b>两条路径都要覆盖，少堵一条就是一个洞</b>（比如只写了 EntryPoint，
 * 结果 {@code @PreAuthorize} 拒绝时返回 500 而不是 403）。
 *
 * <p>它和 {@link RestAccessDeniedHandler} 的区别：<br>
 * 这里是<b>没登录</b>（401，去登录），那里是<b>登录了但没权限</b>（403，别白费劲）。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        // 这个日志是排查「前端说登录了但后端说没登录」的第一现场，别删
        log.warn("认证失败 | {} {} | {}", request.getMethod(), request.getRequestURI(),
                authException.getMessage());
        SecurityResponseWriter.write(objectMapper, response, ResultCode.UNAUTHORIZED);
    }
}
