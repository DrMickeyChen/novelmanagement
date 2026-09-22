package com.novel.content.mq.consumer;

import com.novel.common.constant.RedisKeyConstant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 「这条统计消息是不是旧的」—— {@code NOVEL_STAT} 的单调写守卫（M3）。
 *
 * <p><b>一句话：它让消费端永远不把 {@code collect_count} / {@code score} 写回旧值。</b>
 * 判断在这段 Lua 里：<b>{@code incoming.at >= 已应用.at} 才放行</b>，
 * 否则这条消息被丢弃（返回 0，调用方直接 ACK）。
 *
 * <h3>★★ 为什么用 Redis 而不是一个进程内的 {@code Map}，也不用一把锁</h3>
 *
 * <p>进程内 {@code Map} 看着更省事（不用多一个中间件依赖、不用一次网络往返），
 * 但它在<b>多实例</b>下几乎等于没写：Broker 是<b>按队列</b>把消息轮询投给消费者的，
 * 同一本书的连续两条消息大概率落在<b>不同实例</b>上 ——
 * 每个实例都觉得自己是第一个，各自写一次，谁后写谁算数。
 * <b>于是「单调」这件事只在单实例时成立，而单实例的部署根本不需要它。</b>
 *
 * <p>★ 也不用 {@code synchronized}/{@code ReentrantLock}：那只是把同一个问题
 * 从「跨实例」缩到「跨线程」，而这里的一次判断的代价是<b>一次 Redis 往返</b> ——
 * 加锁之后该往返还在，只是多了一层自欺。
 *
 * <h3>★★ 比较与写入必须在【同一条命令】里完成</h3>
 *
 * <p>下面这段脚本<b>不是优化，是正确性要求</b>。写成
 * {@code GET → 应用层比大小 → HSET} 三步的话，两个线程（或两个实例）
 * 可能<b>都判断通过</b>，然后较旧的那个后写 —— 那就白防了，
 * 而且这种竞态在开发环境几乎撞不上，只在压测和线上偶发。
 *
 * <p>脚本在 Redis 上是<b>单线程原子执行</b>的，所以「读已应用的时间戳」
 * 和「写入新的时间戳」之间不可能插进另一个客户端。
 * 说白了就是用 Redis 的单线程换掉了一把分布式锁 —— 而它比锁便宜得多。
 *
 * <h3>★★ 它没有解决的那件事，必须写在这里（否则下一个读代码的人会以为它解决了）</h3>
 *
 * <p>放行之后，调用方才去写数据库。所以严格来说还是有一个窗口：
 * <pre>
 *   线程 A（at=100）通过 CAS → …… 卡住 ……        → 写库（100 的值）
 *   线程 B（at=200）通过 CAS（200 ≥ 100）      → 写库（200 的值）
 * </pre>
 * B 先写完、A 后写完，<b>库里最终是 100 的值，而 Redis 记的是 200</b>。
 *
 * <p><b>这个窗口没有被消除，是有意选择的结果</b>，理由是它<b>不会累积</b>：
 * <ul>
 *   <li>它的宽度只有「CAS 返回之后到 UPDATE 执行完」这一小段，
 *       不像「完全没有防乱序」那样<b>每一条乱序消息都留下一个错值</b>；</li>
 *   <li>真正兜底的是 {@code NovelStatReconcileJob}（每 10 分钟）——
 *       它是<b>拿绝对值比对</b>的，所以任何静态的错值都会在一个周期内被纠回来。</li>
 * </ul>
 * ★ 要彻底消除它，得让「比较」和「写入」发生在同一个事务里 ——
 * 也就是把 {@code at} 落进 {@code novel} 表的一列（{@code UPDATE ... WHERE at &lt; ?}）。
 * <b>那是 DDL，M3 不做</b>（本期回滚要能是一个干净的 {@code git revert}），
 * 记为 M5 的候选。★ 这条也解释了「为什么用 Redis 存 at 是过渡方案」——
 * 它把错误窗口从「每条乱序」压到「偶发的极端时序」，但没压到零。
 *
 * @author MickeyChen
 */
@Component
@RequiredArgsConstructor
public class NovelStatWriteGuard {

    /**
     * 「要不要应用这条消息」的 Lua 脚本。
     *
     * <pre>
     *   KEYS[1] = novel:novel:stat_at:{novelId}   （一个 Hash）
     *   ARGV[1] = 列名，即 NovelStatField 的枚举名（COLLECT_COUNT / SCORE）
     *   ARGV[2] = 这条消息的生产时刻（epoch 毫秒）
     *
     *   返回 1 = 应用（并且已经把 at 记下了）
     *   返回 0 = 旧消息，丢弃
     * </pre>
     *
     * <p>★ <b>用 {@code >} 而不是 {@code >=} 判旧，用 {@code >=} 判新</b>：
     * 时间戳<b>相等</b>时应当放行（重投同一条消息时就是这样，写的是同一个绝对值，
     * 无害且幂等）。写成严格大于的话，重复消费会被判成「旧的」而跳过 ——
     * 结果是<b>一条消息一次都没写成功、却被当成「已处理」</b>，
     * 这比多写一次危险得多。
     *
     * <p>★ {@code tonumber} 是必须的：Redis 里存的是字符串，
     * 不转数字的话比较会退化成<b>字符串比较</b> —— {@code "9" > "10"} 成立，
     * 时间戳的位数一旦变化就会开始乱判（毫秒时间戳今天都是 13 位，
     * 所以这个 bug 会一直潜伏到某个位数变化的日子，且表现为随机丢更新）。
     */
    private static final DefaultRedisScript<Long> GUARD_SCRIPT = new DefaultRedisScript<>("""
            local applied = redis.call('HGET', KEYS[1], ARGV[1])
            if applied and tonumber(applied) > tonumber(ARGV[2]) then
                return 0
            end
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            return 1
            """, Long.class);

    /**
     * ★ 必须是 {@code StringRedisTemplate}，不是 {@code RedisTemplate<String,Object>}。
     * 后者的 value 序列化器会把数字写成 {@code {"@class":"java.lang.Long","value":...}}，
     * <b>Lua 里的 {@code tonumber} 会返回 nil</b>，比较静默失效。
     * 完整论证见 {@code RedisConfig} 的类注释。
     */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 问一句「这条消息该不该写」，该写的话顺手把它的时间戳记下。
     *
     * <p>★ <b>返回值必须被调用方用上</b> —— 忘了判它（拿到 {@code false} 还照写）
     * 就是「加了守卫但等于没加」，而且代码review 时看起来是有守卫的。
     *
     * @param novelId 作品 id
     * @param field   {@code NovelStatField} 的<b>枚举名</b>（顺带就是 Hash 的 field）
     * @param at      这条消息的生产时刻（epoch 毫秒）
     * @return {@code true} = 该写；{@code false} = 旧消息，丢弃
     */
    public boolean shouldApply(Long novelId, String field, long at) {
        Long applied = stringRedisTemplate.execute(
                GUARD_SCRIPT,
                List.of(RedisKeyConstant.NOVEL_STAT_AT + novelId),
                field,
                String.valueOf(at));
        /*
         * ★ execute 返回 null 的唯一可能是脚本没返回值（或连接断了被吞成 null）——
         *   两种情况都不该当成「可以写」。返回 false 会让调用方丢弃这条消息，
         *   看起来是「丢数据」，但这条链路本来就有对账任务兜底，
         *   而【反过来默认放行】会在 Redis 抖动时静默退回「完全没有防乱序」的状态。
         *   ★ 两个方向都有代价，选「偏向丢弃」是因为它可被对账修复，另一个方向不能。
         */
        return applied != null && applied == 1L;
    }
}
