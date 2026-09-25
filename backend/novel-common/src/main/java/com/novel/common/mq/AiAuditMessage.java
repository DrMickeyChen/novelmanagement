package com.novel.common.mq;

/**
 * 「这份内容要过一遍 AI 审核」—— E1/S8 新增。
 *
 * <pre>
 *   { "targetType": 3, "targetId": 94, "at": 1758600000000 }
 * </pre>
 *
 * <h3>★★ 它和 {@link NovelIndexMessage} 形状完全一样，但【不幂等】—— 这是本类最该读懂的一件事</h3>
 *
 * <p>两条消息都<b>只有一个 id、载荷里没有任何内容字段</b>，消费者都是「拿 id 回去读当时的真值」。
 * 而 {@code NOVEL_INDEX} 由此得到「幂等天然、顺序无关、不需要 SETNX」，
 * 本类<b>一条都得不到</b>。差别不在载荷，在<b>读完真值之后做的那件事</b>：
 * <pre>
 *   NOVEL_INDEX →  读真值 → 写 ES 文档        ← 纯函数：同一个 id 做两次，结果一样
 *   AI_AUDIT    →  读真值 → 【调一次大模型】    ← 非纯函数：温度 &gt; 0，同一个 id 做两次得到两份不同的判定
 * </pre>
 * ★★ 所以铁律 7 里那句「载荷里没有数据 ⇒ 幂等免费」的判据要<b>收紧</b>成：
 * <blockquote>
 * 「读真值」只有在<b>读完之后那件事也是确定的</b>时，才是幂等的充分条件。
 * </blockquote>
 *
 * <p><b>★ 处置：幂等键是【内容指纹】，不是消息键（所以本类里没有 messageKey）。</b>
 * <ul>
 *   <li>指纹由<b>消费端</b>算（正文本来就是它读的）：{@code sha256(读到的正文)}；</li>
 *   <li>★★ 为什么不用消息键（{@code AuditRecordMessage} 那种）：消息键只能挡
 *       「同一次发送被重投」，<b>挡不住「内容没变却又被审了一遍」</b> ——
 *       而后者才是烧钱的那条路（重投好歹还是同一件事，重审是一次新的模型调用）。
 *       反过来消息键还会<b>误伤</b>「改了内容重新提交」—— 那次<b>应该</b>重审。</li>
 *   <li>★ 粒度由此落在「<b>内容版本</b>」上：内容没变 → 永远只审一次；
 *       内容变了 → 新指纹、应该重审。完整论证见 {@code AiAuditConsumer}。</li>
 * </ul>
 *
 * <h3>★★ 载荷里不许放枚举 —— {@code targetType} 必须是 {@code Integer}</h3>
 *
 * <p>{@code rocketmq-spring} 的类型转换失败发生在<b>方法体被调用之前</b>，
 * 我们的 {@code try/catch} 根本没机会执行：载荷里的枚举<b>改名或删值会让在途消息全部静默进 DLQ</b>。
 * 所以这里放 {@code Integer}，与 {@code ai_audit_result.target_type} 的裸 {@code TINYINT} 对齐
 * （同 {@code NovelStatMessage} 的 {@code chapterId}、{@code ViewReportMessage} 的经验）。
 * <p>★ 顺带一条能写进注释的结论：<b>给 {@code AiScene} 加 {@code AUDIT(5)} 不会影响任何在途消息</b> ——
 * {@code AiScene} 住在 {@code novel-content}，本类住在 {@code novel-common}，
 * 而 {@code novel-common} <b>不依赖 content</b>，结构上就不可能被载荷引用。
 * （判据钉在包依赖上，不钉在「grep 某个词」上 —— 铁律 8 的第三种形状。）
 *
 * <p>★ {@code targetType} 的取值就是 {@code AuditTargetType} 的码（2 章节 / 3 评论），
 * <b>但这里不复用那个枚举</b>：理由同上。消费端拿到之后自己
 * {@code AuditTargetType.fromCode(...)} 翻一次即可（那一步跑在我们的 try/catch 之内）。
 *
 * @param targetType 审核对象类型码：2 章节 / 3 评论。★ {@code Integer}，见上
 * @param targetId   审核对象 id
 * @param at         生产时间戳（毫秒）。★ <b>纯观测</b> —— 它<b>不是</b>正确性依据：
 *                   本消息既不做单调写、也不用它防乱序（防重的那把闸门是消费端算的内容指纹）。
 *                   留着它只为了排障时能看出「这条消息在队列里躺了多久」
 * @author MickeyChen
 */
public record AiAuditMessage(
        Integer targetType,
        Long targetId,
        long at
) {

    /**
     * 构造一条消息。★ 照 {@code NovelStatMessage.of} / {@code NovelIndexMessage.of} 的先例：
     * 时间戳由工厂方法填，调用方不用（也不该）自己传。
     */
    public static AiAuditMessage of(Integer targetType, Long targetId) {
        return new AiAuditMessage(targetType, targetId, System.currentTimeMillis());
    }
}
