package com.novel.content.mq.producer;

import com.novel.common.constant.MqConstant;
import com.novel.common.mq.AfterCommit;
import com.novel.common.mq.MqErrors;
import com.novel.common.mq.NovelIndexMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

/**
 * 通知 search「这本书的索引可能过期了」（M4）。
 *
 * <p>★ 它替代的不是某一段旧代码（M4 之前<b>根本没有索引同步</b>），
 * 所以没有「债」要还。它的四个调用点才是设计主体，见 {@link #notifyIndex}
 * 上面那段「为什么是这四个」。
 *
 * <h3>★★ 为什么必须先 {@link AfterCommit}，理由比 M3 那两条更硬</h3>
 *
 * <p>M3 的 {@code NovelStatProducer} 用 {@code AfterCommit} 是因为
 * 「消息先于提交，消费者会把一个<b>随后回滚</b>的收藏数算进去」。
 * 本类同样有这个洞，但还有一个更直接的版本：
 * <p>★★ <b>消费者要回 content 读这本书的真值</b>（{@code /internal/novels/index-docs}）。
 * 如果消息在事务提交【前】就可见，消费者那次读取走的是<b>另一个连接</b> ——
 * 它读到的极可能是<b>尚未提交的旧值</b>（READ COMMITTED 下看不见别人的未提交改动），
 * 于是它会把<b>旧值写进索引</b>。
 * <p>★★★ 关键在于：<b>这个错误不会自愈</b>。索引里那条文档就此停在旧值上，
 * 直到下一次有人动这本书（或 S8 的全量重建）。前面那条「收藏数」至少还有时间戳和下次上报，
 * 而这条是「一次错误的读，写进一个长期存在的存储」。
 * <b>这是 {@code AfterCommit} 在本项目里最硬的一次适用。</b>
 *
 * <h3>★★ 发送方式用 {@code asyncSend}，和同目录的 {@code NovelStatProducer} 不一样</h3>
 *
 * <p>两个 Producer 都在 {@code AfterCommit} 里发，一个 {@code syncSend}、一个 {@code asyncSend} ——
 * <b>这个差别是刻意的</b>，判据是<b>「发送方等这条消息的结论有没有用」</b>：
 *
 * <table border="1">
 *   <caption>同样是提交后发消息，为什么一个同步一个异步</caption>
 *   <tr><th></th><th>{@code NovelStatProducer}（收藏数 / 评分）</th><th>本类（索引）</th></tr>
 *   <tr><td>发送方式</td><td>{@code syncSend}</td><td>{@code asyncSend} + {@code SendCallback}</td></tr>
 *   <tr><td>为什么</td>
 *       <td>它<b>要那个确定结论</b>：「落进队列了没有」会被对账任务算进去，
 *           一条「发了但不知道成没成」的消息让排查变成猜谜</td>
 *       <td>它<b>不需要结论</b>：漏发的后果是「索引旧一会儿」，
 *           而 S8 的定时全量重建会把它纠回来 —— <b>这就是判断 6 里那条「有对账兜底的用它」</b></td></tr>
 *   <tr><td>代价</td>
 *       <td>请求线程要等一次 broker 往返</td>
 *       <td>★ 换来了<b>不阻塞发布章节 / 上下架这些用户操作</b></td></tr>
 * </table>
 *
 * <p>★★ <b>但 {@code asyncSend} 有个必须配 {@code SendCallback} 的理由，否则它是坏的</b>：
 * 「异步」只表示<b>不等结果</b>，<b>不表示失败会有人告诉你</b>。
 * 不带 callback 的话，Broker 拒绝、Topic 不存在、网络断了 —— 全都<b>静默消失</b>，
 * 调用方看到的是「发出去了」。★ 带了 callback，失败才有地方记那行 WARN。
 *
 * <h3>★★ {@code asyncSend} 还会【同步】抛异常 —— 两层保护缺一不可</h3>
 *
 * <p>这是个反直觉的点：方法名叫 async，但它在<b>参数校验和序列化阶段</b>是同步的 ——
 * 序列化失败、Topic 名非法、{@code rocketMQTemplate} 连 namesrv 都还没连上时，
 * 异常直接从这里抛出来，<b>根本走不到 callback</b>。
 * <p>★ 而本方法运行在 {@code afterCommit} 里，按 {@code AfterCommit} 的类注释引的那段
 * Spring 源码：<b>在那里抛异常，调用方会收到一个失败，而事务其实已经提交了</b> ——
 * 「报错了」和「没做成」说的是两件相反的事。
 * ★★ 所以 <b>try/catch 包在 {@code asyncSend} 外面，callback 处理异步失败，两者管的是不同的失败</b>：
 * <pre>
 *   try { asyncSend(...) }        ← 同步失败：序列化 / 参数 / 连不上 namesrv
 *     catch → WARN
 *   callback.onException(...)     ← 异步失败：broker 拒了 / 超时 / 落盘失败
 * </pre>
 * <b>只有一层的话，另一类失败就是静默的。</b>
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NovelIndexNotifier {

    private final RocketMQTemplate rocketMQTemplate;

    /**
     * 通知索引可能过期。<b>★ 四个落点全部走这一个方法</b>，见下方注释。
     *
     * <h3>★★★ 为什么是【这四个】落点 —— 判据是「参与检索的列被写的地方」</h3>
     *
     * <p>参与检索 / 过滤 / 排序的列一共 5 组，它们被写的地方<b>不是一个方法</b>：
     *
     * <table border="1">
     *   <caption>参与检索的列 → 谁写它 → 有没有经过 syncNovelStat</caption>
     *   <tr><th>列</th><th>被谁写</th><th>经 {@code syncNovelStat}？</th></tr>
     *   <tr><td>{@code title} / {@code intro}</td><td>{@code NovelServiceImpl.update()}</td><td>★ ✗ 不经</td></tr>
     *   <tr><td>{@code status}</td><td>{@code NovelServiceImpl.transitStatus()}（状态的唯一写入口）</td><td>★ ✗ 不经</td></tr>
     *   <tr><td>{@code word_count} / {@code chapter_count} / {@code last_update_time}</td>
     *       <td>{@code syncNovelStat()} 的四个调用点</td><td>✓</td></tr>
     *   <tr><td>{@code deleted}</td><td>{@code NovelServiceImpl.delete()}（逻辑删除）</td><td>★ ✗ 不经</td></tr>
     *   <tr><td>{@code authorName}</td><td><b>auth 的 {@code sys_user.nickname}</b></td><td>✗ content 根本不知道</td></tr>
     * </table>
     *
     * <p>★★ <b>这张表 5 行，而 {@code syncNovelStat} 只有 4 个调用点 —— 两者不是同一个集合。</b>
     * 只挂 {@code syncNovelStat} 会永久漏发三条，后果见 {@link MqConstant#TAG_NOVEL_INDEX}。
     * <p>★ 所以四个落点是：{@code transitStatus} 出口 / {@code update} 出口 /
     * {@code delete} 出口 / {@code syncNovelStat} 出口。<b>全部显式写在 {@code NovelServiceImpl} 里</b>
     * （而不是塞进某个公共方法）—— 这样 {@code grep -n "notifyIndex"} 就能一次核对全。
     *
     * <p>★★ 最后一行（{@code authorName}）<b>故意没有落点</b>，这不是遗漏：
     * 昵称在 auth 里，要实时同步就得新增一条 <b>{@code auth → search}</b> 的边，
     * 而 auth 的出站边现在是 <b>0 条</b>，那是 M3 好不容易挣来的
     * （{@code ContentAuditClient} 被事务消息换掉）。
     * <b>不为一个极低频的场景把它加回来</b> —— 收敛点是 S8 的定时全量重建。
     *
     * @param novelId 作品 id
     * @param reason  触发原因，★ 仅用于观测（见 {@link NovelIndexMessage.Reason}）
     */
    public void notifyIndex(Long novelId, NovelIndexMessage.Reason reason) {
        if (novelId == null) {
            /*
             * ★ 刻意不打日志：调用点都在 Service 内部、id 不可能为 null。
             *   真为 null 说明是编程错误，让它静默返回比刷一行 WARN 更合适 ——
             *   这里加日志只会让「日志里有一行 WARN」变成常态。
             */
            return;
        }
        NovelIndexMessage message = NovelIndexMessage.of(novelId, reason);
        AfterCommit.run(() -> send(message));
    }

    /**
     * 统一出口。<b>★★ 只有这一个地方调 {@code rocketMQTemplate}</b> ——
     * 「提交后才发」和「两层失败保护」因此都只实现一次。
     * ★ 绕过它自己调模板，就一定会漏掉其中一层（见类注释）。
     */
    private void send(NovelIndexMessage message) {
        try {
            /*
             * ★ 走 MqConstant.destinationOf：RocketMQ 里 Tag 是 destination 的一部分
             *   （"topic:tag"）。只给 Topic 不带 Tag 也能发出去、也不报错，
             *   但那条消息没有 Tag、任何按 Tag 订阅的消费者都收不到 —— 静默丢失。
             */
            rocketMQTemplate.asyncSend(
                    MqConstant.destinationOf(MqConstant.TAG_NOVEL_INDEX),
                    message,
                    new SendCallback() {
                        @Override
                        public void onSuccess(SendResult sendResult) {
                            /*
                             * ★ 只在 debug 记：这条消息【每次改作品都会发】，
                             *   INFO 级会把正常日志刷满，而它成功是常态、没有信息量。
                             */
                            if (log.isDebugEnabled()) {
                                log.debug("索引重建消息已投递 | novelId={} reason={} msgId={} status={}",
                                        message.novelId(), message.reason(),
                                        sendResult.getMsgId(),
                                        sendResult.getSendStatus() == SendStatus.SEND_OK ? "OK" : sendResult.getSendStatus());
                            }
                        }

                        @Override
                        public void onException(Throwable e) {
                            /*
                             * ★★ 异步失败必须记 WARN —— 这正是「必须带 callback」的理由。
                             *   兜底是 S8 的定时全量重建（判断 6：有对账兜底的用 AfterCommit）。
                             * ★ 不重试：重试要自己维护退避和去重，而全量重建是现成的、
                             *   更强的兜底（它连「消息丢了」和「消息发了但消费者处理失败」都能一起纠）。
                             */
                            log.warn("索引重建消息投递失败（等定时全量重建兜底） | novelId={} reason={} error={}",
                                    message.novelId(), message.reason(), MqErrors.describe(e), e);
                        }
                    });
        } catch (Exception e) {
            /*
             * ★★ 这一层管的是【同步】异常：序列化失败 / destination 非法 /
             *   连 namesrv 都还没连上。★ 这时候 callback 根本不会被调用，
             *   所以只有这一层能看见它。
             * ★ 吞掉的理由见类注释：此刻处在 afterCommit 里，
             *   抛出去会让一个【已经提交】的事务返回失败。
             */
            log.warn("索引重建消息投递失败（同步阶段，等定时全量重建兜底） | novelId={} reason={} error={}",
                    message.novelId(), message.reason(), MqErrors.describe(e), e);
        }
    }
}
