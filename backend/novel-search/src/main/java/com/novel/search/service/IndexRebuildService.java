package com.novel.search.service;

import com.novel.common.constant.RedisKeyConstant;
import com.novel.common.internal.InternalResults;
import com.novel.common.internal.NovelIndexDoc;
import com.novel.search.client.ContentClient;
import com.novel.search.document.NovelDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;

/**
 * <b>全量重建的编排（M4 / S8）—— 加锁、删索引、按游标把整个库灌进去。</b>
 *
 * <p>★ 它调用的原语（{@code recreateIndex} / {@code bulkUpsert}）在
 * {@link IndexService}；这里只管<b>顺序、轮次、以及加不加锁</b>。
 * 分开的理由见 {@code IndexService} 的类注释：<b>两者的失败语义相反</b> ——
 * 原语失败要抛，编排失败要「记下来 + 等下一个周期」。
 *
 * <h3>★★★ 它存在的理由：MQ 是「尽力而为」的，没有它对账，「最终一致」的「最终」是空的</h3>
 *
 * <p>S7 的 {@code NOVEL_INDEX} 消息在三种情况下会永久丢失：
 * <ul>
 *   <li><b>发送侧</b>：{@code asyncSend} 失败（Broker 抖动、发送那一刻进程被杀）——
 *       {@code NovelIndexNotifier} 只来得及记一行 WARN；</li>
 *   <li><b>消费侧</b>：重试 3 次仍失败 → 进 {@code %DLQ%group-search-index}，
 *       那条消息从此不再被消费，<b>也不会有任何东西再试一次</b>；</li>
 *   <li><b>根本没人发</b>：某个写入口还没有落点（判断 2 那张表里已经排掉了已知的，
 *       但「将来新增的写路径忘了加落点」是个持续存在的风险）。</li>
 * </ul>
 * <p>★★ <b>这三条里最要命的是第三条</b>：它不会报错、不会进死信、没有任何痕迹 ——
 * 唯一的症状是「这本书的索引一直是旧的」。
 * <b>本类不去分辨成因，它整个重灌一遍</b>，于是上面三种情况一起被修掉。
 *
 * <h3>★★ 周期怎么定：这是一个「索引能有多旧」的上界</h3>
 *
 * <p>取值 6 小时（{@code IndexRebuildJob}）。它是<b>权衡的直接产物</b>：
 * <pre>
 *   调短 → 自愈快，但每次重建都会 DELETE 索引（窗口内全站走降级路径，
 *          且那一段时间内 ES 的排序能力是关掉的）
 *   调长 → 索引陈旧的窗口变长，用户可能长时间搜不到一本刚改过名的书
 * </pre>
 * <p>★ <b>6 小时这个数不是拍出来的，是「上面那三条都是低频事件」推出来的</b>：
 * 正常情况下 S7 的增量消息是秒级的，本类<b>一次都不该被需要</b>。
 * 它正确的运行状态是「每 6 小时跑一次、每次什么都不用修」。
 * ★★ <b>所以「重建后条数与上一次一致」这件事，本身不是坏消息，是预期结果。</b>
 *
 * <h3>★★★ 锁：它是「计算型」任务，按项目自己的规矩必须加锁</h3>
 *
 * <p>{@code SchedulingConfig} 的类注释把定时任务按「有没有天然的互斥点」分成两类，
 * 本类属于<b>计算型</b>（没有认领点）：两个实例会<b>各自完整跑一遍</b>。
 * 而这里两个实例同时跑不只是白花负载，是<b>真的会互相破坏</b>：
 * A 在灌数据、B 把索引删了 —— 症状是「索引时有时无」，且两边日志都正常。
 *
 * <p>★ 用 {@link RedisKeyConstant#LOCK_PREFIX} + {@code SET NX EX}，
 * 形状照 {@code NovelStatReconcileJob}（M3 第一个使用者）。
 *
 * <p><b>★★ TTL 被两条相反的约束夹住，30 分钟是这两个数之间选的：</b>
 * <pre>
 *   TTL 必须 &gt; 预期执行时间   —— 否则锁在任务跑完前过期，等于没加锁
 *   TTL 必须 &lt; 执行周期(6h)   —— 否则一轮跑崩了没释放，下一轮也拿不到锁
 * </pre>
 * ★ 30 分钟比 {@code NovelStatReconcileJob} 的 5 分钟宽得多，因为两者的工作量差着量级：
 * 那个是翻页读一个数再比对，这个是<b>把整个库灌进 ES</b>。
 * ★ <b>但不主动释放锁</b>（理由同那个任务）：主动释放要做 compare-and-delete，
 * 否则可能在 TTL 刚过期、别人刚拿到锁的那一刻把人家的锁删掉 ——
 * 那就从「少一层保护」变成「制造一次并发」。
 *
 * <h3>★★ 拿不到锁 = 本轮什么都不做，方向是刻意选的</h3>
 *
 * <p>两种成因（另一个实例在跑 / 上一轮的锁还没过期）处置相同，都是<b>直接返回</b>。
 * ★ 对<b>定时</b>触发：晚 6 小时自愈，可以接受（这条任务本来就是兜底，不差一轮）。
 * ★★ 对<b>管理端点</b>触发的：调用方拿到的是 {@code skipped=true}，
 * 而<b>绝不能把这个显示成「重建成功」</b> —— 见 {@link RebuildReport} 的注释。
 *
 * <h3>★ 为什么这里可以放心用 {@code ContentClient}（Feign），而消费者那边要小心</h3>
 *
 * <p>两者都在 search 进程里、都调 content，区别在<b>调用发生在哪个线程</b>：
 * 消费者跑在 MQ 的消费线程上（出问题由重试阶梯兜），
 * 本类跑在定时任务线程 / 请求线程上。
 * ★ <b>定时任务线程上没有 {@code UserContext}</b>（没有请求）——
 * 所以这里调的对端端点必须是不依赖登录态的那种，
 * 而这正是 {@code /internal/**} 的前提（认证只靠共享密钥头，由 {@code FeignIdentityConfig} 带上）。
 * ★★ 这一条和 {@code NovelStatReconcileJob} 里「绝不能调 {@code ShelfService}」是同一类约束：
 * <b>定时任务能调什么，取决于目标方法要不要登录态，不取决于它是不是 public。</b>
 *
 * @author MickeyChen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexRebuildService {

    /* ==================== 可调参数 ==================== */

    /**
     * 任务名。<b>它会进 Redis 的锁 key</b>，所以它是个对外可见的字符串 ——
     * 改动它等于换一把锁（新旧代码同时跑的那段时间两把锁互不相识，都以为自己是唯一的）。
     */
    private static final String JOB_NAME = "search-index-rebuild";

    private static final String LOCK_KEY = RedisKeyConstant.LOCK_PREFIX + JOB_NAME;

    /**
     * 锁 TTL。★ 取值被两条约束夹住，论证在类注释里。
     *
     * <p>★ 比 {@code NovelStatReconcileJob} 的 5 分钟宽得多：那个任务的工作量是
     * 「翻页读一个数再比对」，本类要<b>把整个库灌进 ES</b>。
     */
    private static final Duration LOCK_TTL = Duration.ofMinutes(30);

    /**
     * 一页灌多少条。<b>★★ 100 不是调优取值，是【对端的分页上限】。</b>
     *
     * <p>对端 {@code MybatisPlusConfig} 里 {@code PaginationInnerInterceptor.setMaxLimit(100)}
     * 会把 {@code size > 100} 的请求<b>静默压到 100</b>，而对端
     * {@code /internal/novels/index-page} 的注释明确要求调用方知道这件事。
     * 而本类判断「扫到底了」的依据是<b>「返回条数 &lt; 请求条数」</b>
     * （见 {@link #refill}）—— 请求 500 却永远只拿回 100 的话，
     * <b>第一页就会被判成最后一页</b>。
     *
     * <p>★★★ 而这个 bug 在本类里的后果<b>比在别处严重得多</b>：
     * 重建是「先删索引再灌」，所以漏掉的书不是「没更新」，
     * 是<b>从索引里彻底消失</b> —— 而且不报任何错，直到有人搜一本明明存在的书搜不到。
     * <b>这条约束是正确性，不是性能。</b>
     */
    private static final int PAGE_SIZE = 100;

    /**
     * 一轮最多翻多少页（= 最多灌 5000 本）。
     *
     * <p>★ 它是一个<b>防跑飞</b>的阀门，不是业务规则：真正的上限是「库里有几本书」，
     * 而游标分页不需要 {@code COUNT(*)}，本类事先不知道那个数。
     * ★ 撞到它说明这次重建<b>没灌完</b>，处置是<b>记 ERROR 并照常返回已灌的条数</b> ——
     * 不是抛异常。理由：已经灌进去的那部分是对的（upsert 幂等），
     * 抛异常反而会把「灌了一半」这件事说得像是「整个失败了」，
     * 而下一轮重建照样会来。<b>该报警的是「这个库比预想的大得多」这件事本身。</b>
     */
    private static final int MAX_ROUNDS = 50;

    /**
     * 本轮实例标识，写进锁的 value，只为排查时能看出「是谁拿着锁」。
     * ★ {@code pid@hostname} —— 一行拿到、不抛异常、不发网络请求
     * （{@code InetAddress.getLocalHost()} 在有些环境里会卡住，为一个日志字段不值得）。
     */
    private static final String INSTANCE_ID = ManagementFactory.getRuntimeMXBean().getName();

    private final IndexService indexService;
    private final ContentClient contentClient;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 全量重建的<b>结果报告</b>。
     *
     * <p>★★★ <b>{@code skipped} 必须和「成功」分开表达，这是本类最容易出的一种错。</b>
     * 一次「没抢到锁」的重建和一次「灌了 3000 条」的重建，
     * 如果都只回一个 {@code 200}，那么管理员点了「重建索引」、
     * 页面提示成功、<b>而索引根本没动</b> —— 而且是「另一个实例正在重建」这种
     * 十分钟后就会自愈的情况，<b>最容易被当成「已经好了」</b>。
     * ★ 所以这里的三个字段是刻意分开的：{@code skipped} 说「做没做」，
     * {@code indexed} 说「做了多少」。
     *
     * @param skipped   本轮是否因为没拿到锁而<b>什么都没做</b>
     * @param indexed   实际灌进去的文档条数（{@code skipped=true} 时恒为 0）
     * @param elapsedMs 本轮耗时（毫秒）。★ 它是「该不该调周期/要不要上 alias 切换」
     *                  的唯一实测依据 —— 光看条数看不出重建要跑多久
     */
    public record RebuildReport(boolean skipped, long indexed, long elapsedMs) {

        /**
         * 没抢到锁时用它 —— 三个字段一起说明「本轮没做任何事」。
         *
         * <p>★ 名字不能叫 {@code skipped()} —— <b>那和 record 自动生成的访问器重名，
         * 编译期直接报「方法类型与变量不匹配」</b>。
         * 一个静态工厂和它的 record 访问器同名是个很容易踩的坑：
         * 读代码时两者长得一模一样，而报错信息指向的是调用方、不是定义处。
         */
        public static RebuildReport notRun() {
            return new RebuildReport(true, 0L, 0L);
        }
    }

    /**
     * 重灌整个索引：<b>拿锁 → 删索引重建 → 按游标把 content 的书全部灌进去</b>。
     *
     * <p>★★ 调用方有三个：启动时索引为空（{@code IndexInitializer}）、
     * 定时兜底（{@code IndexRebuildJob}）、管理端点（{@code AdminSearchController}）。
     * <b>三者走的是同一个方法、同一把锁</b> —— 手工触发撞上定时任务时，
     * 后到的那个会拿到 {@code skipped=true}，而不是两边一起删索引。
     *
     * <p>★ <b>本方法外抛异常</b>（ES 不可达、content 不可达、bulk 部分失败）。
     * 三个调用方各自决定怎么处理，而它们的选择是不一样的：
     * <ul>
     *   <li>启动时 —— <b>吞掉只记 WARN</b>。ES/content 还没起来是正常场面，
     *       <b>不能让它把服务拦在启动之外</b>（R7：ES 挂了服务必须能起）；</li>
     *   <li>定时 —— <b>吞掉记 ERROR</b>。下一轮 6 小时后再来；</li>
     *   <li>管理端点 —— <b>不吞，让它变成 500</b>。管理员此刻就站在这里等着结果，
     *       「点了没反应但也没报错」是最坏的回答。</li>
     * </ul>
     *
     * @return 见 {@link RebuildReport}；{@code skipped=true} 表示没拿到锁、本轮无操作
     * @throws RuntimeException 重建过程中任何一步失败（★ 见上：调用方各自决定吞不吞）
     */
    public RebuildReport rebuildAll() {
        long startedAt = System.currentTimeMillis();

        /*
         * ── 第一步：拿锁。★ 只有明确拿到 true 才跑 ──
         * setIfAbsent 在网络异常时会返回 null（同 CommentServiceImpl 里那条提醒），
         * 所以这里必须用 Boolean.TRUE.equals 而不是直接 if(acquired) ——
         * 后者在 null 时会抛 NPE，把一个「拿不准」变成一次崩溃。
         */
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(LOCK_KEY, INSTANCE_ID, LOCK_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            log.info("索引重建跳过：没拿到锁（另一个实例在重建，或上一轮还没到期） | lock={}", LOCK_KEY);
            return RebuildReport.notRun();
        }

        /*
         * ── 第二步：删索引 + 重建 ──
         *
         * ★★ 从这一刻起到 refill() 结束，索引是【不存在】或【不完整】的。
         *   这期间每一次检索都会抛异常 → 被 SearchService 的 catch 接住 →
         *   自动走降级路径（MySQL LIKE）。★ 也就是说窗口内搜索仍然可用，
         *   只是排序退回阅读量。这是判断 11 明确接受的取舍。
         */
        indexService.recreateIndex();

        /*
         * ── 第三步：灌数据 ──
         *
         * ★★★ 灌失败时必须把【刚建出来的空索引】删掉再往外抛 —— 这一条是 S8 实测逼出来的，
         *   不是设计时就想到的。完整论证在 IndexService#dropQuietly 的注释里，这里只留判据：
         *
         *     索引不存在   → 检索抛异常 → 降级到 MySQL LIKE → 结果【是全集的】，只是排序退化  ← 好
         *     索引存在但空 → 检索无异常 → 直接返回 0 条     → 结果【是空的】，且无人报错    ← 坏
         *
         *   ★★ 实测那次：recreateIndex() 的 DELETE 成功、refill() 的 Feign 读超时 →
         *      留下一个空索引 → 搜「剑」返回 `HTTP 200 total=0`，而 LIKE 的真值是 2 条。
         *      【既不降级、也不报错】—— 这是本类能产生的最坏结果。
         *   ★ 半成品（灌了几页才断）同理：那会让一部分书永远搜不到。
         *
         * ★ 注意 try 只包 refill()，不包 recreateIndex()：后者失败时索引【本来就不存在】，
         *   没有什么要清理的，多删一次只是多一次网络往返。
         * ★ dropQuietly() 自己不外抛（见它的注释），所以这里的 catch 之后再 throw e
         *   抛出去的仍然是最初那个异常，不会被覆盖掉。
         */
        long indexed;
        try {
            indexed = refill();
        } catch (RuntimeException e) {
            indexService.dropQuietly();
            throw e;
        }

        /*
         * ★★ 条数为 0 要单独报警。两种成因，处置不同但都必须被看见：
         *   ① content 那边一本都没有（干净库）—— 正常，但值得记一笔，
         *      因为它的另一个可能成因是「Feign 调通了、对端返回了空列表」，
         *      而那是【把索引清空】这个后果里最安静的一种；
         *   ② 对端返回了非空但解析成了空（契约漂移）—— 那是个真 bug。
         * ★ ERROR 而不是 WARN：这个状态意味着【全站搜索此刻走的是降级路径】，
         *   而如果没人注意，它会一直这样到下一个周期。
         *
         * ★★★ 【为什么这一支不跟着调 dropQuietly()】—— 上面那个 catch 会调，这里不会，
         *   两者看起来都是「索引里没东西」，所以这个区别必须写下来，否则下一个人一定会问：
         *
         *   判据是这条不变式 —— 【索引存在 ⟹ 索引里的内容是正确的（空也算正确）】。
         *     ① 干净库：索引空是【对的】，它如实反映了「库里没有书」。
         *        此时删掉索引只会让每次检索白抛一次异常、白跑一趟 Feign，
         *        最终结果还是 0 —— 更差，且什么也没换来。
         *     ② 契约漂移：索引空是【错的】。
         *   ★ 而 search 这边【分辨不出 ① 和 ②】—— 那正是这条 ERROR 日志存在的意义
         *     （文案里那句「先确认 content 的作品表不是空的」就是在把分辨工作交给人的）。
         *   ★★ 交给人的意思是：**能自动处置的自动处置，分不清的宁可留着现场让人来看**。
         *     把 ② 也一并自动删掉的话，唯一的证据（那个空索引）就没了，
         *     而症状会从「搜索返回 0」变成「搜索走了降级路径」—— 同样是坏的，但更难查。
         */
        long elapsed = System.currentTimeMillis() - startedAt;
        if (indexed == 0) {
            log.error("索引重建完成，但一条都没灌进去 —— 全站搜索将走降级路径（MySQL LIKE）"
                            + " | index={} 耗时={}ms。★ 先确认 content 的作品表不是空的，"
                            + "再确认 /internal/novels/index-page 返回的不是空列表",
                    indexService.indexName(), elapsed);
        } else {
            log.info("索引重建完成 | index={} 灌入={} 条 耗时={}ms",
                    indexService.indexName(), indexed, elapsed);
        }

        return new RebuildReport(false, indexed, elapsed);
    }

    /**
     * 按 id 升序翻页取数并灌进 ES，直到扫完或撞上轮次上限。
     *
     * <p>★ <b>「扫到底了」的判断是「返回条数 &lt; 请求条数」</b>，
     * 而这就把 {@link #PAGE_SIZE} 必须 ≤ 对端分页上限（100）变成了硬约束
     * （论证在常量注释里：判错的后果是把书<b>从索引里删掉</b>）。
     *
     * <p>★ 空页也直接结束：content 一本书都没有是合法状态。
     * ★ 游标取<b>本页最后一个 id</b>，升序由对端保证（游标分页的前提）。
     *
     * <p>★ 撞上限时<b>不抛异常</b>，记 ERROR 后返回已灌条数 ——
     * 已经灌进去的部分是对的（upsert 幂等），下一轮重建还会来。
     * <b>抛出去反而会把「灌了一半」说成「整个失败」。</b>
     */
    private long refill() {
        long afterId = 0L;
        long indexed = 0L;

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            List<NovelIndexDoc> page = InternalResults.unwrap(
                    contentClient.indexPage(afterId, PAGE_SIZE), "全量重建取数");

            if (page == null || page.isEmpty()) {
                return indexed;
            }

            /*
             * ★ NovelDocument.from 是全项目唯一一处 NovelIndexDoc → ES 文档的映射
             *   （LocalDateTime → ISO-8601 字符串的转换在那里）。这里不要自己拼字段。
             * ★ 一批一次 bulk，不是一条一次 index：见 IndexService#bulkUpsert。
             */
            indexService.bulkUpsert(page.stream().map(NovelDocument::from).toList());
            indexed += page.size();

            afterId = page.get(page.size() - 1).getId();

            if (page.size() < PAGE_SIZE) {
                return indexed;
            }
        }

        log.error("索引重建达到轮次上限，本次只灌了前 {} 条，剩下的等下一轮 | 每页={} 上限轮次={}",
                indexed, PAGE_SIZE, MAX_ROUNDS);
        return indexed;
    }
}
