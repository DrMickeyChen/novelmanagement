package com.novel.content.mq.producer;

import com.novel.common.constant.MqConstant;
import com.novel.common.mq.MqErrors;
import com.novel.common.mq.ViewReportMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

/**
 * 阅读量上报的生产者：把一次浏览变成一条 {@code VIEW_REPORT} 消息（M3）。
 *
 * <p><b>它是 {@code POST /api/novels/{id}/view} 的整个下半身</b> ——
 * 那个接口的响应体在 M3 里一个字节都没变（永远 200），变的只是
 * 「记一次阅读」这个动作从「Redis INCR + SADD」换成了「发一条异步消息」。
 *
 * <h3>★★ 这个类的第一职责不是「发消息」，是「保证发送失败也不影响读者」</h3>
 *
 * <p>被它取代的 {@code NovelViewCounter.record} 有一条兜底注释，理由是
 * 「只用 Redis，<b>不碰数据库</b> —— 这是这个接口能匿名开放的前提
 * （不落库、不产生数据、不记录调用者）」。
 *
 * <p><b>★★ 走 MQ 之后这个前提就是假的了</b>：接口确实产生了持久数据
 * （一条消息，会在 Broker 上留 48 小时）。前提变了，但结论不变，
 * 所以理由要换成一条仍然成立的：
 * <blockquote>
 * 匿名接口只允许产生<b>可丢弃的、不含调用者信息的</b>消息。
 * </blockquote>
 * <ul>
 *   <li><b>可丢弃</b>：阅读量是近似值，丢了不影响任何人的正确性
 *       （同 {@code ViewReportMessage} 里那条「增量可以乱序、可以丢」的论证）；</li>
 *   <li><b>不含调用者信息</b>：载荷只有 {@code novelId} 和一个时间戳，
 *       没有 userId、没有 IP、没有 UA —— 所以它<b>不构成对匿名调用者的记录</b>。
 *       ★ 这一条是硬约束：往这个载荷里加任何「谁点的」字段，
 *       都等于把一个匿名埋点变成一份用户行为日志 —— 那需要先过合规，
 *       而不是顺手加个字段。</li>
 * </ul>
 * <p>★ 这类「结论还对、前提已经变了」的注释比彻底过时的注释更难发现 ——
 * 所以这里是<b>改写</b>，不是删除（对着 {@code docs/P6} 里那条债看）。
 *
 * <h3>★★ 两条兜底必须同时有，少一条就是一个真实的 500 或一次静默丢失</h3>
 *
 * <p><b>① {@code try/catch(Exception)} 包住整个发送。</b>
 * {@code asyncSend} 的名字听起来「异步所以不会抛」，但它在<b>提交任务之前</b>
 * 就会同步抛异常：producer 没启动（Broker 连不上、`producer service state not OK`）、
 * 消息体序列化失败、发送队列满。而 {@code ReaderNovelController.recordView}
 * 今天<b>只 catch {@code BusinessException}</b> —— 裸调 {@code asyncSend}
 * 会让这个「永远返回 200」的接口多出一条 500 路径，
 * 直接打破它自己注释里承诺的抗探测性质（响应体一个字节都不差）。
 *
 * <p><b>② {@code SendCallback} 不能省。</b>异步发送失败的<b>回调是可选的，
 * 而失败是静默的</b> —— 不带 callback 的话，一条发不出去的消息
 * 既没有异常也没有日志，增量就这么消失了。callback 里的 WARN
 * 是这条链路上唯一能知道「消息丢了」的地方。
 *
 * <p>★ 这里的取舍和 {@code NovelViewCounter.record} 完全一致：
 * <b>埋点失败绝不能让读者的页面出错</b>。阅读量丢一点无所谓，读者打不开书才是事故。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ViewReportProducer {

    private final RocketMQTemplate rocketMQTemplate;

    /**
     * 上报一次浏览。
     *
     * <p>★ <b>调用方必须先校验 novelId 存在且已上架</b>（
     * {@code NovelService.requirePublished}）—— 这里不校验。
     * 那个校验在 M3 里<b>没有被搬走、也不该被搬走</b>：
     * 它的理由从来不是数据准确性，是「<b>不给一个匿名接口提供无限造消息的口子</b>」。
     * 走 MQ 之后这个理由比原来更强 —— 原来造的是 Redis key，现在造的是消息，
     * 而消息会在 Broker 上占盘、留 48 小时。
     *
     * @param novelId 作品 id，调用方已校验过
     */
    public void report(Long novelId) {
        try {
            rocketMQTemplate.asyncSend(
                    MqConstant.destinationOf(MqConstant.TAG_VIEW_REPORT),
                    ViewReportMessage.of(novelId),
                    new SendCallback() {

                        /** 成功什么都不记 —— 这条链路每秒钟可能几千次，记日志就是刷屏。 */
                        @Override
                        public void onSuccess(SendResult sendResult) {
                            log.debug("阅读量上报成功 | novelId={} msgId={}",
                                    novelId, sendResult.getMsgId());
                        }

                        /**
                         * ★★ 异步发送失败是<b>静默</b>的，这里是唯一能知道它丢了的地方。
                         *
                         * <p>用 WARN 而不是 ERROR：阅读量丢一点不是故障，
                         * 它是这条链路<b>被设计成可以接受</b>的结果。
                         * ERROR 应该留给「需要有人半夜起来处理」的事。
                         *
                         * <p>★★ 这里<b>不能</b>写 {@code e.getMessage()}：RocketMQ 异步发送抛出来的
                         * 是个包装异常，它的 message 恒等于字符串 <b>{@code unknown reason}</b> ——
                         * M3 压测时这条日志打了 648 行，每一行都等于没说。
                         * 真正的错因在 {@code getCause()} 里，所以走 {@link MqErrors#describe}。
                         * （★ 这一条有代价地学到：当时只能反编译 {@code rocketmq-client} 才查出来。
                         *   完整形状见 {@code MqErrors} 的类注释。）
                         *
                         * <p>★ <b>只给短描述、不传整只 {@code e}</b>（= 不打堆栈），
                         * 这是本类与另外几个 producers 不同的唯一一处，理由是<b>频率</b>：
                         * 这条链路是匿名埋点，每秒可能上千次，而失败是<b>被设计成可接受</b>的。
                         * 一次网络抖动就是几百条堆栈、每条三十行 —— 那是把日志淹掉，
                         * 而不是把问题说清楚。真要堆栈时把 WARN 调成 DEBUG 复现一次即可。
                         */
                        @Override
                        public void onException(Throwable e) {
                            log.warn("阅读量上报失败（已忽略，不影响读者） | novelId={} error={}",
                                    novelId, MqErrors.describe(e));
                        }
                    });
        } catch (Exception e) {
            /*
             * 同步抛出：producer 没启动 / 序列化失败 / 发送队列满。
             *
             * ★★ 这里【必须】吞掉。这个方法的调用方是一个承诺「永远返回 200」
             * 的匿名埋点接口 —— 让异常冒出去就是一条 500，
             * 而 500 和 200 的差别本身就是一把探针（见 ReaderNovelController 的注释）。
             */
            log.warn("阅读量上报失败（已忽略，不影响读者） | novelId={} error={}", novelId, MqErrors.describe(e));
        }
    }
}
