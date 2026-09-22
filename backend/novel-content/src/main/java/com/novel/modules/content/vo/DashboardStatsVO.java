package com.novel.modules.content.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 数据大盘的顶部卡片 —— 八个数字，一次请求全给它。
 *
 * <p><b>★ 为什么是「一个 VO」而不是「八个接口」：</b>
 * 这八个数字是<b>同一屏</b>上的东西，用户打开大盘就是全都要看。
 * 拆成八个接口的话，首屏要发八个请求，任何一个慢/失败都会让某一格
 * 永远停在「—」上，而用户没法判断是「这个数就是 0」还是「没查到」。
 *
 * <p><b>★ 为什么不是一个泛型的 {@code Map<String, Object>}：</b>
 * 用 Map 的话前端就得靠字符串 key 取值（{@code data.novelTotal}），
 * 拼错了不报错、只是显示 undefined；而且后端也没法在编译期
 * 检查「这个字段到底有没有人给」。字段名写死在一个类里，
 * 改名时 IDE 能一次性找全所有引用。
 *
 * <p><b>★★ 八个字段的「总数」和「待办」是两种含义，别在界面上混着排：</b>
 * <pre>
 *   规模指标：novelTotal / userTotal / commentTotal        —— 只增不减，看趋势
 *   待办队列：novelPending / chapterPending /
 *             commentPending / authorApplyPending         —— 管理员要去处理的东西
 * </pre>
 * 大盘上这两组要分开摆（上面一行数字，下面一行带角标的待办入口），
 * 否则「待审作品 3」和「用户总数 137」并排显示，看起来是同一类信息。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "数据大盘 —— 顶部卡片")
public class DashboardStatsVO {

    /* ==================== 规模指标 ==================== */

    @Schema(description = "作品总数（不含已删除）", example = "86")
    private Long novelTotal;

    @Schema(description = "用户总数（不含已注销）", example = "137")
    private Long userTotal;

    @Schema(description = "评论总数（不含用户自己删掉的）", example = "412")
    private Long commentTotal;

    /**
     * 今日新增用户。
     *
     * <p><b>★ 这个数字的「今天」是按<b>应用时区</b>算的</b>（见
     * {@code DashboardServiceImpl}，起点由 Java 的 {@code LocalDate.now()} 给出）。
     * 不能写成 SQL 里的 {@code DATE(create_time) = CURDATE()} ——
     * 那用的是<b>数据库时区</b>。两边不一致时（容器 UTC、MySQL 东八区），
     * 每天 08:00 之前这个数字会突然跳到「昨天」，而没有任何报错。
     */
    @Schema(description = "今日新增用户", example = "5")
    private Long userToday;

    /* ==================== 待办队列 ==================== */

    /**
     * 待审作品数。
     *
     * <p>★ 注意这里数的是 <b>{@code status = 待审核}</b> 的作品，
     * 和「待审章节」是<b>两个独立的队列</b> —— 一本书被提交审核时
     * 它的章节是各自提交的，所以同一时刻可能既有待审作品又有待审章节，
     * 它们不是同一批东西。界面上要分开显示、分别能点进去。
     */
    @Schema(description = "待审作品数", example = "3")
    private Long novelPending;

    @Schema(description = "待审章节数", example = "27")
    private Long chapterPending;

    @Schema(description = "待审评论数", example = "8")
    private Long commentPending;

    @Schema(description = "待处理的作者申请数", example = "2")
    private Long authorApplyPending;
}
