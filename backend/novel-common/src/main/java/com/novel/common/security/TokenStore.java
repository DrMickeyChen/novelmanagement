package com.novel.common.security;

import com.novel.common.constant.RedisKeyConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 登录态存储 —— 把 {@link LoginUser} 按 {@code novel:auth:token:{userId}:{jti}} 存进 Redis。
 *
 * <p><b>为什么 JWT 之外还要存这一份：</b>JWT 是自包含的，服务端不存任何东西，
 * 好处是无状态、好扩容；代价是<b>签发之后就作废不了</b>。用户点了「退出登录」、
 * 管理员封了号、角色从读者改成了作者 —— token 里还是老样子，
 * 只能干等 7 天自然过期。
 *
 * <p>所以本项目在 JWT 之外多存一份：每次请求<b>验签通过之后还要查一次 Redis</b>，
 * 查不到就当作未登录。删掉这条记录 = 这个 token 立刻失效。
 * 这是「无状态 JWT」和「可主动失效」之间的折中，也是绝大多数生产系统的做法。
 *
 * <p><b>★ 关于 key 里的 {@code jti}（多端并存）：</b>
 * <pre>
 *   novel:auth:token:1:a3f9...   ← 手机登录
 *   novel:auth:token:1:8c21...   ← 电脑登录
 * </pre>
 * 同一账号每登一次生成一个新的 jti，于是两台设备是两把互不干扰的 key。
 * 手机登出只删自己那把，电脑照常用。如果 key 里没有 jti
 * （写成 {@code novel:auth:token:{userId}}），后一次登录会覆盖前一次 ——
 * 手机一登录，电脑就被挤下线了。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenStore {

    /** SCAN 每次返回的游标大小。200 是个折中值：太小要来回很多趟，太大单次阻塞 Redis 较久 */
    private static final long SCAN_COUNT = 200L;

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 保存登录态。
     *
     * @param ttlMillis 有效期，<b>必须和 JWT 的剩余有效期一致</b>
     *                  （调用方用 {@link JwtUtil#getRemainingMillis} 取）。
     *                  两边不一致会出现一个尴尬的中间状态：Redis 里还在、
     *                  JWT 已过期，或者反过来 —— 前者让「登出」看起来没生效，
     *                  后者让用户莫名其妙被踢。
     */
    public void save(LoginUser loginUser, long ttlMillis) {
        String key = buildKey(loginUser.getUserId(), loginUser.getJti());
        redisTemplate.opsForValue().set(key, loginUser, Duration.ofMillis(ttlMillis));
        log.debug("登录态已写入 | key={} ttl={}ms roles={}", key, ttlMillis, loginUser.getRoles());
    }

    /**
     * 取出登录态。
     *
     * @return 不存在（已登出 / 已过期 / 被管理员踢掉）时返回 {@code null}
     */
    public LoginUser get(Long userId, String jti) {
        Object value = redisTemplate.opsForValue().get(buildKey(userId, jti));
        return value instanceof LoginUser loginUser ? loginUser : null;
    }

    /**
     * 删除一条登录态 —— 也就是「登出当前这台设备」。
     *
     * @return true 表示确实删掉了一条（false 说明它本来就不在，比如重复登出）
     */
    public boolean remove(Long userId, String jti) {
        Boolean deleted = redisTemplate.delete(buildKey(userId, jti));
        return Boolean.TRUE.equals(deleted);
    }

    /**
     * 踢掉某个用户的<b>全部</b>设备 —— 改密码、封号、后台强制下线时用。
     *
     * <p><b>★★ 这里必须用 {@code SCAN} 而不是 {@code KEYS}，两者差别很大：</b>
     * {@code KEYS novel:auth:token:1:*} 会让 Redis <b>单线程阻塞</b>扫描整个
     * keyspace，直到扫完才处理别的请求。线上几百万个 key 时这一下就是几百毫秒
     * 到几秒的卡顿，所有接口一起变慢 —— 而它偏偏又是「管理员点一下」这种
     * 看起来毫无压力的操作，很容易埋雷。<br>
     * {@code SCAN} 是游标式的，每次只返回一小批，把一次长阻塞摊成多次短查询。
     *
     * <p>另一个区别：SCAN 在 rehash 期间可能返回重复元素（所以下面用 Set 去重），
     * 也可能漏掉扫描过程中新增的 key。对「踢人」这个场景完全可接受 ——
     * 漏掉的那个是刚登录的，让它重新登一次即可。
     *
     * <p><b>注意本方法不自己拼 SQL 式的模糊匹配字符串去 delete</b>，
     * 而是先扫出确定的 key 再删，避免误伤（比如 userId=1 的模式
     * {@code *:1:*} 会连 userId=11、21 的一起匹配上，所以下面用的是
     * 带冒号结尾的精确前缀 {@code novel:auth:token:1:}）。
     *
     * @return 删掉的登录态条数
     */
    public int removeAll(Long userId) {
        String pattern = RedisKeyConstant.AUTH_TOKEN + userId + ":*";

        Set<String> keys = new LinkedHashSet<>();
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(SCAN_COUNT).build();
            try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                while (cursor.hasNext()) {
                    keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            }
            return null;
        });

        if (keys.isEmpty()) {
            log.debug("没有需要清理的登录态 | userId={}", userId);
            return 0;
        }

        Long deleted = redisTemplate.delete(keys);
        int count = deleted == null ? 0 : deleted.intValue();
        log.info("已踢下线全部设备 | userId={} 清理条数={}", userId, count);
        return count;
    }

    /**
     * 查某用户当前登录了几台设备（管理端展示用）。
     */
    public Set<String> findJtis(Long userId) {
        String pattern = RedisKeyConstant.AUTH_TOKEN + userId + ":*";
        String prefix = RedisKeyConstant.AUTH_TOKEN + userId + ":";

        Set<String> jtis = new LinkedHashSet<>();
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(SCAN_COUNT).build();
            try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                while (cursor.hasNext()) {
                    String key = new String(cursor.next(), StandardCharsets.UTF_8);
                    jtis.add(key.substring(prefix.length()));
                }
            }
            return null;
        });
        return jtis;
    }

    /**
     * 拼 Redis key：{@code novel:auth:token:{userId}:{jti}}
     *
     * <p>只有这一个地方拼这个 key —— 散落多处的话，
     * 将来加个「租户前缀」之类的改动就得靠全局搜索，很容易漏。
     */
    private String buildKey(Long userId, String jti) {
        return RedisKeyConstant.AUTH_TOKEN + userId + ":" + jti;
    }
}
