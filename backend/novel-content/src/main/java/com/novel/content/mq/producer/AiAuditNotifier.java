package com.novel.content.mq.producer;

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
 * 「这一章要过一遍 AI 审核」的生产者（E1/S8）—— <b>content 侧那一半。</b>
 *
 * <p>Tag：{@code AI_AUDIT}；方向：<b>novel-content → novel-content</b>（自发自消）。
 * 另一半在 <b>interaction</b>（{@code AiAuditProducer}，评论），
 * <b>两条 Tag 相同、消费组相同、载荷同一个 record</b> ——
 * 与 {@code AUDIT_RECORD}（auth + interaction 两个生产者）完全同形，
 * 是本项目已经跑熟的那个形状。
 *
 * <h3>★★★ 为什么是「投一条消息」而不是「提交时顺手把关键词跑一遍」</h3>
 *
 * <p>那个写法看起来更省事（免费的那一层不就能同步做完吗），
 * 但它会让<b>筛选逻辑住在两个进程里</b>：{@code 投不投} 在请求路径、
 * {@code 调不调 AI} 在消费端，中间隔着一个 MQ ——
 * <b>没有任何一条日志能同时看到这两个判断。</b>
 * 更坏的是「无命中」这个结论<b>不会落库</b>：
 * 提交了 N 条、审了 M 条，N−M 是多少、少的是哪些，<b>查不出来</b>。
 * ★ 全投之后每一档都能被看见：源行的 {@code status}（人工那道门）
 * + 有没有 {@code ai_audit_result} 行 + 有没有失败的 {@code ai_usage_log} 行，<b>左连接即可定位</b>。
 * ★ 附带的好处：词库升级之后队列<b>可以重放</b>；同步预筛过的存量内容压根没进过队。
 *
 * <h3>★★ 这条消息的失败后果是「人工少一条提示」，不是「违规内容被放出去」</h3>
 *
 * <p>承重事实：评论落库即 {@code status=0}（读者不可见），章节 {@code submit()} 之后是
 * {@code status=1 待审核} —— <b>两者都必须经人工才可见</b>（P5 的审核中心/评论管理就是那道门）。
 * 所以 AI 层挂掉、延迟、漏审的后果都止于「少了一条提示」。
 *
 * <p>★ <b>但「轻」不等于「可以随便丢」，所以发送仍然用 {@code asyncSend + 两层保护}</b>
 * （理由与形状照抄 {@link NovelIndexNotifier}）：
 * <ul>
 *   <li>为什么不是 {@code syncSend}（interaction 的 {@code CommentAuditProducer} 用的是它）：
 *       那边投的是「一次已经发生的人工处置」，语义上它是那次操作的<b>一部分</b>；
 *       这边投的是给下一个环节的<b>提示</b>，而这一步正在用户的请求路径上
 *       （发评论 / 提交章节）——<b>为了送一条提示去阻塞用户，方向反了</b>。</li>
 *   <li>异步的代价是「失败不在当前线程上」，所以必须带 callback 接住它 ——
 *       见下面 {@code send} 里那两层的分工，<b>只有一层就等于另一类失败是静默的</b>。</li>
 * </ul>
 * <p>★ <b>兜底是什么</b>：这一条<b>没有</b>对账任务（不像索引有定时全量重建）。
 * 漏审的内容会一直待在人工队列里（源行 {@code status} 就是 fail-closed 的锚点），
 * 但它<b>不会自己长出 AI 提示来</b>。这是明确接受的取舍，记在
 * {@code docs/E1-AI能力接入.md} 的「不做的事」里 —— 不要以为有个定时任务在补。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiAuditNotifier {

    private final RocketMQTemplate rocketMQTemplate;

    /**
     * 投递一条「章节待审」。
     *
     * @param chapterId 章节 id
     * @param novelId   ★ 只用于日志（这条消息的载荷里没有它）。排障时
     *                  「哪本书的哪一章没被 AI 审过」比一个裸 chapterId 好查得多
     */
    public void notifyChapterAudit(Long chapterId, Long novelId) {
        if (chapterId == null) {
            /*
             * ★ 不打日志：调用点在 Service 内部、id 不可能为 null。
             *   真为 null 说明是编程错误，静默返回比刷一行 WARN 合适
             *   —— 同 NovelIndexNotifier 的取向。
             */
            return;
        }
        /*
         * ★ 生产端【照常】用枚举（它住在 novel-content，编译期就绑死了），
         *   只是把它翻成 Integer 再塞进载荷 —— 「载荷里不许放枚举」约束的是
         *   【载荷的类型】，不是「我们的代码里不许有枚举」。见 AiAuditMessage 的类注释。
         */
        AiAuditMessage message = AiAuditMessage.of(AuditTargetType.CHAPTER.getCode(), chapterId);
        AfterCommit.run(() -> send(message, "chapterId=" + chapterId + " novelId=" + novelId));
    }

    /**
     * 统一出口。<b>★★ 只有这一个地方调 {@code rocketMQTemplate}</b> ——
     * 「提交后才发」与「两层失败保护」因此都只实现一次。
     * ★ 绕过它自己调模板，就一定会漏掉其中一层。
     *
     * @param where 日志里用的定位信息（消息载荷里没有这些字段，见参数注释）
     */
    private void send(AiAuditMessage message, String where) {
        try {
            /*
             * ★ 走 MqConstant.destinationOf：RocketMQ 里 Tag 是 destination 的一部分
             *   （"topic:tag"）。只给 Topic 不带 Tag 也能发出去、也不报错，
             *   但那条消息没有 Tag、任何按 Tag 订阅的消费者都收不到 —— 静默丢失。
             */
            rocketMQTemplate.asyncSend(
                    MqConstant.destinationOf(MqConstant.TAG_AI_AUDIT),
                    message,
                    new SendCallback() {
                        @Override
                        public void onSuccess(SendResult sendResult) {
                            // ★ 只 debug：成功是常态，INFO 级会把正常日志刷满
                            if (log.isDebugEnabled()) {
                                log.debug("AI 审核消息已投递 | {} targetType={} msgId={} status={}",
                                        where, message.targetType(), sendResult.getMsgId(),
                                        sendResult.getSendStatus() == SendStatus.SEND_OK
                                                ? "OK" : sendResult.getSendStatus());
                            }
                        }

                        @Override
                        public void onException(Throwable e) {
                            /*
                             * ★★ 异步失败必须记 WARN —— 这正是「必须带 callback」的理由。
                             *   ★ 不重试：重试要自己维护退避与去重，而这条消息的收益
                             *     只是「人工多一条提示」——重试的复杂度超过它的价值。
                             *     存量内容的补审手段是「人工在队列里看」，不是重发。
                             */
                            log.warn("AI 审核消息投递失败（这一条不会有 AI 判定，等人工审核兜底） | {} error={}",
                                    where, MqErrors.describe(e), e);
                        }
                    });
        } catch (Exception e) {
            /*
             * ★★ 这一层管的是【同步】异常：序列化失败 / destination 非法 /
             *   连 namesrv 都还没连上。★ 这时候 callback 根本不会被调用，
             *   所以只有这一层能看见它。
             *   完整论证在 NovelIndexNotifier 的同名方法里（M4 的教训）。
             * ★ 吞掉：此刻处在 afterCommit 里，抛出去会让一个【已经提交】的事务返回失败 ——
             *   作者提交成功了却看到 500，而章节其实已经在待审队列里。
             */
            log.warn("AI 审核消息投递失败（同步阶段，这一条不会有 AI 判定） | {} error={}",
                    where, MqErrors.describe(e), e);
        }
    }
}
