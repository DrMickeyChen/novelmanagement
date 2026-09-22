package com.novel.config;

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
 * <b>novel-backend 的 Spring Security 配置。</b>
 *
 * <p><b>★ 这个类必须和 pom 里的 spring-boot-starter-security 同时存在。</b>
 * 只加依赖不写放行规则，启动后<b>所有接口</b>（包括 Knife4j 的
 * {@code /doc.html}）都会 401 —— 这是 P0 阶段一直把那个依赖注释掉的原因。
 *
 * <p><b>两条安全规则分别管什么（别混）：</b>
 * <ul>
 *   <li>{@link #WHITELIST 白名单} —— 只管「<b>能不能匿名访问</b>」。
 *       进了白名单不代表谁都能调，方法上照样可以挂 {@code @PreAuthorize}。</li>
 *   <li>{@code @PreAuthorize} —— 管「<b>这个身份能不能干这事</b>」，
 *       是大纲 §177 里说的「服务做鉴权」，第二期拆分时原地不动。</li>
 * </ul>
 *
 * <h3>★★ M1 改了什么</h3>
 *
 * <p>只有三处，<b>一行业务鉴权规则都没动</b>：
 * <ol>
 *   <li><b>换了认证过滤器</b>：{@code JwtAuthenticationFilter}（自己验签 JWT）
 *       → {@link HeaderAuthenticationFilter}（读网关写下的 {@code X-User-*}
 *       头）。这是 M1 的核心 —— <b>本服务从"自己认人"变成"信网关说的"</b>。</li>
 *   <li><b>白名单做了减法</b>：{@code /api/ping/**}、{@code POST /api/auth/login}、
 *       {@code POST /api/auth/register}、{@code GET /api/notices} 四条<b>删掉</b>了 ——
 *       那四个接口已经<b>不在本服务里</b>了（跟着 {@code modules/system}
 *       搬进了 novel-auth）。留着它们不报错，但会让后来的人以为本服务还有登录接口。</li>
 *   <li><b>白名单加了 {@code /internal/**}</b>：M1 新增的服务间调用面。
 *       见下面 {@link #INTERNAL_PATHS} 的注释 —— 那条必须和网关的共享密钥
 *       一起看，只改一处就是一个洞。</li>
 * </ol>
 *
 * <p><b>★★ 这个类为什么不搬进 novel-common、也不和 novel-auth 共用一份：</b>
 * 它的全部内容就是「<b>本服务的</b>白名单 + URL 闸」，天然是 per-service 的 ——
 * 本服务一条 {@code /api/auth/login} 都没有，而 auth 那边一条
 * {@code /api/novels/**} 都没有。合并成一份 = 取并集 = 把两边的公开面互相对开。
 * <p>novel-auth 有它自己的 {@code com.novel.auth.config.AuthSecurityConfig}，
 * <b>类名和包名都不同</b>，不会出现「同 FQN 两个类、谁赢看 classpath 顺序」的鬼故事。
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
    private final HeaderAuthenticationFilter headerAuthenticationFilter;

    /**
     * 允许<b>匿名</b>访问的路径。
     *
     * <p>注意这里是 {@code permitAll()} 不是「无脑放行」——
     * 白名单里的接口如果还需要权限，照样要在方法上写 {@code @PreAuthorize}。
     */
    public static final String[] WHITELIST = {
            /*
             * ★★ M1 起这里【没有】/api/ping/** 和 /api/auth/login|register 了。
             *
             * 它们跟着 modules/system 搬进了 novel-auth（PingController、
             * AuthController 现在住在那边），本服务里已经没有这几个接口。
             * 留着不会报错，但会让人以为本服务还有登录接口 ——
             * 而真正危险的是：将来本服务若新增一个别的 /api/ping/**，
             * 它会因为这些陈旧条目【静默匿名放行】。
             * ★ 每个服务的白名单必须只描述它自己有的接口。
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
     * ★★ <b>内部接口（M1 新增）。</b>本服务给兄弟服务 novel-auth 调的那几个端点。
     *
     * <p>目前只有一个：{@code POST /internal/author-apply-audit-logs}
     * （novel-auth 审完一条作者申请，回头调本服务写审核流水）。
     * 它是铁律 1 原文里明确列出的那个反向接缝，
     * 见 {@code InternalAuditLogController} 的类注释。
     *
     * <p><b>它为什么必须 {@code permitAll()}：</b>
     * 调它的 novel-auth 手里<b>没有</b>登录用户的 token —— 它有的是
     * 「当前请求的登录态」（靠 Feign 的出站拦截器转成 {@code X-User-*} 头带过来）。
     * 而它自己<b>不验签</b>，所以这一层没法做认证，只能放行。
     *
     * <p><b>那它凭什么不能被人随便调？把关的是 {@link HeaderAuthenticationFilter}：</b>
     * 请求必须带服务间共享密钥（{@code X-Internal-Token}），
     * 缺了或者对不上<b>直接 403</b>，根本走不到这里。
     *
     * <p><b>★★ 两处必须一起改，改一处就是一个洞：</b>
     * <pre>
     *   删掉这一条 / 忘了加  → 合法的服务调用被自己的 anyRequest() 拦下 → 审核作者申请直接 500
     *   删掉过滤器里的那道闸 → /internal/** 对全世界开放，且【没有任何症状】
     * </pre>
     * 第二条尤其危险：不报错、不影响功能、测试全过。唯一的发现方式是
     * 「知道这里本来有一道闸」—— 所以这段注释和 auth 那边那一段都不能删。
     *
     * <p>★ 另一层结构保证：{@code /internal/**} <b>刻意不在 {@code /api/**} 之下</b>，
     * 而网关的路由表只有 {@code /api/**} 和 {@code /uploads/**} ——
     * 公网在结构上就够不到这个前缀（见 {@code IdentityHeaders} 的说明）。
     * 验证方式很干脆：{@code curl :8080/internal/...} 必须 404。
     */
    public static final String[] INTERNAL_PATHS = {
            "/internal/**",
    };

    /*
     * ★★ M1 起这里【没有了】passwordEncoder() 这个 bean —— 它是被搬走的，不是被删的。
     *
     * 它原来就在这个位置，而它唯一的使用者（AuthServiceImpl 的登录校验、
     * AdminUserServiceImpl 的管理员重置密码）M1 跟着 modules/system
     * 搬进了 novel-auth，于是 bean 也跟着它唯一的消费者走，
     * 现在住在 com.novel.auth.config.AuthSecurityConfig。
     *
     * 留在这里的话它就是个没人注入的死 bean，而且会让读代码的人
     * 以为「密码哈希是在本服务做的」—— 本服务现在一条密码逻辑都没有。
     * ★ 将来本服务若真需要 BCrypt，从 auth 那边<b>搬回来</b>，
     *   不要在两边各留一份：两份 BCryptPasswordEncoder 一旦 cost 配得不一样，
     *   产生的哈希互相验不过，而症状只是「某些用户登不上」。
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
                         *   ③ HeaderAuthenticationFilter 是 OncePerRequestFilter，
                         *      它的 shouldNotFilterAsyncDispatch() 默认返回 true，
                         *      所以这次派发【不跑认证】→ SecurityContext 是空的
                         *      （★ M1 前这里是 JwtAuthenticationFilter。换了过滤器
                         *        之后这一步的行为【一个字没变】—— 两者都是
                         *        OncePerRequestFilter，默认值一样。别以为换了
                         *        过滤器这条就自动失效了。）
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
                         * ★ 被否决的备选方案：让认证过滤器在 ASYNC
                         *   派发上也跑（shouldNotFilterAsyncDispatch() 返回 false）。
                         *   改造前那会在每次流收尾时再查一次 Redis 校验 token；
                         *   M1 之后则是再读一次请求头 —— 但两者都是同一个已认证的
                         *   请求，多跑一遍换不来任何东西。
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
                         * ==================== 公告（P5 · S15）→ M1 搬走了 ====================
                         * 原来这里有一条 GET /api/notices 的匿名放行。
                         * M1 把公告整个模块（ReaderNoticeController +
                         * AdminNoticeController）搬进了 novel-auth，
                         * 所以本服务一条公告接口都没有了。
                         *
                         * ★ 那条规则的完整论证（为什么必须带 HttpMethod.GET、
                         *   为什么路径不带通配符、为什么敢匿名开放）
                         *   已经<b>整段搬到</b> AuthSecurityConfig 里 ——
                         *   去那边读，不要在这里留一份副本：
                         *   两份会漂移，而漂移的症状是「某个公告接口莫名 401」。
                         *
                         * ★ 注意 /api/admin/notices/** 走的是下面那条
                         *   hasRole("ADMIN") 角色闸 —— 但 M1 之后它其实会被网关
                         *   转发到 novel-auth，由那边的 AuthSecurityConfig 判定。
                         */

                        /*
                         * ==================== 内部接口（M1） ====================
                         * ★★ 见上面 INTERNAL_PATHS 的注释：这一条【必须】排在
                         *    anyRequest() 之前，且把关的是 HeaderAuthenticationFilter。
                         *    顺序错了 → 审核作者申请直接 500（合法的服务调用被自己拦下）。
                         */
                        .requestMatchers(INTERNAL_PATHS).permitAll()

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
                 * 是表单登录用的，我们完全禁用了它；把身份过滤器放在它前面，
                 * 是 Spring Security 里最不容易出错的位置 —— 它在所有
                 * 「需要已认证身份」的过滤器之前，又在安全链的入口之后。
                 */
                .addFilterBefore(headerAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * ★ 阻止 Spring Boot 把这个过滤器<b>再注册一遍</b>到 Servlet 容器上。
     *
     * <p><b>不加会怎样：</b>任何 {@code Filter} 类型的 Bean，Spring Boot 都会
     * 自动注册到 Servlet 容器的过滤器链上（这是「约定优于配置」的一部分）。
     * 于是 {@link HeaderAuthenticationFilter} 会同时存在于<b>两个地方</b>：
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
     * <p>★★ <b>M1 之后关掉自动注册多了一个更硬的理由：</b>
     * 这个过滤器里带着一道 {@code /internal/**} 的 403 闸（缺共享密钥就拒）。
     * 如果它跑在 Security 链<b>之外</b>（也就是容器链上），那道 403 就发生在
     * Security 判定之前 —— 本服务「哪些路径需要密钥」的语义会变得
     * 依赖 Servlet 容器的注册顺序，而那是没人会去读的地方。
     * 显式关掉，让它的入口<b>只有一个</b>。
     */
    @Bean
    public FilterRegistrationBean<HeaderAuthenticationFilter> headerFilterRegistration(
            HeaderAuthenticationFilter filter) {
        FilterRegistrationBean<HeaderAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
