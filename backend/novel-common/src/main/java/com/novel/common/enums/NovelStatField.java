package com.novel.common.enums;

import lombok.Getter;

/**
 * 「这条统计消息说的是 {@code novel} 表上的哪一列」—— M3 新增。
 *
 * <p>用在 {@code NOVEL_STAT} 消息的载荷上（{@code NovelStatMessage#field}）。
 *
 * <h3>★★ 为什么不用一个 {@code String field}</h3>
 *
 * <p>{@code "collectCount"} 和 {@code "collect_count"} 只差一个下划线，
 * 而拼错的下场是消费端走到一个「都不匹配」的分支 ——
 * 要么静默什么都不做（消息被 ACK 掉，值永远同步不过去），
 * 要么抛 {@code IllegalArgumentException} 让这条消息进重试。
 * 两种都不会在<b>发送</b>时被发现。<b>枚举能把这件事变成发送前就编译不过。</b>
 *
 * <h3>★ 为什么这个枚举和隔壁几个不一样：没有 {@code code}、没有 {@code @JsonValue}</h3>
 *
 * <p>隔壁的 {@link AuditAction} / {@link AuditTargetType} / {@link NovelStatus}
 * 都带一个 {@code Integer code} 并标了 {@code @JsonValue}，因为它们对应
 * <b>数据库里的列</b>（{@code audit_log.action} 存 1/2/3），必须以数字形态落库。
 *
 * <p><b>这个枚举不进数据库</b> —— 它只活在消息体里。于是可以选择更好的形态：
 * Jackson 默认按<b>枚举名</b>序列化，消息体里是
 * <pre>
 *   "field": "COLLECT_COUNT"
 * </pre>
 * 而不是
 * <pre>
 *   "field": 1
 * </pre>
 * ★ 区别在排查的时候：MQ 控制台 / {@code mqadmin queryMsgById} 看到的是一串数字时，
 * 你得回头翻代码才知道 1 是什么；看到 {@code COLLECT_COUNT} 就一眼明白了。
 * <b>消息是给人看的东西比给机器看的多，这个便宜不占白不占。</b>
 *
 * <p>★ 代价：枚举名从此是一份<b>对外契约</b>，改名等于改协议
 * （老消息反序列化会失败）。所以下面两个名字写死之后就别动了 ——
 * 和 {@code AuditAction} 那 1/2/3 一个道理，只是载体从数字换成了名字。
 *
 * <p>★ 还有一个顺带的限制：<b>消息可能在队列里躺很久才被消费</b>
 * （重试、积压、上线前的存量），所以这里能加新枚举值（读的时候不认识会报错，
 * 但那是可以接受的：说明该升级了），<b>但不能删、不能改名</b>。
 *
 * @author MickeyChen
 */
@Getter
public enum NovelStatField {

    /** {@code novel.collect_count}：书架收藏数。值域是非负整数 */
    COLLECT_COUNT("收藏数"),

    /** {@code novel.score}：书评评分均值，对应 {@code DECIMAL(3,1)}。★ 没有任何书评时重算结果是 0，不是 null */
    SCORE("评分");

    private final String desc;

    NovelStatField(String desc) {
        this.desc = desc;
    }
}
