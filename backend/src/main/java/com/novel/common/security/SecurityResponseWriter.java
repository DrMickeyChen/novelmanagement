package com.novel.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.common.result.Result;
import com.novel.common.result.ResultCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 把安全层的失败结果按<b>全项目统一的响应体格式</b>写回给前端。
 *
 * <p><b>为什么需要它：</b>Spring Security 的失败处理发生在
 * <b>DispatcherServlet 之外</b> —— 请求还没进 Controller 就被拦下了，
 * 所以 {@code @RestControllerAdvice} 根本接不到，异常会直接冒到 Tomcat，
 * 前端收到一个空 body 或一坨 HTML 错误页。必须由
 * {@link RestAuthenticationEntryPoint} / {@link RestAccessDeniedHandler}
 * 自己往 response 里写。
 *
 * <p><b>★ HTTP 状态码固定写 200，错误码放在 body 的 {@code code} 里。</b>
 * 这是全项目的统一约定（见 {@link Result} 的类注释）。
 * 代价要清楚：Postman 里看到的永远是 200，用 HTTP 状态码做的
 * 监控/告警看不见鉴权失败，排查时只能看 body。
 *
 * @author MickeyChen
 */
public final class SecurityResponseWriter {

    private SecurityResponseWriter() {
    }

    /**
     * @param objectMapper 用 Spring MVC 那一个，保证输出格式和业务接口完全一致
     *                     （比如 null 字段不返回、时间格式化规则都一样）
     * @param resultCode   要返回的错误码
     */
    public static void write(ObjectMapper objectMapper,
                             HttpServletResponse response,
                             ResultCode resultCode) throws IOException {
        // ★ 200 而不是 401/403 —— 见类注释
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        objectMapper.writeValue(response.getWriter(), Result.error(resultCode));
    }
}
