package com.novel.interaction.config;

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
 * <b>novel-interaction（互动服务 :8083）的 Spring Security 配置。</b>
 *
 * <p><b>★ 这个类必须和 pom 里的 spring-boot-starter-security 同时存在。</b>
 * 只加依赖不写放行规则，启动后<b>所有接口</b>（包括 Knife4j 的
 * {@code /doc.html}）都会 401。
 *
 * <p><b>两条安全规则分别管什么（别混）：</b>
 * <ul>
 *   <li>{@link #WHITELIST 白名单} —— 只管「<b>能不能匿名访问</b>」。
 *       进了白名单不代表谁都能调，方法上照样可以挂 {@code @PreAuthorize}。</li>
 *   <li>{@code @PreAuthorize} —— 管「<b>这个身份能不能干这事</b>」。</li>
 * </ul>
 *
 * <h3>★★ M2 改了什么 —— 这个类是「一个类劈成两个」的产物</h3>
 *
 * <p>M2 之前本服务叫 novel-backend，content / interaction / ai 三个模块
 * <b>住同一个进程</b>，共用一份 {@code com.novel.config.SecurityConfig}。
 * 现在 content 与 ai 搬去了 novel-content，那份配置也就必须劈开 ——
 * 因为「谁能匿名访问」这件事，两个服务的答案<b>完全不同</b>。
 *
 * <p><b>★★★ 劈分里最容易出事的一条：三条评论读接口的匿名放行。</b>
 *
 * <p>M2 之前，下面这三条<b>自己一条规则都没有</b>：
 * <pre>
 *   GET /api/novels/{novelId}/comments
 *   GET /api/novels/{novelId}/chapters/{chapterId}/comments
 *   GET /api/novels/{novelId}/comments/{commentId}/replies
 * </pre>
 * 它们靠的是 content 那条 {@code GET /api/novels/**} ——
 * 前缀碰巧盖住了它们。拆开之后那条规则跟着 content 走了，
 * <b>这三条会掉进 {@code anyRequest().authenticated()}</b>。
 *
 * <p><b>症状有多难发现：</b>没有登录的游客打开作品详情页看评论列表，
 * 从 200 变成 <b>401</b> → 前端 {@code request.js} 的 handleUnauthorized
 * 会清掉登录态并跳到登录页 → 于是「游客点进一本书，被弹去登录页」。
 * ★ 而<b>只有真的以游客身份点一次才会发现</b> —— 开发时自己一直是登录状态，
 * 回归脚本如果每个用例都带 token，也完全看不出来。
 * 所以这三条在 M2 的验证清单里是<b>必测项</b>，不是可选项。
 *
 * <p><b>★ 反过来，有一条规则【绝不能】跟着抄过来：</b>
 * {@code GET /api/novels/**}。本服务只提供这个前缀下【极少数】几个端点
 * （就上面那三条），抄过来等于把 content 的正文读接口在这里也匿名开一遍 ——
 * 多开的口子没有对应的实现，看起来无害，但它是<b>纵深防御</b>的反面：
 * 将来本服务往这个前缀下加任何接口，都会静默地变成匿名可读。
 *
 * <p><b>★★ 这个类为什么不搬进 novel-common、也不和别的服务共用一份：</b>
 * 它的全部内容就是「<b>本服务的</b>白名单 + URL 闸」，天然是 per-service 的 ——
 * 本服务一条 {@code /api/ai/**} 都没有（AI 在 content），
 * 而 content 那边一条 {@code /api/reader/shelf/**} 都没有。
 * 合并成一份 = 取并集 = 把两边的公开面互相对开。
 * <p>四个服务各有自己的 {@code XxxSecurityConfig}，<b>类名和包名都不同</b>，
 * 不会出现「同 FQN 两个类、谁赢看 classpath 顺序」的鬼故事。
 *
 * @author MickeyChen
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity   // ★ 打开 @PreAuthorize；不加它，注解会被静默忽略（不报错！）
@RequiredArgsConstructor
public class InteractionSecurityConfig {

    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final HeaderAuthenticationFilter headerAuthenticationFilter;

    /**
     * 允许<b>匿名</b>访问的路径（不含按 HTTP 方法限定的那些，见
     * {@code securityFilterChain} 里）。
     *
     * <p>注意这里是 {@code permitAll()} 不是「无脑放行」——
     * 白名单里的接口如果还需要权限，照样要在方法上写 {@code @PreAuthorize}。
     */
    public static final String[] WHITELIST = {
            /*
             * ★★ 这些条目【只描述本服务自己有的接口】。
             *
             * 每拆一次服务，白名单就要做一次减法：M1 拆走 auth 时删掉了
             * /api/ping/** 与 /api/auth/login|register；M2 拆走 content 时
             * 又删掉了 /api/categories、/api/novels/**、/api/search。
             *
             * ★ 留着它们不会报错，但会让人以为本服务还有那些接口 ——
             * 而真正危险的是：将来本服务若新增一个同前缀的接口，
             * 它会因为这些陈旧条目【静默匿名放行】。
             */

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

            /*
             * ★★ /uploads/** 由【本服务】放行并由本服务提供静态资源。
             *
             * M2 之后上传的【写】发生在两个别的地方：
             *   作品封面 → novel-content（AuthorUploadController）
             *   用户头像 → novel-auth
             * 而【读】仍然只有这里一条路 —— 网关把 /uploads/** 路由到本服务
             * （见 novel-gateway/application.yml，order 10）。
             *
             * ★ 所以三个服务必须把 novel.upload.path 配成【同一个绝对路径】，
             *   否则「上传成功但图片 404」。这是 M1 R10 记录的同一个问题：
             *   开发期它靠「都在同一台 Windows 上」成立，生产必须接对象存储。
             */
            "/uploads/**",
    };

    /**
     * ★★ <b>内部接口。</b>本服务给兄弟服务调的那几个端点。
     *
     * <p>M2 之后有一个：{@code POST /internal/comment-briefs}
     * （novel-content 渲染审核日志时要评论正文，回头调本服务）。
     * 它是铁律 1 原文里明确列出的那个反向接缝
     * （「{@code CommentTextLookup}（content 读 interaction）」），
     * 见 {@code InternalCommentController} 的类注释。
     *
     * <p><b>它为什么必须 {@code permitAll()}：</b>
     * 调它的 novel-content 手里<b>没有</b>登录用户的 token —— 它有的是
     * 「当前请求的登录态」（靠 Feign 的出站拦截器转成 {@code X-User-*} 头带过来）。
     * 而它自己<b>不验签</b>，所以这一层没法做认证，只能放行。
     *
     * <p><b>那它凭什么不能被人随便调？把关的是 {@link HeaderAuthenticationFilter}：</b>
     * 请求必须带服务间共享密钥（{@code X-Internal-Token}），
     * 缺了或者对不上<b>直接 403</b>，根本走不到这里。
     *
     * <p><b>★★ 两处必须一起改，改一处就是一个洞：</b>
     * <pre>
     *   删掉这一条 / 忘了加  → 合法的服务调用被自己的 anyRequest() 拦下
     *                          → 管理端「审核日志」列表直接 500
     *   删掉过滤器里的那道闸 → /internal/** 对全世界开放，且【没有任何症状】
     * </pre>
     * 第二条尤其危险：不报错、不影响功能、测试全过。唯一的发现方式是
     * 「知道这里本来有一道闸」—— 所以这段注释不能删。
     *
     * <p>★ 另一层结构保证：{@code /internal/**} <b>刻意不在 {@code /api/**} 之下</b>，
     * 而网关的路由表只有 {@code /api/**} 和 {@code /uploads/**} ——
     * 公网在结构上就够不到这个前缀。验证方式很干脆：
     * {@code curl :8080/internal/...} 必须 404。
     */
    public static final String[] INTERNAL_PATHS = {
            "/internal/**",
    };

    /*
     * ★★ 这里【没有】passwordEncoder() 这个 bean —— 它住在 novel-auth。
     *
     * 本服务一条密码逻辑都没有（不登录、不改密码、不哈希任何东西）。
     * 将来若真需要 BCrypt，从 auth 那边【搬回来】，
     * 不要在两边各留一份：两份 BCryptPasswordEncoder 一旦 cost 配得不一样，
     * 产生的哈希互相验不过，而症状只是「某些用户登不上」。
     */

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
                 * 这样拆分多实例之后才能横向扩容 —— 换个实例照样认得你。
                 */
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        /*
                         * ==================== 异步派发（P4 起） ====================
                         *
                         * ★★ 先说清楚：本服务【目前没有】SSE / 异步接口，
                         *    这条规则是为 future 保留的【预防性】配置，不是当前必需。
                         *
                         * ★ 那为什么还留着：它挡的那个坑【没有任何症状】。
                         *   删掉它，如果哪天本服务加了一个 SSE 接口（比如
                         *   「评论实时推送」），症状会是「每个流都在结束的那一刻
                         *   断掉，没有 [DONE]，服务端日志里生成明明是成功的」——
                         *   和超时、断网一模一样。留一行配置换掉一整个排查过程，
                         *   是划算的。
                         *
                         * 它挡的是什么（四步，缺一步都不会是这个症状）：
                         *   ① AI 流推完，SseEmitter 调 complete()
                         *   ② Spring MVC 对这个请求发起一次【ASYNC 派发】——
                         *      拿同一个 URI 再走一遍过滤器链，把响应收尾
                         *   ③ HeaderAuthenticationFilter 是 OncePerRequestFilter，
                         *      它的 shouldNotFilterAsyncDispatch() 默认返回 true，
                         *      所以这次派发【不跑认证】→ SecurityContext 是空的
                         *   ④ 而 Spring Security 6 的 AuthorizationFilter 默认
                         *      filterAsyncDispatch = true（5.x 是 false）→
                         *      这次派发被当成【匿名请求】重新鉴权 → 403
                         *
                         * ★★ 放行 ASYNC 为什么不是开洞：
                         *   - DispatcherType 是【Servlet 容器】设的，客户端伪造不了
                         *   - ASYNC 派发只可能由【同一次请求】在自己 REQUEST 派发
                         *     已经走完完整鉴权之后发起
                         *   所以放行它 = 放行「一个已经通过鉴权的请求的收尾动作」。
                         *   真正的匿名请求走的是 REQUEST 派发，照样要过下面所有规则。
                         *
                         * ★★ 必须排在【最前面】：authorizeHttpRequests 是第一个
                         *   命中的规则生效，而 ASYNC 派发的 URI 和原请求【完全一样】。
                         *   这一条若排在具体路径之后，那条会先命中并再次拒绝，
                         *   这一条形同虚设 —— 而且症状和没加它一模一样。
                         */
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()

                        /* 浏览器的 CORS 预检请求不带 token，必须放行，否则跨域时全部失败 */
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(WHITELIST).permitAll()

                        /*
                         * ==================== 评论读：匿名（★★★ M2 新增，最容易漏） ====================
                         *
                         * ★★★ 这三条是 M2 拆服务时【必须】补上的，补不上的后果见类注释。
                         *
                         * 为什么是「单星号」而不是「双星号」：
                         *   单星号只隔一个路径段，于是这三条精确锁死在
                         *   「作品 id + comments」这种形状上，不会顺带放行
                         *   /api/novels/1/delete 之类的路径。
                         *   ★ 写成双星号就等于把 content 的整棵 /api/novels 子树
                         *     在这里也匿名开了一遍 —— 那正是我们要避免的。
                         *
                         * 为什么「读者能读评论」本身就是产品要求：
                         *   和「看小说不需要登录」同源（大纲 §5.3 约束 1 只约束
                         *   「未上架的作品查不到」，从没说要登录才能查）。
                         *   一个游客点进作品详情页，评论列表就该直接显示出来。
                         *
                         * 路径与接口的对应关系（都在 ReaderCommentController）：
                         *   GET /api/novels/{novelId}/comments                        书评列表
                         *   GET /api/novels/{novelId}/chapters/{chapterId}/comments   章评列表
                         *   GET /api/novels/{novelId}/comments/{commentId}/replies    回复列表
                         */
                        .requestMatchers(HttpMethod.GET, "/api/novels/*/comments").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/novels/*/chapters/*/comments").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/novels/*/comments/*/replies").permitAll()

                        /*
                         * ★★ 三个【写】评论接口【刻意不在这里】，它们落到最后的
                         *    anyRequest().authenticated() —— 和 M2 之前的行为【逐字一致】。
                         *
                         * 也就是说「同一个路径，GET 匿名、POST 需登录」。
                         * 这看起来奇怪，但 ReaderCommentController 的类注释专门
                         * 论证过：读是公开内容，写要留下身份（否则评论区就是
                         * 匿名骂人的地方）。这不是疏漏，别去「统一」它。
                         *
                         * 同理 /api/comments/** 那几个（点赞 / 回复 / 删除）
                         * 也全部需要登录。
                         */

                        /*
                         * ==================== 内部接口 ====================
                         * ★★ 见上面 INTERNAL_PATHS 的注释：这一条【必须】排在
                         *    anyRequest() 之前，且把关的是 HeaderAuthenticationFilter。
                         *    顺序错了 → 审核日志列表直接 500（合法的服务调用被自己拦下）。
                         */
                        .requestMatchers(INTERNAL_PATHS).permitAll()

                        /*
                         * ==================== 角色闸 ====================
                         * ★ 顺序【必须】在白名单之后、anyRequest() 之前：
                         *   放在白名单之前会把读者端也挡掉；
                         *   放在 anyRequest() 之后则永远不生效（anyRequest 先命中）。
                         *
                         * ★ M2 之后本服务在 /api/admin/** 下只剩一个端点
                         *   （评论管理 /api/admin/comments），但闸还是照前缀写：
                         *   粒度粗一点、少一处可漏的地方，比逐个端点列举更安全。
                         *   （网关那边则【必须】逐条列举 —— 见 novel-gateway 的路由表，
                         *     因为 /api/admin/** 被 content 和本服务【共享】。
                         *     两边粒度不同是有意的：一个管「谁能进」，一个管「去哪」。）
                         *
                         * 这条是「URL 前缀级」的粗粒度为界，只管「能不能进这个门」，
                         * 不管「这扇门里的东西是不是你的」。
                         */
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        /*
                         * 除白名单外一律要求已登录。
                         * 未登录 → 走 authenticationEntryPoint（body code:401）
                         * 已登录但 URL 级权限不够 → 走 accessDeniedHandler（body code:403）
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
                 * 位置很重要：它必须跑在 AuthorizationFilter（做鉴权判断的那个）
                 * 之前，否则鉴权时 SecurityContext 里还是空的，所有请求都会被
                 * 当成未登录。
                 */
                .addFilterBefore(headerAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * ★ 阻止 Spring Boot 把这个过滤器<b>再注册一遍</b>到 Servlet 容器上。
     *
     * <p><b>不加会怎样：</b>任何 {@code Filter} 类型的 Bean，Spring Boot 都会
     * 自动注册到 Servlet 容器的过滤器链上。于是 {@link HeaderAuthenticationFilter}
     * 会同时存在于<b>两个地方</b>：
     * <pre>
     *   ① Servlet 容器链（自动注册的）      ← 我们不想要的那个
     *   ② Security 过滤器链（上面 addFilterBefore 加的）← 我们要的那个
     * </pre>
     *
     * <p>实际上不会出大问题 —— 因为 {@code OncePerRequestFilter} 内部用请求属性
     * 做了「同一请求只执行一次」的保护。但这是<b>靠两个巧合叠在一起才没事</b>。
     *
     * <p>★★ <b>更硬的理由：</b>这个过滤器里带着一道 {@code /internal/**} 的
     * 403 闸（缺共享密钥就拒）。如果它跑在 Security 链<b>之外</b>
     * （也就是容器链上），那道 403 就发生在 Security 判定之前 ——
     * 本服务「哪些路径需要密钥」的语义会变得依赖 Servlet 容器的注册顺序，
     * 而那是没人会去读的地方。显式关掉，让它的入口<b>只有一个</b>。
     *
     * <p>★ 四个服务的 SecurityConfig 里都有这个方法，一个都不能少。
     */
    @Bean
    public FilterRegistrationBean<HeaderAuthenticationFilter> headerFilterRegistration(
            HeaderAuthenticationFilter filter) {
        FilterRegistrationBean<HeaderAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
