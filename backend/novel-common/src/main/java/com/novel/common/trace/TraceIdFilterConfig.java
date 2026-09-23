package com.novel.common.trace;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * <b>把 {@link TraceIdFilter} 显式注册到 Servlet 容器上 —— 顺序是这一段唯一的重点。</b>
 *
 * <p>为什么要有这个类、而不是给 {@code TraceIdFilter} 加一个 {@code @Component}：
 * <b>自动注册的顺序是 {@code LOWEST_PRECEDENCE}，排在 Spring Security（{@code -100}）后面</b>
 * —— 于是 401/403 的请求会被 Security 短路，永远走不到这个过滤器，
 * <b>日志里就没有 traceId</b>。完整的推演和「为什么只有 401 验得出来」
 * 写在 {@link TraceIdFilter} 的类注释里（那里是主文档，这里只说注册这一半）。
 *
 * <h3>★ 为什么这个类住在 {@code novel-common} 却只对四个服务生效</h3>
 *
 * <p>它是个普通的 {@code @Configuration}，靠<b>组件扫描</b>被拾取。而扫描范围是
 * 启动类所在的那个包：
 * <pre>
 *   四个服务：{@code com.novel.XxxApplication} → 扫 {@code com.novel.**} → ✅ 扫到这个类
 *   网关：    {@code com.novel.gateway.GatewayApplication} → 只扫 {@code com.novel.gateway}
 *             → ❌ 扫不到
 * </pre>
 *
 * <p><b>★★★★ 那个「扫不到」不是巧合，是网关能活下来的原因。</b>
 * 网关跑在 WebFlux 上，classpath 里<b>没有 {@code jakarta.servlet-api}</b>；
 * 而本类引用了 {@code FilterRegistrationBean}，{@link TraceIdFilter} 又
 * {@code extends OncePerRequestFilter} —— 它们被加载到网关里就是
 * {@code NoClassDefFoundError}。<b>网关是通过「不扫描 common」整体躲开的</b>，
 * 不是靠这个类做了什么防御。
 *
 * <p>★ 所以：<b>网关的 {@code GatewayApplication} 上那句「不许把扫描范围放宽到
 * {@code com.novel}」的注释，本节又多了一个理由。</b>
 * 它的 javadoc 里有一张「common 里有哪些 servlet-bound 的类」的清单 ——
 * {@link TraceIdFilter} / 本类已经在 M5 加进去了，别再往 common 里放第三个。
 *
 * <h3>★ 和四个 {@code SecurityConfig} 里那段的关系</h3>
 *
 * <p>那边给 {@code HeaderAuthenticationFilter} 配的是
 * {@code FilterRegistrationBean.setEnabled(false)}（关掉自动注册，
 * 因为它只该活在 Security 链里）；这边配的是
 * {@code setOrder(HIGHEST_PRECEDENCE)}（自己指定顺序，因为它必须活在
 * Security 链【外面】）。<b>同一个套路的两半：</b>
 * Servlet 过滤器的顺序是这个项目里唯一不能靠默认值的东西。
 *
 * @author MickeyChen
 */
@Configuration
public class TraceIdFilterConfig {

    /**
     * 注册 {@link TraceIdFilter}，并且<b>指定它排在所有过滤器之前</b>。
     *
     * <p>★ 这里 {@code new TraceIdFilter()} 而不是注入一个 bean：
     * 因为这个过滤器<b>刻意不是</b> {@code @Component}（见 {@link TraceIdFilter}）。
     * 它没有构造依赖，自己 new 出来交给容器注册就行 ——
     * 这样也就不存在「既被自动注册、又被这里注册一遍」的双份问题。
     *
     * <p>★ 顺序取 {@code HIGHEST_PRECEDENCE}（{@code Integer.MIN_VALUE}）而不是
     * 「比 -100 小一点」：
     * <ul>
     *   <li>它和 Spring Security 的 {@code -100} 之间<b>不该再有别的假设</b> ——
     *       写 {@code -101} 的话，将来谁把 Security 的顺序调早一点，这个过滤器
     *       就<b>静默</b>跑到后面去了，而症状正是「401 的日志没有 traceId」，
     *       和当初那个自动注册的坑一模一样。</li>
     *   <li>和 Boot 自己的 {@code OrderedCharacterEncodingFilter} 同级也无所谓：
     *       两者互不依赖，谁先谁后都对（那个管字符编码，这个只管 MDC）。</li>
     * </ul>
     */
    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration() {
        FilterRegistrationBean<TraceIdFilter> registration =
                new FilterRegistrationBean<>(new TraceIdFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("traceIdFilter");
        return registration;
    }
}
