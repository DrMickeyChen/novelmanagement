package com.novel.common.trace;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * <b>M5 起，全项目「一次请求」的唯一标识 —— 头名、MDC 键名、生成规则都只在这里定义。</b>
 *
 * <p>为什么要有一个类来管这件事（而不是每个服务各写各的字符串）：
 * 链路追踪的<b>全部价值</b>在于「同一个 id 出现在【多个进程】的日志里」——
 * 只要有一处把键名写成 {@code trace_id}、另一处写成 {@code traceId}，
 * 日志照样打、请求照样成功、接口照样 200，
 * <b>只有真正去 grep 的时候才发现串不起来</b>。
 * 这和 {@link com.novel.common.security.IdentityHeaders} 是同一个理由：
 * <b>能编码的东西就不该有第二份实现。</b>
 *
 * <h3>★ 32 位十六进制，不是随手选的</h3>
 *
 * <p>{@code UUID} 去掉横杠正好是 32 个十六进制字符，和 W3C Trace Context 的
 * {@code trace-id} 字段<b>同长度</b>。现在用不上那个标准（M5「已拍板」第 1 条：
 * 自建 traceId，不上 SkyWalking / OTel），但<b>格式先对齐</b> ——
 * 将来真要接，生成规则这一行不用动，要动的是「怎么把它塞进 {@code traceparent} 头」。
 * <p>★ 反过来，随手用 {@code yyyyMMddHHmmss} 加个序号也能用，
 * 但那是一条<b>走了就回不来的路</b>：日志解析、采样、跨语言传递全都要重来一遍。
 *
 * <h3>★★ 谁生成、谁采纳 —— 这个不对称是【刻意】的</h3>
 *
 * <table border="1">
 *   <caption>两条规则不一样，别顺手「统一」</caption>
 *   <tr><th>位置</th><th>收到 {@code X-Trace-Id} 时</th><th>为什么</th></tr>
 *   <tr><td>网关 {@code JwtAuthGlobalFilter}</td><td><b>剥掉，永远生成新的</b></td>
 *       <td>它面对的是<b>浏览器</b>。采纳一个客户端自带的 id，等于
 *           让任何人把<b>自己的请求关联到别人的日志上</b>。</td></tr>
 *   <tr><td>四个服务的 {@link TraceIdFilter}</td><td><b>采纳</b>（合法的话）</td>
 *       <td>它面对的是<b>网关，或兄弟服务的 Feign 调用</b>。
 *           不采纳 = 链路<b>在每个服务边界断掉</b> ——
 *           那就等于没做追踪，只是「每个进程各自编了个号」而已。</td></tr>
 * </table>
 *
 * <p>★ 严重性如实评：伪造 traceId <b>不是身份伪造</b>（身份头另有共享密钥把关，
 * 见 {@code IdentityHeaders}），最坏的结果是「日志里两段本该无关的记录被看成一串」。
 * 低危 —— 但<b>成本为零</b>，而且不做的话，「我们管理的请求头」这一组里
 * 会留下一处说不清理由的不对称。
 *
 * <h3>★ 为什么不放进 {@code IdentityHeaders}</h3>
 *
 * <p>那个类的契约是「{@code LoginUser} 的<b>编解码</b>」，它的 {@code ALL} 数组
 * 就是「要剥哪些头」的清单。往里塞一个和身份毫无关系的头，
 * 会让「{@code ALL} 里全是身份头」这个<b>一眼可核对</b>的判据失效。
 * <p><b>但它仍然在同一处被剥掉</b> —— 见
 * {@code JwtAuthGlobalFilter#forward} 里「先剥后写」那一块。
 * 「住在哪个类」和「在哪一行被剥」是两件事，不必也不该绑在一起。
 *
 * @author MickeyChen
 */
public final class TraceContext {

    /**
     * 请求头名。
     * <p>★ 由网关写、由下游读、由 Feign 拦截器透传 —— <b>三处必须用这一个常量。</b>
     */
    public static final String HEADER = "X-Trace-Id";

    /**
     * MDC 里的键名。★ 它必须和五个 {@code logging.pattern} 里的
     * {@code %X{traceId}} 逐字相同 —— 这里改一个字，日志里的那一格就永远是空的，
     * 而且<b>没有任何报错</b>（Logback 找不到键就打空字符串）。
     */
    public static final String MDC_KEY = "traceId";

    /**
     * 生成一个新的 traceId。
     * <p>★ 生成规则写在类注释里（32 位十六进制、与 W3C 对齐），
     * 所以这里只有一行 —— 需要看「为什么是 32 位」的时候看上面。
     */
    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 当前线程的 traceId；<b>不在一次请求里时为 {@code null}</b>。
     *
     * <p>★ 谁在用这个「可能为 null」：三份 {@code FeignIdentityConfig} 的
     * 出站拦截器 —— 它们只在非空时才往请求上写这个头。
     * 于是 MQ 消费线程、定时任务发起的 Feign 调用不会带上一个
     * <b>属于别人的</b> traceId（那比不带更糟：它会把两段无关的日志粘起来）。
     */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /** 放进当前线程的 MDC。<b>调用方必须保证有对应的 {@link #clear()}，且在同一线程。</b> */
    public static void put(String traceId) {
        MDC.put(MDC_KEY, traceId);
    }

    /**
     * 清掉当前线程的 MDC。
     *
     * <p>★★ <b>这一步不是「顺手清理」，是这个方案能不能成立的一半。</b>
     * Tomcat 的线程是<b>复用</b>的 —— 不清的话，下一个落到这条线程上的请求
     * 会<b>继承上一个请求的 traceId</b>。症状是「日志里两段无关的记录被串成一串」，
     * 而那正好把 traceId 唯一的用途（关联）变成了污染。
     * <p>★ 和 {@code HeaderAuthenticationFilter} 的
     * {@code finally { UserContext.clear(); SecurityContextHolder.clearContext(); }}
     * 是同一个形状、同一个理由。
     */
    public static void clear() {
        MDC.remove(MDC_KEY);
    }

    private TraceContext() {
    }
}
