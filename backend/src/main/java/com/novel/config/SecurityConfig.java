package com.novel.config;

import com.novel.common.security.RestAccessDeniedHandler;
import com.novel.common.security.RestAuthenticationEntryPoint;
import com.novel.common.security.filter.JwtAuthenticationFilter;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置。
 *
 * <p><b>★ 这个类必须和 pom 里的 spring-boot-starter-security 同时存在。</b>
 * 只加依赖不写放行规则，启动后<b>所有接口</b>（包括 {@code /api/ping}
 * 和 Knife4j 的 {@code /doc.html}）都会 401 —— 这是 P0 阶段一直把
 * 那个依赖注释掉的原因。
 *
 * <p><b>两条安全规则分别管什么（别混）：</b>
 * <ul>
 *   <li>{@link #WHITELIST 白名单} —— 只管「<b>能不能匿名访问</b>」。
 *       进了白名单不代表谁都能调，方法上照样可以挂 {@code @PreAuthorize}。</li>
 *   <li>{@code @PreAuthorize} —— 管「<b>这个身份能不能干这事</b>」，
 *       是大纲 §177 里说的「服务做鉴权」，第二期拆分时原地不动。</li>
 * </ul>
 *
 * @author MickeyChen
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity   // ★ 打开 @PreAuthorize；不加它，注解会被静默忽略（不报错！）
@RequiredArgsConstructor
public class SecurityConfig {

    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * 允许<b>匿名</b>访问的路径。
     *
     * <p>注意这里是 {@code permitAll()} 不是「无脑放行」——
     * 白名单里的接口如果还需要权限，照样要在方法上写 {@code @PreAuthorize}。
     */
    public static final String[] WHITELIST = {
            /* 健康检查：P0 验收用的三个探针，必须一直能匿名访问，否则监控没法用 */
            "/api/ping/**",

            /* 登录 / 注册：还没登录当然要能调 */
            "/api/auth/login",
            "/api/auth/register",

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
             * 而这次 401 又触发一次转发……前端只能看到一个空响应，
             * 日志里也看不出原始异常是什么。加这一行，原始错误才能正常返回。
             */
            "/error",

            /* 上传的文件（头像、封面）是静态资源，不需要登录才能看 */
            "/uploads/**",

            /*
             * ==================== 读者端（P2） ====================
             * 看小说不需要注册登录，这是产品的硬要求（大纲 §5.3 约束 1 只约束
             * 「未上架的作品查不到」，从没说「要登录才能查」）。
             *
             * ★★ 注意这里【不能】图省事写成 "/api/**"：
             * 那会把 /api/admin/** 一起放行 —— 管理端接口变成匿名可调，
             * 而且【不报错、不崩溃】，测试时一切正常，上线就是脱库。
             * 白名单必须一条条列出来，多写一条的成本远低于写错一条。
             *
             * 路径与接口的对应关系：
             *   /api/categories                    分类列表
             *   /api/novels                        作品列表
             *   /api/novels/{id}                   作品详情 + 目录
             *   /api/novels/{id}/chapters/{cid}    章节正文（阅读器核心接口）
             *
             * ★ 这两个路径【只】覆盖读者端。/api/author/** 和 /api/admin/**
             * 各自有自己的前缀，不会误伤。
             */
            "/api/categories",
            "/api/novels/**"
    };

    /**
     * 密码编码器。
     *
     * <p><b>为什么必须有这个 bean：</b>
     * <ul>
     *   <li>密码<b>绝对不能存明文</b>，数据库里存的是 BCrypt 哈希</li>
     *   <li>BCrypt 是<b>单向</b>的，只能「拿明文算一遍看和库里的是不是一样」，
     *       不存在「解密」</li>
     *   <li>它每次加密结果都不同（自带随机盐），所以<b>不能用
     *       {@code equals} 比较两个哈希</b>，必须用
     *       {@code passwordEncoder.matches(明文, 库里的哈希)}</li>
     * </ul>
     * 种子管理员 {@code admin / admin123} 在 {@code docs/sql/seed.sql} 里
     * 存的就是 cost=10 的 BCrypt 哈希（{@code $2a$10$Jwayv0o7...}）。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                /*
                 * 关掉 CSRF。CSRF 攻击的前提是「浏览器会自动带上 Cookie」，
                 * 而我们用 JWT 放在 Authorization 头里手动带，
                 * 浏览器不会自动附加 —— 没有 CSRF 的攻击面。
                 * 不关的话，POST 请求会全部 403。
                 */
                .csrf(AbstractHttpConfigurer::disable)

                /*
                 * 关掉 CORS 处理。开发期浏览器只访问 dev-proxy(:3000)，
                 * 由 Vite 代理转发到后端，对浏览器而言是【同源请求】，
                 * 根本不会触发 CORS 预检。见 docs/环境配置.md。
                 * 第二期上线由网关统一处理跨域。
                 */
                .cors(AbstractHttpConfigurer::disable)

                /* 这是前后端分离的 API 服务，不用自带的表单登录 / 弹窗认证 / 默认登出页 */
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)

                /*
                 * ★ 无状态：不让 Spring Security 创建 HttpSession。
                 * 会话状态全在 JWT + Redis 里（Redis key 是 novel:auth:token:{userId}:{jti}），
                 * 这样第二期拆成多实例才能横向扩容 —— 换个实例照样认得你。
                 */
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        /* 浏览器的 CORS 预检请求不带 token，必须放行，否则跨域时全部失败 */
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(WHITELIST).permitAll()

                        /*
                         * ==================== 角色闸（P2） ====================
                         * ★ 顺序【必须】在白名单之后、anyRequest() 之前：
                         *   放在白名单之前会把读者端也挡掉；
                         *   放在 anyRequest() 之后则永远不生效（anyRequest 先命中）。
                         *
                         * 这两条是「URL 前缀级」的粗粒度为界，只管「能不能进这个门」，
                         * 不管「这扇门里的东西是不是你的」。后者由 Service 的
                         * 归属校验兜（见 NovelServiceImpl#requireOwnedNovel），
                         * 两件事的分工见 P2 方案 C-6。
                         *
                         * ★ 故意【不】在这里挂 @PreAuthorize 之外的细粒度权限码：
                         *   sys_menu.perms 只用于前端菜单显隐，不参与 API 鉴权。
                         *   否则给作者开一个新接口就得先造一个权限点、
                         *   再把它挂到角色上，接口和菜单数据被绑死在一起。
                         */
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        /*
                         * ★ 作者端放 ADMIN 进来是有意的：ADMIN 在种子数据里
                         * 拥有全部菜单，是被当作超级角色设计的。挡住他只会制造
                         * 「管理员点创作中心被 403」这种怪现象。
                         * 真正的边界由归属校验兜住 —— 管理员能进这个门，
                         * 但改不了别人的书（会得到 code 2002）。
                         */
                        .requestMatchers("/api/author/**").hasAnyRole("AUTHOR", "ADMIN")

                        /*
                         * 除白名单外一律要求已登录。
                         * 未登录 → 走 authenticationEntryPoint（body code:401）
                         * 已登录但 URL 级权限不够 → 走 accessDeniedHandler（body code:403）
                         */
                        .anyRequest().authenticated())

                /*
                 * ★ 这两个 handler 不能省。省了的话 filter chain 里抛的异常会直接
                 * 冒到 Tomcat，前端收到空 body —— 表现为「登录过期了但页面没跳登录页」。
                 * 见 SecurityResponseWriter 的类注释。
                 */
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))

                /*
                 * ★ 认证过滤器插在 UsernamePasswordAuthenticationFilter 之前。
                 * 位置很重要：它必须跑在 AuthorizationFilter（做鉴权判断的那个）
                 * 之前，否则鉴权时 SecurityContext 里还是空的，所有请求都会被
                 * 当成未登录。
                 *
                 * 为什么是「之前」而不是「之后」：UsernamePasswordAuthenticationFilter
                 * 是表单登录用的，我们完全禁用了它；把 JWT 过滤器放在它前面，
                 * 是 Spring Security 里最不容易出错的位置 —— 它在所有
                 * 「需要已认证身份」的过滤器之前，又在安全链的入口之后。
                 */
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * ★ 阻止 Spring Boot 把这个过滤器<b>再注册一遍</b>到 Servlet 容器上。
     *
     * <p><b>不加会怎样：</b>任何 {@code Filter} 类型的 Bean，Spring Boot 都会
     * 自动注册到 Servlet 容器的过滤器链上（这是「约定优于配置」的一部分）。
     * 于是 {@link JwtAuthenticationFilter} 会同时存在于<b>两个地方</b>：
     * <pre>
     *   ① Servlet 容器链（自动注册的）      ← 我们不想要的那个
     *   ② Security 过滤器链（上面 addFilterBefore 加的）← 我们要的那个
     * </pre>
     *
     * <p>实际上不会出大问题 —— 因为 {@code OncePerRequestFilter} 内部用请求属性
     * 做了「同一请求只执行一次」的保护，而且 Security 链的 order 是 -100，
     * 会先跑。但这是<b>靠两个巧合叠在一起才没事</b>：一旦有人调整了过滤器顺序，
     * 或者把 {@code OncePerRequestFilter} 改成普通 {@code Filter}，
     * 就会变成「认证跑两遍」，而且完全不报错。
     *
     * <p>所以这里显式关掉自动注册，让它的入口<b>只有一个</b>。
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
