package com.novel.content.config;

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
 * <b>novel-content（内容服务 :8082）的 Spring Security 配置。</b>
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
 * <h3>★★ M2 改了什么 —— 这个类是「一个类劈成两个」的产物</h3>
 *
 * <p>M2 之前 content / interaction / ai 三个模块住同一个进程，
 * 共用一份 {@code com.novel.config.SecurityConfig}。现在必须劈开，
 * 因为「谁能匿名访问」这件事两个服务的答案完全不同。
 *
 * <p><b>★ 本服务这一侧的最大改动：{@code GET /api/novels/**} 被【收窄】了。</b>
 *
 * <p>劈分之后，原来那一条通配符在<b>本服务里</b>其实还是「能用」的 ——
 * 评论那几条读接口已经被路由去了 novel-interaction，留在这里不会立刻出洞。
 * 那为什么还要收窄成下面那 7 条具体规则：
 * <b>它是纵深防御</b>。通配符的问题是「加新接口的那一刻静默张开」：
 * 将来本服务在 {@code /api/novels/**} 下新增一个需要登录的接口
 * （比如「我的阅读进度」），它会因为这条通配符【静默匿名可读】——
 * 不报错、不崩溃、测试全过，只是所有人都能调。
 * <p>收窄的代价是一次性的（写死 7 条），收益是「新增接口时默认是安全的」。
 * ★ 这条论证和 {@link #WHITELIST} 里那段「为什么不把 /api/novels/** 放进数组」
 * 是同一个道理的两面，一起读。
 *
 * <p><b>★★ 这个类为什么不搬进 novel-common、也不和别的服务共用一份：</b>
 * 它的全部内容就是「<b>本服务的</b>白名单 + URL 闸」，天然是 per-service 的。
 * 合并成一份 = 取并集 = 把两边的公开面互相对开。
 * 四个服务各有自己的 {@code XxxSecurityConfig}，类名包名都不同。
 *
 * @author MickeyChen
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity   // ★ 打开 @PreAuthorize；不加它，注解会被静默忽略（不报错！）
@RequiredArgsConstructor
public class ContentSecurityConfig {

    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final HeaderAuthenticationFilter headerAuthenticationFilter;

    /**
     * 允许<b>匿名</b>访问的路径（不含按 HTTP 方法限定的那些）。
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

            /*
             * ★ 本服务【不提供】/uploads/** 的静态资源 —— 那是 novel-interaction 的活
             * （网关把 /uploads/** 整体路由给它，见 novel-gateway/application.yml）。
             *
             * ★★ 但本服务【写】上传目录（AuthorUploadController 存封面），
             *    所以 novel.upload.path 必须和 interaction / auth 配成
             *    【同一个绝对路径】—— 否则「上传成功，图片 404」。
             *    这是 M1 R10 记录的同一个问题，M5 接对象存储时解决。
             */

            /*
             * ==================== 读者端【不在这里】，见下面方法限定的那几条 ====================
             * 看小说不需要注册登录，这是产品的硬要求（大纲 §5.3 约束 1 只约束
             * 「未上架的作品查不到」，从没说「要登录才能查」）。
             *
             * ★★ 但这个数组里的路径【不限定 HTTP 方法】：
             * requestMatchers("/api/novels/**") 放行的是这个前缀下的【所有方法】。
             * 于是以后任何人往 /api/novels/** 下加一个 POST 写接口，
             * 都会【静默匿名放行】—— 不报错、不崩溃、测试全过，只是所有人都能调。
             * 这是个真实的口子，而且它是在你【加新接口】的那一刻张开的。
             *
             * 改成方法限定之后，默认值变安全了：
             *   新加一个 GET   → 自动公开（读者端接口本来就该公开）
             *   新加一个写接口 → 自动要求登录（谁都得先想一下）
             *
             * ★★ 千万不要为了省事把 "/api/novels/**" 加回这个数组 ——
             * 那会让下面那几条方法限定的规则【彻底失效】（白名单先命中），
             * 而且没有任何症状，直到有人往这个前缀下加了写接口。
             */
    };

    /**
     * ★★ <b>内部接口。</b>本服务给别的服务调的那些端点。
     *
     * <p>M2 之后有两组，<b>M3 之后只剩一组</b>（全部内容见 {@code InternalContentController}）：
     * <ul>
     *   <li><b>被 novel-interaction 调</b>：拿作品/章节简介、查章节状态、
     *       校验作品已上架、取章节 id 列表 —— 即 {@code ContentClient} 那一套。</li>
     *   <li><s><b>被 novel-auth 调</b>（1 个）：{@code POST /internal/author-apply-audit-logs}</s>
     *       ★★ <b>M3 已删除</b>：auth 审完作者申请改成发一条事务消息
     *       （{@code AuthorApplyAuditProducer}），那个端点连同
     *       {@code InternalAuditLogController} 整个类一起没了 ——
     *       连带删掉的是 {@code novel-auth} 里全部的 Feign 依赖，
     *       所以「auth 按服务名找 content」这件事再也不存在。
     *       ★ 这个端点 M2 之前住在 novel-backend，M2 跟着 {@code AuditLogService}
     *       搬到这里，客户端也从 BackendAuditClient 改名成 ContentAuditClient ——
     *       <b>现在两头都没了。</b></li>
     * </ul>
     * <p>★★ 同一次改造还删掉了两个<b>写</b>端点（{@code PUT .../collect-count}、
     * {@code PUT .../score}），它们改走 {@code NOVEL_STAT} 消息（自发自消）。
     * <b>于是 content 的 {@code /internal/**} 现在全是只读的。</b>
     * 这不是「白名单该收了」，恰恰相反：只读接口更需要 {@code permitAll}，
     * 因为「它凭什么不要身份」这个问题现在连问都不用问 ——
     * 它们本来就是查询。
     *
     * <p><b>它们为什么必须 {@code permitAll()}：</b>
     * IAM 不在这里。调它们的服务手里<b>没有</b>登录用户的 token ——
     * 网关已经把 token 验完并转成了身份头，而下游<b>不验签</b>，
     * 所以这一层没法做认证，只能放行。
     * ★ 改造前这里还多一句「身份靠 Feign 出站拦截器带 {@code X-User-*} 头过来」——
     * 那件事本身仍然成立（interaction 的拦截器还写着这个头），
     * 只是 content 的 {@code /internal/**} 里已经<b>没有一条需要身份的写接口</b>了，
     * 判据的落点搬到了 MQ 消费者那侧，见 {@code InternalContentController} 的类注释。
     *
     * <p><b>那它们凭什么不能被人随便调？把关的是 {@link HeaderAuthenticationFilter}：</b>
     * 请求必须带服务间共享密钥（{@code X-Internal-Token}），
     * 缺了或者对不上<b>直接 403</b>，根本走不到这里。
     *
     * <p><b>★★ 两处必须一起改，改一处就是一个洞：</b>
     * <pre>
     *   删掉这一条 / 忘了加  → 合法的服务调用被自己的 anyRequest() 拦下
     *                          → 书架/历史列表 500
     *                          （M3 之前这里还列着「作者申请审核 500」——
     *                           那条边改走 MQ 了，不再经过这个白名单）
     *   删掉过滤器里的那道闸 → /internal/** 对全世界开放，且【没有任何症状】
     * </pre>
     *
     * <p>★ 另一层结构保证：{@code /internal/**} <b>刻意不在 {@code /api/**} 之下</b>，
     * 而网关的路由表只有 {@code /api/**} 和 {@code /uploads/**} ——
     * 公网在结构上就够不到这个前缀。验证：{@code curl :8080/internal/...} 必须 404。
     */
    public static final String[] INTERNAL_PATHS = {
            "/internal/**",
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

                .authorizeHttpRequests(auth -> auth
                        /*
                         * ==================== 异步派发（P4，★ 本服务【必须】有） ====================
                         *
                         * ★★★ 这一条是给 SSE 流收尾用的，看着莫名其妙，删了会让
                         *     【每一个 AI 流都在结束的那一刻断掉】。别删。
                         *
                         * ★★ M2 之后它【只能在本服务】—— AI 那五个接口
                         *    （/api/ai/outline|write|inspire|polish|quota）
                         *    跟着 modules/ai 搬到了这里，SSE 也就只剩这一处。
                         *    novel-interaction 那边虽然也留着一条（预防性），
                         *    但它目前没有任何 SSE 接口。
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
                         *        OncePerRequestFilter，默认值一样。）
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
                         *   - ASYNC 派发只可能由【同一次请求】在自己 REQUEST 派发
                         *     已经走完完整鉴权之后发起
                         *   所以放行它 = 放行「一个已经通过鉴权的请求的收尾动作」。
                         *
                         * ★★ 必须排在【最前面】：authorizeHttpRequests 是第一个命中的
                         *   规则生效，而 ASYNC 派发的 URI 和原请求【完全一样】
                         *   （还是 /api/ai/outline）。这一条若排在 /api/ai/** 那条之后，
                         *   那条会先命中并再次拒绝，这一条形同虚设 —— 而且症状
                         *   和没加它一模一样，会让人以为「加了没用」。
                         */
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()

                        /* 浏览器的 CORS 预检请求不带 token，必须放行 */
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(WHITELIST).permitAll()

                        /*
                         * ==================== 读者端：匿名只读（★★ M2 收窄） ====================
                         *
                         * ★★ 这几条【按 HTTP 方法】限定，而不是把路径放进 WHITELIST。
                         * 理由见 WHITELIST 上面那段注释：路径级放行不区分方法，
                         * 会让以后新增的写接口静默匿名。
                         *
                         * ★★ 为什么从一条 GET /api/novels/** 拆成 7 条：
                         *    见类注释「本服务这一侧的最大改动」。
                         *    一句话：通配符在「加新接口」的那一刻会静默张开，
                         *    列举则让新增接口的默认值是安全的。
                         *
                         * 路径与接口的对应关系（都在 ReaderNovelController）：
                         *   GET /api/categories                     分类列表
                         *   GET /api/novels                         作品列表
                         *   GET /api/novels/rank                    榜单
                         *   GET /api/novels/{id}                    作品详情
                         *   GET /api/novels/{id}/chapters           章节目录（分页）
                         *   GET /api/novels/{id}/catalog            完整目录
                         *   GET /api/novels/{id}/chapters/{cid}     章节正文（阅读器核心）
                         *
                         * ★★ M4：`GET /api/search` 已从这份白名单里【删掉】——
                         *    搜索整体搬去了 novel-search，这个服务不再有那条路径。
                         *    ★ 白名单和接口必须同时增删：留着一个已经不存在的路径
                         *      不会报错，只是让这份清单**悄悄地说了假话**
                         *      （下一个人照着它去 content 里找搜索接口，会找不到）。
                         *    ★ 路径下面那条 `GET /api/categories` 仍然在，
                         *      分类树没有搬走。
                         *
                         * ★ 为什么每条都要写「裸路径 + 带通配」两遍（比如
                         *   /api/novels 和 /api/novels/** 都要）：
                         *   两种路径匹配器的语义不同 —— Ant 风格里 `/**` 能匹配
                         *   零个路径段（于是 /api/novels 也被覆盖），而 PathPattern
                         *   风格里 `/**` 至少要一个段（/api/novels 就不匹配）。
                         *   写两遍在两种语义下都成立，代价是每处多一行。
                         *   ★ 漏写裸路径的症状是【列表页 401 而详情页正常】——
                         *     因为列表的路径正好是那个没有子路径的裸路径。
                         */
                        .requestMatchers(HttpMethod.GET, "/api/categories").permitAll()

                        .requestMatchers(HttpMethod.GET, "/api/novels").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/novels/rank").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/novels/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/novels/*/chapters").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/novels/*/catalog").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/novels/*/chapters/*").permitAll()

                        /*
                         * ★ 唯一一条匿名 POST：阅读量埋点 POST /api/novels/{id}/view。
                         *
                         * ★★ 路径模式用的是【单星号】，别改成双星号：
                         *   单星号只隔一个路径段，于是它精确锁死在
                         *   「作品 id + view」这个形状上，不会顺带放行
                         *   /api/novels/1/delete 之类的路径。
                         *
                         * 它敢匿名开放，是因为三件事同时成立：
                         *   ① 不写库 —— ★ M3 起是「发一条异步消息就返回」，
                         *      请求路径上一次数据库写都没有（改造前是 Redis INCR
                         *      + 定时回写）。★ 措辞从「不落库」改成了「不写库」：
                         *      走 MQ 之后接口确实产生了持久数据（一条消息），
                         *      「不落库」已经是一句假话。完整论证见
                         *      ReaderNovelController 的类注释。
                         *   ② 不产生数据 —— 没有 INSERT、没有唯一键、不记录调用者；
                         *   ③ 不放宽读的口径 —— 对不存在的书、未上架的书、正常的书，
                         *      响应<b>一个字节都不差</b>（见 ReaderNovelController#recordView
                         *      里那个 catch），所以它不能被当成「这本书存不存在」的探针。
                         * 三条里少一条，它就必须挪到需要登录的前缀下。
                         *
                         * ★ 本行注释里不能出现字面的「星号加斜杠」—— 那会提前闭合
                         *   块注释，编译报一堆莫名其妙的「需要 ')'」。
                         */
                        .requestMatchers(HttpMethod.POST, "/api/novels/*/view").permitAll()

                        /*
                         * ==================== 内部接口 ====================
                         * ★★ 见上面 INTERNAL_PATHS 的注释：这一条【必须】排在
                         *    anyRequest() 之前，且把关的是 HeaderAuthenticationFilter。
                         */
                        .requestMatchers(INTERNAL_PATHS).permitAll()

                        /*
                         * ==================== 角色闸 ====================
                         * ★ 顺序【必须】在白名单之后、anyRequest() 之前：
                         *   放在白名单之前会把读者端也挡掉；
                         *   放在 anyRequest() 之后则永远不生效（anyRequest 先命中）。
                         *
                         * 这三条是「URL 前缀级」的粗粒度为界，只管「能不能进这个门」，
                         * 不管「这扇门里的东西是不是你的」。后者由 Service 的
                         * 归属校验兜（见 NovelServiceImpl#requireOwnedNovel）。
                         *
                         * ★ 故意【不】挂细粒度权限码：sys_menu.perms 只用于
                         *   前端菜单显隐，不参与 API 鉴权。
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
                         * 它的 URL 前缀应该和它的包对应。
                         *
                         * ★ M2 之后这两个前缀都住在本服务（AI 跟着 content 走），
                         *   所以这两条规则在同一个文件里，顺序无所谓 ——
                         *   但都要在 anyRequest() 之前。
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
     * <p>★★ <b>更硬的理由：</b>这个过滤器里带着一道 {@code /internal/**} 的
     * 403 闸（缺共享密钥就拒）。如果它跑在 Security 链<b>之外</b>，
     * 那道 403 就发生在 Security 判定之前 —— 「哪些路径需要密钥」的语义
     * 会变得依赖 Servlet 容器的注册顺序，而那是没人会去读的地方。
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
