package com.novel.interaction.mq.consumer;

import com.novel.common.constant.MqConstant;
import com.novel.common.mq.ProgressReportMessage;
import com.novel.modules.interaction.mapper.ReadHistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 阅读进度上报的消费者（M3）—— <b>全项目唯一一个顺序消费者</b>。
 *
 * <p>它只做一件事：把消息里的进度 {@code upsert} 进 {@code read_history}。
 *
 * <h3>★★ 为什么校验不在这里 —— 「校验留生产者」是刻意的分工</h3>
 *
 * <p>{@code HistoryServiceImpl.report} 改造前有三步校验（作品已上架 / 章节属于该书 /
 * 章节已发布），它们<b>全部留在生产者那一侧</b>。这不是偷懒，理由有两条，都很硬：
 *
 * <ol>
 *   <li><b>那两次校验是有信息量的错误码，不是内部检查。</b>
 *       它们返回 {@code 2001} / {@code 3001}，而
 *       {@code HistoryServiceImpl.report} 的注释把这件事叫<b>抗探测</b>：
 *       读者拿着过期链接点进一个草稿章，原来会拿到 {@code 3001}，
 *       现在是 200 —— 而「上报成功 / 失败」的差别本身就是一把探针，
 *       能反推出一本未上架的书里有哪些章节 id。
 *       把它们搬进消费者，等于把接口所有失败都变成 200，
 *       <b>连合法读者的正常报错也一起废掉</b>：他的进度被静默丢弃，
 *       历史列表里没有这本书，而服务端日志里什么都没有。</li>
 *   <li><b>校验需要的信息在消费者里拿不到（至少不是免费的）。</b>
 *       那两步是 {@code ContentGateway} 的 Feign 读。搬进消费者意味着
 *       <b>在顺序消费的线程里做一次跨服务调用</b> ——
 *       而顺序消费失败是线程内本地重试，<b>每次失败都要先等一次
 *       Feign 的超时（read-timeout 5s）</b>，队头阻塞从「秒」变成「十秒级」。
 *       ★ 这条正是「全项目只留一条 ORDERLY」能成立的前提：
 *       <b>这条链路上没有任何远程调用</b>，所以它没有那个暴露面。</li>
 * </ol>
 *
 * <h3>★ 时间戳来自载荷，不是 {@code LocalDateTime.now()}</h3>
 *
 * <p>{@code update_time} 就是阅读历史列表的排序依据（{@code idx_user_time}）。
 * 在消费端盖时间戳的话，一有积压，列表顺序就变成「消息处理顺序」
 * 而不是「读者阅读顺序」—— 用户看到自己的阅读记录前后颠倒，且不报错。
 * 所以 {@code at} 由生产者在<b>收到请求的那一刻</b>盖好，随载荷带过来。
 *
 * <h3>★★ 失败处理：「永久失败不抛、基础设施异常才抛」</h3>
 *
 * <p>这条规矩在<b>顺序消费</b>里比在并发消费里重要得多，因为两者的失败代价完全不同：
 * <ul>
 *   <li><b>并发</b>消费失败 → 进 {@code %RETRY%{group}}，按延迟阶梯重试，超限进
 *       {@code %DLQ%{group}}（本项目保留 <b>48 小时</b>）。失败是<b>有出路</b>的。</li>
 *   <li><b>顺序</b>消费失败 → 走 {@code SUSPEND_CURRENT_QUEUE_A_MOMENT}，
 *       是<b>消费者线程内按固定 1 秒间隔的本地重试</b>，而且
 *       {@code maxReconsumeTimes} 在顺序消费下<b>默认是 {@code Integer.MAX_VALUE}</b>。
 *       ★★ <b>但「默认」不等于「必然」—— M3 实测把这里原来那句
 *       「永不放弃、永不进死信、永久卡死」推翻了。</b>
 *       我们显式配了 {@code maxReconsumeTimes = 3}，于是实测是：
 *       <pre>
 *         23:19:55.016  reconsumeTimes:0
 *         23:19:56.115  reconsumeTimes:1     ← 本地重试固定 +1s
 *         23:19:57.121  reconsumeTimes:2
 *         23:19:58.129  reconsumeTimes:3
 *         23:20:02      队列恢复，后续消息正常消费
 *         drain %DLQ%group-interaction-progress → reconsume=4 的那条就在里面
 *       </pre>
 *       <b>即：真正的代价是「这条队列被阻塞约 4 秒 + 重试频率高」，
 *       不是「不可恢复」。</b>完整实测记录见 {@code MqConstant} 的类注释。</li>
 * </ul>
 *
 * <p>所以这里的判断标准是「<b>重试一万次会不会变好</b>」：
 * <table border="1">
 *   <caption>两类失败的处置</caption>
 *   <tr><th>类型</th><th>例子</th><th>怎么做</th></tr>
 *   <tr><td>永久失败</td><td>载荷缺字段（{@code null} userId）</td>
 *       <td>记 WARN，<b>正常返回 = ACK</b>，把这条坏消息放过去</td></tr>
 *   <tr><td>瞬时失败</td><td>数据库连不上</td>
 *       <td><b>抛出去</b>，让本地重试生效</td></tr>
 * </table>
 *
 * <p>★ 直觉在这里是反的：看到「失败了」就想抛出去重试，
 * 而在这个方法里抛出去等于<b>让一条坏消息把整条队列堵上 4 秒</b> ——
 * 它重试一万次也不会变好，只是白占着队头。
 * <p>★ 本方法<b>没有 try/catch</b>，是故意的：{@code upsert} 抛出来的
 * {@code DataAccessException} 正是「瞬时失败」，应该原样冒泡；
 * 而永久失败在它之前就被判别并 return 了。
 * <b>加一层 catch 反而会把两类失败混成一种。</b>
 *
 * <p>★ {@code maxReconsumeTimes = 3} 是显式写的：顺序消费的默认值是
 * {@code -1}（即 {@code Integer.MAX_VALUE}），不写就是「永不放弃」。
 * ★ 顺带记一笔：{@code rocketmq.consumer.max-reconsume-times} 这个配置项
 * <b>在 starter 里根本不存在</b>（{@code RocketMQProperties$PullConsumer} 没有这个字段），
 * 写在 yml 里会被<b>静默忽略</b> —— 只能写在注解上。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstant.TOPIC,
        selectorExpression = MqConstant.TAG_PROGRESS_REPORT,
        consumerGroup = MqConstant.GROUP_INTERACTION_PROGRESS,
        /*
         * ★★ 必须与生产者的 syncSendOrderly+hashKey 配对。
         *   发的时候有序、这里声明并发 —— 同一条队列的消息会被线程池打散，
         *   一点顺序都没有，而且静默（ORDERLY 只在单队列内部保证顺序，
         *   所以「发送端对了」并不能推出「消费端也是有序的」。
         *   两者是【各自独立】的一半）。
         */
        consumeMode = ConsumeMode.ORDERLY,
        /*
         * ★★ 必须显式写。顺序消费的默认值是 -1（Integer.MAX_VALUE），
         *   不写就是「永不放弃」—— 而 M3 实测证明：正因为写了 3，
         *   那条毒消息才会重试 3 次后进 %DLQ% 并放行队列。
         *   这两句话是同一次实测的两面，见类注释。
         */
        maxReconsumeTimes = 3
)
public class ProgressReportConsumer implements RocketMQListener<ProgressReportMessage> {

    private final ReadHistoryMapper readHistoryMapper;

    @Override
    public void onMessage(ProgressReportMessage message) {
        /*
         * ★ 唯一的一类「永久失败」：载荷本身不完整。
         *   重试一万次它还是缺字段，所以 ACK 掉，把队列让给后面的消息。
         *   ★ 打 WARN 而不是 ERROR：这条消息确实丢了，但队列是通的，
         *   不需要人半夜起来处理 —— 级别要配得上「需要做什么」。
         */
        if (message == null || message.userId() == null
                || message.novelId() == null || message.chapterId() == null) {
            log.warn("进度消息缺少必要字段，已丢弃 | message={}", message);
            return;
        }

        /*
         * ★ progress 允许为 null，兜成 0 —— 和改造前 HistoryServiceImpl.clampProgress
         *   对 null 的处置一字不差（列是 NOT NULL DEFAULT 0）。
         *   ★ 夹紧在生产者做过了，这里不重复夹：两个地方都夹不会更安全，
         *   只会让人以为「肯定有一处在把关」而两边都不敢改。
         *
         * ★ 时间戳用载荷里的 at，不用 now() —— 理由见类注释。
         */
        int progress = message.progress() == null ? 0 : message.progress();

        readHistoryMapper.upsert(message.userId(), message.novelId(),
                message.chapterId(), progress, message.atAsLocalDateTime());
    }
}
