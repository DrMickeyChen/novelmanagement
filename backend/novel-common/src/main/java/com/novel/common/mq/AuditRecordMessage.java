package com.novel.common.mq;

import com.novel.common.enums.AuditAction;
import com.novel.common.enums.AuditTargetType;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 「刚刚发生了一次审核动作」—— 审核流水的消息载荷（M3）。
 *
 * <p>Tag：{@code AUDIT_RECORD}；方向：<b>novel-auth → novel-content</b>
 * （{@code audit_log} 表在 content 那边）。
 *
 * <h3>★★ 它是四条消息里唯一用【事务消息】发的</h3>
 *
 * <p>因为它替换掉的那次调用原本躺在 {@code @Transactional} 的方法里面：
 * {@code AuthorApplyServiceImpl.audit()} 与 {@code CommentServiceImpl.audit()}
 * 都是事务方法，里面的 {@code recordXxxAudit(...)} 是一次 Feign 写。
 *
 * <p><b>而 Feign 不参与调用方的事务。</b>
 * 于是 {@code AuditLogService.record} 那句「我刻意不加 {@code @Transactional}，
 * 是为了加入调用方的事务、让『审核』和『写流水』同生共死」——
 * <b>这句话的前提在 M2 换接缝的那一刻就静默失效了</b>：
 * 内容立刻提交了一条流水，auth 随后一回滚，它就是一条<b>不会有人删的孤儿流水</b>。
 *
 * <p>事务消息是这条接缝唯一能恢复原子性的机制：半消息先行、本地事务提交后才对
 * 消费者可见、生产端宕机由 Broker 回查裁决。
 *
 * <h3>★★ 关于 {@code operatorId}：这里要写清楚它为什么【这次】可以放在载荷里</h3>
 *
 * <p>★ <b>曾经有两个 DTO 承担这条边，M3 之后这两个都不在了</b>：
 * {@code AuthorApplyAuditDTO}（auth 用）与 {@code CommentAuditLogDTO}（interaction 用）——
 * 两条边都改成了本消息，两个类随它们各自的 {@code /internal} 端点一起删除。
 * 它们的类注释花了很大篇幅论证「不在这里放 {@code operatorId}」，
 * 理由是那会把「操作人不可伪造」从一条系统保证降级成一个<b>纯靠调用方自觉</b>的约定。
 * <b>那个论证是对的，但它成立的前提是「存在第二条不可伪造的通道」</b> ——
 * HTTP 时代那条通道是 Feign 出站拦截器重写的 {@code X-User-*} 请求头，
 * content 侧的过滤器再读回来。<b>MQ 把这条第二通道整个拿掉了</b>
 * （消费者线程上没有请求、没有过滤器、{@code UserContext} 永远是空的）。
 *
 * <p><b>所以约束的【载体】换了，不是约束没了：</b>
 * <pre>
 *   HTTP 时代：能不能调通 /internal/**   ← 共享密钥 + 网络隔离
 *   MQ   时代：能不能往 novel-topic 发消息 ← ★ 同一条信任边界
 * </pre>
 * 两者都在信任边界<b>之内</b>（能发消息的服务本来就是可信进程），
 * 所以「操作人不可伪造」这条不变量没有变弱，只是执行机制从
 * 「读请求头」换成了「读消息字段」。
 *
 * <p>★ <b>但这条通道变宽了一点点，要如实记下来：</b>
 * HTTP 那条路要同时拿到内部令牌<b>和</b>一个有效的登录态（第二个头也是拦截器
 * 从 {@code UserContext} 取的），而 MQ 这条路只要求「能发消息」——
 * 发什么 {@code operatorId} 都行。<b>所以生产环境的 broker 必须只对内网开放</b>，
 * 和 {@code :8081} / {@code :8082} / {@code :8083} 是同一条规矩。
 *
 * <h3>★ 幂等：{@code audit_log} 没有唯一键，只能靠 {@code messageKey} + SETNX</h3>
 *
 * <p>四条消息里<b>只有这一条</b>需要显式幂等。另外三条的重复消费都是无害的：
 * 阅读量是累加（重复会多加，但它是近似值）、进度是 upsert、统计值是绝对值覆盖。
 * <b>而流水是「一次事实」</b> —— 重复写一条等于记了一次没发生的事，
 * 正好撞上 {@code AuditLogService} 那句「一条不真实的流水比没有流水更糟」。
 *
 * @param operatorId 操作人 {@code sys_user.id}。★ 生产方从 {@code UserContext} 取，
 *                   消费者把它一路传到 {@code AuditLogService.record(..., operatorId)}
 * @param targetType 审核对象类型（作品 / 章节 / 评论 / 作者申请）
 * @param targetId   对象 id
 * @param action     动作。★ 用 {@link AuditAction}，不是 {@code AuditStatus}，见 {@code AuditLog} 实体注释
 * @param remark     备注，驳回时是理由，可为 {@code null}
 * @param messageKey 幂等键。消费者拿它做 {@code SETNX}，见过就跳过
 * @param at         生产时刻（epoch 毫秒）。★ 会写进 {@code audit_log.create_time}，
 *                   理由见 {@link #atAsLocalDateTime()}
 */
public record AuditRecordMessage(
        Long operatorId,
        AuditTargetType targetType,
        Long targetId,
        AuditAction action,
        String remark,
        String messageKey,
        long at
) {

    /**
     * 生产者用的工厂方法：{@code operatorId} 由调用方从登录态取出来传进来，
     * {@code messageKey} 与 {@code at} 在这里一起生成。
     *
     * <p>★ <b>为什么 key 里要带上时间戳</b>：{@code (targetType, targetId, action)}
     * 三元组看起来已经够唯一了（状态机保证同一本书不会被通过两次），
     * 但它<b>不是</b>严格唯一的 —— 评论的审核日志可以是「同一条评论先通过、
     * 后来又被下架再重新通过」。带上毫秒级的时间戳，key 就必然唯一。
     *
     * <p>★ <b>而 key 在【生产者】生成、随消息走，这一点是关键</b>：
     * 幂等要防的是 <b>Broker 重投同一条消息</b>（消费失败重试、集群切换、
     * offset 回退），重投时 {@code messageKey} 原样不变，所以能认出来。
     * 如果改成消费端自己按载荷算一个 hash，效果一样；
     * 但如果改成消费端「每次生成一个新的」—— 幂等就完全失效了，
     * <b>而且是那种「看起来加了幂等」的失效</b>。
     */
    public static AuditRecordMessage of(Long operatorId, AuditTargetType targetType,
                                        Long targetId, AuditAction action, String remark) {
        long at = System.currentTimeMillis();
        return new AuditRecordMessage(operatorId, targetType, targetId, action, remark,
                keyOf(targetType, targetId, action, at), at);
    }

    /**
     * 幂等键的格式：{@code audit:{targetType}:{targetId}:{action}:{at}}。
     *
     * <p>★ 把格式收在这一个方法里、而不是让调用方自己拼：
     * Redis 里那把 key 的全貌是
     * {@code novel:mq:idempotent:audit:AUTHOR_APPLY:7:APPROVE:1758000000000}，
     * 排查「这条消息为什么被跳过了」时要照着它去 Redis 里捞 ——
     * 格式散在各处就捞不到（或者捞到错的）。
     */
    private static String keyOf(AuditTargetType targetType, Long targetId,
                                AuditAction action, long at) {
        return "audit:" + targetType.name() + ":" + targetId + ":" + action.name() + ":" + at;
    }

    /**
     * 把 {@code at} 还原成 {@code audit_log.create_time} 要的 {@code LocalDateTime}。
     *
     * <p><b>★ 为什么不交给 {@code MybatisMetaObjectHandler} 自动填：</b>
     * 那个填充器用的是 {@code LocalDateTime.now()} —— <b>消费时刻</b>。
     * 而 {@code audit_log.create_time} 是管理端日志页上显示的时间，
     * 它必须回答的是「管理员什么时候审的」，不是「这条消息什么时候轮到被处理」。
     * <p>一有积压这两者就明显不是一回事了（积压十分钟，日志页上所有操作的时间
     * 全都晚了十分钟，而没人能从页面上看出这件事）。
     *
     * <p>★ 好在填充器用的是 {@code strictInsertFill}，语义是「字段已经有值就不覆盖」，
     * 所以显式 set 一个 {@code createTime} 就能盖过它 ——
     * 这正是那个方法注释里写的「手动 set 的场景」。
     *
     * <p>★ 这个方法不会被 Jackson 序列化进载荷（record 的 JSON 字段只来自组件访问器），
     * <b>别把它改名叫 {@code getAt()}</b>。
     */
    public LocalDateTime atAsLocalDateTime() {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault());
    }
}
