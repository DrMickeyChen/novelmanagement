package com.novel.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.common.result.Result;
import com.novel.common.result.ResultCode;
import com.novel.common.result.ResultHttpStatus;
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
 * <p><b>★★ HTTP 状态码跟着 {@code code} 走，不再固定 200。</b>
 * 由 {@link ResultHttpStatus} 统一翻译：401 / 403 落在 400~599 区间，
 * 所以会原样发给前端；业务码（1001+）才返 200。
 *
 * <p><b>为什么必须和 {@code GlobalExceptionHandler} 用同一个规则：</b>
 * 鉴权失败有两条路 ——
 * <pre>
 *   ① URL 级（这里）：filter chain 拦截 → EntryPoint / DeniedHandler → 本类
 *   ② 方法级：{@code @PreAuthorize} 拒绝 → 已进 DispatcherServlet
 *             → {@code @RestControllerAdvice}
 * </pre>
 * 同一个「权限不足」，走①和走②必须给出<b>一样的 HTTP 状态码</b>，
 * 否则监控上要写两条规则才能统计全，而且两条规则的阈值还可能被改歪。
 * 两边都调用 {@link ResultHttpStatus} 是保证一致性的唯一手段 ——
 * 不靠人肉对齐，靠只有一处判断逻辑。
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
        /*
         * ★ 状态码从 resultCode 推导，不写死。
         *
         * 这里传进来的只会是 UNAUTHORIZED(401) 和 FORBIDDEN(403)，
         * 都在 400~599 区间，所以发出去的就是真实的 401/403。
         * 但仍然走 ResultHttpStatus 而不是直接写 SC_UNAUTHORIZED ——
         * 让它和 GlobalExceptionHandler 共用同一处判断，
         * 以后改规则（比如某个码想特殊处理）只用改一个地方。
         */
        response.setStatus(ResultHttpStatus.of(resultCode.getCode()).value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        objectMapper.writeValue(response.getWriter(), Result.error(resultCode));
    }
}
