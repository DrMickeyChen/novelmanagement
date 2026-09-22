package com.novel.interaction.mq.producer;

import com.novel.common.constant.MqConstant;
import com.novel.common.enums.AuditAction;
import com.novel.common.enums.AuditTargetType;
import com.novel.common.mq.AfterCommit;
import com.novel.common.mq.AuditRecordMessage;
import com.novel.common.mq.MqErrors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

/**
 * 写一条「评论审核」流水的生产者（M3）。
 *
 * <p>Tag：{@code AUDIT_RECORD}；方向：<b>novel-interaction → novel-content</b>
 * （{@code audit_log} 是 content 的表）。它替换掉的是
 * {@code ContentGateway#recordCommentAudit} 那次 Feign 写 ——
 * 那次调用躺在 {@code CommentServiceImpl.audit} 的 {@code @Transactional} 里，
 * 于是 auth 一发回滚就会在 content 留下一条第 <b>孤儿流水</b>（M2 记的环 #1 的一半）。
 *
 * <h3>★★ 和 auth 那条 AUDIT_RECORD 的最大区别：这里【不是】事务消息</h3>
 *
 * <p>两条消息、同一个 {@code consumerGroup}、同一个幂等键机制，
 * 唯一不同的是<b>发送方式</b>。这不是疏漏，是一个被论证过的取舍 ——
 * 而且它是「同一个 Tag 可以有不同可靠性等级」这个说法的唯一实证。
 *
 * <p>先说清楚两者共同的部分：{@link AfterCommit} 已经<b>消掉了最坏的那个方向</b>。
 * 「一条不真实的流水」（记录了一次没发生的事）<b>不可能产生</b>，因为：
 * <pre>
 *   事务回滚 → afterCommit 回调根本不执行 → 消息根本没发出去 → 消费者看不到
 *   事务提交 → 消息才发出去 → 消费者看到的一定是真实发生过的事
 * </pre>
 * ★ 这正是 {@code AuditLogService} 那句「一条不真实的流水比没有流水更糟」
 * 要守的东西。<b>两个方向里，坏的那个被彻底堵死了。</b>
 *
 * <p>剩下的是一个更轻的窗口：<b>提交成功、进程在发送前挂了 → 少一条流水</b>。
 * 事务消息能把这一半也补上（半消息 + 回查），代价是：
 * <ul>
 *   <li>需要本服务有 {@code @RocketMQTransactionListener} 和一个<b>独立的事务组</b>。
 *       ★ interaction 的 {@code rocketmq.producer.group} 已经被
 *       {@code PROGRESS_REPORT} / {@code NOVEL_STAT} 两条普通消息共用了，
 *       事务组要独立就得再养一个模板 bean（{@code @RocketMQTransactionListener}
 *       是按<b>模板 bean 名</b>绑定的，见 {@code MqConstant} 里那段）。</li>
 *   <li>需要 {@code checkLocalTransaction} 真查库裁决 ——
 *       那是本服务对 {@code novel_comment} 的一次额外查询路径，要单独维护与测试。</li>
 * </ul>
 *
 * <p><b>判断：为「少一条流水」这个方向引入上面两样，不划算。</b>
 * ★ auth 那条之所以用事务消息，是因为它<b>只有一个生产者用途</b>，
 * 一个独立事务组是白拿的（auth 整个服务的 {@code producer.group} 就是事务组）；
 * 而这里要额外养一个。两条边的业务价值相同，<b>代价不同，所以处置不同</b> ——
 * 这才是「按业务区分可靠性等级」的真实形态，不是「为了统一而统一」。
 *
 * <p>★ <b>如果哪天要求流水强一致</b>（合规审计那类场景），
 * 照 {@code AuthorApplyAuditProducer} 的写法补一个模板 bean + 监听器即可，
 * 消费者与幂等键一个字都不用改。这一点记在这里，免得后人以为
 * 「AUDIT_RECORD 只有 auth 能保证不丢」是设计意图。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommentAuditProducer {

    private final RocketMQTemplate rocketMQTemplate;

    /**
     * 投递一条评论审核流水。
     *
     * @param operatorId ★ 由调用方从 {@code UserContext} 取（{@code CommentServiceImpl.audit}
     *                   第一行就是 {@code requireUserId()}），不接受请求体里的值
     * @param commentId  评论 id —— 同时是 {@code targetId}
     * @param action     ★ 只有 {@code APPROVE} / {@code REJECT} 两种可能，
     *                   判据写在 {@code CommentServiceImpl.audit} 里
     * @param remark     审核备注（驳回理由），可为 {@code null}
     */
    public void publishCommentAudit(Long operatorId, Long commentId,
                                    AuditAction action, String remark) {
        /*
         * ★ 不用在这里做「评论存不存在」的校验：调用方刚用 selectById 查过，
         *   而且那次查询和状态更新在同一个事务里。多一次校验不会更安全，
         *   只会多一次往返 —— 并且是【在事务里】的往返。
         */
        AuditRecordMessage message = AuditRecordMessage.of(
                operatorId, AuditTargetType.COMMENT, commentId, action, remark);

        AfterCommit.run(() -> {
            try {
                rocketMQTemplate.syncSend(
                        MqConstant.destinationOf(MqConstant.TAG_AUDIT_RECORD), message);
                log.debug("审核流水已投递 | commentId={} action={} operatorId={} key={}",
                        commentId, action, operatorId, message.messageKey());
            } catch (Exception e) {
                /*
                 * ★ 吞掉：调用方（评论审核）已经成功了，不能因为 Broker 抖动
                 *   就给它一个 500 —— 那会让管理员以为「没审成功」而再点一次，
                 *   而第二次会撞上「状态已被改过」，拿到 4005。
                 *   一次成功的操作变成两次报错，比少一条流水糟得多。
                 * ★ 兜底：DLQ + 人工（本项目还没有看 DLQ 的东西，M5 的监控债）。
                 *   这里没有对账任务可依赖 —— 流水的真值在业务因果里，
                 *   不在任何一张能 COUNT 的表里（见 AfterCommit 的类注释）。
                 */
                log.warn("审核流水投递失败（这次审核将缺少一条流水） | commentId={} action={} error={}",
                        commentId, action, MqErrors.describe(e), e);
            }
        });
    }
}
