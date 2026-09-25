package com.novel.interaction.mq.producer;

import com.novel.common.constant.MqConstant;
import com.novel.common.enums.AuditTargetType;
import com.novel.common.mq.AfterCommit;
import com.novel.common.mq.AiAuditMessage;
import com.novel.common.mq.MqErrors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

/**
 * 「这条评论要过一遍 AI 审核」的生产者（E1/S8）—— <b>interaction 侧那一半。</b>
 *
 * <p>Tag：{@code AI_AUDIT}；方向：<b>novel-interaction → novel-content</b>。
 * 另一半在 content（{@code AiAuditNotifier}，章节）。
 *
 * <h3>★★★ 它为什么【不】等于「在 interaction 里调 AI」</h3>
 *
 * <p>评论正文在 interaction 手里，就地调 AI 看起来最省事。那样做会：
 * <ol>
 *   <li>在 interaction 里凭空多出一条到 <b>AI 上游</b>的依赖
 *       —— 那是本项目<b>唯一一处会花钱的出站边</b>，它必须只有一个落点；</li>
 *   <li>把 {@code ai_audit_result} 的写留在 interaction，而判定结果要和
 *       content 的章节判定<b>共用同一张表、同一个队列</b>；</li>
 *   <li>★ 最要命的是把「评论正文」变成必须跨服务传的东西 ——
 *       而 content 已经有 {@code CommentTextLookup} 这条<b>读</b>的接缝了，
 *       为了写一条判定再开一条反方向的边，等于把环 #2 焊死。</li>
 * </ol>
 * <p>所以：<b>interaction 只负责把「有评论要审」这件事投出来</b>
 * （载荷只有 id，正文由 content 回头来读），判定与落库全部留在 content ——
 * <b>单库单事务</b>。
 *
 * <h3>★★ 方向对了，但这件事【不加重】服务级环 #2</h3>
 *
 * <p>{@code novel-content ⇄ novel-interaction} 那个环是<b>同步调用</b>构成的
 * （见铁律 1 与 {@code InternalCommentController} 的类注释），
 * <b>MQ 不算边</b>。本类是 {@code interaction --MQ--> content}，
 * 与 {@code CommentAuditProducer} 同向、同性质 ——
 * ★ <b>它不会让那个环更难解</b>，也不会让 content 的入站边多一条。
 *
 * <h3>★ 发送方式是 {@code asyncSend}，与 content 侧那一半【刻意保持一致】</h3>
 *
 * <p>interaction 里现成的 {@code CommentAuditProducer} 用的是 {@code syncSend}
 * （它投的是一次已经发生的人工处置，语义上是那次操作的一部分）。
 * 本类投的是<b>给下一个环节的提示</b>，而这一步正在用户发评论的请求路径上 ——
 * 为送一条提示去阻塞用户，方向反了。
 * ★★ <b>同一个 Tag 的两个生产者必须一样</b>：一条用同步一条用异步，
 * 会让「失败时谁记了日志、谁没记」变成一件要看是哪一半才知道的事。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiAuditProducer {

    private final RocketMQTemplate rocketMQTemplate;

    /**
     * 投递一条「评论待审」。
     *
     * @param commentId 评论 id
     */
    public void publishCommentAudit(Long commentId) {
        if (commentId == null) {
            // ★ 静默返回：调用点在 Service 内部，id 不可能为 null（同 AiAuditNotifier）
            return;
        }
        /*
         * ★ 生产端照常用枚举，翻成 Integer 再进载荷 —— 理由见 AiAuditMessage 的类注释。
         */
        AiAuditMessage message = AiAuditMessage.of(AuditTargetType.COMMENT.getCode(), commentId);
        AfterCommit.run(() -> send(message, commentId));
    }

    /** 统一出口。★ 只有这一个地方调 {@code rocketMQTemplate}，两层保护只实现一次。 */
    private void send(AiAuditMessage message, Long commentId) {
        try {
            /*
             * ★ 走 destinationOf：RocketMQ 里 Tag 是 destination 的一部分（"topic:tag"）。
             *   只给 Topic 不带 Tag 也能发出去、也不报错，但那条消息没有 Tag、
             *   按 Tag 订阅的消费者收不到 —— 静默丢失。
             */
            rocketMQTemplate.asyncSend(
                    MqConstant.destinationOf(MqConstant.TAG_AI_AUDIT),
                    message,
                    new SendCallback() {
                        @Override
                        public void onSuccess(SendResult sendResult) {
                            if (log.isDebugEnabled()) {
                                log.debug("AI 审核消息已投递 | commentId={} msgId={} status={}",
                                        commentId, sendResult.getMsgId(),
                                        sendResult.getSendStatus() == SendStatus.SEND_OK
                                                ? "OK" : sendResult.getSendStatus());
                            }
                        }

                        @Override
                        public void onException(Throwable e) {
                            /*
                             * ★★ 异步失败必须记 WARN —— 「必须带 callback」的理由就在这里。
                             *   ★ 不重试：收益只是「人工多一条提示」，而重试要自己维护
                             *     退避与去重。评论的人工那道门（status=0 读者不可见）
                             *     始终在，所以丢的只是提示。见 AiAuditNotifier 的类注释。
                             */
                            log.warn("AI 审核消息投递失败（这条评论不会有 AI 判定，等人工审核兜底） | commentId={} error={}",
                                    commentId, MqErrors.describe(e), e);
                        }
                    });
        } catch (Exception e) {
            /*
             * ★★ 这一层管【同步】异常：序列化失败 / destination 非法 / namesrv 还没连上。
             *   这时候 callback 根本不会被调用，只有这一层能看见它。
             * ★ 吞掉：此刻在 afterCommit 里，抛出去会让一次【已经提交】的评论发布
             *   返回失败 —— 读者其实已经能看到了，作者却以为没发出去。
             */
            log.warn("AI 审核消息投递失败（同步阶段，这条评论不会有 AI 判定） | commentId={} error={}",
                    commentId, MqErrors.describe(e), e);
        }
    }
}
