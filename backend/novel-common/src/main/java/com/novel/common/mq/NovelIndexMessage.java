package com.novel.common.mq;

/**
 * 「这本书的索引可能过期了」—— M4 新增，<b>全项目载荷最轻的一条消息</b>。
 *
 * <pre>
 *   { "novelId": 1001, "reason": "TRANSIT_STATUS", "at": 1758600000000 }
 * </pre>
 *
 * <h3>★★★ 载荷里【没有】任何内容字段，这是本类最重要的一件事</h3>
 *
 * <p>没有 {@code title}、没有 {@code intro}、没有 {@code status} ——
 * <b>只有一个 id</b>。消费者拿到它之后<b>回 content 读当时的真值</b>再写索引。
 *
 * <p>★★ 由此得到一条别的消息都没有的性质：<b>幂等天然、顺序无关、不需要任何附加机制。</b>
 * <ul>
 *   <li>重复投递：两次都读同一份当前状态、写同一个文档 → <b>结果一样</b>（不需要 {@code SETNX}）；</li>
 *   <li>乱序投递：同上（不需要时间戳、不需要顺序消费）；</li>
 *   <li>★ 连「<b>上架时会重复发两次</b>」都是无害的 ——
 *       {@code transitStatus} 出口发一次、它调用的 {@code syncNovelStat} 出口再发一次。
 *       ★★ <b>这份重复是刻意保留的</b>，不去重：为了消掉它得引入「谁已经发过了」的状态，
 *       而那个状态本身就是新的故障源。<b>幂等设计的意义就是让这种重复不必被消灭。</b></li>
 * </ul>
 *
 * <p>★ 对照 {@link MqConstant#TAG_NOVEL_INDEX} 里那张五种形态的表：这一条之所以能这么省，
 * 是因为<b>它推的是「去重算一次」这个指令，而不是算好的结果</b>。
 *
 * <h3>★ 那 {@code at} 和 {@code reason} 是干什么的</h3>
 *
 * <p><b>都不是正确性需要的</b>，这一点必须说清楚，否则下一个人会以为「有时间戳 = 要防乱序」：
 * <ul>
 *   <li>{@code at} —— <b>纯观测</b>。排障时能看出「索引落后了多少」，
 *       以及两条消息谁先谁后（虽然先后不影响结果）。</li>
 *   <li>{@code reason} —— <b>纯观测</b>。它让日志能回答「这次重建是被谁触发的」，
 *       而这个问题在「明明没改书名，索引却一直在重建」的情况下是唯一线索。
 *       ★ 它是枚举不是自由文本：自由文本会变成第二个日志系统，而且没法 grep 汇总。</li>
 * </ul>
 * <p>★★ <b>这条「载荷里可以放观测字段，但它们不许被当成正确性依据」的分界，
 * 值得单独记一笔</b>：M3 的 {@code NOVEL_STAT} 里那个时间戳是<b>承重的</b>（单调写靠它），
 * 而这里的 {@code at} <b>纯粹是注释</b>。两个字段长得一样、地位完全不同。
 *
 * @param novelId 作品 id
 * @param reason  触发原因，★ 仅用于观测
 * @param at      生产时间戳（毫秒），★ 仅用于观测
 * @author MickeyChen
 */
public record NovelIndexMessage(
        Long novelId,
        Reason reason,
        long at
) {

    /**
     * 触发原因。<b>★ 全项目唯一的作用是让日志可读、可汇总</b> ——
     * 消费者对它的处置<b>一个字都不变</b>（这正是幂等天然的另一种说法）。
     */
    public enum Reason {
        /** 上下架（{@code transitStatus} 出口）—— 含审核通过上架 */
        TRANSIT_STATUS,
        /** 改书名 / 简介（{@code update} 出口） */
        UPDATE,
        /** 逻辑删除（{@code delete} 出口） */
        DELETE,
        /** 章节增删改导致的统计字段变化（{@code syncNovelStat} 出口） */
        SYNC_STAT,
        /** ★ 全量重建（S8）—— 它其实不经消息，直接批量写，这个值留给手工补发用 */
        REBUILD
    }

    /**
     * 构造一条消息。★ 照 {@code NovelStatMessage.of} 的先例：
     * 时间戳由工厂方法填，调用方不用（也不该）自己传。
     */
    public static NovelIndexMessage of(Long novelId, Reason reason) {
        return new NovelIndexMessage(novelId, reason, System.currentTimeMillis());
    }
}
