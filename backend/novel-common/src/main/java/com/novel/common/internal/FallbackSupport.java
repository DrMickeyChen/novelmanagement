package com.novel.common.internal;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.novel.common.result.Result;
import feign.FeignException;
import feign.RetryableException;
import lombok.extern.slf4j.Slf4j;

/**
 * <b>跨服务调用失败时的统一归因（M5）。</b>
 *
 * <p>它只回答一个问题：<b>这一次跨服务调用，是因为什么挂的？</b>
 * 五个 Feign 客户端的 {@code fallbackFactory} 都走这里，逻辑只有一份。
 *
 * <h3>★★★ 为什么这件事需要单独一个类 —— 「失败」不是一个形状</h3>
 *
 * <p>Feign 抛出来的异常至少有四种，而它们的<b>处置完全不同</b>：
 *
 * <table border="1">
 *   <caption>四种失败</caption>
 *   <tr><th>异常</th><th>发生了什么</th><th>该往哪查</th></tr>
 *   <tr>
 *     <td>{@link BlockException}（含 {@code DegradeException}）</td>
 *     <td>Sentinel 把它挡下来了 —— <b>请求根本没发出去</b></td>
 *     <td>熔断器开了；查下游是不是一直在慢/错，<b>不是</b>查网络</td>
 *   </tr>
 *   <tr>
 *     <td>{@link RetryableException}</td>
 *     <td>发出去没回来：连不上、或超过 {@code read-timeout}</td>
 *     <td>下游进程在不在、是不是被拖慢了</td>
 *   </tr>
 *   <tr>
 *     <td>{@link FeignException}（非 Retryable）</td>
 *     <td>回来了，但对端返回非 2xx</td>
 *     <td>对端自己的日志 —— 这是一次<b>成功的</b>网络往返</td>
 *   </tr>
 *   <tr>
 *     <td>★★ {@code FeignException$ServiceUnavailable}（503，且消息里带
 *         {@code Load balancer does not contain an instance}）</td>
 *     <td><b>请求根本没发出去</b> —— 注册中心里没有可用实例</td>
 *     <td>服务停了吗、注册上了吗。<b>不是</b>对端返回了 503</td>
 *   </tr>
 * </table>
 *
 * <h3>★★★ 最后那一行是实测踩出来的，它差点骗过我（S3）</h3>
 *
 * <p>「停掉 auth」这一个状态，在日志里会先后长出<b>两种完全不同的归因</b>，
 * 而它们<b>不是两种故障</b>，是<b>同一个故障的两个时刻</b>：
 *
 * <table border="1">
 *   <caption>停掉下游之后，归因随时间变化</caption>
 *   <tr><th>时刻</th><th>Nacos 里</th><th>实际发生</th><th>异常</th></tr>
 *   <tr>
 *     <td>刚停的几十秒内</td><td>实例<b>还没摘掉</b></td>
 *     <td>按注册的地址连过去，被拒</td><td>{@code RetryableException}</td>
 *   </tr>
 *   <tr>
 *     <td>心跳超时之后</td><td>实例<b>已摘掉</b></td>
 *     <td><b>一个字节都没发</b>，负载均衡器直接判失败</td>
 *     <td>{@code FeignException$ServiceUnavailable}</td>
 *   </tr>
 * </table>
 *
 * <p>★★ 而第二种的形状<b>特别容易读错</b>：Spring Cloud OpenFeign 在
 * 「负载均衡器里没有实例」时，会<b>合成一个 503 的 {@code Response}</b>
 * 交给 Feign 的 {@code ErrorDecoder}，于是它长得和「对端真的返回了 503」
 * 一模一样 —— 连状态码都合理地是 503。照原样打出去就是
 * <b>「下游返回了非 2xx（HTTP 503）」</b>，而那一句是<b>反的</b>：
 * 没有任何下游参与这次交互。
 *
 * <p>★ 所以下面专门为它分了一支。判据只能靠消息文本（那段英文是
 * {@code FeignBlockingLoadBalancerClient} 拼进 reason 里的），
 * 因为 {@code novel-common} 的 classpath 上<b>没有</b>那个类
 * （{@code spring-cloud-starter-openfeign} 在这里是 optional），
 * 引用不到它的常量。<b>别为此把这个类提到 novel-common 去</b> ——
 * 那等于给所有服务的 classpath 上都塞一份 Feign。
 *
 * <p>★ 光打一句「跨服务调用失败」是不够的：<b>这几种情况的排查方向完全相反</b>
 * （一个去查熔断统计、一个去查进程、一个去查注册中心、一个去查对端日志）。
 * 日志里写错归因，会把人带到错误的地方去 —— M4 已经有过一次实例：
 * {@code SearchService} 的降级日志把「content 挂了」说成了「ES 检索失败」。
 *
 * <h3>★ 为什么不按「根因」分类，而是沿 cause 链找第一个认得的</h3>
 *
 * <p>{@code RetryableException} 的 cause 是 {@code ConnectException} /
 * {@code SocketTimeoutException}，而 {@code BlockException}
 * <b>根本没有 cause</b>。要是先取到链尾再判断，
 * 第一种会被降级成「一个不认识的 IOException」，第二种则什么都取不到。
 * 沿链找第一个认得的最稳：越外层的包装越能说明「这一次调用出了什么事」。
 *
 * @author MickeyChen
 */
@Slf4j
public final class FallbackSupport {

    /**
     * 沿 cause 链最多看这么深，看穿就放弃、退化成「类名 + message」。
     *
     * <p>★ 为什么要有上限：这是<b>出错路径</b>上的代码，而它跑在
     * 「已经出了别的事」的时候。一个病态的深链（层层包装的代理很容易造出来）
     * 会让它自己变成第二个故障点 —— 那时候日志里就只剩这个类自己的栈了，
     * 真正的原因反而看不到。
     * <p>★ 自引用的链（{@code t.getCause() == t}）由 {@link #nextCause} 单独挡掉，
     * 不靠这个常数 —— 两件事分开管，各自的判据才说得清。
     * <p>★ 实测里的链只有 1~2 层，5 是留足余量的边界，不是调优出来的值。
     */
    private static final int MAX_CAUSE_DEPTH = 5;

    private FallbackSupport() {
    }

    /**
     * <b>★ 记一笔，然后把原始异常【原样】抛回去 —— 这一档【不降级】。</b>
     *
     * <p>★ 它有一段历史，值得留着：S3 引入 Sentinel 时，<b>所有</b>
     * fallbackFactory 走的都是这个方法，为的是让那一步<b>不改变任何行为</b> ——
     * 装上 fallbackFactory 之后，调用方拿到的异常、HTTP 状态码、响应体
     * 都必须和装之前一模一样，这样才能用「32 端点快照 0 差异」证明
     * 引入 Sentinel 没弄坏东西。
     * <p>★★ S5 把<b>可降级</b>的那些换成了 {@link #logThenDegrade}，
     * 于是这个方法<b>只剩下一条接缝</b>：{@code userIdsByNickname}
     * （它返回的是搜索的<b>筛选输入</b>，降级等于伪造一个「没有找到相关作品」——
     * 判据见 {@link #logThenDegrade} 与 M5 文档判断 1）。
     *
     * <p><b>★★ 原样抛，而不是包一层 {@code RuntimeException}：</b>
     * {@code SentinelInvocationHandler} 拿到 fallback 方法抛出的异常后
     * 执行的是 {@code throw e.getCause()}，所以<b>包一层就会改变调用方
     * 看到的异常类型</b>。虽然响应体不会变（兜底的
     * {@code @ExceptionHandler(Exception.class)} 一律返回泛化的
     * {@code SYSTEM_ERROR}），但日志里的栈会多出一层毫无信息量的包装。
     * 降级日志本来就是这个阶段唯一要的东西，别把它弄脏。
     *
     * <p><b>★★ 为什么是 {@code create()} 里抛，而不是返回一个「方法里抛」的实现：</b>
     * {@code fallbackFactory.create(ex)} 是在 {@code try} 里被调用的，
     * 而那个 {@code try} 只 catch {@code IllegalAccessException} /
     * {@code InvocationTargetException}（它俩只有反射调用才会抛）——
     * 从 {@code create()} 里抛出的异常<b>会原样穿出去</b>。
     *
     * <p>★ 这依赖 SCA 源码里的一个细节，所以它<b>必须被实测</b>，
     * 不能只靠读代码下结论：判据是「停掉下游 → 这条日志出现 + 响应仍是 500」。
     * 阴性对照是「把开关写成错误的前缀 → 这条日志不出现、响应仍是 500」——
     * 两者响应相同、只有日志不同，<b>正好把『日志』单独隔出来验</b>。
     *
     * @param call  这次调用是干什么的。★ 必须写成人话（「取作者昵称」），
     *              因为排查的人要先知道「哪件事没做成」才看得懂原因
     * @param cause Feign 抛出来的异常
     * @return 永不返回（永远抛）。返回值类型存在只是为了让调用处
     *         {@code return FallbackSupport.logThenRethrow(...);} 能编译
     */
    public static <T> T logThenRethrow(String call, Throwable cause) {
        log.error("跨服务调用失败 | {} | {}", call, describe(cause), cause);
        throw sneakyThrow(cause);
    }

    /**
     * <b>★ S5：记一笔，然后返回一个【成功的空信封】—— 这就是降级。</b>
     *
     * <p>和 {@link #logThenRethrow} 是一对，判据只有一条（M5 文档判断 1）：
     * <blockquote>
     * <b>降级只对「缺失在界面上【看得见】」的字段成立；
     * 对「参与筛选的输入」一律不降级。</b>
     * </blockquote>
     * 同样是失败、同样是一个空值，`nicknamesOf` 降级后每张卡片的作者名位置
     * 显示成 {@code —}（一眼可辨），而 {@code userIdsByNickname} 降级后
     * 页面说的是「<b>没有找到相关作品</b>」—— 和「这个作者真的没有作品」
     * 一模一样。后者正是 M1 那句「宁可 500，也不要一个看起来正常但内容是错的页面」。
     *
     * <h3>★★★ 为什么返回类型是 {@code Result<T>} 而不是 {@code T}</h3>
     *
     * <p>因为降级物<b>不能是裸 {@code null}</b>。内部接口一律返回统一信封，
     * 而调用方是拿 {@link InternalResults#unwrap} 拆的 —— 裸 {@code null}
     * 会被 {@code unwrap} 判成「对端返回了空 body」，记一条 error
     * 然后抛 {@code SYSTEM_ERROR}。<b>降级就白做了，而且多出一条误导性的 ERROR。</b>
     * 必须要给的是 {@code Result.success(null)}，也就是
     * 「对端成功地告诉我：这个值没有」。
     *
     * <p>这个区别<b>没有任何编译期保护</b>，两种写法都能编译、都能跑，
     * 差别只在运行时一个是 200 + 破折号、一个是 500。所以它<b>由签名来兜</b>：
     * 让调用方拿到的是信封，<b>「返回裸 null」这件事在这里根本写不出来</b>。
     *
     * <p>★ 记 {@code WARN} 而不是 {@code ERROR}（{@link #logThenRethrow} 记的是 error）：
     * 这一档是<b>已经处置过的</b>降级，请求会正常返回 200 —— 不是系统故障，
     * 记 error 会让真正的 5xx 被淹掉。★ 但<b>仍然带上异常</b>：
     * 降级日志是这条链路唯一的痕迹，去掉栈就只剩一句「挂了」。
     *
     * @param call     这次调用是干什么的。<b>要写成人话，且带上服务方向</b>
     *                 （「取评论作者昵称（interaction → auth）」）——
     *                 排查的人要先知道「哪件事没做成」才看得懂原因
     * @param cause    Feign 抛出来的异常
     * @param degraded 降级值。★ 只允许 {@code null} 或<b>空集合</b>，
     *                 绝不能是 {@code 0} / {@code false} / {@code ""}
     *                 —— 那些是「看起来正常」的假值，比空值更坏
     *                 （见 {@code ShelfStatusVO#onShelf} 的注释：
     *                 「布尔字段一不小心漏填，表现正好是看起来很正常」）
     * @return {@code Result.success(degraded)} —— 一个成功的空信封
     */
    public static <T> Result<T> logThenDegrade(String call, Throwable cause, T degraded) {
        log.warn("跨服务调用失败，改用降级值 | {} | {} | 降级值 = {}", call, describe(cause), degraded, cause);
        return Result.success(degraded);
    }

    /**
     * 把一次失败归成一句人话。<b>只有这一个类知道「什么异常叫什么名字」。</b>
     */
    public static String describe(Throwable cause) {
        if (cause == null) {
            return "没有异常（不该发生）";
        }
        int depth = 0;
        for (Throwable t = cause; t != null && depth < MAX_CAUSE_DEPTH; t = nextCause(t), depth++) {
            if (BlockException.isBlockException(t)) {
                /*
                 * ★ 走到这里说明这次调用【一个字节都没发出去】。
                 *   和下面两种的区别是根本性的：不是下游有问题，是我们主动不发。
                 */
                return "被 Sentinel 拦下（熔断已打开，请求没有发出）";
            }
            if (t instanceof RetryableException) {
                return "下游不可达或响应超时（请求发出去了，没等到结果）";
            }
            if (t instanceof FeignException feignException) {
                int status = feignException.status();
                /*
                 * ★★ 特例，必须排在通用的「非 2xx」之前（理由见类注释那一节）：
                 *   负载均衡器里没有可用实例时，SCA 合成一个 503 Response，
                 *   于是这里拿到的是一个「长得像对端返回 503」的异常，
                 *   而实际上【一个字节都没发出】。
                 *   照原样说成「下游返回了非 2xx」是【归因反了】——
                 *   这句话会把人派去查对端日志，而对端根本没醒着。
                 */
                if (status == 503 && isLoadBalancerMiss(feignException)) {
                    return "注册中心里没有可用实例（请求没有发出 —— 下游多半刚停，或没起来）";
                }
                /*
                 * ★ 注意 status() 可能是 -1（比如响应体读不出来）。
                 *   那不是「HTTP -1」，是「连状态码都没拿到」——
                 *   所以单独说一句，别让它看起来像一个合法的状态码。
                 */
                return status > 0
                        ? "下游返回了非 2xx（HTTP " + status + "）"
                        : "下游返回了无法解析的响应（没拿到状态码）";
            }
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    /**
     * 这一次 503 是不是「负载均衡器里没有实例」合成的那个，而不是对端真返回的。
     *
     * <p>★ 只能靠消息文本判断：那段英文是
     * {@code FeignBlockingLoadBalancerClient} 拼进 {@code Response.reason} 的，
     * 而那个类不在本模块的 classpath 上（{@code novel-common} 里的
     * {@code spring-cloud-starter-openfeign} 是 optional），取不到它的常量。
     * <p>★ 判据的来源是实测：{@code FeignException$ServiceUnavailable: [503] during
     * [POST] to [http://novel-auth/internal/users/nicknames] ...:
     * [Load balancer does not contain an instance for the service novel-auth]}。
     * <p>★ 为什么不干脆把所有 503 都归成这一类：<b>对端真的返回 503 是存在的</b>
     * （网关路由不到时就返 503），把它说成「请求没有发出」同样是反的归因，
     * 只是把错误换了个方向。多写一个字符串常量，换掉一整类误报。
     */
    private static boolean isLoadBalancerMiss(FeignException feignException) {
        String message = feignException.getMessage();
        return message != null && message.contains("Load balancer does not contain an instance");
    }

    /** 下一层 cause。★ 自引用的 cause 链（{@code t.getCause() == t}）要当成到头。 */
    private static Throwable nextCause(Throwable t) {
        Throwable cause = t.getCause();
        return cause == null || cause == t ? null : cause;
    }

    /**
     * <b>★★ 原样抛出一个 {@code Throwable}，包括受检异常。</b>
     *
     * <p>为什么需要它：{@link BlockException} <b>是受检异常</b>
     * （它 {@code extends Exception}），而 Feign 接口的方法签名里
     * 不会声明它。不绕过编译器的检查，就只能包一层 —— 而包一层
     * 正是上面那段要避免的事。
     *
     * <p>★ 这个手法依赖泛型擦除：{@code T} 在字节码里就是 {@code Throwable}，
     * 方法末尾的 {@code throws} 子句是编译期概念，运行时没有对应检查。
     * 调用方看不到任何变化，栈里也不会多出这一帧。
     */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneakyThrow(Throwable cause) throws T {
        throw (T) cause;
    }
}
