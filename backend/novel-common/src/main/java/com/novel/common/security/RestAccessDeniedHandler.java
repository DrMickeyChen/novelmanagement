package com.novel.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.common.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * <b>鉴权失败</b>的出口（filter chain 层）—— 知道你是谁，但你这个身份不能干这事，
 * 返回 {@code code:403}。
 *
 * <p><b>只覆盖了一半的情况，另一半不在这里：</b>
 * <pre>
 *   URL 级（如 SecurityConfig 里配的 requestMatchers）→ 本类
 *   方法级（@PreAuthorize）                          → GlobalExceptionHandler
 * </pre>
 * 后者因为在 Controller/Service 方法里抛，已经进了 DispatcherServlet，
 * 会走 {@code @RestControllerAdvice}。所以
 * {@code GlobalExceptionHandler} 里那个 {@code AccessDeniedException}
 * 的 handler <b>不能删</b> —— 删了 {@code @PreAuthorize} 拒绝就变成
 * {@code code:500}「服务器开小差了」，排查时完全看不出是权限问题。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        log.warn("鉴权失败（filter chain 层） | {} {} | {}", request.getMethod(),
                request.getRequestURI(), accessDeniedException.getMessage());
        SecurityResponseWriter.write(objectMapper, response, ResultCode.FORBIDDEN);
    }
}
