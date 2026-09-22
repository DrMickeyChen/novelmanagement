package com.novel.interaction.mq.producer;

import com.novel.common.constant.MqConstant;
import com.novel.common.enums.NovelStatField;
import com.novel.common.mq.AfterCommit;
import com.novel.common.mq.MqErrors;
import com.novel.common.mq.NovelStatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 作品统计值（收藏数 / 评分）同步到 content 的生产者（M3）。
 *
 * <p>它替换掉的是 {@code ContentGateway} 上的两个同步 Feign 写回
 * （{@code refreshCollectCount} / {@code refreshScore}），
 * 那两处就是 M2 留下的两笔债 R9 / R8 —— 一笔在事务外、一笔在事务内，各有各的坏法。
 * <p>★ 债的完整论证<b>搬到了 {@code NovelStatMessage} 的类注释里</b>
 * （它讲的是「这条消息为什么长这样」），这里只讲「怎么发」。
 *
 * <h3>★★ 两个方法都必须走 {@link AfterCommit}</h3>
 *
 * <p>这是本类唯一容易写错的地方，而且<b>写错了照样跑通</b>：
 * <ul>
 *   <li>收藏数那条：{@code ShelfServiceImpl.add/remove} 在 M3 起是
 *       {@code @Transactional} 的（M3 才加的，理由见那个类）。直接在方法体里发，
 *       消息会<b>先于提交</b>可见 —— 消费者把 {@code collect_count} 改成了包含
 *       这次收藏的数，而这次收藏随后回滚了。</li>
 *   <li>评分那条：{@code CommentServiceImpl.delete} / {@code audit}
 *       本来就是 {@code @Transactional} 的，同理。</li>
 * </ul>
 * <p>★ 注意这里<b>不是</b>「发完再提交」的问题，是「消费者可能比提交更早看见」——
 * 两者在单机开发环境里几乎总是碰不到，只在慢查询、长事务、并发下才现形。
 *
 * <h3>★★ 为什么这里可以吞掉发送失败，而进度那条的理由完全不同</h3>
 *
 * <p>本类失败的后果是：{@code collect_count} / {@code score} 停在旧值，
 * 直到<b>对账任务</b>（{@code NovelStatReconcileJob}，每 10 分钟）
 * 把两边的绝对值比一遍再重推。
 * <p><b>兜底的来源是「有真值可比」，不是「下次会自己好」。</b>
 * ★ 别把这条和 {@code ProgressReportProducer} 那条混起来 ——
 * 那边是「读者继续滚动就会再报一次」，靠的是<b>上报行为本身是持续的</b>；
 * 这边靠的是<b>绝对值 + 对账</b>。两种自愈的机制不一样，
 * 所以「能不能吞异常」这件事在这两条链路上是按各自的机制分别论证的，
 * 不是一条通用规矩。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NovelStatProducer {

    private final RocketMQTemplate rocketMQTemplate;

    /**
     * 同步收藏数（绝对值）。
     *
     * @param novelId 作品 id
     * @param count   ★ <b>绝对值</b>（interaction 侧 COUNT 出来的真值），不是 ±1
     */
    public void publishCollectCount(Long novelId, long count) {
        publish(NovelStatMessage.of(novelId, NovelStatField.COLLECT_COUNT, BigDecimal.valueOf(count)));
    }

    /**
     * 同步评分（绝对值）。
     *
     * @param novelId 作品 id
     * @param score   ★ 重算出来的均值。<b>没有任何书评时是 0，不是 null</b> ——
     *                这条口径定义在 {@code CommentServiceImpl#refreshNovelScore}，
     *                这里原样透传，不在这里把 null 兜成 0（那会让「没算出来」
     *                和「算出来是 0」变成同一件事）。
     */
    public void publishScore(Long novelId, BigDecimal score) {
        publish(NovelStatMessage.of(novelId, NovelStatField.SCORE, score));
    }

    /**
     * 统一出口：<b>等事务提交之后再发</b>，发送失败只记 WARN。
     *
     * <p>★ 只有这一个私有方法，两个公开方法都收敛到这里 ——
     * 「提交后才发」这条规矩因此只实现一次。将来加第三个统计列时，
     * 走这个出口就自动是对的；<b>绕过它自己调 {@code RocketMQTemplate}
     * 则一定会漏掉 {@code AfterCommit}</b>，所以在下面把它写死在注释里。
     */
    private void publish(NovelStatMessage message) {
        /*
         * ★★ 注意 AfterCommit.run 收的是一个 Runnable，注册进事务同步器之后
         *   【在另一个时机】才被执行 —— 所以这里不能捕获「lambda 外面的局部变量」
         *   然后指望它还有意义。message 是不可变的 record，正好安全。
         *   （如果哪天要传可变对象进来，得想清楚「注册时刻的值」还是
         *   「执行时刻的值」—— 那是另一个坑，别顺手用。）
         */
        AfterCommit.run(() -> {
            try {
                /*
                 * ★ 用同步发送（syncSend），不是 asyncSend。
                 *   理由和进度那条不同：这里已经在【提交之后】，
                 *   事务不再被挂住，同步发送换来的是「落进队列了」这个确定结论
                 *   —— 而这条链路的失败要能被对账任务算进去，
                 *   一条「发了但不知道成没成」的消息会让排查变成猜谜。
                 * ★ 不用 sendOrderly：载荷是绝对值 + 带时间戳做单调写，
                 *   消费端会丢弃乱序的旧值（见 NovelStatMessage 的类注释）。
                 *   ★ 理由（M3 实测后修正过）：原来写的是「顺序消息的代价
                 *   （毒消息卡死整条队列）」—— 那句被推翻了。
                 *   真正的代价是【队头阻塞 + 固定 1 秒重试 + 单线程消费】，
                 *   而修正乱序一个时间戳就够，不值得付那些。
                 */
                rocketMQTemplate.syncSend(
                        MqConstant.destinationOf(MqConstant.TAG_NOVEL_STAT), message);
                log.debug("统计值已投递 | novelId={} field={} value={}",
                        message.novelId(), message.field(), message.value());
            } catch (Exception e) {
                /*
                 * ★ 吞掉：调用方（收藏 / 评论审核）已经成功了，不能因为
                 *   兄弟服务或 Broker 抖动就给它一个 500 ——
                 *   那正是 M2 的 R9 债的成因（「报错了」和「没做成」相反）。
                 * ★ 兜底是对账任务，不是本次请求。
                 */
                log.warn("统计值投递失败（等对账任务兜底） | novelId={} field={} value={} error={}",
                        message.novelId(), message.field(), message.value(), MqErrors.describe(e), e);
            }
        });
    }
}
