package com.novel.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;

/**
 * JWT 的签发与校验。
 *
 * <p>按大纲 §216 的分层，这个类属于 {@code novel-common} ——
 * <b>第二期 M1 拆分时要整个搬给 novel-gateway 用</b>，
 * 所以它只依赖 jjwt 和配置，不碰任何业务代码。
 * 这也是「认证层能被整体搬走」的前提。
 *
 * <p><b>为什么用 JWT 而不是 Session：</b>
 * Session 是「服务端记住谁登录了」，多实例部署时得做 Session 共享，
 * 否则用户在 A 实例登录、下一个请求被负载均衡打到 B 实例就变成未登录。
 * JWT 把身份写在 token 里由客户端携带，服务端只验签，
 * 天然适合微服务横向扩容。
 *
 * <p><b>但 JWT 有个绕不过去的缺点，本项目的应对方式是「再存一份 Redis」：</b>
 * 它一旦签发就<b>无法作废</b> —— 改角色、封号、退出登录都拦不住它，
 * 只能干等过期。所以登录时会把身份同时写进 Redis
 * （{@code novel:auth:token:{userId}:{jti}}），每次请求两边都校验。
 * 详见 {@link TokenStore}。
 *
 * <p><b>★ jjwt 0.12 的 API 和网上绝大多数教程（0.11 及以前）不一样</b>，
 * 照抄老写法会编译不过或运行期报错：
 * <pre>
 *   老：Jwts.builder().setSubject(x).signWith(SignatureAlgorithm.HS256, key)
 *   新：Jwts.builder().subject(x).signWith(key, Jwts.SIG.HS256)
 *
 *   老：Jwts.parser().setSigningKey(key).parseClaimsJws(t).getBody()
 *   新：Jwts.parser().verifyWith(key).build().parseSignedClaims(t).getPayload()
 * </pre>
 *
 * @author MickeyChen
 */
@Slf4j
@Component
public class JwtUtil {

    /** 载荷里存用户名的键。userId 用标准的 {@code sub}，jti 用标准的 {@code jti}，不自定义 */
    public static final String CLAIM_USERNAME = "username";

    /** HMAC-SHA256 要求密钥至少 256 bit = 32 字节 */
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey secretKey;

    /** token 有效期（毫秒） */
    private final long expireMillis;

    /**
     * @param secret     签名密钥，来自配置 {@code novel.jwt.secret}
     * @param expireDays 有效期天数，来自配置 {@code novel.jwt.expire-days}
     */
    public JwtUtil(@Value("${novel.jwt.secret}") String secret,
                   @Value("${novel.jwt.expire-days}") long expireDays) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);

        /*
         * ★ 自己先检查长度，而不是等 Keys.hmacShaKeyFor 抛 WeakKeyException。
         * 因为那个异常的提示是「The signing key's size is 168 bits which is not
         * secure enough」，看到的人第一反应是「哪儿来的 168 bit？」，
         * 很难联想到是自己把密钥配短了。这里直接说清楚。
         */
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(String.format(
                    "JWT 密钥太短：当前 %d 字节，至少需要 %d 字节（HMAC-SHA256 的要求）。"
                            + "请把配置 novel.jwt.secret 或环境变量 JWT_SECRET 改长一些。",
                    keyBytes.length, MIN_SECRET_BYTES));
        }

        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        this.expireMillis = Duration.ofDays(expireDays).toMillis();

        log.info("JwtUtil 初始化完成 | 算法=HS256 有效期={}天 密钥长度={}字节",
                expireDays, keyBytes.length);
    }

    /**
     * 签发 token。
     *
     * <p><b>载荷里只有身份标识，不含角色和权限</b> —— 那些在 Redis 的
     * {@link LoginUser} 里。两个原因：
     * <ol>
     *   <li>角色/权限可能存在很多条，塞进 token 会让每个请求的
     *       {@code Authorization} 头变得很长（几 KB），白白浪费带宽</li>
     *   <li>更重要：token 改不了，角色写进去就<B>过期前一直是旧的</B>。
     *       只放 userId，角色每次从 Redis 现取，改角色立刻生效</li>
     * </ol>
     *
     * @param userId   用户 ID → 标准声明 {@code sub}
     * @param username 用户名 → 自定义声明 {@code username}
     * @param jti      token 唯一编号（{@code UUID}），多端并存的关键，见 {@link LoginUser#getJti()}
     */
    public String generateToken(Long userId, String username, String jti) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expireMillis);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .id(jti)
                .claim(CLAIM_USERNAME, username)
                .issuedAt(now)
                .expiration(expiration)
                /*
                 * ★ 显式指定 HS256，不用 signWith(key) 让它按密钥长度自动挑。
                 * 自动挑的话，密钥从 32 字节改成 64 字节会静默变成 HS512，
                 * 而 JWT 头里的 alg 是 token 自己声明的 —— 这类「算法可被
                 * 输入左右」的细节正是历史上各种 JWT 漏洞的温床。
                 */
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 校验并解析 token。
     *
     * <p>签名不对、格式不对、已过期，都会抛 {@link JwtException} 的子类。
     * <b>调用方必须捕获它</b>，否则伪造的 token 会直接变成 500。
     *
     * @return 载荷
     * @throws ExpiredJwtException 已过期
     * @throws JwtException        签名不匹配 / 结构损坏 / 其它
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** 从载荷里取用户 ID。签发时就写成了字符串，这里转回来 */
    public Long getUserId(Claims claims) {
        return Long.valueOf(claims.getSubject());
    }

    /** 从载荷里取用户名 */
    public String getUsername(Claims claims) {
        return claims.get(CLAIM_USERNAME, String.class);
    }

    /** 从载荷里取 token 唯一编号 */
    public String getJti(Claims claims) {
        return claims.getId();
    }

    /**
     * token 还剩多少毫秒过期。
     *
     * <p>用于给 Redis 里那份身份设 <b>和 JWT 一致的 TTL</b>：
     * 两边同时失效，不会出现「JWT 过期了 Redis 还留着」或者反过来的
     * 中间状态（那会导致用户明明该被踢下线，却还能继续访问）。
     *
     * <p>已过期的返回 0（正常流程走不到这里，解析阶段就会抛异常）。
     */
    public long getRemainingMillis(Claims claims) {
        long remaining = claims.getExpiration().getTime() - System.currentTimeMillis();
        return Math.max(remaining, 0L);
    }

    /** token 的有效期（毫秒），测试和展示用 */
    public long getExpireMillis() {
        return expireMillis;
    }
}
