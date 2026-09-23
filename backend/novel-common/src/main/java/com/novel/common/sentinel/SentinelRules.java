package com.novel.common.sentinel;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.CircuitBreaker;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.CircuitBreakerStateChangeObserver;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.EventObserverRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <b>熔断规则的唯一加载点（M5）。</b>
 *
 * <p>三个有出站边的服务（content / interaction / search）在启动时各调一次
 * {@link #loadSlowCallRules}，把自己那几条资源名交进来。阈值只有一份，
 * 写在下面那四个常量里。
 *
 * <h3>★★★ 资源名不是我们起的 —— 它是 SCA 拼出来的，必须按它的公式写</h3>
 *
 * <p>{@code spring-cloud-starter-alibaba-sentinel} 的
 * {@code SentinelInvocationHandler} 在每次 Feign 调用前执行
 * {@code SphU.entry(resourceName, EntryType.OUT, 1, args)}，而 resourceName 是：
 *
 * <pre>
 *   resourceName = 方法的大写 HTTP 动词 + ":" + hardCodedTarget.url() + 方法上的 path
 *
 *   例：AuthUserClient（name="novel-auth", path="/internal"）
 *       上的 @PostMapping("/users/nicknames")
 *       → "POST:http://novel-auth/internal/users/nicknames"
 *
 *   而 search 的 ContentClient【没有写 path】——
 *       → "POST:http://novel-content/internal/novels/reader-vos"
 *         （方法上的 @PostMapping 自己带了 /internal，所以看起来一样，
 *           但两段字符串的来源完全不同：一个是类的 path，一个是方法的 path）
 * </pre>
 *
 * <p>★ 其中 {@code hardCodedTarget.url()} 在「只写了 name、没写 url」时是
 * <b>{@code "http://" + name + cleanPath()}</b> —— 这也是为什么资源名里带着
 * 服务名，而不是一个真实可解析的地址。
 *
 * <p><b>★★ 名字写错一个字会怎样：规则照常加载、不报任何错、永远不触发。</b>
 * 这是一条<b>不会失败的护栏</b>（铁律 8），而且它和「规则生效了、只是下游
 * 一直很健康」在日志里<b>长得一模一样</b>。所以每个服务那几张名单
 * <b>不是抄来的，是量出来的</b>，量法见下。
 *
 * <h3>★★★ 怎么证明名单是对的 —— 两条都不需要写生产代码</h3>
 *
 * <p>starter 会拖进 {@code sentinel-transport-simple-http}，每个服务因此多监听
 * 一个端口（<b>8719 起、按启动顺序抢，不是固定映射</b>，见 R13）。那个端口就是
 * Sentinel dashboard 读数据用的通道，<b>它本身是一组可 curl 的接口</b>：
 *
 * <table border="1">
 *   <caption>三个用得上的</caption>
 *   <tr><th>命令</th><th>回答什么</th></tr>
 *   <tr><td>{@code GET /getRules?type=degrade}</td>
 *       <td>★ 规则<b>进 JVM 了没有</b> —— 空数组和「规则生效了」在没有故障时长得一样，
 *           这是唯一能把它们分开的东西</td></tr>
 *   <tr><td>{@code GET /tree}</td>
 *       <td>★★ 所有<b>走过的</b>资源名。名单里写了、但跑完全量回归后
 *           {@code /tree} 里没有的名字 = <b>死规则</b></td></tr>
 *   <tr><td>{@code GET /version}</td><td>1.8.8（对得上 BOM 里那个 {@code sentinel.version}）</td></tr>
 * </table>
 *
 * <p>★ 还有一条更直接的探针：给某个资源名注册一条 {@code count=0} 的<b>流控</b>规则
 * （任何请求必被拦），打一次那个接口 —— <b>被拦 = 名字对</b>。
 * 它便宜到可以逐条验，而且用的是和熔断完全相同的「按资源名索引」机制。
 * ★ 用流控而不是熔断来探，是因为熔断要先攒够失败才开，探针会变慢；流控是即时的。
 *
 * <h3>★★★ 为什么三个字段必须显式写 —— 少写一个的表现完全不同</h3>
 *
 * <table border="1">
 *   <caption>{@code DegradeRule} 的默认值，以及不写会怎样</caption>
 *   <tr><th>字段</th><th>不写时的默认值</th><th>症状</th></tr>
 *   <tr><td>{@code timeWindow}</td><td><b>0</b></td>
 *       <td>一眼能看出不对：熔断立刻结束，等于没开</td></tr>
 *   <tr><td>{@code count}</td><td><b>0</b></td>
 *       <td>同上，也是 0，也是「等于没开」</td></tr>
 *   <tr><td>{@code slowRatioThreshold}</td><td>★★ <b>1.0（=100%）</b></td>
 *       <td>★★★ <b>看不出来</b>：慢调用比例要 <b>&gt; 100%</b> 才打开，
 *           而比例永远不可能超过 1.0 → <b>这条规则永远不会触发</b>，
 *           但它<b>加载成功、{@code /getRules} 里查得到、日志里也打了</b>。
 *           ★ 官方文档里这一格是<b>空的</b>，只有源码写了 1.0 ——
 *           照着文档配的人一定踩，所以这条是本项目 R15</td></tr>
 * </table>
 *
 * <p>★ 还有一个默认值是<b>故意保留</b>的：{@code minRequestAmount = 5}，
 * 而判据是「请求数<b>严格大于</b>」→ 统计窗口内<b>至少 6 次</b>。
 * 夹具按 10 次设计（留余量），而不是按 5 次。
 *
 * <h3>★ 阈值为什么是这几个数</h3>
 *
 * <p>{@code read-timeout} 是 <b>5000ms</b>（三个服务的
 * {@code spring.cloud.openfeign.client.config.default}）。所以：
 * <ul>
 *   <li>{@link #SLOW_RT_MS} = <b>500</b> —— 要落在「本机正常 10ms」之上、
 *       「超时 5000ms」之下。<b>超过 read-timeout 就不是慢，是抛异常</b>，
 *       那种失败喂的是<b>异常比例</b>，不是慢调用比例；
 *   <li>{@link #SLOW_RATIO_THRESHOLD} = <b>0.5</b> —— 见上表，不写是 1.0；
 *   <li>{@link #TIME_WINDOW_SECONDS} = <b>10</b> —— 熔断打开后维持 10 秒，
 *       再进入半开探测。太短则抖动期反复开合，太长则下游恢复了还一直快速失败；
 *   <li>{@link #MIN_REQUEST_AMOUNT} = <b>5</b> —— 框架默认值，显式写出来
 *       是因为上面那条「严格大于」的坑必须写在代码里才有人看得见。
 * </ul>
 *
 * <p><b>★ 统计窗口是 {@code statIntervalMs}（默认 1000ms），这一点决定了
 * 「哪些接缝适合配熔断」：</b>调用频次低于每秒几次的出站边（对账任务、
 * 按作者名搜索的降级路径）<b>几乎攒不满 6 次</b>，规则会在那里一直「上膛但不击发」。
 * ★ 那<b>不是</b>死规则（名字是对的、{@code /tree} 里查得到），
 * 只是统计窗口决定的 —— 别把两者混成一句话。
 *
 * <h3>★★ 为什么还要注册一个状态变化观察者</h3>
 *
 * <p>{@code fallbackFactory} 里能拿到 {@code BlockException}，于是能回答
 * <b>「这一次为什么降级」</b>。但它<b>回答不了「熔断器什么时候开的、什么时候恢复的」</b>——
 * 那两个时刻在 fallback 之外，没有任何一次调用能代表它们。
 *
 * <p>★ 而「打开 → 半开 → 恢复」这件事必须在<b>同一个进程</b>里被观测到，
 * 不能靠重启（重启会把统计清零，于是「恢复了」和「重新开始」分不开）。
 * {@code EventObserverRegistry} 就是为这个存在的第二条通道。
 *
 * <p>★ 拿得到的：{@code prevState / newState / rule / snapshotValue}。
 * <b>拿不到的</b>：这次降级的堆栈（{@code DegradeException.fillInStackTrace()}
 * 返回 {@code this}，它<b>没有堆栈</b>）。
 *
 * @author MickeyChen
 */
@Slf4j
public final class SentinelRules {

    /**
     * 慢调用判定线（毫秒）。★ 参考系是 <b>{@code read-timeout: 5000}</b> —— 必须明显小于它。
     */
    public static final double SLOW_RT_MS = 500;

    /**
     * 慢调用比例阈值。<b>★★ 这个数不写就是 1.0 = 永远不会触发</b>，见类注释那张表。
     */
    public static final double SLOW_RATIO_THRESHOLD = 0.5;

    /** 熔断打开的持续时间（<b>秒</b>，不是毫秒）。★ 不写是 0。 */
    public static final int TIME_WINDOW_SECONDS = 10;

    /** 统计窗口内至少要有的请求数。<b>★ 判据是「严格大于」，所以真正需要的是 6 次。</b> */
    public static final int MIN_REQUEST_AMOUNT = 5;

    /**
     * 观察者只注册一次。
     *
     * <p>★ {@code EventObserverRegistry} 是<b>进程内单例</b>，按名字覆盖 ——
     * 重复注册不会报错，只会让同一个事件被打印两遍。用一个 flag 挡住，
     * 比让日志里出现双份「熔断器状态变化」便宜得多。
     */
    private static final AtomicBoolean OBSERVER_REGISTERED = new AtomicBoolean(false);

    private SentinelRules() {
    }

    /**
     * 给一组资源名装上「慢调用比例」熔断，并把名单打进启动日志。
     *
     * <p><b>★ 必须在任何请求进来之前调用</b>（在各服务的 {@code @PostConstruct} 里），
     * 否则第一个请求会以「无规则」的状态跑掉。
     *
     * <p>★ 调用是<b>幂等覆盖</b>的：{@code DegradeRuleManager.loadRules} 是整份替换。
     * 所以<b>一个 JVM 里只能有一个地方调它</b> —— 两处各调一次的话，
     * 后调的那次会把前一次的名单<b>整个抹掉</b>，而且不报错。
     *
     * @param owner     谁在加载（只用于日志，写服务名）
     * @param resources 资源名。★★ <b>按 SCA 的公式拼，见类注释</b>；
     *                  这里不做任何校验 —— 名字对不对只能靠 {@code /tree} 和探针去量
     */
    public static void loadSlowCallRules(String owner, String... resources) {
        List<DegradeRule> rules = new ArrayList<>(resources.length);
        for (String resource : resources) {
            rules.add(new DegradeRule(resource)
                    .setGrade(RuleConstant.DEGRADE_GRADE_RT)
                    .setCount(SLOW_RT_MS)
                    .setSlowRatioThreshold(SLOW_RATIO_THRESHOLD)
                    .setTimeWindow(TIME_WINDOW_SECONDS)
                    .setMinRequestAmount(MIN_REQUEST_AMOUNT));
        }
        DegradeRuleManager.loadRules(rules);
        registerStateChangeObserver(owner);

        /*
         * ★★ 逐条打印，而不是只打一个总数。
         *
         *   「装了 N 条规则」这句话在【N 条里有一条名字写错】时同样成立，
         *   而排查的人需要的是「到底装了哪几条」，好拿去和 /tree 对照。
         *   多打这几行的成本，远小于「规则不生效但不知道从哪查起」。
         */
        log.info("已加载熔断规则 | 服务={} | 条数={} | 判定线={}ms | 慢调用比例阈={} | 窗口={}s | 最少请求={}（实际需≥{}次）",
                owner, rules.size(), (long) SLOW_RT_MS, SLOW_RATIO_THRESHOLD,
                TIME_WINDOW_SECONDS, MIN_REQUEST_AMOUNT, MIN_REQUEST_AMOUNT + 1);
        for (DegradeRule rule : rules) {
            log.info("  · {}", rule.getResource());
        }
    }

    /**
     * <b>熔断器状态变化的日志通道。</b>
     *
     * <p>打到 WARN：状态变化本身就是「下游出事了」的信号，
     * 而 INFO 在正常运行期是一堆噪音，很容易被人调高日志级别后一起静音掉。
     *
     * <p>★ {@code snapshotValue} 在慢调用比例模式下是<b>当前的慢调用比例</b>，
     * 它和规则里的 {@link #SLOW_RATIO_THRESHOLD} 一对比，
     * 就能判断这次是「刚好压线」还是「远远超标」。
     */
    private static void registerStateChangeObserver(String owner) {
        if (!OBSERVER_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        EventObserverRegistry.getInstance().addStateChangeObserver(owner,
                new CircuitBreakerStateChangeObserver() {
                    @Override
                    public void onStateChange(CircuitBreaker.State prevState,
                                              CircuitBreaker.State newState,
                                              DegradeRule rule,
                                              Double snapshotValue) {
                        /*
                         * ★ 把资源名放在这一行里：观察者是按服务注册的（一个服务一个名字），
                         *   而一个服务有多条规则 —— 不打资源名就分不清是哪条边开了。
                         */
                        log.warn("熔断器状态变化 | {} | {} → {} | 慢调用比例={}（阈值 {}）",
                                rule.getResource(), prevState, newState,
                                snapshotValue, rule.getSlowRatioThreshold());
                    }
                });
    }

    /**
     * 当前生效的熔断规则条数。<b>只给排查用</b>（比如临时在日志里对一下
     * {@code /getRules} 的返回），业务代码不要拿它做判断。
     */
    public static int loadedRuleCount() {
        return DegradeRuleManager.getRules().size();
    }
}
