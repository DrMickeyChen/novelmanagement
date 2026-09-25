package com.novel.modules.ai.service.impl;

import com.novel.common.constant.RedisKeyConstant;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.ResultCode;
import com.novel.modules.ai.service.AiQuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * AI 每日配额（E1）—— Redis 计数器 + 次日自动过期。
 *
 * <h3>★★★ 键里带日期，这是本类最重要的一行</h3>
 *
 * <pre>
 *   novel:ai:quota:{userId}:{yyyyMMdd}
 * </pre>
 *
 * <p>★ <b>「每天重置」不是靠定时任务，是靠键名换了一天。</b>
 * 这样做的收益不只是省一个定时任务：
 * <ul>
 *   <li><b>即使 TTL 丢了也只是内存泄漏，不是正确性问题</b> ——
 *       第二天键名就变了，用户立刻拿到满额。而如果用的是「一个固定键 + 定时清零」，
 *       定时任务一旦没跑，<b>所有人第二天都还是满额用完的状态</b>，
 *       故障表现是「AI 突然全站不可用」，而根因在另一个进程里。</li>
 *   <li>不需要分布式锁（多实例同时跑定时任务会互相打断）</li>
 * </ul>
 *
 * <h3>★★ 为什么不是「先 GET 判断再 INCR」</h3>
 *
 * <p>那是两步非原子操作，并发下会<b>双双通过</b> —— 作者在编辑页连点两下「续写」
 * 就够触发（不需要恶意）。{@code CommentServiceImpl} 的频控注释论证过同一个形状。
 * ★ 所以 <b>INCR 才是权威判断</b>，{@link #remaining} 只用来给好看的提示。
 *
 * <h3>★★ 为什么「超了要 DECR 还回去」，而不是直接不管</h3>
 *
 * <p>不还的话，<b>被拒绝的请求也吃掉了额度</b>：用户点 10 次全部被拒，
 * 额度白掉 10 次，而界面上<b>看不出来</b>（remaining 掉得比实际快）。
 * 那是本项目一路在猎的「看起来正常的假数据」，只不过这次它出现在一个数字上。
 *
 * <p>★ 代价：INCR 与 DECR 之间进程被杀 → 丢一个单位。
 * <b>失败方向是「少给」，这正是本类想要的方向</b>（见 {@code AiQuotaService} 的类注释）。
 *
 * @author MickeyChen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiQuotaServiceImpl implements AiQuotaService {

    /**
     * 25 小时。
     *
     * <p>★ 它<b>必须大于一天</b>（覆盖到当天 23:59 之后仍有意义的窗口），
     * 又<b>不该长太多</b>（否则一个当天没用过的用户，第二天的额度会被
     * 前一天残留的键挡住 —— 而日期在键名里，那种情况根本不会发生，
     * 所以这个 TTL 只是一道「键最终会被回收」的保险）。
     */
    private static final Duration TTL = Duration.ofHours(25);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public int usedToday(Long userId) {
        String raw = stringRedisTemplate.opsForValue().get(key(userId));
        if (raw == null) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            /*
             * ★★ 键被人手工改过 / 存的不是数字。
             *   返回 0 是【错的】（那是「今天没用过」），但它只影响展示；
             *   真正的判断在 acquire —— 那里走的是 INCR，坏值会直接抛异常而不是被当成 0。
             *   ★ 判据：读路径可以容忍坏值（顶多显示不准），写路径不行（会真的超发）。
             */
            log.error("AI 配额键的值不是数字，展示口径按 0 处理（实际判断走 INCR，不受影响）| key={} | value={}",
                    key(userId), raw);
            return 0;
        }
    }

    @Override
    public int remaining(Long userId, int dailyLimit) {
        return Math.max(0, dailyLimit - usedToday(userId));
    }

    @Override
    public void acquire(Long userId, int dailyLimit) {
        String key = key(userId);

        /*
         * ★ ① 建键 + TTL，一条命令。
         *   setIfAbsent 对应 Redis 的 SET key value NX EX，原子。
         *   ★ 写成「SETNX 再 EXPIRE」的话，两条命令之间进程被杀，
         *     这个键就【永远不会过期】—— 这个用户从此再也用不了 AI，
         *     而且没有任何日志。见 CommentServiceImpl#requireNotTooFast 的同款论证。
         */
        stringRedisTemplate.opsForValue().setIfAbsent(key, "0", TTL);

        /*
         * ★ ② INCR 保留 TTL（它不会重置过期时间），所以 ① 只会在当天第一次执行时生效。
         *   ★ 返回值是【自增之后】的值 —— 所以第一次调用得到 1，而不是 0。
         *     差这一位的话，每日上限会变成 limit + 1，而那是【静默】多花的钱。
         */
        Long used = stringRedisTemplate.opsForValue().increment(key);
        long n = used == null ? 0L : used;

        if (n > dailyLimit) {
            /*
             * ★③ 满了就【还回去】—— 见类注释「为什么超了要 DECR」。
             *   ★ DECR 本身也可能失败（连接断了）。失败就失败：
             *     代价是这一个单位永久占用，方向是「少给」，可以接受。
             *     为了它还去 catch 一堆异常、写一段补偿逻辑，收益不值。
             */
            stringRedisTemplate.opsForValue().decrement(key);
            log.warn("AI 配额已满，本次请求被拒 | userId={} | 今日上限={} | 已用={}", userId, dailyLimit, n);
            throw new BusinessException(ResultCode.AI_QUOTA_EXCEEDED,
                    "今日 AI 额度已用完（" + dailyLimit + " 次），明天会自动恢复。");
        }

        log.debug("AI 配额已占位 | userId={} | {}/{}", userId, n, dailyLimit);
    }

    /**
     * 键名。
     *
     * <p>★ {@code AI_QUOTA} 常量（{@code novel:ai:quota:}）在 P0 就定义了、
     * 一直零引用 —— E1 是它的第一个也是唯一的使用者。
     * ★ <b>它自带结尾的冒号</b>，所以这里不要再加一个（{@code novel:ai:quota::1}
     * 看着也能用，但和常量表里的注释对不上，下次有人按注释去查键会查不到）。
     */
    private static String key(Long userId) {
        return RedisKeyConstant.AI_QUOTA + userId + ":" + LocalDate.now().format(DAY);
    }
}
