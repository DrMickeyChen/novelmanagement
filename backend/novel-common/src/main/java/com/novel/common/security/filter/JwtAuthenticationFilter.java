package com.novel.common.security.filter;

import com.novel.common.constant.CommonConstant;
import com.novel.common.security.JwtUtil;
import com.novel.common.security.LoginUser;
import com.novel.common.security.TokenStore;
import com.novel.common.security.UserContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * <b>认证层</b>的核心：每个请求都过一遍，把 JWT 换成 Spring Security 认识的身份。
 *
 * <p>处理顺序（四步全都通过才算登录）：
 * <pre>
 *   1. 从 Authorization 头里取出 token        → 没有就放行，当匿名处理
 *   2. 验签（JwtUtil）                        → 签名不对/过期就放行，当匿名处理
 *   3. 查 Redis（TokenStore）                 → 查不到就放行，当匿名处理（已登出/被踢）
 *   4. 写入 SecurityContext + UserContext     → 认证成功
 * </pre>
 *
 * <p><b>★ 为什么失败时「放行」而不是「直接返回 401」：</b>
 * 因为放行之后请求会以<b>匿名身份</b>继续走到安全链的鉴权环节，
 * 由 {@code SecurityConfig} 里配的 {@code anyRequest().authenticated()} 拦下，
 * 统一交给 {@code RestAuthenticationEntryPoint} 输出
 * {@code {"code":401,...}} 的信封。
 *
 * <p>如果在这里自己写响应，就会出现<b>两处</b>生成 401 的代码 ——
 * 格式迟早会不一致（少个字段、编码不同），前端就得写两套解析。
 * <b>一个出口</b>比「看起来更直接」重要。
 *
 * <p><b>第二期（M1）怎么变：</b>网关接管认证后，这个类整份搬去
 * {@code novel-gateway}（连带 {@link JwtUtil}、{@link TokenStore}），
 * 区别只是第 4 步改成「把身份写进 {@code X-User-Id} 请求头」。
 * 下游服务的鉴权代码（{@code @PreAuthorize}）完全不动 —— 这就是
 * 大纲 §177 说的「网关做认证，服务做鉴权」。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** ★ 前缀本身定义在 CommonConstant，前端、网关、这里三处必须用同一个值 */
    private static final String BEARER_PREFIX = CommonConstant.TOKEN_PREFIX;

    /** Spring Security 要求角色权限带这个前缀，hasRole('ADMIN') 才能匹配上 */
    private static final String ROLE_PREFIX = "ROLE_";

    private final JwtUtil jwtUtil;
    private final TokenStore tokenStore;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String token = resolveToken(request);
            if (token != null) {
                authenticate(token, request);
            }
            filterChain.doFilter(request, response);
        } finally {
            /*
             * ★★ 这两行不能省，也不能挪到 chain.doFilter 之前。
             *
             * Tomcat 的线程是【复用】的：这个请求处理完，线程会被拿去处理
             * 下一个用户的请求。UserContext 是 ThreadLocal（线程私有），
             * 不清的话下一个请求会读到上一个用户的身份 —— 张冠李戴的越权，
             * 而且在本地开发时几乎不可能复现（并发一上来才出现）。
             *
             * SecurityContextHolder 其实由 Spring Security 自己负责清理，
             * 这里再清一次是防御性的：我们用的是 STATELESS 模式，
             * 万一将来有人改成别的模式，这行也不会让它出错。
             */
            UserContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 从请求头里取 token。
     *
     * <p>格式：{@code Authorization: Bearer eyJhbGci...}，注意 {@code Bearer}
     * 后面有个空格（所以 {@code CommonConstant.TOKEN_PREFIX} 是带空格的
     * {@code "Bearer "}，不是 {@code "Bearer"}）。
     *
     * <p><b>★ 为什么不从 URL 参数里取（{@code ?token=xxx}）：</b>
     * URL 会被记进浏览器历史、Nginx access log、Referer 头、以及各种
     * 中间代理的日志 —— token 一旦进日志就等于泄露（日志的访问权限
     * 通常比数据库松得多）。这是明确拒绝过的方案。
     *
     * @return 去掉前缀的 token，没有或格式不对时返回 {@code null}
     */
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(CommonConstant.TOKEN_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * 验签 + 查 Redis，通过则写入身份。
     *
     * <p>任何一步失败都<b>静默返回</b>（打个日志，不抛异常），
     * 让请求以匿名身份继续走 —— 理由见类注释。
     */
    private void authenticate(String token, HttpServletRequest request) {
        Claims claims;
        try {
            claims = jwtUtil.parseToken(token);
        } catch (ExpiredJwtException e) {
            // 过期是最常见的情况（用户挂着页面过了一周），debug 级别即可，别刷 warn
            log.debug("token 已过期 | {} {}", request.getMethod(), request.getRequestURI());
            return;
        } catch (JwtException | IllegalArgumentException e) {
            /*
             * 签名不匹配 / 结构损坏 —— 可能是客户端存了个残缺的 token，
             * 也可能是有人在伪造。用 warn 留痕：如果日志里频繁出现，
             * 那是一个值得警惕的信号。
             */
            log.warn("token 非法 | {} {} | {}", request.getMethod(), request.getRequestURI(),
                    e.getMessage());
            return;
        }

        Long userId = jwtUtil.getUserId(claims);
        String jti = jwtUtil.getJti(claims);

        LoginUser loginUser = loadLoginUser(userId, jti, request);
        if (loginUser == null) {
            /*
             * 签名是对的，但 Redis 里没有 —— 说明用户已经登出、
             * 管理员把他踢下线了，或者 token 是「上一个 7 天周期」签发的。
             * 这正是「JWT 之外再存一份 Redis」要换来的能力：
             * 让本来作废不了的 token 能立刻失效。
             */
            log.debug("登录态不存在（已登出或已被踢下线）| userId={} jti={}", userId, jti);
            return;
        }

        // ↓↓↓ 走到这里才算认证成功
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(loginUser, null, buildAuthorities(loginUser));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 供 Service 层直接取，见 UserContext 的类注释
        UserContext.set(loginUser);
    }

    /**
     * 从 Redis 取登录态。
     *
     * <p><b>★★ Redis 挂了怎么办 —— fail closed，绝不能 fail open。</b>
     * 查不到身份就当作未登录（拒绝访问），而不是「查不到就放行」。
     * 后者听起来荒谬，但真有人这么写：想的是「Redis 挂了别影响用户使用」，
     * 实际效果是「Redis 一挂，所有已登出的、被踢下线的 token 全部复活」，
     * 攻击者只要把 Redis 打挂就绕过了整套鉴权。
     *
     * <p>所以这里不吞异常，而是<b>打一条明确的 error 日志再把异常抛出去</b>：
     * 让请求失败（500），并且日志里一眼能看出是 Redis 的问题，
     * 而不是看到一堆莫名其妙的 {@code RedisConnectionFailureException} 堆栈。
     */
    private LoginUser loadLoginUser(Long userId, String jti, HttpServletRequest request) {
        try {
            return tokenStore.get(userId, jti);
        } catch (Exception e) {
            log.error("Redis 不可用，认证无法完成 —— 出于安全考虑拒绝本次请求（fail closed）| {} {}",
                    request.getMethod(), request.getRequestURI(), e);
            throw e;
        }
    }

    /**
     * 把 {@link LoginUser} 里的角色和权限翻译成 Spring Security 的权限列表。
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
