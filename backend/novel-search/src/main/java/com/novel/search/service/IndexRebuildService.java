package com.novel.search.service;

import com.novel.common.constant.RedisKeyConstant;
import com.novel.common.internal.InternalResults;
import com.novel.common.internal.NovelIndexDoc;
import com.novel.search.client.ContentClient;
import com.novel.search.document.NovelDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;

/**
 * <b>全量重建的编排（M4 / S8）—— 加锁、删索引、按游标把整个库灌进去。</b>
 *
 * <p>★ 它调用的原语（{@code newPhysicalIndexName} / {@code createPhysicalIndex} /
 * {@code bulkUpsert} / {@code switchAlias} / {@code deleteStaleIndices}）在
 * {@link IndexService}；这里只管<b>顺序、轮次、以及加不加锁</b>。
 * 分开的理由见 {@code IndexService} 的类注释：<b>两者的失败语义相反</b> ——
 * 原语失败要抛，编排失败要「记下来 + 等下一个周期」。
 * ★★ S8 之后这条分界更清楚了：<b>「别名什么时候切」是编排的决定，不是原语的决定</b> ——
 * {@code switchAlias} 自己不知道这一轮灌得对不对，所以「灌进来 0 条就【不许切】」
 * 这个判断只能写在这一侧（见下面那段）。
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
 * ★★ <b>拿到锁的那一段，退出时一定放锁（compare-and-delete）—— 成功如此，失败也如此。</b>
 *
 * <p>原文写的是「不主动释放（理由同那个任务）」，这里订正 —— <b>那句话只说对了【做法】
 * 要小心，没说【不做】是对的。</b> 不裸 {@code DEL} 是对的（TTL 刚过期、别人刚拿到锁时
 * 裸删就从「少一层保护」变成「制造一次并发」），但那个风险由
 * {@link #UNLOCK_SCRIPT} 的 {@code GET == ARGV[1]} 原子判断消掉了。
 *
 * <p>★★★ <b>「不释放」的代价在 M5 实测里暴露了两次，第二次比第一次更隐蔽：</b>
 * <ol>
 *   <li><b>成功路径不放</b>（S8 实测）→ 一次成功的重建也留下 30 分钟幽灵锁，
 *       它<b>正好卡死启动自愈</b>（{@code IndexInitializer} 探到索引为空 → 触发全量
 *       → 拿不到锁 → {@code skipped=true}），系统停在<b>一个索引都没有</b>的状态里走 LIKE 降级。
 *       ★ S8 只修了这一条。</li>
 *   <li><b>失败路径不放</b>（S9 实测）→ 停 content 造一次必然失败的重建，
 *       再修好重试，拿到的是 {@code skipped=true}，而它的成因被写成
 *       「另一个重建正在跑」—— <b>一句假话</b>，且没人知道要等 30 分钟。
 *       ★ 这才让「失败也放」被补上。</li>
 * </ol>
 * <p>完整的两次现场见 {@link #UNLOCK_SCRIPT} 与 {@link #rebuildAll}。
 *
 * <p>★ 判据是「<b>锁的寿命要对齐它保护的东西的寿命</b>」：锁要防的是
 * 「A 在灌、B 把索引删了」，而失败时半成品新索引已被删、别名没切、
 * 旧索引原样在用 —— <b>它保护的东西一件都不剩了。</b>
 * ★ 唯一仍然靠 TTL 兜底的是<b>进程被 kill -9</b>（{@code finally} 不执行），
 * 那条路本来就只能等 TTL，而它也依旧与「跑完了」长得不一样。
 *
 * <h3>★★ 拿不到锁 = 本轮什么都不做，方向是刻意选的</h3>
 *
 * <p>两种成因（另一个实例在跑 / 上一轮的锁还没过期）处置相同，都是<b>直接返回</b>。
 * ★ 对<b>定时</b>触发：晚 6 小时自愈，可以接受（这条任务本来就是兜底，不差一轮）。
 * ★★ 对<b>管理端点</b>触发的：调用方拿到的是 {@code skipped=true}，
 * 而<b>绝不能把这个显示成「重建成功」</b> —— 见 {@link RebuildReport} 的注释。
 *
 * <h3>★★★ S8 的代价：重建期间到达的【增量】会随旧索引一起被删掉</h3>
 *
 * <p>别名切换消掉了「读出 0 条」这个坏状态，<b>但它消不掉一个写的缺口</b>，
 * 这一条必须写清楚，否则它看起来像是个没人想到的 bug：
 *
 * <pre>
 *   t0  重建开始，新索引建好，开始一页页读 content
 *   t1  ★ 某本书被改了 → MQ → 消费者写【别名】→ 落在【旧】索引上
 *   t2  重建跑完，别名切到新索引，旧索引被删
 *   →   新索引里那本书是 t0 时刻的样子，t1 那次改动【丢了】
 * </pre>
 *
 * <p>★ 丢失的窗口是<b>「重建本身的耗时」</b>，恢复有两条路：
 * 那本书<b>下次被改动</b>时（增量消息会重新带上它），或者<b>下一轮 6 小时的全量重建</b>。
 *
 * <p>★★ <b>为什么宁可留着这个缺口，也不做「重建期间双写」</b>：
 * 双写要在 Redis 里放一个「本轮重建的目标索引名」，让消费者每条消息都去读它、
 * 然后写两份 —— 那等于给<b>最热的那条路径</b>（增量消息）加一次 Redis 往返和一个新的失败面：
 * 那个 key 一旦泄漏（重建崩了没清），消费者就会<b>往一个没人指向的索引里写</b>，
 * 而且是静默的。★ 用一个「只有几秒、且有两条自动恢复路径」的小缺口，
 * 去换一个「静默写错地方」的新失败模式，不划算。
 * ★★ 这条判断的形状和 M4 那条一样：<b>「去重算一次」永远比「算好的结果」好维护</b> ——
 * 这里选的是「让下一条消息把真值重新算一遍」，而不是「小心翼翼地维护两份写入」。
 *
 * <p>★ 还有一层：<b>M4 时代这个缺口其实更大、只是没人算过</b> ——
 * 那时候整个重建窗口里检索都在降级（排序退回阅读量），
 * 而现在只是「重建期间被改的那几本书，索引旧了一点」。</p>
 *
 * <h3>★ 为什么这里可以放心用 {@code ContentClient}（Feign），而消费者那边要小心</h3>
 *
 * <p>两者都在 search 进程里、都调 content，区别在<b>调用发生在哪个线程</b>：
 * 消费者跑在 MQ 的消费线程上（出问题由重试阶梯兜），
 * 本类跑在定时任务线程 / 请求线程上。
 * ★ <b>定时任务线程上没有 {@code UserContext}</b>（没有请求）——
 * 所以这里调的对端端点必须是不依赖登录态的那种，
 * 而这正是 {@code /internal/**} 的前提（认证只靠共享密钥头，
 * 由 {@code com.novel.common.internal.FeignIdentityConfig} 带上 —— ★ M5 起它只有一份）。
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

    /**
     * 「如果锁是我的，就删掉它」的 Lua 脚本。★★★ S8 实测补上的那一步。
     *
     * <pre>
     *   KEYS[1] = novel:lock:search-index-rebuild   （锁本身，一个 String）
     *   ARGV[1] = INSTANCE_ID                       （本进程的 pid@hostname）
     *
     *   返回 1 = 锁是我的，已删
     *   返回 0 = 锁不是我的（早过期被别人拿了，或压根没锁）→ 什么都不做
     * </pre>
     *
     * <p>★★★ <b>为什么必须补这一步 —— 先把「不释放」的原始理由摆出来：
     * 它当时是对的，只是漏了一种触发路径。</b>
     *
     * <p>类注释原本写着「不主动释放（理由同 {@code NovelStatReconcileJob}）」，
     * 而那条理由说的是<b>释放的【做法】</b>要小心（不能裸 {@code DEL}），
     * 不是「释放这件事本身没必要」。当时的取舍是：**宁可留个幽灵锁，也不冒删错别人锁的风险。**
     *
     * <p>★★ <b>但这个取舍没有算过【启动自愈】这条路径，而 M5 实测把它撞出来了：</b>
     *
     * <pre>
     *   21:36:23  一次【成功】的重建跑完，别名切好 —— 锁没人删，留着
     *   21:39:21  重启 search：IndexInitializer 探到「别名不存在」→ 触发全量自愈
     *   21:39:21  ★ 拿不到锁（上一轮那把还在，TTL 30 分钟）→ skipped=true，灌入 0 条
     *   →         结果是 ES 里【一个索引都没有】，全站搜索走 LIKE 降级，
     *             直到 22:06 锁自己过期 —— 而这一轮自愈早就结束了，没人会再来看一眼
     * </pre>
     *
     * <p>★★ <b>也就是说：那个「幽灵锁」不是无害的，它正好卡死了唯一能自愈的那条路。</b>
     *
     * <p>★★★ <b>而 S8 之后，释放它已经不需要付出任何风险</b> —— 这正是本条能成立的原因：
     * <ul>
     *   <li>锁要保护的是「A 在灌、B 把索引删了」这个互相破坏。而<b>失败时</b>
     *       别名根本没切、旧索引原样还在（见 {@link IndexService#switchAlias} 的原子性），
     *       半成品新索引也被 {@code deleteIndexQuietly} 删掉了
     *       —— 下一个重建者与它<b>没有任何共享的可破坏对象</b>。</li>
     *   <li>★★ 所以<b>成功和失败都要释放</b>：成功是「新索引已就位、旧索引已摘除」，
     *       失败是「什么都不剩」—— <b>两种情况下锁保护的那个东西都已经不存在了。</b>
     *       ★ 一度只放了成功那条（S8），S9 实测立刻撞出代价：
     *       一次失败的重建让运维入口在接下来 30 分钟里只会返回
     *       {@code skipped=true}，而归因写的是「另一个重建正在跑」（见 {@link #rebuildAll}）。</li>
     * </ul>
     * ★★ 换句话说：<b>锁的寿命对齐到「它保护的那个东西的寿命」</b>，
     * 而不是拍一个 30 分钟。TTL 退化成纯粹的<b>崩溃兜底</b>（进程被 kill 时没人执行到这里）。
     *
     * <p>★ 而 {@code GET == ARGV[1]} 这个判断是<b>原子的</b>（Lua 在 Redis 里单线程执行），
     * 正好消掉类注释担心的那个竞态：TTL 刚过期、B 刚拿到锁、A 这会儿才走到释放 ——
     * 此时 {@code GET} 拿到的是 B 的 INSTANCE_ID，脚本返回 0，<b>B 的锁毫发无伤</b>。
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

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
         * ── 拿到锁之后：干活的整段包在 try/finally 里，放锁写在这里而不是在下面各条 return 上 ──
         *
         * ★★★ S9 实测把「只由成功路径放锁」这个写法推翻了。原文的理由是
         *   「失败路径放锁不是更保险，而是会把『上一轮跑崩了』和『上一轮跑完了』
         *     在下一个【人】眼里抹平 —— 而它们该有不同的后续」。
         *   ★ 这句话本身没错，错的是它把那个「人」放在了【锁】这个通道上。
         *   M5 之后那个人的通道是【任务记录】（{@code ReindexTaskStore}：
         *   FAILED + error / SUCCEEDED + skipped），锁上根本没有人去读这份区别。
         *
         * ★★ 而「不放」的代价是 S9 实测出来的，具体、可复现：
         *   停掉 content 提交一次重建 → 重建失败 → 锁被留下 30 分钟 →
         *   修好 content 再提交 → 拿到的是 {@code skipped=true}（本轮什么都没做），
         *   而它在文档和直觉里的成因写的是「另一个重建正在跑」—— 【那是一句假话】。
         *   管理员于是看到一个「成功但什么都没做」的结果，并且无从知道该等 30 分钟。
         *
         * ★ 判据仍然是 S8 自己那条「锁的寿命要对齐它保护的东西的寿命」，
         *   只是这一次它指向了【放】而不是【留】：锁保护的是「一个一致的就绪状态」
         *   与「没有第二个重建在动索引」，而失败时 —— 半成品新索引已被
         *   {@code deleteIndexQuietly} 删掉、别名没切、旧索引原样在用 ——
         *   ★ 那把锁保护的**什么都不剩了**。（M5 实测：失败后 ES 里仍然只有一个物理索引。）
         *   留着它买不到任何东西，只买到一个 30 分钟的运维盲区。
         *
         * ★ 而 S8 真正想留的那条边界并没有被抹掉：**进程被 kill -9 时 finally 不会执行**，
         *   那条路仍然只能等 TTL 到期 —— 也就是「跑崩了」依旧由 TTL 兜底、
         *   依旧与「跑完了」长得不一样（一个 taskId 停在 RUNNING 且没有 finishedAt）。
         */
        try {
            return doRebuild(startedAt);
        } finally {
            releaseLock();
        }
    }

    /**
     * <b>已经拿到锁之后真正干活的那一段。</b>拆出来只为让上一层的
     * 「拿锁 → try → finally 放锁」在源码里一眼看得见形状 ——
     * 也就是「这把锁的寿命 = 这个方法体的寿命」这句话不是注释里的承诺，是代码的结构。
     *
     * <p>★ 本方法<b>只管干活，不管锁</b>：它拿到的锁一定已经被 {@link #rebuildAll} 拿着了，
     * 而且无论从哪条路出去（正常 return / 抛异常）都会由上层放掉。
     */
    private RebuildReport doRebuild(long startedAt) {
        /*
         * ── 第二步：建一个【新的空物理索引】 ──
         *
         * ★★★ S8 之前这里做的是「把当前索引整个 DELETE 掉再建回来」，
         *   从那一刻到灌完为止，全站检索都拿不到索引。现在它只是【另起一个】，
         *   别名仍然指着旧索引 —— 也就是说从这一行到切换那一行之间，
         *   ★ 用户读到的始终是那个完好的旧索引。
         */
        String newIndex = indexService.newPhysicalIndexName();
        indexService.createPhysicalIndex(newIndex);

        /*
         * ── 第三步：灌数据（灌进【新索引】，不是别名） ──
         *
         * ★★ 灌失败时必须把【刚建出来的空索引】删掉再往外抛，
         *   否则它会作为一个没人指向的孤儿留在 ES 里。判据在 IndexService#deleteIndexQuietly
         *   的注释里，这里只留一句：
         *   ★ S8 之后「索引存在但空」这个坏状态【不可达】了 —— 空的只可能是这个新索引，
         *     而别名还没指过去，谁也读不到它。所以这里的清理是「扫地」，不是「止血」。
         *
         * ★ deleteIndexQuietly() 自己不外抛（见它的注释），所以这里的 catch 之后再 throw e
         *   抛出去的仍然是最初那个异常，不会被覆盖掉。
         */
        long indexed;
        try {
            indexed = refill(newIndex);
        } catch (RuntimeException e) {
            indexService.deleteIndexQuietly(newIndex);
            throw e;
        }

        /*
         * ── 第四步：切换别名 —— 这是整个 S8 的落点，也是唯一一次「让用户看到新数据」 ──
         *
         * ★★★ 条件是「灌进来了东西」【或】「当前根本没有可用的索引」。
         *   反过来读更清楚：**一条都没灌进来，而旧索引是有内容的 → 绝不切换。**
         *
         *   为什么必须留这个分支：refill 返回 0 有两种成因，而本类【分辨不出】：
         *     ① content 那边真的一本书都没有（干净库）—— 该切；
         *     ② 契约漂移 / 对端返回了空列表（真 bug）—— 切过去就是【把一个好好的索引
         *        换成一个空索引】，全站搜索从此静默返回 0 条。
         *   ★ M4 时代这个取舍做不了（旧索引早被 DELETE 掉了，「留住它」这个选项不存在），
         *     S8 之后做得了，所以必须做。★ 判据是「宁可这次重建白跑」——
         *     白跑的代价是「索引旧了几分钟」，误判的代价是「搜索静默空了」。
         *
         *   ★★ 而 ② 的现场要留着让人看：这里【故意不删】那个新索引以外的任何东西，
         *     并记一条 ERROR 说清「本次没有切换」。把空索引静默换上，
         *     症状会从「搜索返回 0」变成「搜索看起来正常但少了一半」——
         *     同样是坏的，但难查得多。
         */
        long elapsed = System.currentTimeMillis() - startedAt;

        if (indexed == 0 && indexService.aliasTargetsNonEmptyIndex()) {
            log.error("索引重建：一条都没灌进来，而当前索引是有内容的 —— 【本次不切换别名】，"
                            + "搜索继续用旧索引 | alias={} 新索引={}（已丢弃）耗时={}ms。"
                            + "★ 先确认 content 的作品表不是空的，再确认 /internal/novels/index-page "
                            + "返回的不是空列表",
                    indexService.indexName(), newIndex, elapsed);
            indexService.deleteIndexQuietly(newIndex);
            return new RebuildReport(false, 0L, elapsed);
        }

        indexService.switchAlias(newIndex);

        /*
         * ── 第五步：删旧索引（best-effort） ──
         *
         * ★★ 注意它在 switchAlias 之后、且【绝不影响成败】：
         *   此刻旧索引已经没有任何人指向它，删不掉只是占空间。
         *   ★ 让一次「删旧索引失败」把一次已经成功的重建报成失败，
         *     会让管理员以为索引没切过去 —— 而事实正好相反。
         * ★ 它顺带清掉历史遗留的孤儿索引（上次重建崩了留下的），
         *   所以 ES 里不会慢慢攒下一堆没人指向的 novel_index_v*。
         */
        indexService.deleteStaleIndices(newIndex);

        if (indexed == 0) {
            /*
             * ★ 走到这里说明「一条都没灌进来，且当前没有可用的旧索引」——
             *   也就是【首次部署在一张空库上】。索引确实是空的，而那是【对的】。
             *   ★ 仍然记 ERROR：另一个成因是 ② 契约漂移，而首次部署这个解释
             *     只有在「库确实是空的」时才成立 —— 那句话是留给人的第一个检查动作。
             */
            log.error("索引重建完成，但一条都没灌进去 —— 全站搜索将走降级路径（MySQL LIKE）"
                            + " | alias={} 新索引={} 耗时={}ms。★ 先确认 content 的作品表不是空的，"
                            + "再确认 /internal/novels/index-page 返回的不是空列表",
                    indexService.indexName(), newIndex, elapsed);
        } else {
            log.info("索引重建完成（已原子切换别名） | alias={} → {} 灌入={} 条 耗时={}ms",
                    indexService.indexName(), newIndex, indexed, elapsed);
        }

        /*
         * ── 第六步：交给上层放锁 ──
         *
         * ★★ 这里【不再】直接调 releaseLock()（S9 之前是）。
         *   放锁移到了 rebuildAll 的 finally 里，见那里对「为什么失败也放」的完整论证 ——
         *   一句话：锁保护的东西在失败时已经不存在了，留着它只买到一个 30 分钟的运维盲区。
         *   ★ 本方法只管「干完了记得 return」，锁的寿命由调用方的结构保证。
         */
        return new RebuildReport(false, indexed, elapsed);
    }

    /**
     * 放锁。<b>compare-and-delete，不是裸 {@code DEL}</b> —— 理由见 {@link #UNLOCK_SCRIPT}。
     *
     * <p>★ 整个方法<b>不抛异常</b>。它现在由 {@code finally} 调用，所以「不抛」有了
     * <b>第二条、也更硬的理由</b>：在失败路径上，抛出去会把<b>最初那个业务异常整个替换掉</b>
     * （{@code try} 里正在往外冒的那个），日志里的人和轮询到的 error 都会看到
     * 「删锁失败」而不是「content 连不上」—— 一个纯副作用的清理动作把主因盖掉了。
     * <p>★ 而在成功路径上的理由不变：一个「锁没删掉」不该把一次成功的重建报成失败
     * （那个后果是管理员以为索引没切，而事实正好相反 —— 与 {@code deleteStaleIndices} 同一条判据）。
     * <p>★ 代价说清楚：删不掉 = 退化成这条修复之前的行为（TTL 到期自动消失），<b>不是错，只是旧</b>。
     */
    private void releaseLock() {
        try {
            Long released = stringRedisTemplate.execute(
                    UNLOCK_SCRIPT, List.of(LOCK_KEY), INSTANCE_ID);
            if (Long.valueOf(1L).equals(released)) {
                log.info("索引重建：已释放锁（compare-and-delete） | lock={}", LOCK_KEY);
            } else {
                /*
                 * ★ 返回 0 不是错误，但【值得记一笔】：它说明本次执行超过了 TTL(30 分钟)，
                 *   锁早已过期并被别人拿走 —— 也就是说同一时刻可能有第二个重建在跑。
                 *   这正是锁本该防住的事，所以要留下痕迹（记 WARN，不抛）。
                 */
                log.warn("索引重建：锁已不是本实例的，未删除（本次执行可能超过了 TTL={}） | lock={}",
                        LOCK_TTL, LOCK_KEY);
            }
        } catch (Exception e) {
            log.warn("索引重建：释放锁失败，将等 TTL 到期自动消失（不影响本次重建结果） | lock={} | {}: {}",
                    LOCK_KEY, e.getClass().getSimpleName(), e.getMessage());
        }
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
     *
     * @param target 目标物理索引名。★ <b>是它而不是别名</b> —— 别名这时候还指着旧索引，
     *               灌到别名上等于「一边重建一边往生产索引里掺半成品」。
     */
    private long refill(String target) {
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
            indexService.bulkUpsert(page.stream().map(NovelDocument::from).toList(), target);
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
