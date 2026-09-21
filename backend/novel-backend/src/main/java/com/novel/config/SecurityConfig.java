package com.novel.config;

import com.novel.common.security.RestAccessDeniedHandler;
import com.novel.common.security.RestAuthenticationEntryPoint;
import com.novel.common.security.filter.JwtAuthenticationFilter;
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
             * ==================== 读者端（P3 起）不在这里 ====================
             * 看小说不需要注册登录，这是产品的硬要求（大纲 §5.3 约束 1 只约束
             * 「未上架的作品查不到」，从没说「要登录才能查」）。
             *
             * ★★ 但 P2 那两条 "/api/categories" 和 "/api/novels/**" 已经
             * 【挪走】了，见下面 securityFilterChain 里按 HTTP 方法限定的那几条。
             *
             * 原因是这个数组里的路径【不限定 HTTP 方法】：
             * requestMatchers("/api/novels/**") 放行的是这个前缀下的【所有方法】。
             * 于是以后任何人往 /api/novels/** 下加一个
             * POST /api/novels/{id}/delete 之类的写接口，都会【静默匿名放行】——
             * 不报错、不崩溃、测试全过，只是所有人都能调。这是个真实的口子，
             * 而且它是在你【加新接口】的那一刻张开的，不是在改这个文件的时候。
             *
             * 改成方法限定之后，默认值变安全了：
             *   新加一个 GET  → 自动公开（读者端接口本来就该公开）
             *   新加一个写接口 → 自动要求登录（谁都得先想一下）
             * 不需要再维护一份路径清单，也不会有人「忘了加一条」。
             *
             * ★★ 千万不要为了省事把 "/api/novels/**" 加回这个数组 ——
             * 那会让下面那几条方法限定的规则【彻底失效】（白名单先命中），
             * 而且没有任何症状，直到有人往这个前缀下加了写接口。
             */
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
                        /*
                         * ==================== 异步派发（P4） ====================
                         *
                         * ★★★ 这一条是给 SSE 流收尾用的，看着莫名其妙，删了会让
                         *     【每一个 AI 流都在结束的那一刻断掉】。别删。
                         *
                         * 事情的经过（四步，缺一步都不会是这个症状）：
                         *   ① AI 流推完，SseEmitter 调 complete()
                         *   ② Spring MVC 对这个请求发起一次【ASYNC 派发】——
                         *      拿同一个 URI 再走一遍过滤器链，把响应收尾
                         *   ③ JwtAuthenticationFilter 是 OncePerRequestFilter，
                         *      它的 shouldNotFilterAsyncDispatch() 默认返回 true，
                         *      所以这次派发【不跑认证】→ SecurityContext 是空的
                         *   ④ 而 Spring Security 6 的 AuthorizationFilter 默认
                         *      filterAsyncDispatch = true（5.x 是 false，这是 6 的
                         *      行为变更）→ 这次派发被当成【匿名请求】重新鉴权
                         *      → /api/ai/** 要 AUTHOR → AccessDeniedException
                         *
                         * ★ 症状之所以难认，是因为异常发生在响应【已经提交】之后：
                         *   ExceptionTranslationFilter 写不出 403，只留下两条 ERROR
                         *     「Unable to handle the Spring Security Exception
                         *       because the response is already committed」
                         *     「Cannot render error page ... has already been committed」
                         *   然后【连接被硬切】。前端看到的是「流断了、没有 [DONE]」——
                         *   和超时、断网的症状一模一样，而服务端日志里生成明明是成功的。
                         *
                         * ★★ 放行 ASYNC 为什么不是开洞：
                         *   - DispatcherType 是【Servlet 容器】设的，客户端伪造不了
                         *     （改不了请求头、加不了参数让它变成 ASYNC）
                         *   - ASYNC 派发只可能由【同一次请求】在自己 REQUEST 派发
                         *     已经走完完整鉴权之后发起
                         *   所以放行它 = 放行「一个已经通过鉴权的请求的收尾动作」，
                         *   不是新开一个匿名入口。真正的匿名请求走的是 REQUEST 派发，
                         *   照样要过下面所有的规则。
                         *
                         * ★★ 必须排在【最前面】：authorizeHttpRequests 是第一个命中的
                         *   规则生效，而 ASYNC 派发的 URI 和原请求【完全一样】
                         *   （还是 /api/ai/outline）。这一条若排在 /api/ai/** 那条之后，
                         *   那条会先命中并再次拒绝，这一条形同虚设 —— 而且症状
                         *   和没加它一模一样，会让人以为「加了没用」。
                         *
                         * ★ 为什么不用 shouldFilterAllDispatcherTypes(false)：
                         *   那个开关会连 ERROR 派发一起不管，范围更大。这里只放开
                         *   ASYNC，ERROR 派发仍然按 /error 那条白名单走 ——
                         *   宁可少放开一点。
                         *
                         * ★ 被否决的备选方案：让 JwtAuthenticationFilter 在 ASYNC
                         *   派发上也跑（shouldNotFilterAsyncDispatch() 返回 false）。
                         *   那会在每次流收尾时再查一次 Redis 校验 token，而它本来就是
                         *   同一个已认证的请求 —— 多一次网络往返，换不来任何东西。
                         */
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()

                        /* 浏览器的 CORS 预检请求不带 token，必须放行，否则跨域时全部失败 */
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(WHITELIST).permitAll()

                        /*
                         * ==================== 读者端（P3） ====================
                         * ★★ 这几条【按 HTTP 方法】限定，而不是把路径放进 WHITELIST。
                         * 理由见 WHITELIST 上面那段注释：路径级放行不区分方法，
                         * 会让以后新增的写接口静默匿名。这里 GET 是显式的，
                         * 于是「读者端能读」和「读者端不能写」同时成立，
                         * 而且新增接口时不需要回来改这个文件。
                         *
                         * 路径与接口的对应关系：
                         *   GET /api/categories                    分类列表
                         *   GET /api/novels                        作品列表
                         *   GET /api/novels/rank                   榜单
                         *   GET /api/novels/{id}                   作品详情 + 目录
                         *   GET /api/novels/{id}/chapters/{cid}    章节正文（阅读器核心）
                         *   GET /api/search                        搜索
                         */
                        .requestMatchers(HttpMethod.GET, "/api/categories").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/novels/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/search").permitAll()

                        /*
                         * ★ 唯一一条匿名 POST：阅读量埋点 POST /api/novels/{id}/view。
                         *
                         * 路径模式用的是【单星号】：novels 和 view 之间只隔一个路径段，
                         * 于是它精确锁死在「作品 id + view」这个形状上，
                         * 不会顺带放行 /api/novels/1/delete 之类的路径。
                         * 这里若写成双星号，等于把上面那几条 GET 限定的努力全废掉。
                         * （★ 本行注释里不能出现字面的 星号加斜杠 —— 那会提前闭合块注释，
                         *   编译报一堆莫名其妙的「需要 ')'」。上面就踩过一次。）
                         *
                         * 它敢匿名开放，是因为三件事同时成立：
                         *   ① 不写库 —— 只对一个 Redis 计数器 INCR，由定时任务异步汇总；
                         *   ② 不产生数据 —— 没有 INSERT、没有唯一键、不记录调用者；
                         *   ③ 不放宽读的口径 —— 对不存在的书、未上架的书、正常的书，
                         *      响应<b>一个字节都不差</b>（见 ReaderNovelController#recordView
                         *      里那个 catch），所以它不能被当成「这本书存不存在」的探针。
                         * 三条里少一条，它就必须挪到需要登录的前缀下。
                         */
                        .requestMatchers(HttpMethod.POST, "/api/novels/*/view").permitAll()

                        /*
                         * ==================== 公告（P5 · S15） ====================
                         * ★ 读者端首页的公告横幅。**只有这一条** ——
                         *   管理端的 /api/admin/notices 什么都不用加，见下面角色闸那段。
                         *
                         * ★★ 三个坑，一个都不能少：
                         *   ① 【必须带 HttpMethod.GET】。白名单条目默认是
                         *      「方法无关」的，只写路径就等于把将来任何
                         *      POST /api/notices（发公告）一起匿名放行了 ——
                         *      这一条 P2 已经有白名单条目踩过，见上面 WHITELIST 的注释。
                         *   ② 【路径是精确的，不带通配符】。写成 /api/notices 加双星号
                         *      会顺带放行将来新增的 GET /api/notices/{id}，
                         *      而那需要单独判断一次（单条公告将来可能带草稿）。
                         *   ③ 【必须排在这一行】—— 在 .anyRequest().authenticated() 之前。
                         *      顺序错了规则不生效，而且没有任何报错。
                         *
                         * ★ 它敢匿名开放，是因为只读、且只读 status = 1 的公告：
                         *   过滤写在 SQL 的 WHERE 里（NoticeServiceImpl#listPublished），
                         *   不是查出来再 if —— 所以「未发布的公告泄露给所有人」
                         *   这条唯一的风险路径不存在。
                         */
                        .requestMatchers(HttpMethod.GET, "/api/notices").permitAll()

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
                         * ==================== AI 接口（P4） ====================
                         *
                         * ★★ 这一条【必须单独写】，它盖不住 —— 这是 P4 最容易漏的一处。
                         *
                         * 写作助手的接口路径是 /api/ai/**（大纲 §6.3 定的），
                         * 而【不是】/api/author/ai/**。前缀不一样，
                         * 上面那条 /api/author/** 规则对它【完全无效】。
                         *
                         * 漏了这一条的症状：AI 接口一路落到最后的
                         * anyRequest().authenticated() —— 听起来还算安全
                         * （匿名的确调不到），但【普通读者 READER 能调】。
                         * 而 AI 是要烧配额的（E1 接了真实模型之后就是烧钱），
                         * 且不出任何错、不打任何日志，直到有人把额度刷完。
                         *
                         * ★ 为什么不让 AI 接口挪到 /api/author/ai/** 下面：
                         * 那会让「一个包 = 一个将来要拆出去的微服务」这条约定破功 ——
                         * modules/ai 是独立的包（将来的 novel-ai），
                         * 它的 URL 前缀应该和它的包对应，而不是寄居在 author 下面。
                         * 多写一行配置，换 URL 结构和模块结构一致，是划算的。
                         */
                        .requestMatchers("/api/ai/**").hasAnyRole("AUTHOR", "ADMIN")

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
