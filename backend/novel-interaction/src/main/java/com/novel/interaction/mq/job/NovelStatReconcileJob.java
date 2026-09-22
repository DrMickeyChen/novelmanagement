package com.novel.interaction.mq.job;

import com.novel.common.constant.RedisKeyConstant;
import com.novel.common.internal.NovelCollectCountDTO;
import com.novel.interaction.client.ContentGateway;
import com.novel.interaction.mq.producer.NovelStatProducer;
import com.novel.modules.interaction.mapper.UserShelfMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>收藏数的对账补偿任务（M3）—— 每 10 分钟一次，把 content 那边漂掉的
 * {@code collect_count} 修回来。</b>
 *
 * <p>方向：interaction → content（<b>读</b> content 的账本，<b>推</b>一条
 * {@code NOVEL_STAT} 消息回去）。它本身不写任何库。
 *
 * <h3>★★ 为什么必须有这么一个任务</h3>
 *
 * <p>M3 把收藏数的写回从「同步 Feign」改成了「发一条 {@code NOVEL_STAT} 消息」，
 * 换来的是：请求路径上不再有跨服务调用、下游挂了不拖累用户、有重试和死信。
 * <b>代价是这条链路【最终】一致，而不是立刻一致</b> —— 而「最终」需要一个
 * 兜底者：消息真的丢了（Broker 抖动、发送时进程被杀、消费端重试耗尽进死信）
 * 之后，<b>没有任何东西会再试一次</b>。
 *
 * <p>★ 这就是大纲 §11.3 坑 2 说的那一套：<b>MQ 事务消息 / 最终一致性必须配一个
 * 定时对账补偿任务</b>。少了它，「最终一致」里的「最终」是空的。
 *
 * <h3>★★ 它凭什么能发现漂移 —— 两边的账本对比，而不是「找最近变动的书」</h3>
 *
 * <p>最自然的想法是「哪本书最近动过就去重算它」。<b>这条路在这里走不通</b>，
 * 理由是一张表的形状：
 *
 * <pre>
 *   user_shelf 表【没有 deleted 列】，也没有 update_time（见 schema.sql）
 *   → 取消收藏是【物理 DELETE】→ 那一行连同它的痕迹一起消失了
 *   → 「最近变动的 novel」这个集合里【永远不会有被移除的那些书】
 *   → 而 R9 那笔债要修的恰恰是「移出时忘了减」
 * </pre>
 *
 * <p>所以锚点不能是「变动」，只能是<b>两边的绝对值本身</b>：
 * <ol>
 *   <li><b>本地真值</b>：{@code GROUP BY novel_id} 数一遍收藏人数
 *       （{@link UserShelfMapper#countGroupByNovel}）。一本书最后一行被删掉之后，
 *       它就<b>从结果里消失</b>了 —— 真值 0 是靠「缺席」表达的。</li>
 *   <li><b>对端的账本</b>：翻页读 content 报出来的<b>非零</b>收藏数集合
 *       （{@link ContentGateway#collectCountsAfter}）。</li>
 *   <li><b>并集比对</b>：只在一侧出现的书也进对比 —— 这正是「移除」
 *       能被看见的原因（本地没有它，对端还停在旧的非零值）。</li>
 * </ol>
 * ★★ <b>少了第 2 步，「移除」这个方向就彻底瞎了</b>：只看本地真值的话，
 * 缺席的书没有任何理由被拎出来重推一次 0。<b>这个并集不是「查得全一点」，是功能本身。</b>
 *
 * <h3>★★ 推的是【真值】，这换来了两条安全性质 —— 它决定了本类的容错可以很粗</h3>
 *
 * <ul>
 *   <li><b>幂等</b>：同一条重推两次，消费端写的是同一个数。</li>
 *   <li><b>扫不全 = 少修，不是修错</b>：哪怕这一轮只扫了一半（对端慢、
 *       轮次上限到了），凡是判成「漂移」的地方<b>都是真的漂</b>（推的值就是真值），
 *       最多是「有些该修的这轮没修到」，下一轮还在。
 *       <b>所以本类不需要「账本必须完整才敢动手」这类保护</b> ——
 *       这是推绝对值换来的，推增量的话这两条一条都不成立。</li>
 * </ul>
 *
 * <h3>★★ 锁：它是「计算型」任务，按项目自己的规矩必须加锁</h3>
 *
 * <p>{@code SchedulingConfig} 的类注释把定时任务分成两类，判据是
 * <b>「有没有天然的互斥点」</b>：
 * <ul>
 *   <li><b>消费型</b>（content 的 {@code NovelViewAggregator}）：每个实例只处理
 *       自己那份数据，多跑一遍无害，<b>不需要锁</b>；</li>
 *   <li><b>计算型</b>（本类）：全表扫描 + 比对 + 重推，<b>没有认领点</b> ——
 *       两个实例会各自完整跑一遍。</li>
 * </ul>
 * <p>所以用 {@link RedisKeyConstant#LOCK_PREFIX} + {@code SET NX EX}
 * （那个常量一直是「预留未用」，M3 起第一个真实使用者就是本类）。
 *
 * <p><b>★★ TTL 被两条相反的约束夹住，5 分钟是这两个数之间选的：</b>
 * <pre>
 *   TTL 必须 &gt; 预期执行时间   —— 否则锁在任务跑完前过期，等于没加锁
 *                                （SchedulingConfig 里那条 ★）
 *   TTL 必须 &lt; 执行周期(10min) —— 否则一轮跑崩了没释放，下一轮也【拿不到锁】，
 *                                对账会连着几轮不动，而日志上只有一句
 *                                「锁被占」，看起来完全像「另一个实例在跑」
 * </pre>
 * ★ 5 分钟给两边都留了余量（实际执行是秒级，下一轮在 10 分钟后）。
 *
 * <p><b>★ 不主动释放锁。</b>主动释放要做 compare-and-delete（先确认锁里还是自己的
 * {@code INSTANCE_ID} 再删），否则可能在 TTL 刚过期、别人刚拿到锁的那一刻把人家的锁删了 ——
 * 那就从「少一层保护」变成「制造一次并发」。而 TTL 到点自动过期已经保证了下一轮能拿到，
 * 代价仅仅是「这个 key 多存 5 分钟」。
 *
 * <p><b>★ 拿不到锁/拿不准时【放弃本轮】，方向是刻意选的：</b>
 * {@code setIfAbsent} 返回值在 Redis 连接异常时可能是 {@code null}
 * （同 {@code CommentServiceImpl#requireNotTooFast} 里那条提醒），
 * 那里选择<b>放行</b>（频控不是安全边界，让评论发出去更安全）；
 * <b>这里选择放弃</b> —— 两件事的代价结构相反：
 * 少跑一轮只是晚 10 分钟自愈（这条任务存在的意义就是兜底，它不差一轮），
 * 而两个实例同时全表扫 + 重推是白花的负载。
 *
 * <h3>★★ 绝不能调 {@code ShelfService} —— 这是会当场炸的那种错</h3>
 *
 * <p>{@code ShelfServiceImpl} 的每个公开方法第一行都是
 * {@code UserContext.requireUserId()}，而<b>定时任务线程上没有请求，
 * {@code UserContext} 是空的，一调就抛</b>（{@code SchedulingConfig} 的
 * 类注释专门警告过这条：「定时任务要么直接用 mapper，要么调那种显式接收
 * userId 参数的方法」）。所以本类注入的是 {@link UserShelfMapper}。
 *
 * <p>★ 顺带：{@link NovelStatProducer#publishCollectCount} 是可以直接在任务线程上调的 ——
 * 它内部走 {@code AfterCommit.run}，而那个 helper 在<b>没有事务的时候立即执行</b>
 * （见 {@code AfterCommit} 的类注释）。定时任务线程上本来就没有事务，
 * 所以消息是当场发出去的，不会「注册了同步器却永远不触发」。
 *
 * <h3>★★ 已知的残余（刻意不处理，但必须写下来）</h3>
 *
 * <ol>
 *   <li><b>逻辑删除的书会每轮都对不上。</b>本地的真值里还有它（书架行还在），
 *       而对端报不出它（查询带 {@code deleted = 0}）→ 每 10 分钟重推一次真值，
 *       消费端的 {@code UPDATE} 因为 {@code @TableLogic} <b>影响 0 行</b>。
 *       ★ 这是<b>无害但有信息量</b>的噪声：一条 INFO 日志，
 *       每本书每轮一条 —— 它说明「有人在删一本有人收藏的书」。
 *       要消掉它，得让对端把已删除的书也报出来，
 *       那等于把一个「统计口径」偷偷换成「含墓碑的行集」。</li>
 *   <li><b>只对账 {@code collect_count}，不修 {@code score}。</b>
 *       评分那一路丢消息同样会停在旧值（真值在 {@code novel_comment} 的 AVG）。
 *       不在这里一起修，是因为那条 AVG <b>带口径</b>
 *       （{@code status = 1} 且 {@code deleted = 0} 且 {@code score IS NOT NULL}
 *       且 {@code ROUND(..., 1)}），而那个口径已经有<b>一处</b>实现
 *       （{@code CommentServiceImpl#refreshNovelScore}）——
 *       在这里再写一遍就是第二个会各自漂移的版本，
 *       而这正是 {@code NovelService#refreshCollectCount} 那段「值在 interaction 算、
 *       行在 content 写、一处实现」要避免的事。
 *       ★ <b>记为 M5</b>：正解是把「重算」本身变成可重放的事实（outbox / 事件溯源），
 *       而不是给每条链路各配一个手抄口径的对账。</li>
 * </ol>
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NovelStatReconcileJob {

    /* ==================== 可调参数 ==================== */

    /**
     * 任务名。<b>它会进 Redis 的锁 key</b>（{@code RedisKeyConstant.LOCK_PREFIX} 的用法：
     * {@code SET novel:lock:{任务名} {实例标识} NX EX {秒数}}），
     * 所以它是个对外可见的字符串 —— 改动它等于换一把锁，
     * 新旧代码同时跑的那段时间两把锁互不相识（都以为自己是唯一的）。
     */
    private static final String JOB_NAME = "novel-stat-reconcile";

    private static final String LOCK_KEY = RedisKeyConstant.LOCK_PREFIX + JOB_NAME;

    /** 锁 TTL。★ 取值被两条约束夹住，论证在类注释里 */
    private static final Duration LOCK_TTL = Duration.ofMinutes(5);

    /**
     * 一页扫多少行。<b>★★ 100 不是随便取的，它是【对端的分页上限】。</b>
     *
     * <p>{@code MybatisPlusConfig} 里 {@code PaginationInnerInterceptor.setMaxLimit(100)}
     * 会把对端 {@code size} 超过 100 的请求<b>静默压到 100</b>。
     * 而本类判断「扫到底了」的依据是<b>「返回条数 &lt; 请求条数」</b>
     * （见 {@link #scanContentSide}）—— 请求 500 却永远只拿回 100 的话，
     * 第一页就会被判成「到底了」，<b>后面的书全部漏掉，而且不报任何错</b>。
     *
     * <p>★ 所以这个值的上限不是「性能调优」，是<b>正确性</b>：
     * 它必须 ≤ 对端的分页上限。写 100 而不是 99 是因为 100 正好顶到上限且不被截断
     * （{@code setMaxLimit} 只在 {@code size > 100} 时才压）。
     */
    private static final int SCAN_PAGE_SIZE = 100;

    /**
     * 一轮最多翻多少页（= 最多扫 5000 本）。
     *
     * <p>它是一个<b>防跑飞</b>的阀门，不是业务规则：真正的上限应该是「这个库里有几本书」，
     * 而那个数字本类事先不知道（游标分页不需要 {@code COUNT(*)}）。
     * 撞到它只说明一件事 —— 这次扫描<b>没扫完</b>，而没扫完的后果是安全的
     * （推的是真值，最多「这轮少修几个」，见类注释），
     * 所以处置是<b>记一条 ERROR 然后照样拿已经扫到的部分去比对</b>，
     * 而不是抛异常、也不是放弃整轮。
     */
    private static final int MAX_SCAN_ROUNDS = 50;

    /**
     * 「该去看看了」的线：一轮扫到超过这么多本<b>收藏数非 0</b> 的书就 WARN。
     *
     * <p>照 {@code CommentServiceImpl.ADMIN_CHAPTER_FILTER_WARN_THRESHOLD} 那个
     * 2000 章阈值的既有范式：<b>超过只打 WARN，不截断</b> ——
     * 取值没什么讲究（一个作品集项目的收藏数据远到不了这个量级），
     * 它是一条「该去看看了」的线，不是一条业务规则。
     *
     * <p>★ 为什么这条线画在【循环这一侧】而不画在对端的接口上：
     * 对端一次只回答一页，一次调用本身没有「太大了」的风险；
     * 有风险的是循环调用它的人，而这里才是循环发生的地方。
     * 两边各画一条的坏处是具体的：两个阈值，改一个忘一个，谁都不报错。
     */
    private static final int SCAN_WARN_THRESHOLD = 2000;

    /**
     * 本轮对账的实例标识，写进锁的 value，只为排查时能看出「是谁拿着锁」。
     *
     * <p>{@code ManagementFactory} 给的是 {@code pid@hostname} ——
     * 一行拿到、不抛异常、不发网络请求（{@code InetAddress.getLocalHost()}
     * 在有些环境里会卡住，为了一个日志字段不值得）。
     */
    private static final String INSTANCE_ID = ManagementFactory.getRuntimeMXBean().getName();

    private final UserShelfMapper userShelfMapper;
    private final ContentGateway contentGateway;
    private final NovelStatProducer novelStatProducer;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 每 10 分钟对一次账。
     *
     * <p>★ 用 {@code cron} 而不是 {@code fixedDelay}，是因为这里要表达的是
     * <b>「每个整十分钟检查一次」</b>这个日历语义（面试时也好说：「每 10 分钟一轮」），
     * 而 {@code fixedDelay} 表达的是「上一轮结束后隔 10 分钟」——
     * 两者在正常情况下一样，但前者的周期是确定可预期的，
     * 后者会随执行时间漂移。
     *
     * <p>★ 周期和锁 TTL 的关系是<b>刻意配的</b>：TTL（5 分钟）&lt; 周期（10 分钟），
     * 所以哪怕一轮跑崩了没释放锁，下一轮一定能拿到。反过来配
     * （TTL &gt; 周期）会出现「连着几轮都在打『锁被占』」——
     * 而那看起来像「另一个实例在跑」，方向就找错了。
     */
    @Scheduled(cron = "0 */10 * * * *")
    public void reconcile() {
        /*
         * ── 第一步：拿锁。★ 只有明确拿到 true 才跑，理由（含 null 的方向选择）在类注释 ──
         */
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(LOCK_KEY, INSTANCE_ID, LOCK_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            log.info("对账跳过：没拿到锁（另一个实例在跑，或上一轮的锁还没过期） | lock={}", LOCK_KEY);
            return;
        }

        /*
         * ── 第二步：本地真值。★ 走 mapper，绝不调 ShelfService（类注释里那条会当场炸的错） ──
         */
        Map<Long, Long> truth = new HashMap<>();
        for (UserShelfMapper.NovelCollectCount row : userShelfMapper.countGroupByNovel()) {
            truth.put(row.getNovelId(), row.getCollectCount());
        }

        /*
         * ── 第三步：对端账本（只报非 0 的那些书） ──
         */
        Map<Long, Long> contentSide = scanContentSide();

        /*
         * ★ 阈值检查放在【扫描之后】而不是循环里：放循环里的话，
         *   3000 本书会打 10 条一模一样的 WARN（每翻一页一条），
         *   而「该去看看了」这件事只需要说一次。
         */
        if (contentSide.size() > SCAN_WARN_THRESHOLD) {
            log.warn("对账扫描量偏大，该去看看了 | 扫到={} 阈值={}",
                    contentSide.size(), SCAN_WARN_THRESHOLD);
        }

        /*
         * ── 第四步：并集比对 ──
         *
         * ★★ 并集是功能本身，不是「查得全一点」：
         *   只在本地出现的书（真值 &gt; 0、对端报不出来）→ 需要重推真值；
         *   只在【对端】出现的书（真值 0、对端还是旧的非零值）→ 需要重推 0
         *   ——「取消收藏」这个方向就靠后半条才能被看见。
         *   两边都有的书才走普通的「值不一样就修」。
         */
        Set<Long> novelIds = new HashSet<>(truth.keySet());
        novelIds.addAll(contentSide.keySet());

        int repaired = 0;
        for (Long novelId : novelIds) {
            long expected = truth.getOrDefault(novelId, 0L);
            long actual = contentSide.getOrDefault(novelId, 0L);
            if (expected == actual) {
                continue;
            }
            /*
             * ★ 每条重推都打 INFO：正常情况下这条日志应该是【一条都没有】的
             *   （对齐的两边不需要修）。它出现，就说明前面那条 NOVEL_STAT
             *   消息真的丢了，或者消费端把它丢进了死信 —— 这正是要看见的信息。
             */
            novelStatProducer.publishCollectCount(novelId, expected);
            repaired++;
            log.info("对账发现收藏数漂移，已重推 | novelId={} 真值={} content上报={}",
                    novelId, expected, actual);
        }

        log.info("收藏数对账完成 | content非零={} 本地真值={} 待比对={} 重推={}",
                contentSide.size(), truth.size(), novelIds.size(), repaired);
    }

    /**
     * 翻页读 content 报出来的「非零收藏数」集合，直到扫完或撞上轮次上限。
     *
     * <p>★ <b>「扫到底了」的判断是「返回条数 &lt; 请求条数」</b>，
     * 而这就把 {@link #SCAN_PAGE_SIZE} 必须 ≤ 对端分页上限（100）这件事
     * 变成了硬约束 —— 否则第一页就会被误判成最后一页（论证在常量注释里）。
     *
     * <p>★ 空页也直接结束：对端一本非零收藏的书都没有（干净库 / 刚清过数据）
     * 是合法状态，不值得为它多打一次 HTTP。
     *
     * <p>★ 撞上限时的处置是「记 ERROR + 用已经扫到的部分继续」，
     * 不是抛异常：推的是真值，扫不全只会少修几个（类注释里那两条安全性质），
     * 而抛异常会把这一轮已经拿到的东西全部作废，下一轮 10 分钟后重来一遍、
     * 很可能又撞同一个上限。<b>该报警的是「这个库比预想的大得多」这件事本身。</b>
     */
    private Map<Long, Long> scanContentSide() {
        Map<Long, Long> contentSide = new HashMap<>();
        long afterId = 0L;

        for (int round = 1; round <= MAX_SCAN_ROUNDS; round++) {
            List<NovelCollectCountDTO> page =
                    contentGateway.collectCountsAfter(afterId, SCAN_PAGE_SIZE);
            if (page.isEmpty()) {
                return contentSide;
            }
            for (NovelCollectCountDTO row : page) {
                contentSide.put(row.novelId(), row.collectCount().longValue());
            }
            // ★ 下一页的游标 = 本页最后一个 id。升序由对端的接口保证（游标分页的前提）
            afterId = page.get(page.size() - 1).novelId();

            if (page.size() < SCAN_PAGE_SIZE) {
                return contentSide;
            }
        }

        /*
         * 走到这里 = 每一页都是满的、翻了 MAX_SCAN_ROUNDS 轮还没完。
         * ★ 注意它和「拿不到锁」的区别：那个是【本轮不做】，这个是【本轮做一半】——
         *   半份账本仍然安全（推真值），只是有些漂移要等下一轮。
         */
        log.error("对账扫描达到轮次上限，本轮只覆盖了前 {} 本，剩下的等下一轮 | 每页={} 上限轮次={}",
                contentSide.size(), SCAN_PAGE_SIZE, MAX_SCAN_ROUNDS);
        return contentSide;
    }
}
