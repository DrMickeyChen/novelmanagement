package com.novel.search.config;

import com.novel.common.security.RestAccessDeniedHandler;
import com.novel.common.security.RestAuthenticationEntryPoint;
import com.novel.common.security.filter.HeaderAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * <b>novel-search（搜索服务 :8084）的 Spring Security 配置。</b>
 *
 * <p><b>★ 这个类必须和 pom 里的 spring-boot-starter-security 同时存在。</b>
 * 只加依赖不写放行规则，启动后<b>所有接口</b>（包括 Knife4j 的
 * {@code /doc.html}）都会 401。
 *
 * <p><b>两条安全规则分别管什么（别混）：</b>
 * <ul>
 *   <li>{@link #WHITELIST 白名单} —— 只管「<b>能不能匿名访问</b>」。</li>
 *   <li>{@code @PreAuthorize} —— 管「<b>这个身份能不能干这事</b>」。</li>
 * </ul>
 *
 * <h3>★★ M4 改了什么 —— 这个类是从 content 那里「搬了一半过来」</h3>
 *
 * <p>M4 之前 {@code GET /api/search} 住在 {@code novel-content}，
 * 放行规则写在 {@code ContentSecurityConfig}。M4 把它整个搬到了本服务，
 * 于是<b>那条 {@code permitAll} 必须跟着搬</b>，并且
 * <b>content 那边要同时删掉</b>。
 *
 * <p><b>★★ 为什么这是 M4 最容易漏的一处，而且漏了【不会 500】：</b>
 * <pre>
 *   搬到本服务、忘了删 content 那份 → 没有直接后果，但 content 多一条
 *                                     指向不存在接口的白名单（见下）
 *   没搬过来（本服务没有这条）      → /api/search 匿名访问【401】
 *   ★★ 而 401 的迷惑性在于：它看起来像「没登录」，
 *      不像「白名单搬漏了」。前端搜索页会在【未登录状态】下报错，
 *      而登录之后一切正常 —— 于是很容易被误判成
 *      「搜索需要登录」这种产品问题，而不是配置问题。
 * </pre>
 * <p>★ 症状对照已经写进 M4 的验收表：<b>匿名 {@code GET /api/search} 必须 200</b>。
 *
 * <h3>★★ 本服务和 content 的白名单为什么必须【各写各的】</h3>
 *
 * <p>看着像重复，其实不能合并：白名单天然是 per-service 的，
 * 合并成一份 = 取并集 = 把两个服务的公开面互相对开。
 * ★ 本服务的公开面比 content 小得多 —— 只有搜索一条。
 *
 * <h3>★ 本服务没有 {@code INTERNAL_PATHS}，这不是漏了</h3>
 *
 * <p>content / interaction / auth 的白名单里都有一条 {@code "/internal/**"}，
 * 因为<b>它们互相调用</b>。本服务<b>没有对外提供任何内部端点</b> ——
 * 它只被网关调用（{@code /api/search}），自己则是 content 的消费者。
 * <p>★★ 所以这里刻意<b>不</b>照抄那一条：抄过来会是一条
 * <b>永远为空的放行规则</b>（没有任何 Handler 挂在这个前缀下），
 * 而它会让人以为「本服务有内部接口」。<b>一条空放行比没有更糟</b> ——
 * 将来真加了内部端点时，它是静默可用的，没人会想起来补鉴权。
 * <p>★ 判据：白名单里的每一条都应该能说出「哪个 Handler 在接它」。
 * 说不出就是在描述一个不存在的接口。
 *
 * @author MickeyChen
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity   // ★ 打开 @PreAuthorize；不加它，注解会被静默忽略（不报错！）
@RequiredArgsConstructor
public class SearchSecurityConfig {

    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final HeaderAuthenticationFilter headerAuthenticationFilter;

    /**
     * 允许<b>匿名</b>访问的路径（不含按 HTTP 方法限定的那些）。
     *
     * <p>★ 对照 {@code ContentSecurityConfig.WHITELIST}：本服务【不提供】静态资源，
     * 所以没有 {@code /uploads/**}；也没有 {@code /internal/**}（理由见类注释）。
     */
    public static final String[] WHITELIST = {
            /* Knife4j / OpenAPI 文档页。少一个都会让 /doc.html 打不开，
               而且报的是 401 不是 404，很容易误以为「文档没配好」。 */
            "/doc.html",
            "/webjars/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-resources/**",
            "/favicon.ico",

            /*
             * ★★ /error 必须放行，否则会出现「401 套 401」。
             *
             * 任何请求出错时，Spring Boot 会把它【转发】到 /error 再处理。
             * 如果 /error 本身受保护，这个转发又会被安全链拦下报 401，
             * 而这次 401 又触发一次转发……前端只能看到一个空响应。
             */
            "/error",
    };

    /*
     * ★★ 这里【没有】passwordEncoder() 这个 bean —— 它住在 novel-auth。
     * 本服务一条密码逻辑都没有。
     */

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                /* 关掉 CSRF：JWT 放在 Authorization 头里手动带，浏览器不会自动附加 —— 没有攻击面。
                   不关的话，POST 请求会全部 403。 */
                .csrf(AbstractHttpConfigurer::disable)

                /* 关掉 CORS：开发期浏览器只访问 dev-proxy(:3000)，对浏览器是同源请求。
                   第二期上线由网关统一处理跨域。 */
                .cors(AbstractHttpConfigurer::disable)

                /* 前后端分离的 API 服务，不用自带的表单登录 / 弹窗认证 / 默认登出页 */
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)

                /* ★ 无状态：会话状态全在 JWT + Redis 里，这样才能横向扩容 */
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                /*
                 * ★ ASYNC 派发放行：Spring MVC 的异步请求（Callable / DeferredResult）
                 * 会二次派发回容器，那次派发的 SecurityContext 是空的。
                 * 不放行的话，任何异步接口都会在第二次派发时被判成未登录。
                 * ★ 项目里目前没有异步接口（AI 流式用的是 SseEmitter + 线程池，
                 * 是另一套机制），但这条和 content 保持逐字一致 —— 少一条
                 * 将来加异步接口时会出现「同步能调、异步 401」这种极难查的现象。
                 */
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()

                        /* 预检请求放行：浏览器发 OPTIONS 时不会带 Authorization 头 */
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        /* 文档页等固定白名单 */
                        .requestMatchers(WHITELIST).permitAll()

                        /*
                         * ==================== 读者端：搜索【必须匿名可访问】 ====================
                         *
                         * 看小说不需要注册登录，这是产品的硬要求
                         * （大纲 §5.3 约束 1 只约束「未上架的作品查不到」，
                         *   从没说「要登录才能查」）。
                         *
                         * ★★ 这条规则是从 content 搬过来的，逐字保留 ——
                         *    包括「按 HTTP 方法限定」这个写法：搜索只有 GET，
                         *    写方法限定比写 "/api/search/**" 安全，
                         *    将来若在本前缀下加写接口，默认就是「要登录」。
                         *
                         * ★★ 它同时是【判断 3 那道第二闸门】的入口：
                         *    这个接口匿名放行、没有登录态可以兜底，
                         *    所以「用户能搜到什么」完全由服务端自己把关 ——
                         *    ES 侧的 status 过滤 + content 装配端的 status 校验，
                         *    两道都在，缺一道就是【直接对外的内容泄露】。
                         */
                        .requestMatchers("/internal/book-finder/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/search").permitAll()

                        /*
                         * ==================== 管理端：重建索引 ====================
                         *
                         * ★★ 这条是 M4 新增的、本服务唯一的写操作。
                         *    它触发一次全量重建（DELETE 索引 + 重建 + 灌数据）——
                         *    是全项目权限要求最高的操作之一，所以只给 ADMIN。
                         *
                         * ★ 顺序必须在白名单之后、anyRequest() 之前：
                         *   放在 anyRequest() 之后则永远不生效（anyRequest 先命中）。
                         */
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        /*
                         * ★ 除白名单外一律要求已登录。
                         * 未登录 → 走 authenticationEntryPoint（body code:401）
                         * 已登录但权限不够 → 走 accessDeniedHandler（body code:403）
                         */
                        .anyRequest().authenticated())

                /*
                 * ★ 这两个 handler 不能省。省了的话 filter chain 里抛的异常会直接
                 * 冒到 Tomcat，前端收到空 body —— 表现为「登录过期了但页面没跳登录页」。
                 */
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))

                /*
                 * ★ 认证过滤器插在 UsernamePasswordAuthenticationFilter 之前。
                 * 它必须跑在 AuthorizationFilter 之前，否则鉴权时 SecurityContext
                 * 里还是空的，所有请求都会被当成未登录。
                 */
                .addFilterBefore(headerAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * ★ 阻止 Spring Boot 把这个过滤器<b>再注册一遍</b>到 Servlet 容器上。
     *
     * <p><b>不加会怎样：</b>任何 {@code Filter} 类型的 Bean，Spring Boot 都会
     * 自动注册到 Servlet 容器的过滤器链上。于是 {@link HeaderAuthenticationFilter}
     * 会同时存在于两个地方（容器链 + Security 链）。
     *
     * <p>实际上不会出大问题 —— 因为 {@code OncePerRequestFilter} 内部做了
     * 「同一请求只执行一次」的保护。但这是<b>靠巧合才没事</b>。
     *
     * <p>★★ <b>更硬的理由：</b>这个过滤器里带着「缺共享密钥就按匿名处理」那道闸。
     * 如果它跑在 Security 链<b>之外</b>，那道判断就发生在 Security 判定之前 ——
     * 「身份头可不可信」的语义会变得依赖 Servlet 容器的注册顺序，
     * 而那是没人会去读的地方。
     *
     * <p>★ <b>四个服务的 SecurityConfig 里都有这个方法</b>
     * （M4 起是四个：auth / content / interaction / search），一个都不能少。
     * ★★ 注意：这里重复的是<b>这段注册代码</b>，不是过滤器本身 ——
     * {@code HeaderAuthenticationFilter} 全项目只有一份，住在
     * {@code novel-common} 里。这一点很容易说反，见 M4 的文档。
     */
    @Bean
    public FilterRegistrationBean<HeaderAuthenticationFilter> headerFilterRegistration(
            HeaderAuthenticationFilter filter) {
        FilterRegistrationBean<HeaderAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
