package com.novel.common.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * <b>M5 起，四个 servlet 服务把 traceId 放进 MDC 的唯一入口。</b>
 *
 * <p>它只做三件事，顺序不能变：
 * <pre>
 *   ① 从请求头取 X-Trace-Id（网关写的，或兄弟服务 Feign 拦截器透传的）
 *   ② 合法就采纳；否则自己生成一个
 *   ③ 放进 MDC → 放行 → finally 里清掉
 * </pre>
 *
 * <h3>★★★ 这个类【不是】 {@code @Component} —— 这一条是它最重要的设计</h3>
 *
 * <p>如果写成 {@code @Component extends OncePerRequestFilter}，Spring Boot 会
 * <b>自动把它注册到 Servlet 容器</b>上，而自动注册的默认顺序是
 * {@code Ordered.LOWEST_PRECEDENCE}（最小优先级）。<b>而 Spring Security 的
 * 过滤器链注册顺序是 {@code -100}。</b>
 *
 * <pre>
 *   自动注册（错的）：  ... → Spring Security(-100) → ... → TraceIdFilter(LOWEST)
 *                              ↑ 401/403 在这里就 return 了，根本走不到后面
 *   显式注册（对的）：  TraceIdFilter(HIGHEST) → ... → Spring Security(-100) → ...
 * </pre>
 *
 * <p><b>★ 漏了会怎样：鉴权失败的请求，日志里没有 traceId。</b>
 * 而那恰恰是<b>最需要 traceId 的一类请求</b> —— 用户报「我点了没反应」，
 * 十有八九是一条 401/403，而你去翻日志时那几行偏偏是唯一没有编号的。
 *
 * <p>★★ <b>更阴的是这个坑只有一种请求验得出来</b>：拿一个<b>必然 401</b> 的请求
 * （例如不带 token 打 {@code /api/admin/**}）去看日志。
 * 用任何 200 的请求去验，<b>两种写法都"通过"</b> —— 因为 200 的请求本来就会
 * 一路走到底，排在 Security 前面还是后面都能跑到。
 * 所以这个类的验收判据<b>必须是那条 401</b>（见 {@code docs/M5-治理.md}）。
 *
 * <p>注册代码在 {@link TraceIdFilterConfig}（显式 + {@code HIGHEST_PRECEDENCE}），
 * 也就是四个 {@code SecurityConfig} 里给 {@code HeaderAuthenticationFilter}
 * 配 {@code setEnabled(false)} 的同一个套路的另一半 ——
 * 那边是「关掉自动注册」，这边是「自己指定顺序」，都在解决同一个问题：
 * <b>Servlet 过滤器的顺序是这个项目里唯一不能靠默认值的东西。</b>
 *
 * <h3>★ 为什么采纳，而网关不采纳</h3>
 *
 * <p>见 {@link TraceContext} 的类注释那张表。一句话：<b>网关面对浏览器，
 * 下游面对网关/兄弟服务</b>。不在这里采纳，链路就在每个服务边界断掉。
 *
 * <h3>★ 收到的值要过一遍白名单（不是洁癖）</h3>
 *
 * <p>{@code X-Trace-Id} 会被<b>原样打进日志</b>。不做校验的话，
 * 一个能直连 {@code :8082} 的人可以塞进任意文本去污染日志行
 * （日志伪造 / log forging）。生产上 8081~8084 本该只对内网开放，
 * 但那是<b>网络层</b>的保证，代码层不该假设它一定成立。
 * <p>★ 所以：<b>只采纳 {@code [A-Za-z0-9_-]{1,64}} 形状的值</b>，
 * 不合形状的一律当没带（生成新的）—— 而不是「剥掉」或「报错」：
 * 一个畸形的追踪头不该让请求失败。
 *
 * @author MickeyChen
 */
public class TraceIdFilter extends OncePerRequestFilter {

    /**
     * 合法 traceId 的形状。
     * <p>★ 上界 64 而不是 32：本服务自己生成的是 32 位，但采纳时留一点余量，
     * 好让「将来换成 W3C {@code traceparent} 里那种带连字符的 id」不用改这里。
     * ★ 真正的目的是<b>挡住空格、换行、控制字符和非 ASCII</b>，
     * 长度只是个顺带的护栏。
     */
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String traceId = adoptOrGenerate(request.getHeader(TraceContext.HEADER));
        TraceContext.put(traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            /*
             * ★★ finally 不能省，理由见 TraceContext#clear 的注释
             *   （Tomcat 线程复用 → 下一个请求继承上一个的 traceId）。
             * ★ 和 HeaderAuthenticationFilter 的 finally 是同一个形状。
             */
            TraceContext.clear();
        }
    }

    /**
     * 合法就采纳，否则生成新的。
     *
     * <p>★ 抽成一个方法只是为了让 {@link #doFilterInternal} 里
     * 「取 → 放 → 放行 → 清」这四步一眼能看完 —— 这个方法本身没有分支要展开。
     */
    private static String adoptOrGenerate(String incoming) {
        if (incoming != null && SAFE_TRACE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return TraceContext.newTraceId();
    }

    /*
     * ★ 刻意【不】覆写 shouldNotFilterAsyncDispatch() / shouldNotFilterErrorDispatch()：
     *   OncePerRequestFilter 里这两个的默认值都是 true（= 异步派发和 ERROR 派发
     *   都【不】再进这个过滤器），正是我们要的。
     *
     *   ★ 代价要说清楚：容器把异常转发到 /error 的那一次派发没有 MDC，
     *     所以 BasicErrorController 打的那行日志里 traceId 是空的。
     *     可接受 —— 走到 /error 说明异常没被 GlobalExceptionHandler 接住，
     *     而「真正的原因」那行是在 GlobalExceptionHandler 里打的，
     *     它在过滤器链【之内】，traceId 是有的。
     */
}
