package com.novel.common.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.common.result.ResultCode;
import com.novel.common.security.IdentityHeaders;
import com.novel.common.security.LoginUser;
import com.novel.common.security.SecurityResponseWriter;
import com.novel.common.security.UserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>M1 起，下游服务重建身份的唯一入口。</b>
 *
 * <p>它是 {@code JwtAuthenticationFilter} 的接班人。区别只有一条，但那条是本质的：
 * <pre>
 *   改造前：本服务【自己验签】+【自己查 Redis】→ 得到 LoginUser
 *   改造后：网关已经验完签、查完 Redis，把身份写进了请求头 → 本服务只负责拼回来
 * </pre>
 * 也就是大纲 §177 说的「<b>网关做认证，服务做鉴权</b>」。本服务里
 * 再也找不到 {@code JwtUtil} / {@code TokenStore} 的影子 —— 这不是巧合，
 * 是 M1 的验收标准之一（停掉网关直连本服务，带合法 token 也会拿 401）。
 *
 * <p><b>★★ 内部信任边界：凭什么相信请求头里的身份？</b>
 *
 * <p>「身份写在请求头里」这件事本身是危险的 —— 请求头是<b>客户端可以随便设</b>的。
 * 所以加了一道共享密钥（{@link IdentityHeaders#INTERNAL_TOKEN}）：
 *
 * <pre>
 *   ┌─ 请求带 X-Internal-Token，且和本服务配置的一致 ─┐
 *   │   → 相信同一请求里的 X-User-* 是真的           │  = 来自网关，或来自兄弟服务的 Feign 调用
 *   └────────────────────────────────────────────────┘
 *   ┌─ 没带 / 带错 ──────────────────────────────────┐
 *   │   → 【完全不看】X-User-*，当匿名处理           │  = 有人直连了 8090/8081
 *   └────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>没有这道闸的话，开发期直连 {@code :8090} 发一个
 * {@code -H "X-User-Id: 1" -H "X-Roles: ADMIN"} 就是管理员了。
 * 生产上还有网络隔离兜底（只有网关对外），但开发期 8090/8081 是开着的
 * —— 那是排错要用的，见 {@code application-dev.yml} 顶上那段。
 *
 * <p><b>★ 为什么「不看」而不是「剥掉」伪造的头：</b>
 * 本服务的业务代码<b>只从 {@link UserContext} 读身份</b>，从不直接读请求头
 * （这是 P1 就定下的规矩，见 {@code UserContext} 的类注释）。
 * 既然不可信时不往 {@code UserContext} 里写东西，那些头就是一堆无人读取的
 * 字符串，剥不剥都一样。少一个「必须记得剥」的动作，就少一个漏剥的机会。
 *
 * <p><b>★ 三条与旧实现【逐字保持一致】的规矩，改这个类时一条都不能丢：</b>
 * <ol>
 *   <li><b>失败时「放行」而不是「直接 401」</b>（{@code /internal/**} 那条除外，
 *       见下）。放行之后请求以匿名身份走到安全链的鉴权环节，由
 *       {@code anyRequest().authenticated()} 拦下，交给
 *       {@code RestAuthenticationEntryPoint} 输出信封。<b>401 的出口只有一个</b>，
 *       格式才不会分叉（这是 {@code SecurityResponseWriter} 的类注释里
 *       那条「一个出口比看起来更直接重要」）。</li>
 *   <li><b>{@code finally} 里清 ThreadLocal。</b>Tomcat 的线程是复用的，
 *       不清的话下一个请求会读到上一个用户的身份 —— 张冠李戴的越权，
 *       而且本地几乎复现不了（并发上来才出现）。</li>
 *   <li><b>{@code buildAuthorities} 的 {@code ROLE_} 前缀只在 Java 层拼</b>，
 *       数据库里存的是裸码。理由见那个方法的注释，照搬未改。</li>
 * </ol>
 *
 * <p><b>★ 唯一一处例外：{@code /internal/**} 缺令牌时直接 403。</b>
 * 那些接口是给兄弟服务调的，<b>根本没有「匿名访问」这种合法情形</b> ——
 * 放行成匿名的话，请求会走到安全链里（那里必须给 {@code /internal/**}
 * 配 {@code permitAll()}，否则合法的服务调用会被自己的
 * {@code anyRequest().authenticated()} 拦下），然后被 Controller 正常处理，
 * 于是一个「没带令牌的内部请求」会拿到 200 和真实数据。
 * <p>所以这一条的语义不是「鉴权」而是「这里压根不该有匿名流量」：
 * 与其让它匿名地走进去，不如在门口就挡掉。<b>不带令牌打 {@code /internal/**}
 * 得到的 403，是一个开发期就能立刻发现的信号</b>（说明有东西绕过了网关
 * 又想拿内部数据），而不是一条静默的放行。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
public class HeaderAuthenticationFilter extends OncePerRequestFilter {

    /** Spring Security 要求角色权限带这个前缀，hasRole('ADMIN') 才能匹配上 */
    private static final String ROLE_PREFIX = "ROLE_";

    /** 内部接口的路径前缀。★ 刻意不在 {@code /api/**} 之下，见 IdentityHeaders 的说明 */
    private static final String INTERNAL_PREFIX = "/internal/";

    /**
     * 内部接口的<b>裸路径</b>（没有结尾斜杠）—— 必须单独判一次。
     *
     * <p><b>★ 漏了它会怎样：</b>只判 {@code startsWith("/internal/")} 的话，
     * 一个正好落到 {@code /internal}（不带斜杠）的请求<b>不匹配前缀</b>，
     * 于是那道「必须带共享密钥」的闸被整个跳过。
     * <p>今天这个路径上<b>没有</b>任何 Controller（所有端点都是
     * {@code /internal/users/...} 这种带子路径的），所以它现在<b>打不出东西</b>
     * —— 也就是说，这个洞今天是【无害】的。
     * <p><b>★★ 但「今天无害」正是它危险的地方：</b>将来谁加一个
     * {@code @RequestMapping("/internal")} 的类（或者某个框架的自动配置
     * 在这个路径上挂一个端点），那个端点就<b>完全绕过了密钥校验</b>，
     * 而且不会有任何报错、任何日志。
     * <p>一行判断换「将来不会因为加了个端点而开一个洞」，很划算。
     * <p>★ 注意 Spring MVC 的路径匹配默认会忽略结尾斜杠
     * （{@code /internal} 和 {@code /internal/} 命中同一个 handler），
     * 所以这两者必须<b>一起</b>受控，不能只覆盖带斜杠的那个。
     */
    private static final String INTERNAL_ROOT = "/internal";

    private final ObjectMapper objectMapper;

    /**
     * 服务间共享密钥。
     *
     * <p><b>★ 故意不给默认值</b>（{@code ${novel.internal.token}} 没有 {@code :} 兜底）：
     * 没配就直接启动失败。给了默认值的话，「忘了配」会退化成
     * 「用一个写在代码里的弱密钥在生产上跑」，而且没有任何症状。
     * 开发环境的默认值写在各自的 {@code application-dev.yml} 里。
     */
    private final String internalToken;

    public HeaderAuthenticationFilter(
            ObjectMapper objectMapper,
            @Value("${novel.internal.token}") String internalToken) {
        this.objectMapper = objectMapper;
        this.internalToken = internalToken;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String provided = request.getHeader(IdentityHeaders.INTERNAL_TOKEN);
            boolean trusted = IdentityHeaders.tokenMatches(provided, internalToken);

            if (isInternalPath(request) && !trusted) {
                /*
                 * ★ 见类注释最后一段。这是全项目唯一一处由「认证过滤器」自己
                 * 输出响应的地方，它输出的不是 401 而是 403 ——
                 * 「这里不该有匿名流量」是权限语义，不是「你还没登录」。
                 */
                log.warn("内部接口缺少有效令牌，拒绝 | {} {} | 令牌={}",
                        request.getMethod(), request.getRequestURI(),
                        provided == null ? "未携带" : "不匹配");
                SecurityResponseWriter.write(objectMapper, response, ResultCode.FORBIDDEN);
                return;
            }

            if (trusted) {
                LoginUser loginUser = IdentityHeaders.read(request::getHeader);
                if (loginUser != null) {
                    authenticate(loginUser, request);
                } else if (hasAnyIdentityHeader(request)) {
                    /*
                     * ★★ 令牌是对的，但身份头不全 —— 这【只可能】是网关和本服务
                     * 的版本对不上（网关升级漏了一个头，或本服务比网关新）。
                     * 见 IdentityHeaders#read 的硬校验说明。
                     *
                     * 用 error 而不是 debug：它一定是个部署问题，而且后果是
                     * 全站 401。运营上「全站 401」很难和「网关挂了」区分开，
                     * 这条日志就是那个区分点。
                     */
                    log.error("身份头不完整（令牌有效但缺 X-User-Id 或 X-Jti）—— "
                                    + "网关与本服务的版本可能不一致 | {} {} | {}",
                            request.getMethod(), request.getRequestURI(),
                            IdentityHeaders.describe(request::getHeader));
                }
                /*
                 * 令牌有效但一个身份头都没有 → 匿名请求（首页、未登录浏览）。
                 * 这是【完全正常】的，不带日志：网关对匿名请求只写令牌不写身份。
                 */
            } else if (hasAnyIdentityHeader(request)) {
                /*
                 * ★ 有人直连本服务并手工构造了身份头 —— 要么是排错的人在试，
                 * 要么是攻击。无论哪种，按匿名处理（不写 UserContext），
                 * 并留下痕迹：这一行如果频繁出现，就是有人在扫内网端口。
                 *
                 * ★ 注意这里【不剥掉】那些头 —— 理由见类注释。
                 */
                log.warn("收到无令牌的身份头，按匿名处理 | {} {} | {}",
                        request.getMethod(), request.getRequestURI(),
                        IdentityHeaders.describe(request::getHeader));
            }

            filterChain.doFilter(request, response);
        } finally {
            // ★ 见类注释「三条规矩」的第 2 条
            UserContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 这个请求是不是落在内部接口的范围里。
     *
     * <p>★ 两种形状都要判：{@code /internal} 与 {@code /internal/...}。
     * 只判后者会漏掉裸路径那一格 —— 详见 {@link #INTERNAL_ROOT} 的注释。
     */
    private boolean isInternalPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        return uri.equals(INTERNAL_ROOT) || uri.startsWith(INTERNAL_PREFIX);
    }

    /**
     * 请求里有没有任何一个身份头（不含共享密钥本身）。
     *
     * <p>只用于<b>决定打不打日志</b>，不参与任何判断 —— 判断只认
     * {@code trusted} 那一个布尔量。这个区分很重要：如果哪天有人拿这个方法
     * 去参与鉴权，就成了「有头就信」。
     */
    private boolean hasAnyIdentityHeader(HttpServletRequest request) {
        for (String name : IdentityHeaders.ALL) {
            if (IdentityHeaders.INTERNAL_TOKEN.equals(name)) {
                continue;
            }
            if (request.getHeader(name) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把身份写进 Spring Security 与 {@link UserContext}。
     *
     * <p>这两件事必须同时做，而且做的是同一件事的两面：
     * <pre>
     *   SecurityContextHolder → 给 SecurityConfig 的 URL 闸（hasRole / authenticated）
     *   UserContext           → 给 Service 层（requireUserId() / 归属校验）
     * </pre>
     * 少写一个的症状完全不同：少了前者是「有权限的人被 403」，
     * 少了后者是「所有写接口 500」。两处都要。
     */
    private void authenticate(LoginUser loginUser, HttpServletRequest request) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(loginUser, null, buildAuthorities(loginUser));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        UserContext.set(loginUser);
    }

    /**
     * 把 {@link LoginUser} 里的角色和权限翻译成 Spring Security 的权限列表。
     *
     * <p><b>★ 这个方法是从 {@code JwtAuthenticationFilter} 逐行搬过来的，
     * 一行都没改 —— 也一行都不该改。</b>它描述的是「数据库里的裸角色码」
     * 和「Spring Security 期望的 {@code ROLE_} 前缀」之间的翻译，
     * 而 M1 换掉的只是「身份从哪来」，没换「身份长什么样」。
     *
     * <p><b>★ {@code ROLE_} 前缀为什么在这里拼，而不是在数据库里存成
     * {@code ROLE_ADMIN}：</b>
     * {@code sys_role.role_code} 里存的是<b>裸的 {@code ADMIN}</b>，
     * 这是和数据库的数据契约（种子数据、后台管理界面、SQL 查询都按它来）。
     * 而 {@code @PreAuthorize("hasRole('ADMIN')")} 内部会自动去比对
     * {@code ROLE_ADMIN} —— 少一个前缀就永远匹配不上，而且不报错，
     * 只表现为「有权限的人被 403 拦住」，非常难查。
     *
     * <p>所以：<b>数据库保持裸码，前缀只在 Java 层拼</b>。
     * 这样两边各自都是干净的，也顺带满足了大纲要求的命名规范。
     *
     * <p>另外注意区分两种写法：
     * <pre>
     *   hasRole('ADMIN')          → 比对 ROLE_ADMIN   （角色，走第 1 个循环）
     *   hasAuthority('content:novel:list') → 原样比对 （权限，走第 2 个循环，不带前缀）
     * </pre>
     */
    private List<GrantedAuthority> buildAuthorities(LoginUser loginUser) {
        List<GrantedAuthority> authorities = new ArrayList<>();

        if (loginUser.getRoles() != null) {
            for (String roleCode : loginUser.getRoles()) {
                authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + roleCode));
            }
        }

        // 权限码原样放进去，不加前缀 —— 见上面 hasAuthority 的说明
        if (loginUser.getPermissions() != null) {
            for (String perm : loginUser.getPermissions()) {
                authorities.add(new SimpleGrantedAuthority(perm));
            }
        }

        return authorities;
    }
}
