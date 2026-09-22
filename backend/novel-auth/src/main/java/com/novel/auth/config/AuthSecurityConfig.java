package com.novel.auth.config;

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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * <b>novel-auth 的 Spring Security 配置（M1 新建）</b>—— 认证服务的「白名单 + URL 闸」。
 *
 * <p><b>★★ 为什么它和 novel-backend 的 {@code SecurityConfig} 是两个类，
 * 而不是复制一份改改：</b>
 * 两个服务的白名单<b>本来就不同</b>：auth 这边有 {@code /api/auth/login}
 * （登录当然要能匿名调），backend 那边没有这个接口，留着那条就是一条
 * 永不命中的死规则。反过来 backend 那边有 {@code /api/novels/**} 的匿名
 * GET 限定，auth 这边一条作品接口都没有。
 * <p>如果强行合成一份放 {@code novel-common}，就成了「两个服务共用一份
 * 取并集的白名单」—— <b>那等于把 auth 的公开接口在 backend 上也开一遍</b>。
 * 每个服务的最小权限面必须由它自己声明。这是 M0 留下 {@code SecurityConfig}
 * 不搬进 common 的原因，M1 兑现了它。
 * <p>★ 两个类的<b>类名和包名都不同</b>（{@code com.novel.config.SecurityConfig}
 * 与 {@code com.novel.auth.config.AuthSecurityConfig}），
 * 避免出现「同一个 FQN 有两个类、谁赢看 classpath 顺序」这种查不出来的鬼故事。
 *
 * <p><b>★ 这个类管的两件事（和 backend 那边完全一致，别混）：</b>
 * <ul>
 *   <li>{@link #WHITELIST 白名单} —— 只管「<b>能不能匿名访问</b>」</li>
 *   <li>{@code @PreAuthorize} —— 管「<b>这个身份能不能干这事</b>」，
 *       是大纲 §177 里说的「服务做鉴权」，M1 一行都没动</li>
 * </ul>
 *
 * <p><b>★★ M1 之后这个服务【不再自己验签 JWT】。</b>
 * 身份来自 {@link HeaderAuthenticationFilter}（读网关写下的 {@code X-User-*}
 * 头）。所以这个类里配的全是「URL 闸」—— 一行业务鉴权代码都没改。
 *
 * @author MickeyChen
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity   // ★ 打开 @PreAuthorize；不加它，注解会被静默忽略（不报错！）
@RequiredArgsConstructor
public class AuthSecurityConfig {

    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final HeaderAuthenticationFilter headerAuthenticationFilter;

    /**
     * 允许<b>匿名</b>访问的路径。
     *
     * <p>注意这里是 {@code permitAll()} 不是「无脑放行」——
     * 白名单里的接口如果还需要权限，照样要在方法上写 {@code @PreAuthorize}。
     */
    public static final String[] WHITELIST = {
            /* 健康检查：P0 验收用的探针，必须一直能匿名访问，否则监控没法用 */
            "/api/ping/**",

            /* 登录 / 注册：还没登录当然要能调。★ 这两条只在本服务的白名单里，backend 没有 */
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
             * 日志里也看不出原始异常是什么。
             */
            "/error",

            /*
             * 上传的文件（头像、封面）是静态资源，不需要登录才能看。
             *
             * ★ 开发期这个服务也带着 WebMvcConfig（在 novel-common 里），
             *   所以它同样能直接服 /uploads/**。生产的正式入口是网关
             *   （那条路由指给 novel-backend），这里留着是为了
             *   「直连 :8081 排错时头像也能显示」。
             */
            "/uploads/**",
    };

    /**
     * ★★ <b>内部接口为什么必须 {@code permitAll()}，这是整个 M1 最反直觉的一处。</b>
     *
     * <p>{@code /internal/**} 是给兄弟服务（novel-backend）调的。
     * 它<b>不能</b>要求登录态 —— 因为匿名首页的书列表就要调它
     * （见 {@code InternalUserController} 的类注释）。设成
     * {@code hasRole("ADMIN")} 或落进 {@code anyRequest().authenticated()}
     * 都会让匿名首页直接 500/401。
     *
     * <p><b>那它不是对全世界开放了吗？不是 —— 把关的是另一层：</b>
     * {@link HeaderAuthenticationFilter} 会在请求进到这里之前检查共享密钥
     * （{@code X-Internal-Token}），缺了直接 403。
     * 所以「能不能进」由那个过滤器决定，Spring Security 这一层不用管。
     *
     * <p><b>★★ 两个必须一起改的地方，改一个就是一个洞：</b>
     * <pre>
     *   删掉本方法 / 忘了加  → 合法的服务调用被自己的 anyRequest() 拦下 → 全站 500
     *   删掉过滤器里的那道闸 → /internal/** 对全世界开放，且【没有任何症状】
     * </pre>
     * 第二条尤其危险：它不报错、不影响功能、测试全过。唯一能发现它的方式是
     * 「知道这里本来有一道闸」—— 所以这段注释不能删。
     *
     * <p>★ 另一层结构保证：{@code /internal/**} <b>刻意不在 {@code /api/**} 之下</b>，
     * 而网关的路由表只有 {@code /api/**} 和 {@code /uploads/**} ——
     * 公网在结构上就够不到这个前缀（见 {@code IdentityHeaders} 的说明）。
     */
    public static final String[] INTERNAL_PATHS = {
            "/internal/**",
    };

    /**
     * 密码编码器。
     *
     * <p><b>★ M1 起这个 bean 搬到了本服务。</b>它原来在 novel-backend 的
     * {@code SecurityConfig} 里，而<b>唯一的使用者就是本服务的
     * {@code AuthServiceImpl} 和 {@code AdminUserServiceImpl}</b>
     * （登录校验、注册、管理员重置密码）—— 那两个类 M1 跟着
     * {@code modules/system} 搬过来了，bean 也就跟着它唯一的消费者走。
     *
     * <p>留在 novel-backend 的话，它会变成一个<b>没人用的 bean</b>，
     * 而且会让读代码的人以为「密码哈希是在那边做的」。
     *
     * <p><b>为什么必须有它：</b>
     * <ul>
     *   <li>密码<b>绝对不能存明文</b>，数据库里存的是 BCrypt 哈希</li>
     *   <li>BCrypt 是<b>单向</b>的，只能「拿明文算一遍看和库里的是不是一样」</li>
     *   <li>它每次加密结果都不同（自带随机盐），所以<b>不能用
     *       {@code equals} 比较两个哈希</b>，必须用
     *       {@code passwordEncoder.matches(明文, 库里的哈希)}</li>
     * </ul>
     * 种子管理员 {@code admin / admin123} 在 {@code docs/sql/seed.sql} 里
     * 存的就是 cost=10 的 BCrypt 哈希（{@code $2a$10$Jwayv0o7...}）。
     * <p>★ cost 必须由这个 bean 统一决定 —— 自己 {@code new} 一个
     * {@code BCryptPasswordEncoder} 会出现「有的地方 cost=10、有的地方 12」，
     * 两者产生的哈希互相验不过，而症状只是「某些用户登不上」。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                /* 关掉 CSRF：我们用 JWT 放在 Authorization 头里手动带，浏览器不会自动附加 */
                .csrf(AbstractHttpConfigurer::disable)

                /* 关掉 CORS：开发期浏览器只访问 dev-proxy(:3000)，对浏览器而言是同源请求。
                   第二期上线由网关统一处理跨域。 */
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
                        /*
                         * ==================== 异步派发 ====================
                         * ★ 本服务目前<b>没有任何 SSE / 异步接口</b>，所以这一条
                         *   今天不生效。留着是因为它是一个「加了异步接口之后
                         *   才发作、而且症状完全指不到这里」的坑 —— 完整的事故
                         *   经过（四步）写在 novel-backend 的 SecurityConfig 里，
                         *   这里只留结论：Spring Security 6 默认会对 ASYNC 派发
                         *   重新鉴权，而那时 SecurityContext 已经是空的。
                         * ★ 一行配置换「将来加异步接口时不会莫名其妙断流」，划算。
                         */
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()

                        /* 浏览器的 CORS 预检请求不带 token，必须放行，否则跨域时全部失败 */
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(WHITELIST).permitAll()

                        /*
                         * ==================== 内部接口 ====================
                         * ★★ 见上面 INTERNAL_PATHS 的注释：这一条【必须】排在
                         *    anyRequest() 之前，且把关的是 HeaderAuthenticationFilter。
                         *    顺序错了 → 全站 500（合法的服务调用被自己拦下）。
                         */
                        .requestMatchers(INTERNAL_PATHS).permitAll()

                        /*
                         * ==================== 公告（P5 · S15） ====================
                         * ★ 读者端首页的公告横幅，本服务提供的唯一一个匿名读接口。
                         *
                         * ★★ 三个坑，一个都不能少（都是 P2/P5 踩出来的）：
                         *   ① 【必须带 HttpMethod.GET】。白名单条目默认是「方法无关」的，
                         *      只写路径就等于把将来任何 POST /api/notices（发公告）
                         *      一起匿名放行了。
                         *   ② 【路径是精确的，不带通配符】。写成 /api/notices 加双星号
                         *      会顺带放行将来新增的 GET /api/notices/{id}。
                         *   ③ 【必须排在 anyRequest() 之前】—— 顺序错了规则不生效，
                         *      而且没有任何报错。
                         *
                         * ★ 它敢匿名开放，是因为只读、且只读 status = 1 的公告：
                         *   过滤写在 SQL 的 WHERE 里（NoticeServiceImpl#listPublished），
                         *   不是查出来再 if。
                         */
                        .requestMatchers(HttpMethod.GET, "/api/notices").permitAll()

                        /*
                         * ==================== 角色闸 ====================
                         * ★ 顺序【必须】在白名单之后、anyRequest() 之前。
                         *
                         * 这两条是「URL 前缀级」的粗粒度为界，只管「能不能进这个门」，
                         * 不管「这扇门里的东西是不是你的」。后者由 Service 的
                         * 归属校验兜（比如 AdminUserServiceImpl 的防自锁）。
                         *
                         * ★ 故意【不】在这里挂细粒度权限码：
                         *   sys_menu.perms 只用于前端菜单显隐，不参与 API 鉴权。
                         */
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        /*
                         * 除白名单外一律要求已登录。
                         * 未登录 → authenticationEntryPoint（body code:401）
                         * 已登录但 URL 级权限不够 → accessDeniedHandler（body code:403）
                         *
                         * ★ 本服务剩下的需要登录的接口：
                         *   /api/auth/logout、/api/auth/me
                         *   /api/reader/profile/**（个人资料、改密码、传头像）
                         *   /api/reader/author-apply/**（申请成为作者、查自己的申请）
                         *   全部由这一条兜住 —— 它们都是「只能操作自己」的接口，
                         *   归属校验在 Service 里，不需要 URL 级别的角色闸。
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
                 * 之前，否则鉴权时 SecurityContext 里还是空的，所有请求都会被当成未登录。
                 */
                .addFilterBefore(headerAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * ★ 阻止 Spring Boot 把这个过滤器<b>再注册一遍</b>到 Servlet 容器上。
     *
     * <p>不加会怎样：任何 {@code Filter} 类型的 Bean，Spring Boot 都会
     * 自动注册到 Servlet 容器的过滤器链上。于是
     * {@link HeaderAuthenticationFilter} 会同时存在于两个地方
     * （容器链 + Security 链），靠 {@code OncePerRequestFilter} 内部的
     * 「同一请求只执行一次」保护才没出事 —— 而那是<b>靠巧合叠出来的安全</b>。
     * <p>★ 更要紧的是：如果它跑在 Security 链【之外】，那道
     * {@code /internal/**} 的 403 就发生在 Security 之前，路径语义会变得
     * 依赖容器实现。这里显式关掉自动注册，让它的入口<b>只有一个</b>。
     */
    @Bean
    public FilterRegistrationBean<HeaderAuthenticationFilter> headerFilterRegistration(
            HeaderAuthenticationFilter filter) {
        FilterRegistrationBean<HeaderAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
