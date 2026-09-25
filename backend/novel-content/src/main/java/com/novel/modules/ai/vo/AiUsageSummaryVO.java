package com.novel.modules.ai.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * AI 用量汇总（E1 用量页顶部的几张卡片）。
 *
 * <h3>★★★ {@link Stat#untrackedTokenCalls} 是这个 VO 存在的理由</h3>
 *
 * <p>不加它的话，这里就只剩一个 {@code totalTokens} 的和 ——
 * 而那个和<b>在中止 / 报错的那些调用上是偏小的</b>（用量帧在流的末尾，
 * 没走到就没有）。偏小的方向恰好是「看起来更省钱」。
 *
 * <p>更糟的是<b>它偏小得没有痕迹</b>：页面显示「今日 3,240 token」，
 * 读者会默认这是个完整的数。而真相可能是「3,240 + 4 次不知道」。
 *
 * <p>★ 所以这里显式带上计数，页面就能写成
 * 「今日 3,240 token（其中 4 次未统计）」——
 * <b>把不知道的那个部分也变成一个数字</b>，这是本项目一贯的做法
 * （铁律 10：降级要能被诚实地渲染出来）。
 *
 * <p>★★ <b>它和明细列表里每行的 {@code tokenUntracked} 用的是同一个判据</b>
 * （{@code success = 1 AND total_tokens = 0}）。这不是顺手写成一样的，
 * 而是必须一样：卡片说「4 次未统计」，管理员点开明细应该能数出正好 4 行。
 * <b>对不上的汇总比没有汇总更坏</b> —— 它会让人开始怀疑每一个数字。
 *
 * <h3>★ 为什么 today / total 各是一个嵌套对象，而不是把字段铺平加前缀</h3>
 *
 * <p>铺平就得写 {@code todayCalls} / {@code totalCalls} / {@code todayTokens} / … 十二个字段，
 * 而<b>「今天」和「累计」的口径完全是同一套</b>。嵌套之后，
 * 「口径」这件事只用写一遍（见 {@link Stat}），少掉一半的注释和一半的抄错机会。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "AI 用量汇总")
public class AiUsageSummaryVO {

    /**
     * 「今天」统计的是哪一天，{@code yyyy-MM-dd}。
     *
     * <p>★ 为什么要把这一天显式送出去：它的边界是<b>服务端时区</b>的
     * {@code LocalDate.now()}（与配额计数器同一个口径，见 {@code AiQuotaServiceImpl}）。
     * 浏览器在别的时区时，「今日」两个字本身是有歧义的 ——
     * 带上日期，页面就能写成「今日（2026-09-24）」，歧义消失。
     *
     * <p>★ 用 {@code String} 而不是 {@code LocalDate}：抄 {@code DashboardMapper}
     * 里日键那个既有取舍 —— 序列化格式不依赖任何全局配置，前端拿到的就是原样的 {@code yyyy-MM-dd}。
     */
    @Schema(description = "「今日」指的是哪一天（服务端时区）", example = "2026-09-24")
    private String statDay;

    @Schema(description = "今日")
    private Stat today;

    @Schema(description = "累计（不限时间）")
    private Stat total;

    /**
     * 一组统计口径。<b>今天和累计共用这一个类</b>，所以「哪个数字是什么意思」
     * 只需要在一个地方说清楚。
     */
    @Data
    @Schema(description = "一组调用统计")
    public static class Stat {

        @Schema(description = "调用总次数（含失败）")
        private Long calls;

        @Schema(description = "成功次数")
        private Long successCalls;

        /**
         * ★ 失败次数。
         *
         * <p>它<b>不等于</b>「没花钱」：失败里既有连都没连上的（真 0），
         * 也有生成到一半断掉的（烧了但不知道多少，见 {@link #untrackedTokenCalls}）。
         */
        @Schema(description = "失败次数。★ 失败不等于没花钱，见 untrackedTokenCalls")
        private Long failedCalls;

        /**
         * ★ 只是「统计到的」token 合计，<b>不是「实际消耗」</b>。
         * 页面必须和 {@link #untrackedTokenCalls} 一起显示。
         */
        @Schema(description = "统计到的 token 合计。★ 不是实际消耗 —— 必须与 untrackedTokenCalls 一起显示")
        private Long totalTokens;

        /** ★★ 见类注释：成功、但用量没统计到的调用次数 */
        @Schema(description = "成功但用量未统计到的次数。★ 大于 0 时 totalTokens 一定偏小")
        private Long untrackedTokenCalls;

        /** 有多少个不同的用户调用过 —— 它是「AI 有没有人用」的唯一直接证据 */
        @Schema(description = "调用过的不同用户数")
        private Long distinctUsers;
    }
}
