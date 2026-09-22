package com.novel.common.mq;

/**
 * 把异常写成一条<b>能读懂</b>的因果链：{@code 类名(消息) <- 类名(消息) <- ...}。
 *
 * <h3>★★ 为什么不直接用 {@code e.getMessage()} —— 它是 M3 实测出来的一句假话</h3>
 *
 * <p>M3 压测时，{@code ViewReportProducer} 打了 648 行
 * <pre>
 *   阅读量上报失败（已忽略，不影响读者） | novelId=2001 error=unknown reason
 * </pre>
 * <b>每一行都等于没说</b>：既不知道是什么错、也不知道该不该管。
 * 最后只能去反编译 {@code rocketmq-client-5.3.1.jar} 才查出这句话的来历 ——
 * 它在 {@code MQClientAPIImpl$1}（异步发送的 {@code InvokeCallback}）里，
 * 形状是：
 * <pre>
 *   if      (e instanceof RemotingConnectException)   → new MQClientException("send request failed", e)
 *   else if (e instanceof RemotingTimeoutException)   → new MQClientException("wait response timeout, cost=…", e)
 *   else                                              → new MQClientException("unknown reason", e)
 * </pre>
 * ★ <b>真正的错因在 {@code e.getCause()} 里，而 {@code getMessage()} 拿到的永远是外层那句壳。</b>
 * 所以「unknown reason」不是「原因不明」，是「<b>问错了对象</b>」——
 * 客户端其实知道原因（cause 就在手上），只是没往外说。
 *
 * <h3>★ 这一类问题的通用形状（不只是 RocketMQ）</h3>
 *
 * <p>Spring 的 {@code DataAccessException} 家族、{@code CompletionException}、
 * {@code InvocationTargetException} 都是同一个套路：
 * <b>外层是「包装器」，里层才是真话</b>。凡是「抛出的异常类型和自己写的代码对不上」，
 * 就先看 cause —— 只看 {@code getMessage()} 等于只看了信封。
 *
 * <h3>★ 它是纯 JDK 的，所以能住 {@code novel-common}</h3>
 *
 * <p>这一点对本项目是硬要求：{@code novel-common} 被 <b>novel-gateway</b> 依赖着，
 * 而网关的 classpath 是「一行都不能多」的（见 {@code novel-gateway/pom.xml} 里那条红线）。
 * 这个类不 import 任何 rocketmq 类型，所以它<b>不会</b>把 MQ 拖进网关 ——
 * 同 {@code ViewReportMessage} 那几个载荷的道理。
 *
 * @author MickeyChen
 */
public final class MqErrors {

    private MqErrors() {
    }

    /**
     * ★ 展开层数上限。
     *
     * <p>必须有上限，因为 {@code Throwable.getCause()} 的链<b>不保证是有向无环的</b>：
     * 自引用（{@code initCause(this)}）虽然极少见，但一旦出现，
     * 一段没有上限的 while 就会在<b>打日志的时候</b>把线程卡死 ——
     * 而那正是系统已经出问题、最需要日志的时刻。
     * <p>★ 取 5：实测 RocketMQ 的包装最多到第 3 层
     * （{@code MQClientException → RemotingException → IOException}）。
     */
    private static final int MAX_DEPTH = 5;

    /**
     * 描述一个异常，含它的 cause 链。
     *
     * @param e 可能为 {@code null}（回调里传进来的 Throwable 理论上不会是 null，
     *          但「日志工具自己抛 NPE」是最不该发生的事故，所以这里判一下）
     * @return 形如 {@code MQClientException(unknown reason) <- RemotingTooMuchRequestException(...)}
     */
    public static String describe(Throwable e) {
        if (e == null) {
            return "null（没有异常对象）";
        }
        StringBuilder sb = new StringBuilder();
        Throwable current = e;
        for (int depth = 0; depth < MAX_DEPTH && current != null; depth++) {
            if (depth > 0) {
                sb.append(" <- ");
            }
            // ★ 用 getSimpleName() 而不是 getName()：日志是给人看的，
            //   包名在这里是噪音（真要看全名，call site 会另外把 e 整只传给 log，
            //   那样才有堆栈 —— 见下面「谁传 e、谁不传」）。
            sb.append(current.getClass().getSimpleName());
            if (current.getMessage() != null) {
                sb.append('(').append(current.getMessage()).append(')');
            }
            Throwable next = current.getCause();
            // ★ 自引用保护：cause 指向自己时立刻停，否则循环到 MAX_DEPTH 也只是白转。
            current = (next == current) ? null : next;
        }
        return sb.toString();
    }
}
