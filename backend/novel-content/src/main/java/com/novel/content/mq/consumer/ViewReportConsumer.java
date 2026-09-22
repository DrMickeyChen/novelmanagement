package com.novel.content.mq.consumer;

import com.novel.common.constant.MqConstant;
import com.novel.common.mq.ViewReportMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 阅读量上报的消费者（M3）。
 *
 * <p><b>一句话：它只做「收下来、交给聚合器」，不做任何数据库操作。</b>
 * 真正的落库在 {@link NovelViewAggregator#flush()} 里，每 5 秒一次 ——
 * 消费线程必须保持轻，它一慢积压就上来了。
 *
 * <h3>★★ 为什么是 {@code CONCURRENTLY}，而不是像进度那样用顺序消息</h3>
 *
 * <p>因为载荷是<b>增量</b>（{@code +N}）。{@code view_count = view_count + 1}
 * 加两次，和「先加谁的」完全无关 —— <b>累加与顺序无关，所以根本不需要防乱序</b>。
 *
 * <p>★ 这就是「顺序消息是订单里最贵的一种，只给真正需要它的那条链路」
 * 的落地：并发消费换来的吞吐，正是削峰要的东西。
 *
 * <h3>★★ {@code maxReconsumeTimes = 3} 必须显式写 —— 默认值是个陷阱</h3>
 *
 * <p>注解上这个属性的默认值是 <b>{@code -1}</b>。在<b>并发</b>消费下它会被
 * 折算成默认的 16 次重试（走 {@code %RETRY%group-content-view} 的延迟阶梯，
 * 最后进 {@code %DLQ%}），所以「不写」在并发下不算灾难。
 *
 * <p>★★ <b>但在顺序消费下，{@code -1} 等于 {@code Integer.MAX_VALUE}</b> ——
 * 也就是不写就永不放弃。
 * 三条 ORDERLY 里我们只留了一条（{@code PROGRESS_REPORT}），
 * 但这里仍然显式写上 3，理由是<b>统一</b>：
 * 「每一条消息的重试次数都在注解上看得见」，而不是「有的靠默认、有的靠推导」。
 * 六个月后有人把这条改成顺序消费时，这个数字救他一次。
 *
 * <p>★★ <b>而且「显式写」这件事是【必须】的，不是风格问题</b> ——
 * M3 实测：正因为四个消费者都写了 3，那条 ORDERLY 毒消息才会在
 * 重试 3 次后进 {@code %DLQ%group-interaction-progress}、队列随之恢复。
 * 如果当初靠默认值，那条队列就真的永不恢复了。
 * <b>「默认值很危险」和「显式写了就没事」这两句话，是同一次实测的两面。</b>
 * 证据见 {@code MqConstant} 的类注释。
 *
 * <p>★ 顺带记一笔（M3 查证、与大纲草稿不一致的地方）：
 * <b>不存在 {@code rocketmq.consumer.max-reconsume-times} 这个配置项</b> ——
 * {@code RocketMQProperties$PullConsumer} 上根本没有这个字段，
 * 写进 yml 会被 Spring Boot 的宽松绑定<b>静默忽略</b>。
 * 它<b>只能</b>写在每个监听器的注解上。
 *
 * <h3>★★ 失败处理：永久失败绝不抛异常（判断 E）</h3>
 *
 * <p>这条规矩在开发环境<b>完全看不出来</b>，但在生产上决定了一条坏消息
 * 是「被丢弃」还是「把整条队列堵死」：
 * <ul>
 *   <li><b>永久失败</b>（业务性，重试一万次结果一样）→ <b>记日志、正常返回（= ACK）</b>。
 *       本类的场景是「载荷里的 novelId 为 null」或「增量非正数」——
 *       生产者不会发这种消息，所以它意味着<b>消息被人手工投过</b>或者代码写错了，
 *       重试没有意义。</li>
 *   <li><b>瞬时失败</b>（基础设施：数据库连不上、Redis 挂了）→ <b>抛出去</b>，
 *       让 Broker 按阶梯重试。</li>
 * </ul>
 * <p>★ 直觉正好是反的：直觉是「失败了就该重试」，于是把永久失败也抛出去 ——
 * 结果是把一条坏消息变成一条<b>死队列</b>。
 *
 * <p>★★ 而本类还有一个更微妙的点：<b>它根本不会碰数据库</b>，
 * 所以「瞬时失败」在这里几乎不存在 —— {@code accumulate} 只操作堆内存，
 * 唯一的失败模式是载荷本身有问题。<b>因此这个 onMessage 应该是「永不抛」的</b>，
 * 这也正是它敢挂在并发模式上的底气。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstant.TOPIC,
        // ★ 用 selectorExpression 而不是再写一个 Topic：Tag 过滤在 Broker 端完成，
        //   消息根本不会投到本消费者 —— 这是大纲 §12.1「一个 Topic + 四个 Tag」的收益。
        selectorExpression = MqConstant.TAG_VIEW_REPORT,
        // ★★ 独占的消费组，见 MqConstant 的类注释：一个 (服务, Tag) 一个组。
        consumerGroup = MqConstant.GROUP_CONTENT_VIEW,
        // ★ 增量可以乱序 → 不需要顺序消费（理由见类注释）。
        consumeMode = ConsumeMode.CONCURRENTLY,
        // ★ 见类注释：默认值 -1 在顺序消费下等于永不放弃，所以显式写死。
        maxReconsumeTimes = 3
)
public class ViewReportConsumer implements RocketMQListener<ViewReportMessage> {

    private final NovelViewAggregator aggregator;

    @Override
    public void onMessage(ViewReportMessage message) {
        /*
         * ★ 这里没有 try/catch，也不该有。
         *
         *   accumulate 只动内存，它自己会把「载荷坏了」这种永久失败消化掉
         *   （记 WARN + 直接返回），不会抛。
         *   所以这个方法【没有需要重试的失败模式】——
         *   包一层 try/catch 只会把「本该被看见的 bug」变成一条安静的日志。
         *
         * ★ 反过来说：如果哪天有人往这里加了一次数据库写或者一次 Feign 调用，
         *   就必须重新想一遍失败语义（瞬时失败要抛出去，
         *   而且要考虑「抛出去会不会把队列堵死」）。见类注释。
         */
        if (message == null || message.novelId() == null) {
            log.warn("阅读量消息缺少 novelId，已丢弃 | message={}", message);
            return;
        }
        aggregator.accumulate(message.novelId(), message.delta());
    }
}
