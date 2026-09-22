package com.novel.modules.content.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 大盘的 N 天趋势 —— 三条并列的序列。
 *
 * <pre>
 * {
 *   "days":     ["2026-08-23", "2026-08-24", ..., "2026-09-21"],   ← 30 个，一天不缺
 *   "novels":   [0, 3, 1, ..., 5],
 *   "users":    [0, 0, 4, ..., 2],
 *   "comments": [0, 0, 0, ..., 9]
 * }
 * </pre>
 *
 * <p><b>★★ 三个数组一定<b>等长</b>，而且长度 = {@code days.size()}，
 * 这是这个类唯一的契约。</b>
 * 它们的下标是同一个含义：{@code novels[i]} 就是 {@code days[i]} 那一天的新增数。
 * 前端 <b>不要</b>自己按日期去对，直接按下标取 ——
 * ECharts 的 {@code xAxis.data = days}、{@code series[].data = novels}
 * 就是这么用的。
 *
 * <p><b>★ 为什么是「三个平行数组」而不是「一个对象数组」：</b>
 * 后者（{@code [{day, novels, users, comments}, ...]}）语义更内聚，
 * 但前端拿到之后为了喂给 ECharts 还得拆一遍，而拆的过程中
 * <b>任何一天漏了一个字段都不会报错</b>，只会在图上少一个点。
 * 平行数组把「长度」变成唯一需要保证的东西，而这个东西在
 * {@code DashboardServiceImpl} 里由构造方式本身保证（见那里的注释）。
 *
 * <p><b>★★ 数组里没有「缺的天」—— 没有数据的那天是 0，不是 null、更不是跳过。</b>
 * 这句是整个大盘最容易做错的一处，展开说见
 * {@code DashboardServiceImpl#trend} 的注释。简单说：
 * {@code GROUP BY DATE(create_time)} 只会返回<b>有数据的那几天</b>，
 * 直接丢给折线图，ECharts 会把「9/1 有 5 本」和「9/20 有 3 本」两点
 * <b>连成一条直线</b>，中间 18 天的 0 完全看不出来 ——
 * 那不是显示不好看，是<b>图在说谎</b>。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "大盘趋势（按天，零填充）")
public class TrendVO {

    /**
     * 完整的日期轴，形如 {@code "2026-09-21"}，从早到晚。
     *
     * <p>长度 = 请求的 {@code days}（已被夹到 1~90），最后一个是今天。
     */
    @Schema(description = "日期轴（yyyy-MM-dd，连续无缺口）", example = "[\"2026-09-19\",\"2026-09-20\",\"2026-09-21\"]")
    private List<String> days;

    @Schema(description = "每日新增作品数，与 days 一一对应", example = "[2,0,5]")
    private List<Long> novels;

    @Schema(description = "每日新增用户数，与 days 一一对应", example = "[0,1,3]")
    private List<Long> users;

    @Schema(description = "每日新增评论数，与 days 一一对应", example = "[7,4,0]")
    private List<Long> comments;
}
