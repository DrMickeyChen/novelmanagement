package com.novel.content.mq.consumer;

import com.novel.common.constant.MqConstant;
import com.novel.common.mq.AiAuditMessage;
import com.novel.modules.ai.service.AiAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * AI 违禁审核的消费者（E1/S8）：把一条 {@code AI_AUDIT} 变成一条判定。
 *
 * <pre>
 *   生产者两处：content 的 AiAuditNotifier（章节 submit）
 *              interaction 的 AiAuditProducer（评论 insertComment）
 *   消费方一个：本类 → {@link AiAuditService}（两层判定 + 落 ai_audit_result）
 * </pre>
 * ★ 与 {@code AUDIT_RECORD}（auth + interaction 两个生产者、content 一个消费者）
 * <b>完全同形</b> —— 那是本项目已经跑熟的形状，不是新发明的。
 *
 * <h3>★★★ 命名：{@code group-content-ai-audit} 必须与 {@code group-content-audit} 分得清</h3>
 *
 * <p>它俩<b>只差三个字符</b>，而在 RocketMQ 里<b>两个 Tag 共用一个消费组名的后果是静默的</b>：
 * 订阅按消费组存在 Broker 上，<b>后注册的会覆盖前一个</b> ——
 * 症状是「某个 Tag 收不到消息，且不报错」。
 * 所以这两个常量在 {@code MqConstant} 里<b>隔开排列并互相引用说明</b>。
 * ★ 本项目记在案的判据：
 * <blockquote>
 * 某个 Tag 收不到消息且不报错 → 先查有没有两个 Tag 共用一个消费组。
 * </blockquote>
 * <p>★ 顺带一个容易搞反的事实：<b>两个生产者共用一个组是对的</b>（它们 Tag 相同、
 * 消费模式相同、载荷是同一个 record）；<b>两个 Tag 共用一个组才是灾难</b>。
 *
 * <h3>★★★ ACK 还是重投，由 {@link AiAuditService} 决定 —— 本类【不】catch</h3>
 *
 * <p>这是本类唯一偏离既有模板的地方（{@code AuditRecordConsumer} 把判断写在消费者里），
 * 而理由很硬：<b>判据是「钱花出去了没有」，那只有 Service 知道。</b>
 * <pre>
 *   词库不可用 / 目标已删 → Service 记日志后 return（= ACK，重投也不会变对）
 *   互动服务不可用 / 写库失败且钱没花 → Service 抛异常（→ %RETRY%，重投有意义）
 *   AI 层失败             → Service 记 ERROR 后 return（= ACK，重投要再花一次钱）
 * </pre>
 * ★ 在这里加一个 {@code try/catch} 会把最后一档重新变成「无限重试」——
 * <b>而「无限重试」在这条链上的意思是「无限计费」</b>。
 * 所以本类刻意保持成一个纯转发：能出错的地方一个都不在这里。
 *
 * <p>★ {@code maxReconsumeTimes = 3} <b>显式写</b>：注解默认 −1 在顺序消费下
 * 等于永不放弃，而这里虽然是并发（−1 折算成默认 16 次），
 * <b>「重试次数看得见」比「靠推导」值钱</b> —— 同 {@code ViewReportConsumer} / {@code AuditRecordConsumer}。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstant.TOPIC,
        selectorExpression = MqConstant.TAG_AI_AUDIT,
        // ★★ 独占的消费组，见类注释：一个 (服务, Tag) 一个组。
        consumerGroup = MqConstant.GROUP_CONTENT_AI_AUDIT,
        // ★ 并发，不是顺序：内容指纹那套幂等对乱序天然免疫 ——
        //   不同版本各自留一次判定（各自一个指纹），同版本被 SETNX 挡住。
        //   ★ 本项目只有 PROGRESS_REPORT 是 ORDERLY，别把这条也改成它。
        consumeMode = ConsumeMode.CONCURRENTLY,
        maxReconsumeTimes = 3
)
public class AiAuditConsumer implements RocketMQListener<AiAuditMessage> {

    private final AiAuditService aiAuditService;

    @Override
    public void onMessage(AiAuditMessage message) {
        /*
         * ── 唯一一处本类自己判的失败：载荷不合法 ──
         *
         * ★ 缺 targetId 只能丢弃，不能「算了就当 0」：那会把一次审核变成
         *   对 {@code target_id=0} 的一次判定，而那行数据不存在。
         *   ★ 这里【不】校验 targetType：它由 Service 判（不认识的类型是永久失败，
         *   而 Service 比我更清楚哪些类型在范围内）。
         *   ★★ 顺序上它排在调 Service 之前 —— 一次错投的消息不该产生任何副作用。
         */
        if (message == null || message.targetId() == null) {
            log.error("AI 审核消息缺少 targetId，已丢弃（它不像是本项目生产者发出的） | message={}", message);
            return;
        }

        /*
         * ★ 不加 try/catch，理由见类注释：这里抛出去 = 进 %RETRY%group-content-ai-audit，
         *   而「哪些情况该抛」是 Service 的判据（它知道钱花了没有）。
         * ★ message.at() 只用于排障（「投递到处理隔了多久」），业务判据一个都不用它 ——
         *   它不是幂等键（幂等键是内容指纹），也不是顺序依据。
         */
        aiAuditService.audit(message.targetType(), message.targetId());
    }
}
