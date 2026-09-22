package com.novel.content.mq.consumer;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.novel.common.mq.MqErrors;
import com.novel.modules.content.entity.Novel;
import com.novel.modules.content.mapper.NovelMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 阅读量的进程内聚合器：把「每来一条消息就 UPDATE 一次」压成「每 5 秒 UPDATE 一次」（M3）。
 *
 * <p><b>它是 M3 里「写热点削峰」这句话真正落地的地方。</b>
 * 上游 {@code ViewReportConsumer} 每收到一条 {@code VIEW_REPORT} 就调一次
 * {@link #accumulate}，这里只在一个 {@link ConcurrentHashMap} 里加数字；
 * 落库由 {@link #flush()} 每 5 秒批量做一次。
 *
 * <p>效果：几 QPS 的上报 → 0.2 QPS 的数据库写入（大纲 §12.4 要求的那组数字，
 * 实测值记在 {@code docs/M3-引入MQ.md}）。
 *
 * <h3>★★ 为什么这个版本【不需要】分布式锁，而老版本需要「认领」</h3>
 *
 * <p>老实现（{@code NovelViewCounter}）把计数攒在 <b>Redis 这个共享存储</b>里，
 * 所以两个 content 实例会同时看见同一个脏集合 —— 必须用 {@code SREM} 的返回值
 * 当分布式锁把人认领走。
 *
 * <p>M3 之后计数攒在<b>每个实例自己的堆内存</b>里，共享点消失了：
 * <ul>
 *   <li>{@code group-content-view} 是<b>一个</b>消费组，一条消息只会投给<b>一个</b>实例，
 *       所以同一份增量不会被两个实例各加一次；</li>
 *   <li>每个实例只 flush 自己那份 map；</li>
 *   <li>★ 而 {@code view_count = view_count + ?} 是<b>可加的</b>——
 *       实例 A 加 3、实例 B 加 5，加起来正好是 8，谁先谁后都对。</li>
 * </ul>
 * <b>比原来那套还简单</b> —— 这是「把共享状态换成私有状态」白拿的好处。
 *
 * <p>★★ <b>这也就是「阅读量必须保持增量语义」的原因。</b>
 * 一旦改成推绝对值（「这本书现在是 1280」），多实例立刻算错：
 * 后写的那个会把先写的整个盖掉，实例越多丢得越多。
 * 对照 {@code NOVEL_STAT}（收藏数 / 评分）—— 那条推的就是绝对值，
 * 因为它必须算准，代价是它得带时间戳做单调写。两条路的分界就在这里。
 *
 * <h3>★★★ 加法和「取走」必须都在 {@code compute} 的 lambda 里 —— 否则静默丢数据</h3>
 *
 * <p>这是本类唯一一处容易写错、<b>而且错了在开发环境基本看不出来</b>的地方。
 * 直觉写法是这样（<b>错的</b>）：
 * <pre>
 *   // ✗ 危险：取 map 是一把锁，add 在锁外面
 *   buffer.computeIfAbsent(novelId, k -&gt; new LongAdder()).add(delta);
 * </pre>
 * {@code computeIfAbsent} 返回之后锁就放开了，而 {@code add} 在那之后才执行。
 * 于是这个窗口里可能发生：
 * <pre>
 *   ① 消费线程拿到 adder A（还没加）
 *   ② flush 线程 compute 到同一个 key，把 A 取走、放进待落库列表
 *   ③ flush 线程读 A.sum() → 还是旧值
 *   ④ 消费线程这才执行 A.add(delta)  ← ★ 加到了一个已经被统计过的对象上
 *   → 这次浏览永远丢了，而且【没有任何日志】，因为每一步都是「成功」的。
 * </pre>
 *
 * <p><b>修法：把 add 挪进 lambda。</b>{@code ConcurrentHashMap.compute} /
 * {@code computeIfAbsent} 在执行 lambda 期间持有该 key 的<b>桶锁</b>，
 * 所以同一个 key 上的「加法」和「取走」天然串行 ——
 * 一次加法要么整个发生在取走之前（落在会被 sum 的 adder 上），
 * 要么整个发生在取走之后（落在换上去的新 adder 上）。<b>没有中间态。</b>
 *
 * <p>★ 这和 {@code NovelViewCounter} 当年用「先 SREM 认领、再 GETDEL」
 * 解决的是<b>同一类竞态</b>——老代码在那一点上是对的，
 * 改写时把它写丢了，就是一次静默的数据丢失。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NovelViewAggregator {

    private final NovelMapper novelMapper;

    /**
     * 待落库的增量：{@code novelId → 累加器}。
     *
     * <p>★ 没有 TTL、也不会无限增长 —— {@link #flush()} 每轮把处理过的 key
     * <b>整个移除</b>，所以这个 map 的稳态大小是「上一轮 5 秒内被读过的书数」，
     * 不是「历史上被读过的所有书」。
     * <p>（★ 这正是 flush 里 {@code compute} 返回 {@code null} 而不是
     * 换一个新的 {@code LongAdder} 的原因：换新的会把每个 key 永久留在 map 里，
     * 那是个每本书泄漏一个对象的慢泄漏。返回 null 让缺席的 key 在下次
     * 有人读时被重新创建。）
     */
    private final ConcurrentHashMap<Long, LongAdder> buffer = new ConcurrentHashMap<>();

    /**
     * 累加一次浏览。由 {@code ViewReportConsumer} 调用。
     *
     * <p>★★ 看方法体：{@code computeIfAbsent} 和 {@code add} 是<b>同一个 lambda 里</b>
     * 的两步，不是「先取出来再在外面加」。原因见类注释里那段竞态推演 ——
     * 这一行看着啰嗦，它换来的是「不会静默丢增量」。
     */
    public void accumulate(Long novelId, int delta) {
        if (delta <= 0) {
            /*
             * 增量非正数：载荷坏了（生产者只会发 +1）。
             * ★ 不抛异常 —— 这是一个【永久失败】，重试一万次结果一样。
             *   ★ 注：这里原来写的理由是「在顺序消费里抛异常会永久卡死队列」，
             *     那句话 M3 实测已推翻（显式配 maxReconsumeTimes 就会进 DLQ）。
             *     但【结论不变，而且更强】：这条本来就是并发消费，
             *     永久失败在并发下的正确处置也是丢弃 + WARN ——
             *     抛出去只会让它白走三遍重试阶梯再进 DLQ，换不来任何东西。
             *     丢弃 + WARN 是唯一正确的处置。
             */
            log.warn("阅读量消息的增量非正数，已丢弃 | novelId={} delta={}", novelId, delta);
            return;
        }
        buffer.compute(novelId, (id, adder) -> {
            if (adder == null) {
                adder = new LongAdder();
            }
            adder.add(delta);
            return adder;
        });
    }

    /**
     * 把积压的增量刷进 {@code novel.view_count}。每 5 秒一轮。
     *
     * <p>周期取 5 秒：快到「验收时手点两下就能在页面上看到变化」，
     * 又慢到能把这几秒内的所有浏览合并成一条 UPDATE。
     * ★ 用 ISO-8601 的 Duration 写法，别写裸数字 —— 裸数字的单位是毫秒，
     * {@code 5} 会变成每 5 毫秒跑一次。
     *
     * <p>★★ <b>为什么挂在 {@code @Scheduled} 上、而不是在消费线程里顺手做：</b>
     * 消费线程是有限的（默认 20 个），它的职责是「尽快把消息拿下来」。
     * 在里面跑逐 id 的 UPDATE 会把消费拖慢，积压就上来了 ——
     * 而积压正是上层要做削峰的原因，等于把问题挪了个地方。
     * 攒在内存里、由定时任务批量落库，消费线程才能一直保持轻。
     *
     * <p>★★ <b>失败方向和老实现一样刻意选的：宁可少算，不可多算。</b>
     * 数据库更新失败时<b>不做补偿、不把增量塞回 buffer</b>，只记一条 WARN
     * 把丢失的量写进日志。理由和老代码一模一样：
     * 「UPDATE 已经提交、但响应回不来」是会真实发生的（网络抖动、连接被掐断），
     * 这时候塞回去就是重复累加，而<b>阅读量虚高没有任何办法核对回来</b>；
     * 少算的代价只是一次近似值偏低，这本书下次有人读又会涨上去。
     *
     * <p>★ <b>诚实记一笔（M3 唯一一处「退步」）：</b>
     * 老实现是<b>自愈</b>的 —— 计数在 Redis 里累积，这本书下次有人读时
     * 会重新 {@code INCR}，{@code GETDEL} 把积压的一起捞出来。
     * 内存 {@code LongAdder} <b>没有这个性质</b>：每次浏览都是独立的一次 +1，
     * 没有「待补的余额」可以再捞一次。所以这一版的失血窗口比改造前大。
     * 换取的是：旧版<b>数据库一挂，那一批就永久丢了</b>（{@code GETDEL} 已经把值取走，
     * 只记一条 WARN、没有补偿），而 MQ 至少给了消费失败的重试与死信。
     * <b>两边的失血点不一样，不是单纯的变好或变坏。</b>
     * 见 {@link #drainOnShutdown()} —— 它把「随时可能丢 5 秒」压到「只有 kill -9 才丢」。
     */
    @Scheduled(fixedDelayString = "PT5S")
    public void flush() {
        if (buffer.isEmpty()) {
            /*
             * 绝大多数轮次走这里（没人读书就没有增量）。
             * 用 debug 而不是 info —— 每 5 秒一条 info 会把真正有用的行冲走。
             */
            log.debug("阅读量落库：无增量");
            return;
        }

        /*
         * ★★ 原子交换：把每个 key 当前的累加器【取走】，并让这个 key 从 map 里消失。
         *
         * 先拷贝一份 keySet 再遍历：compute 会修改 map，
         * 直接在 keySet 上迭代会抛 ConcurrentModificationException。
         * （keySet() 返回的是视图，不是快照。）
         */
        List<Map.Entry<Long, LongAdder>> drained = new ArrayList<>();
        for (Long novelId : new ArrayList<>(buffer.keySet())) {
            buffer.compute(novelId, (id, adder) -> {
                if (adder != null) {
                    drained.add(Map.entry(id, adder));
                }
                // ★ 返回 null = 把这个 key 移除。
                //   并发到达的 accumulate 会在同一个桶锁上排队，随后看见 null
                //   并创建一个新的 adder —— 那笔增量落在下一轮，不会被丢。
                return null;
            });
        }

        int flushed = 0;
        long totalDelta = 0;
        for (Map.Entry<Long, LongAdder> entry : drained) {
            Long novelId = entry.getKey();
            // ★ 锁外才 sum() —— 此刻这个 adder 已经不属于 map 了，没有别人再写它。
            long delta = entry.getValue().sum();
            if (delta <= 0) {
                continue;
            }
            try {
                if (updateViewCount(novelId, delta)) {
                    flushed++;
                    totalDelta += delta;
                } else {
                    /*
                     * ★ 影响 0 行 = 这本书已经不存在了（被物理删除，或 deleted=1）。
                     * 不是错误，但我们确实丢了一次增量，所以记 WARN 而不是 debug。
                     */
                    log.warn("阅读量落库：目标作品不存在，本次增量丢弃 | novelId={} delta={}",
                            novelId, delta);
                }
            } catch (Exception e) {
                /*
                 * ★★ 数据库更新失败。按上面的取舍【不做补偿】：
                 * 丢失的量写进日志，运维可以照着核对，但绝不塞回 buffer。
                 * 注意这里不能让异常冒出去 —— 冒出去会中断整个 for 循环，
                 * 剩下几十本书的增量会一起丢掉。
                 */
                log.warn("阅读量落库失败，本次增量丢弃（宁可少算，不可多算） | novelId={} delta={} error={}",
                        novelId, delta, MqErrors.describe(e), e);
            }
        }

        if (flushed > 0) {
            log.info("阅读量落库完成 | 作品数={} 增量合计={}", flushed, totalDelta);
        }
    }

    /**
     * 关停前把还在内存里的增量刷下去。
     *
     * <p>★★ <b>这是对上面那条「退步」的补偿措施，不是可有可无的收尾。</b>
     * 没有它，一次正常的滚动发布就会丢掉最多 5 秒的阅读量 ——
     * 而发布是<b>计划内</b>的动作，不该有数据损失。
     *
     * <p>加上它之后，失血窗口从「随时最多 5 秒」压到<b>「只有 {@code kill -9} 才有」</b>
     * （强杀不走 {@code @PreDestroy}）。这是内存聚合能给出的最好结果 ——
     * 到此为止，剩下的损失是这套设计的<b>固有代价</b>，
     * 而不是「忘了处理」。
     *
     * <p>★ 时序上是安全的：本 bean 依赖 {@code NovelMapper}，
     * 所以 Spring 会<b>先销毁本 bean、再销毁数据源</b>（依赖者先死）。
     * 这里调 {@code flush()} 时数据库连接仍然可用。
     * ★ 反过来说：如果哪天给它加了一个「被别的 bean 注入」的关系，
     * 这个时序就要重新确认一遍。
     */
    @PreDestroy
    public void drainOnShutdown() {
        try {
            log.info("应用关闭，排空内存中未落库的阅读量…");
            flush();
        } catch (Exception e) {
            /*
             * ★ 关停路径上【绝不能抛异常】—— 抛出去会让 Spring 的销毁流程
             * 在这里中断，后面还没销毁的 bean（连接池、Redis 客户端）
             * 可能就得不到正常关闭。
             */
            log.warn("关停排空阅读量失败（已忽略） | error={}", MqErrors.describe(e), e);
        }
    }

    /**
     * 把增量加到 {@code novel.view_count} 上。
     *
     * <p>用 {@code setSql} 而不是先查后写：{@code view_count = view_count + ?}
     * 由数据库自己完成读改写，天然原子，不会和另一个实例或读者的操作互相覆盖。
     *
     * <p>★ <b>{@code {0}} 是占位符，不是字符串拼接。</b>
     * MP 会把它转成 {@code #{ew.paramNameValuePairs.MPGENVAL1}} 的预编译参数。
     * 写成 {@code setSql("view_count = view_count + " + delta)} 虽然 delta
     * 是解析出来的 long、当下安全，但那是「靠调用方保证安全」的写法 ——
     * 全仓不出现任何拼 SQL 的地方，后来人才不会照着抄出一个注入口
     * （对照 {@code NovelServiceImpl} 里拒绝用 {@code .last("LIMIT " + n)} 的理由）。
     *
     * <p>★ 这里传的 entity 是 {@code null}，所以 {@code MybatisMetaObjectHandler}
     * 的 {@code updateFill} <b>不会执行</b>，{@code update_time} 保持不动。
     * 这是想要的：阅读量涨了不代表「这本书的信息被修改了」，
     * 被人每读一下就更新一次 {@code update_time} 会让这个字段失去意义。
     * （{@code syncNovelStat} 同样传 null entity，但那边是<b>必须</b>传 ——
     * 它要显式把 {@code last_chapter_id} 写成 null，理由见那个方法的注释。）
     *
     * <p>★ 条件里的 {@code deleted = 0} 由 {@code @TableLogic} 自动补上，
     * 这就是 {@code NovelMapper} 保持空接口的价值。
     *
     * <p>★★ <b>这两段注释是从已删除的 {@code NovelViewCounter.updateViewCount}
     * 原样搬过来的</b>（M3 删除那个类时做的搬迁）—— 它们讲的是 SQL 写法和
     * 字段语义，与「计数攒在 Redis 还是内存」无关，所以换实现之后它们依然成立。
     *
     * @return 是否命中了一行。false 表示作品不存在或已被删除。
     */
    private boolean updateViewCount(Long novelId, long delta) {
        LambdaUpdateWrapper<Novel> update = new LambdaUpdateWrapper<Novel>()
                .eq(Novel::getId, novelId)
                .setSql("view_count = view_count + {0}", delta);
        return novelMapper.update(null, update) > 0;
    }
}
