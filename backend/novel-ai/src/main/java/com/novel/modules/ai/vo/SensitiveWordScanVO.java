package com.novel.modules.ai.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 试扫结果（E1/S7）。
 *
 * <h3>★★★ 为什么结果里必须带上 {@link #wordCount} 和 {@link #version}</h3>
 *
 * <p>只有 {@code maxLevel} 和 {@code hits} 的话，<b>「无命中」有两种读法而它们长得一模一样</b>：
 * <pre>
 *   ① 词库好好的，这段文字确实干净        → 可以放行
 *   ② 词库是空的 / 快照是旧的，压根没在筛  → 【不能】放行
 * </pre>
 * ★ 这正是铁律 10 的判据：<b>消费方能不能诚实地把这个缺失渲染出来。</b>
 * 带上词条数和快照版本号，「这次判定是基于什么做出的」就变成了一个能看见的数字。
 * ★★ 而查库条数<b>不是</b>这个字段的语义 —— 那是 {@link SensitiveWordStatusVO} 的事，
 * 这里说的是<b>此刻装进 DFA 的那一份</b>。
 *
 * <p>★ 顺带一个免费的收益：{@code version} 每次成功 reload 都 +1，
 * 所以「改了词库之后到底换没换快照」这件事<b>有一个单调递增的物证</b>，
 * 不需要靠日志。S7 的第三条阳性对照断言的就是它。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "违禁词试扫结果")
public class SensitiveWordScanVO {

    /**
     * 命中的最高等级（{@code 0} = 一个词都没命中）。
     *
     * <p>★★ <b>{@code 0} 与「没有这一行」是两件事</b>（判断 3.6 的同一条）：
     * {@code maxLevel=0} 的语义是「扫过了，干净」，而「没扫成」在本类里
     * <b>压根不产生一个 200 的响应</b> —— 它是 {@code AI_WORD_LIB_UNAVAILABLE(5007)}。
     * ★ 所以这个字段<b>永远不会</b>因为故障而变成 0，这正是它敢用基础类型的原因。
     */
    @Schema(description = "命中的最高等级，0 表示无命中")
    private Integer maxLevel;

    @Schema(description = "命中的词数（去重后）。★ 同一个词在正文里出现多次只算一条")
    private Integer hitCount;

    /** ★ 去重后的命中词。同一个词出现多次只留第一条 —— 审核要的是「命中过哪些词」，不是词频 */
    @Schema(description = "命中的词（已去重）")
    private List<SensitiveWordHitVO> hits;

    @Schema(description = "★ 本次判定所依据的快照里有多少条词（不是库里的行数）")
    private Integer wordCount;

    @Schema(description = "★ 快照版本号，每次成功 reload +1。用来证明「快照真的被替换过」")
    private Integer version;
}
