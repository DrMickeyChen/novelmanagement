package com.novel.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.common.constant.CommonConstant;
import com.novel.common.result.Result;
import com.novel.common.result.ResultCode;
import com.novel.common.result.ResultHttpStatus;
import com.novel.common.security.IdentityHeaders;
import com.novel.common.security.JwtUtil;
import com.novel.common.security.LoginUser;
import com.novel.common.security.TokenStore;
import com.novel.common.trace.TraceContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>网关接管 JWT 校验的地方（M1 新增）—— 这是整个 M1 的核心类。</b>
 *
 * <p>它做的事只有一件：<b>把 {@code Authorization} 头里的 JWT 换成一组成员身份请求头</b>。
 * 验签 → 查 Redis → 写 {@code X-User-*} → 转发。下游服务不再自己验签，
 * 只读头（见 {@code HeaderAuthenticationFilter}）。
 *
 * <h3>★★★ 设计上最重要的一条：本过滤器【只加不拦】</h3>
 *
 * <p>它<b>不返回 401</b>，一次都不返回。这是刻意的，而且这条最容易被后来的人
 * 「顺手优化」掉，所以理由要写全：
 *
 * <table border="1">
 *   <caption>网关不做的事，以及为什么</caption>
 *   <tr><th>不做</th><th>为什么</th></tr>
 *   <tr>
 *     <td><b>不返回 401</b></td>
 *     <td>401 的唯一出口仍然是下游的 {@code RestAuthenticationEntryPoint}。
 *         token 无效 / 过期 / 已被踢下线 → 网关<b>原样放行为匿名</b>
 *         （和改造前的 {@code JwtAuthenticationFilter} 完全一样）→
 *         请求继续走到下游 → 由下游的 {@code .anyRequest().authenticated()}
 *         拦下并输出统一信封。<b>这样对外的行为逐字节不变。</b>
 *         <p>★ 反过来在网关返回 401 的坏处不只是「多一处出口」——
 *         下游那个 entry point 输出的 body 是 {@code Result} 信封
 *         （{@code code:401} + message），前端 {@code request.js} 认的是它。
 *         网关自己拼一份，格式迟早会跟下游漂移，而症状是
 *         「有的 401 会跳登录页、有的不会」。</td>
 *   </tr>
 *   <tr>
 *     <td><b>不做匿名路径白名单</b></td>
 *     <td>若网关维护一份「哪些路径免鉴权」，它就<b>复制了
 *         {@code SecurityConfig} 的白名单</b>。将来给后端加一个公开接口、
 *         忘了同步网关 → 那个接口 401。这是纯自找的漂移地雷。
 *         <p>★ 本过滤器天然不需要白名单：它对<b>任何</b>请求都只是
 *         「有有效 token 就写头，没有就不写」，然后把请求原样放行。
 *         公开接口不需要 token，所以它什么都不写、请求照样通过。</td>
 *   </tr>
 *   <tr>
 *     <td><b>不解析也不改写响应</b></td>
 *     <td>免得给 SSE（AI 写作助手的流式输出）引入缓冲 ——
 *         一旦响应体被包一层，逐块到达就会变成攒完再发。</td>
 *   </tr>
 * </table>
 *
 * <h3>★★ 唯一的例外：Redis 挂了必须是 500，绝不能是 401</h3>
 *
 * <p>前端 {@code request.js} 把 HTTP 401 解释为「登录态没了」，
 * 于是 {@code clearAuth()} + 跳登录页。所以「网关连不上 Redis」如果返 401，
 * 连锁反应是：
 * <pre>
 *   Redis 抖一下  →  全站所有请求 401  →  所有在线用户被清登录态、踢回登录页
 * </pre>
 * 而真正的原因（Redis 不可用）在用户侧完全看不出来。
 *
 * <p>改造前的实现在 {@code loadLoginUser} 里是 log.error 之后 <b>rethrow</b>
 * （→ 500）。<b>M1 保持这个行为</b>：Redis 异常一律变成 500，
 * 让监控能看见、让用户刷新一下就好，而不是被登出。
 *
 * <h3>★★ 先剥后写</h3>
 *
 * <p>转发前把客户端可能自带的 {@code X-User-Id} / {@code X-Roles} / … /
 * {@code X-Internal-Token} <b>全部删掉</b>，再写自己的。少剥一个，
 * 任何人都能靠 {@code curl -H "X-User-Id: 1"} 变成管理员。
 * 剥与写都用 {@link IdentityHeaders} 里的同一份清单（{@link IdentityHeaders#ALL}），
 * 所以「加了新头忘了剥」这件事在结构上不会发生。
 *
 * <h3>★ 为什么是 {@code GlobalFilter} 而不是 {@code GatewayFilter}</h3>
 *
 * <p>{@code GatewayFilter} 要挂在每一条 route 上（{@code filters:} 列表里），
 * 意味着 11 条路由要写 11 遍 —— <b>而且新加一条路由时忘了挂就静默失效</b>：
 * 那条路由的身份头不会被剥也不会被写，症状取决于下游怎么处理，
 * 大概率是「某几个接口匿名可用」这种查不出来的洞。
 * {@code GlobalFilter} 对所有路由自动生效，没有「忘了挂」这个状态。
 *
 * <h3>★ order 为什么是 -100</h3>
 *
 * <p>转发的实际执行者是 {@code NettyRoutingFilter}（order =
 * {@code Ordered.LOWEST_PRECEDENCE}），所以任何负数都能排在它前面。
 * 取 -100 是沿用 Spring Security 过滤器链的惯例值，好记。
 * <b>必须排在路由之前</b>：身份头要在请求被发出去【之前】写好，
 * 否则 Netty 已经把原样的头发出去了。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {

    /** ★ 见类注释「order 为什么是 -100」 */
    private static final int ORDER = -100;

    private final JwtUtil jwtUtil;
    private final TokenStore tokenStore;
    private final ObjectMapper objectMapper;

    /**
     * 服务间共享密钥。
     *
     * <p>★ 不给默认值 —— 没配就启动失败。理由和另外两个服务里那个字段一样：
     * 给了默认值，「忘了配」会退化成「用一个写在 git 里、全世界都知道的字符串
     * 当密钥跑在生产上」，而且没有任何症状。
     * <p>★ 本服务写这个头，是决策 4 里「两处都加共享密钥头」中的
     * 「网关 → 下游」那一处；另一处是各服务的 Feign 出站拦截器
     * （服务与服务之间调 {@code /internal/**}）。
     */
    @Value("${novel.internal.token}")
    private String internalToken;

    /**
     * <b>只负责 traceId 的生成与清理（M5），鉴权与转发全在 {@link #handle} 里。</b>
     *
     * <p>之所以拆成两个方法，是为了让 {@code try/finally} 不用把一百多行业务包进去
     * —— 拆开之后那个业务方法的缩进<b>一行都不用动</b>，
     * 而「生成 → 放 → 清」这三步能一眼看完。
     *
     * <h3>★★ 永远生成新的，不看客户端带没带</h3>
     *
     * <p>网关面对的是<b>浏览器</b>，{@code X-Trace-Id} 是客户端可以随便设的头。
     * 采纳它 = 任何人加一个 {@code -H "X-Trace-Id: xxx"} 就能把自己的请求
     * <b>关联到别人的日志上</b>。
     * <p>★ 这和 {@code IdentityHeaders.strip} 剥掉 {@code X-User-*} 是同一个道理，
     * 只是后果不同：那个是<b>越权</b>，这个是<b>日志串号</b>。
     * 严重性差很多，但成本都是零，所以一样处理。
     *
     * <h3>★ 而下游【必须】采纳 —— 两条规则不一样是刻意的</h3>
     *
     * <p>四个服务里的 {@code TraceIdFilter} 面对的是网关或兄弟服务的 Feign 调用，
     * 它<b>不采纳</b>的话链路就在每个服务边界断掉，等于没做追踪。
     * 完整的对照表在 {@code TraceContext} 的类注释里。
     *
     * <h3>★ MDC 只覆盖「这个 filter 自己的同步段」</h3>
     *
     * <p>WebFlux 里一个请求会跨线程，MDC 是 ThreadLocal，所以
     * <b>下游路由（{@code NettyRoutingFilter}）那一段的日志没有 traceId</b>
     * —— 那是 Reactor 上下文传播要解决的问题，M5 不引入（记为债）。
     * <p>而<b>写进请求头的那个值走参数、不走 MDC</b>：它是下游四个服务唯一的
     * 关联依据，不能「大部分时候对」。见 {@link #handle} 的 {@code traceId} 参数。
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = TraceContext.newTraceId();
        TraceContext.put(traceId);
        try {
            return handle(exchange, chain, traceId);
        } finally {
            /*
             * ★ put 和 clear 在【同一个线程】上成对出现 —— 这一点能成立，
             *   是因为 filter 返回的 Mono 是【懒】的：真正的转发要等外层订阅
             *   才会执行，那时已经出了这个 try/finally。
             *   ★ 也就是说：Netty 相关的日志里 MDC 是空的（上面已经写明），
             *     但【绝不会】把 traceId 泄漏到同一条 Netty 线程上的下一个请求 ——
             *     那个泄漏才是真正会污染日志的（Tomcat 线程复用是同一个问题，
             *     见 TraceContext#clear）。
             */
            TraceContext.clear();
        }
    }

    /**
     * 鉴权 + 写头 + 转发的本体。
     *
     * @param traceId ★ <b>由 {@link #filter} 生成的、本次请求唯一的关联 id。</b>
     *                它<b>不走 MDC</b>：MDC 是给日志用的 best-effort，
     *                而写进 {@code X-Trace-Id} 头的值必须是确定的。
     */
    private Mono<Void> handle(ServerWebExchange exchange, GatewayFilterChain chain, String traceId) {
        ServerHttpRequest request = exchange.getRequest();
        HttpHeaders headers = request.getHeaders();

        String token = resolveToken(headers);

        /*
         * ============================================================
         * 情况一：没带 token（或格式不是 Bearer）
         * ============================================================
         * ★ 这是【完全正常】的一条路，不是错误：读者端首页、作品列表、
         *   搜索、章节正文全都不需要登录。
         * ★ 所以这里连日志都不打（打 debug）—— 匿名访问是这个系统
         *   最主要的流量，用 warn/info 会把日志淹掉，真正的问题反而看不见。
         */
        if (token == null) {
            log.debug("匿名请求（无 token）| {} {}", request.getMethod(), request.getURI().getPath());
            return forward(exchange, null, traceId, chain);
        }

        /*
         * ============================================================
         * 情况二：有 token —— 验签
         * ============================================================
         * ★ 验签失败【不返回 401】，只当作匿名放行（见类注释第一条）。
         *   真正的 401 由下游出。
         */
        Claims claims;
        try {
            claims = jwtUtil.parseToken(token);
        } catch (JwtException | IllegalArgumentException e) {
            /*
             * ★ 这里打 debug 不打 warn：过期 token 是【每天都会发生】的正常现象
             *   （用户开着页面过了一夜），伪造 token 才是异常。
             *   两者在这个 catch 里长得一模一样，无法区分，所以按常见的那种定级。
             *   ★ 真要看伪造请求，应该看的是「同一 IP 大量 401」这类聚合指标，
             *     而不是在这一行上加日志级别。
             */
            log.debug("token 无效，按匿名放行 | {} {} | {}",
                    request.getMethod(), request.getURI().getPath(), e.getClass().getSimpleName());
            return forward(exchange, null, traceId, chain);
        }

        Long userId = jwtUtil.getUserId(claims);
        String jti = jwtUtil.getJti(claims);

        if (userId == null || jti == null) {
            /*
             * ★ claims 齐全的话这两个不可能为 null（subject 和 id 都是签发时写死的），
             *   走到这里说明 token 的结构不对 —— 同样是按匿名处理。
             */
            log.debug("token 缺少 sub/jti，按匿名放行 | {}", request.getURI().getPath());
            return forward(exchange, null, traceId, chain);
        }

        /*
         * ============================================================
         * 情况三：验过签了，还要问 Redis「这把 token 还有效吗」
         * ============================================================
         * ★★ 这一查是【不可省的】，JWT 自己证明不了这三件事：
         *      用户点了退出登录   → Redis 里那把 key 被删了
         *      管理员封了号/改了角色 → AdminUserServiceImpl 把该用户的 key 全删了
         *      改密码            → 同上
         *   没有这一查，上面三件事全都要等 7 天 token 自然过期才生效。
         *
         * ★★ Redis 异常【必须】返 500，绝不能 catch 成「按匿名放行」——
         *   那等于把「Redis 抖了一下」变成「全站用户被登出」。
         *   见类注释里那段连锁反应。
         *
         * 用的是 sync 调用（这块是 WebFlux，理论上是阻塞的）：
         *   ★ 取舍写在这里，因为它是一个真实的反模式 ——
         *     Redis 的本机往返是亚毫秒级，而网关的线程是 Netty 的 event loop。
         *     严格来说应该用 ReactiveRedisTemplate 包一层。
         *     M1 不做，是因为 TokenStore 必须是【和 novel-auth 完全相同的一份】
         *     实现（那份是 servlet 世界的，用 RedisTemplate）——
         *     为了网关改成响应式而复制出第二份 TokenStore，
         *     正好撞上本方案最忌讳的那件事（R1：序列化/实现漂移 → 全站静默登出）。
         *   ★ 记为 M5 治理项：上 ReactiveRedisTemplate，或者给 TokenStore
         *     加一个响应式重载。
         */
        LoginUser loginUser;
        try {
            loginUser = tokenStore.get(userId, jti);
        } catch (Exception e) {
            log.error("查询 Redis 登录态失败 —— 返回 500（【不是】401，"
                    + "401 会让前端清登录态、把全站用户踢下线）| userId={} jti={}", userId, jti, e);
            return writeServerError(exchange);
        }

        if (loginUser == null) {
            /*
             * ★ 验签通过但 Redis 里没有 —— 已登出 / 已被踢下线 / 已过期。
             *   按匿名放行，由下游出 401（见类注释第一条）。
             * ★ 这一条【要打 info】，因为它的成因值得看：
             *   正常登出之后前端不会再拿着旧 token 请求，
             *   还在请求说明要么是「点了退出但其实没成功」（见 IdentityHeaders#JTI
             *   那段——不过那是【写】的一端出问题，这里会表现为退出后仍能访问），
             *   要么是「被管理员踢下线」——后者是安全事件，值得留痕。
             */
            log.info("token 有效但登录态已不存在（已登出/被踢下线）| userId={} jti={} path={}",
                    userId, jti, request.getURI().getPath());
            return forward(exchange, null, traceId, chain);
        }

        /*
         * ============================================================
         * 情况四：一切正常 —— 写身份头，转发
         * ============================================================
         */
        log.debug("身份已解析 | userId={} username={} roles={} path={}",
                loginUser.getUserId(), loginUser.getUsername(), loginUser.getRoles(),
                request.getURI().getPath());
        return forward(exchange, loginUser, traceId, chain);
    }

    /**
     * 剥掉客户端自带的身份头，按需要写上网关解析出来的身份，然后放行。
     *
     * <p>★ {@code chain} 是从 {@link #filter} 一路传进来的，<b>不能存字段</b> ——
     * 这个类是 Spring 单例，而每条请求各有自己的 chain；
     * 存字段就是并发串号（A 的请求走了 B 的链）。
     * WebFlux 里也没有 {@code ThreadLocal} 可躲：一个请求会在多个线程上被处理
     * （event loop 线程 + 调度线程），ThreadLocal 会在切换线程时丢掉。
     * <b>只能用参数传。</b>
     *
     * @param loginUser {@code null} 表示匿名 —— 此时<b>只写共享密钥</b>，
     *                  身份头一个都不写
     * @param traceId   ★ M5 新增。见下面「③」那一段：它<b>永远非空</b>，
     *                  这一点决定了它<b>不需要</b>像身份头那样先剥一下
     */
    private Mono<Void> forward(ServerWebExchange exchange, LoginUser loginUser,
                               String traceId, GatewayFilterChain chain) {
        /*
         * ★★ 先攒一个普通的 Map<String,String>，再整个落到请求头上。
         *
         * 为什么不直接把 HttpHeaders 传给 IdentityHeaders：
         *   {@code HttpHeaders} 是 {@code Map<String, List<String>>}
         *   （一个头名对应【多个】值），而 {@link IdentityHeaders#write}
         *   收的是 {@code Map<String, String>}（一个头名一个值）。
         *   两者不是同一个类型，直接传编译就过不去。
         *
         *   ★ 这个签名是为 Feign 那侧定的（{@code RequestTemplate.header}
         *     收的确实是单值）。网关这侧就多一次搬运 —— 但**换来的是一份
         *     共用的编解码实现**，那正是这个类存在的全部意义。
         *     让网关自己写一遍「哪个头叫什么、怎么编码」，就等于把这个类
         *     拆成了两份会各自漂移的副本（漂移的症状见 IdentityHeaders 的类注释）。
         */
        Map<String, String> identity = new LinkedHashMap<>();

        /*
         * ① 共享密钥 —— 【无论匿名与否都要写】。
         *    匿名请求（首页）走的也是这条路，而它同样要经过下游的
         *    HeaderAuthenticationFilter。没有这个头，下游会把整个
         *    请求当成「有人直连内网端口伪造身份」，连匿名的读接口都拿不到。
         */
        IdentityHeaders.writeInternalToken(identity, internalToken);

        /* ② 有身份才写身份（loginUser 为 null 时 write 直接返回，什么都不加） */
        IdentityHeaders.write(identity, loginUser);

        /*
         * ③ M5：本次请求的关联 id。
         *
         * ★★ 注意这里【没有】一句 headers.remove(X-Trace-Id) 去剥客户端的同名头 ——
         *   而身份头那边有一句 IdentityHeaders.strip。这不是漏了，两边的条件不同：
         *
         *     身份头：匿名时 loginUser 为 null → write 什么都不写
         *             → 客户端自带的 X-User-Id 会【原样活到下游】
         *             → 所以【必须剥】，否则「任何人加一个 X-User-Id 就是管理员」
         *
         *     traceId：每一个请求都会走到这一行，值【永远非空】
         *             → 下面 identity.forEach(headers::set) 里的 set
         *               本身就会把客户端那个值顶掉（set 是替换整个值列表）
         *             → 再写一句 remove 是【永远不起作用】的一行
         *
         *   ★ 所以这里不写。写了一句不会失败的防护，比不写更糟 ——
         *     它会让后来的人以为「不剥就会漏」（铁律 8 的同族形状）。
         *   ★ 但这条推断是【可证伪】的，所以 S1 的验收里有一条阳性对照：
         *     带 -H "X-Trace-Id: DEADBEEF..." 打网关，
         *     下游日志里必须【没有】DEADBEEF。
         */
        identity.put(TraceContext.HEADER, traceId);

        ServerHttpRequest mutated = exchange.getRequest().mutate().headers(headers -> {
            /*
             * ★★ 第一步永远是【剥】。顺序不能和上面的「攒」以及下面的「写」调换 ——
             *   反过来的话客户端的头会覆盖网关写的（或两个值并存，
             *   下游取到哪个取决于实现），那就是
             *   「任何人加一个 -H "X-User-Id: 1" 都是管理员」。
             *   ★ 剥的是【身份头】那一组（IdentityHeaders.ALL）；traceId 不参与
             *     这次剥的原因见上面「③」那段。
             */
            IdentityHeaders.strip(headers::remove);
            identity.forEach(headers::set);
        }).build();

        /*
         * ★ 用 exchange.mutate().request(...) 造一个新的 exchange 往下传。
         *   WebFlux 的请求头是不可变的，改不了原来那个对象。
         *   ★ 注意 chain.filter 收的必须是【这个新的】exchange ——
         *     传原来那个等于什么都没做，而且不报错、不影响任何功能，
         *     只是身份头全部丢失（症状：全站 401）。
         */
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    // ============================================================
    // 工具
    // ============================================================

    /**
     * 从 {@code Authorization} 头里取出裸 token。
     *
     * @return 没有这个头、或者不是 {@code Bearer } 开头 → {@code null}
     */
    private String resolveToken(HttpHeaders headers) {
        String raw = headers.getFirst(CommonConstant.TOKEN_HEADER);
        if (raw == null || !raw.startsWith(CommonConstant.TOKEN_PREFIX)) {
            return null;
        }
        String token = raw.substring(CommonConstant.TOKEN_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * 输出一个 500 —— <b>只在 Redis 不可用时走这里</b>。
     *
     * <p>★ body 用的是和下游同一个 {@link Result} 信封。这一处是「网关自己
     * 输出响应」的唯一例外，所以它必须和下游长得一样，否则前端要写两套解析。
     * <p>★ 为什么是 {@link ResultCode#SYSTEM_ERROR}（HTTP 500）而不是一个
     * 9xxx 的业务码：{@code ResultHttpStatus} 会把 400~599 之外的码
     * 一律映射成 <b>HTTP 200</b>，那样监控上看起来一片祥和，
     * 而实际上全站都不可用了。
     */
    private Mono<Void> writeServerError(ServerWebExchange exchange) {
        ResultCode resultCode = ResultCode.SYSTEM_ERROR;
        ServerHttpResponse response = exchange.getResponse();

        /*
         * ★ ResultHttpStatus.of 返回的是 Spring 自己的 HttpStatusCode，
         *   不是 int —— 所以这里没有 .value() 可调（它是给 servlet 那侧
         *   SecurityResponseWriter 用的，那边收 int）。
         */
        response.setStatusCode(ResultHttpStatus.of(resultCode.getCode()));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(Result.error(resultCode));
        } catch (JsonProcessingException e) {
            /*
             * ★ 序列化一个只有 code/message 的对象不该失败。
             *   真失败了也不能让异常冒出去（那会变成 Netty 的 500 空 body），
             *   手拼一个最小 JSON 顶上。
             */
            log.error("序列化错误响应失败，改用手拼 JSON", e);
            body = ("{\"code\":" + resultCode.getCode() + ",\"message\":\""
                    + resultCode.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
        }

        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }
}
