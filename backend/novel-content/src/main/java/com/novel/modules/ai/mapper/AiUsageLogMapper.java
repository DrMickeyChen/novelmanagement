package com.novel.modules.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.novel.modules.ai.entity.AiUsageLog;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * AI 调用流水 Mapper（E1）。
 *
 * <p>S4 之前这个类<b>零手写 SQL</b>（只写不读，全部走 {@code BaseMapper}）。
 * S5 加了用量页，于是这里有了两条 {@code @Select} —— 但<b>只加真正有人调的</b>，
 * 没有先写好一堆备用的查询。
 *
 * <p>★★ 手写 {@code @Select} 时记住一条本项目已记在案的坑：
 * <b>{@code @TableLogic} 对手写 SQL 完全不生效</b>。这张表本来也没有
 * {@code deleted} 列（见 {@code AiUsageLog} 的类注释），所以这里没有那个坑，
 * 但下一个往这个文件里加 {@code @Select} 的人要自己确认查询条件。
 *
 * @author MickeyChen
 */
@Mapper
public interface AiUsageLogMapper extends BaseMapper<AiUsageLog> {

    /* ==================== 聚合（S5 用量页用） ==================== */

    /**
     * 统计一个时间区间内的调用情况。<b>区间是左闭右开</b> {@code [from, to)}。
     *
     * <p>★★★ <b>这里最要紧的是最后一个字段 {@link StatRow#getUntrackedTokenCalls}</b>，
     * 它存在的理由是：{@code total_tokens} 这一列<b>结构上分不出「没消耗」和「没统计到」</b>
     * （三列都是 {@code INT NOT NULL DEFAULT 0}，见 {@code AiFailure#ABORTED} 的注释）。
     *
     * <p>所以「今天一共花了 N 个 token」这句话，在<b>有中断调用</b>的那些天里
     * <b>必然偏小</b>，而它偏小的方向恰好是「看起来更省钱」——
     * 这正是最不该出错的方向。让这个数字单独可查，页面就能说
     * 「其中 3 次未统计」而不是假装那个和是完整的。
     *
     * <p>★ 判据：{@code SUM(total_tokens) == 0} 且 {@code calls > 0}
     * <b>不等于</b>「没花钱」。要区分这两件事，只能靠这一列。
     *
     * <p>★ {@code CAST(... AS SIGNED)} 不是装饰：MySQL 的 {@code SUM()} 返回
     * {@code DECIMAL}，不 CAST 就得靠驱动替我们转成 {@code Long} ——
     * 而「驱动替我们转」这种事在换驱动版本时是不保证的。
     */
    @Select("""
            SELECT COUNT(*)                                                          AS calls,
                   CAST(COALESCE(SUM(success = 1), 0) AS SIGNED)                     AS successCalls,
                   CAST(COALESCE(SUM(success = 0), 0) AS SIGNED)                     AS failedCalls,
                   CAST(COALESCE(SUM(total_tokens), 0) AS SIGNED)                    AS totalTokens,
                   CAST(COALESCE(SUM(success = 1 AND total_tokens = 0), 0) AS SIGNED) AS untrackedTokenCalls,
                   COUNT(DISTINCT user_id)                                           AS distinctUsers
            FROM ai_usage_log
            WHERE create_time >= #{from} AND create_time < #{to}
            """)
    StatRow statBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 累计统计（不限时间）。
     *
     * <p>★ 与 {@link #statBetween} 是<b>两个方法而不是一个带可选条件的方法</b>，
     * 因为 {@code <if>} 动态 SQL 在这里换不来任何东西：条件只有「有 / 没有」两种，
     * 而两张查询的语义本来就不同（一个是「今天」，一个是「从有这张表以来」）。
     * 写成一个之后，读的人要多想一步「这次传没传参数」。
     */
    @Select("""
            SELECT COUNT(*)                                                          AS calls,
                   CAST(COALESCE(SUM(success = 1), 0) AS SIGNED)                     AS successCalls,
                   CAST(COALESCE(SUM(success = 0), 0) AS SIGNED)                     AS failedCalls,
                   CAST(COALESCE(SUM(total_tokens), 0) AS SIGNED)                    AS totalTokens,
                   CAST(COALESCE(SUM(success = 1 AND total_tokens = 0), 0) AS SIGNED) AS untrackedTokenCalls,
                   COUNT(DISTINCT user_id)                                           AS distinctUsers
            FROM ai_usage_log
            """)
    StatRow statAll();

    /* ==================== 查询结果的载体 ==================== */

    /**
     * 一段时间的调用统计。
     *
     * <p><b>★ 为什么定义成嵌套类，而不是放进 {@code modules/ai/vo/}：</b>
     * 它是这个 Mapper 的<b>内部行类型</b>，不是对外的接口契约 ——
     * 字段名就是 SQL 别名（{@code calls} / {@code successCalls} …），
     * 而对外那个 {@code AiUsageSummaryVO} 的字段名和口径是<b>分开定的</b>
     * （它还要带上「统计的是哪一天」这类 SQL 里没有的东西）。
     * 放进 vo 包会让人以为它是个 DTO，然后有人拿它当接口出参用，
     * 于是「这个数字什么意思」就有了两个说法。
     *
     * <p>★ 全部用包装类型 {@code Long}：聚合查询在空表上返回的是
     * {@code COUNT(*)=0} 加一堆 {@code NULL}（{@code COALESCE} 兜了，
     * 但兜不兜得住取决于 SQL 有没有写错），用 {@code long} 接的话
     * 一个 {@code null} 就是 NPE。
     */
    @Data
    class StatRow {
        /** 调用总次数（含失败） */
        private Long calls;
        private Long successCalls;
        private Long failedCalls;
        /** ★ 只是「统计到的」token 数，不是「实际消耗」—— 见 {@link #untrackedTokenCalls} */
        private Long totalTokens;
        /** ★★ 成功但 token 为 0 的调用次数，即「烧了钱但没统计到」 */
        private Long untrackedTokenCalls;
        /** 有多少个不同的用户调用过 */
        private Long distinctUsers;
    }
}
