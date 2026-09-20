package com.novel.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JwtUtil} 的单元测试。
 *
 * <p><b>为什么这个测试不启动 Spring：</b>安全相关的代码最怕「测了个寂寞」——
 * 起整个 Spring 容器跑一遍，测到的其实是「容器能不能起来」。
 * {@code JwtUtil} 的依赖只有两个配置值，直接 {@code new} 出来最准也最快
 * （毫秒级，且不受数据库/Redis 连不上的影响）。这就是为什么它把配置
 * 放在构造器参数上，而不是用 {@code @Value} 注入字段。
 *
 * <p><b>它覆盖的正是「JWT 到底安不安全」这个问题：</b>
 * 能签、能验、改一个字符就验不过、过期就验不过、换个密钥签的也验不过。
 *
 * @author MickeyChen
 */
class JwtUtilTest {

    /** ≥32 字节，否则构造 JwtUtil 时就会因为密钥太短而失败 */
    private static final String SECRET = "novel-unit-test-secret-at-least-32-bytes-long";

    private static final long EXPIRE_DAYS = 7L;

    private final JwtUtil jwtUtil = new JwtUtil(SECRET, EXPIRE_DAYS);

    @Test
    @DisplayName("签发后能用同一个密钥解析出 userId / username / jti")
    void shouldParseWhatWasSigned() {
        String token = jwtUtil.generateToken(1L, "admin", "jti-abc-123");

        Claims claims = jwtUtil.parseToken(token);

        assertEquals(1L, jwtUtil.getUserId(claims));
        assertEquals("admin", jwtUtil.getUsername(claims));
        assertEquals("jti-abc-123", jwtUtil.getJti(claims));
    }

    @Test
    @DisplayName("★ jti 不同的两次签发，token 必须不同（多端并存的前提）")
    void shouldProduceDifferentTokenForDifferentJti() {
        // 同一个用户、同一秒内、同样的密钥签发两次。
        // 如果 jti 没起作用（比如漏了 .id()），两个 token 会完全一样，
        // 那「手机登出不该影响电脑」就做不到 —— 两把 key 会撞成一把。
        String phone = jwtUtil.generateToken(1L, "admin", "jti-phone");
        String pc = jwtUtil.generateToken(1L, "admin", "jti-pc");

        assertNotEquals(phone, pc);
        assertEquals("jti-phone", jwtUtil.getJti(jwtUtil.parseToken(phone)));
        assertEquals("jti-pc", jwtUtil.getJti(jwtUtil.parseToken(pc)));
    }

    @Test
    @DisplayName("★ 改一个字符就验不过（签名防篡改）")
    void shouldRejectTamperedToken() {
        String token = jwtUtil.generateToken(1L, "admin", "jti-abc");

        // 动最后一个字符 = 动签名。这是攻击者最想干的事：
        // 把 sub 里的 1 改成 2 就能冒充别人 —— 只要签名对得上。
        char last = token.charAt(token.length() - 1);
        String tampered = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');

        assertThrows(JwtException.class, () -> jwtUtil.parseToken(tampered));
    }

    @Test
    @DisplayName("★ 已过期的 token 抛 ExpiredJwtException")
    void shouldRejectExpiredToken() {
        // 用 -1 天构造一个「签发时就已经过期」的工具，
        // 比 Thread.sleep 等 7 天现实得多（也快得多）
        JwtUtil expired = new JwtUtil(SECRET, -1L);
        String token = expired.generateToken(1L, "admin", "jti-abc");

        assertThrows(ExpiredJwtException.class, () -> jwtUtil.parseToken(token));
    }

    @Test
    @DisplayName("★ 换一个密钥签的 token 验不过（别人伪造不了）")
    void shouldRejectTokenSignedByAnotherKey() {
        JwtUtil attacker = new JwtUtil(
                "a-completely-different-secret-also-32-bytes-plus", EXPIRE_DAYS);
        String forged = attacker.generateToken(999L, "admin", "jti-forged");

        assertThrows(JwtException.class, () -> jwtUtil.parseToken(forged));
    }

    @Test
    @DisplayName("密钥短于 32 字节时直接拒绝启动（HMAC-SHA256 的硬性要求）")
    void shouldRejectShortSecret() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new JwtUtil("too-short", EXPIRE_DAYS));

        // 错误信息里要能直接看出「配短了」，否则查起来很痛苦
        assertTrue(e.getMessage().contains("JWT 密钥太短"), "错误提示应说明原因：" + e.getMessage());
    }

    @Test
    @DisplayName("剩余有效期接近配置的天数（Redis 的 TTL 靠它对齐）")
    void remainingMillisShouldMatchConfiguredExpiry() {
        String token = jwtUtil.generateToken(1L, "admin", "jti-abc");

        long remaining = jwtUtil.getRemainingMillis(jwtUtil.parseToken(token));

        // 刚签发的 token 剩余时间应该几乎就是 7 天，
        // 只留一点点误差余量（签发到断言的毫秒级耗时）
        assertTrue(remaining > Duration.ofDays(EXPIRE_DAYS).toMillis() - 60_000,
                "剩余有效期应该接近 " + EXPIRE_DAYS + " 天，实际 " + remaining + " 毫秒");
    }
}
