package com.novel.modules.content.controller;

import com.novel.common.result.Result;
import com.novel.modules.content.service.DashboardService;
import com.novel.modules.content.vo.DashboardStatsVO;
import com.novel.modules.content.vo.StatusDistVO;
import com.novel.modules.content.vo.TrendVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 —— 数据大盘。
 *
 * <p><b>★ 访问控制由 URL 前缀兜住</b>（{@code /api/admin/**} → {@code hasRole("ADMIN")}），
 * 这个类里没有 {@code @PreAuthorize}，也不用往 {@code SecurityConfig} 里加一行。
 * 这是 P1 就设计好的：新加的管理端接口<b>自动继承</b>那道闸，
 * 漏配的可能性为零 —— 反过来，如果哪天有人把这条前缀规则删了，
 * 这个类里的三个接口会<b>一起</b>变成匿名可访问，所以那条规则不能动。
 *
 * <p><b>★★ 这个类在 {@code modules/content/} 下，不在 {@code modules/system/}。</b>
 * 理由见 {@code DashboardService} 的类注释（会反转依赖箭头、M2 拆不开）。
 * 别因为「它是管理端的」就把它挪到 system 去。
 *
 * <p><b>★ 三个接口分三个请求，不是一个「大盘」大接口。</b>
 * 首屏要的是卡片，图表可以晚半秒 —— 合在一起的话，
 * 趋势的 GROUP BY 扫表慢一点，连上面那八个数字都跟着出不来。
 * 分开之后卡片先渲染，图在转圈，体验反而更好。
 *
 * @author MickeyChen
 */
@Tag(name = "26. 管理端 · 数据大盘", description = "卡片数字 / 30 天趋势 / 状态分布。★ 需要 ADMIN 角色")
@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final DashboardService dashboardService;

    @Operation(
            summary = "顶部卡片：八个数字",
            description = """
                    规模指标 4 个：作品总数 / 用户总数 / 评论总数 / 今日新增用户
                    待办指标 4 个：待审作品 / 待审章节 / 待审评论 / 待处理作者申请

                    ★ 八个数字来自**同一条 SQL**，是同一瞬间的快照 ——
                    分八次查的话，两次查询之间有人提交了作品，就会出现
                    「作品总数 86、待审 3，但列表点进去只有 2 本」这种对不上。

                    ★ **全部不含已删除的行**（@TableLogic 对手写 SQL 无效，
                    所以每个子查询都自己写了 `deleted = 0`）。

                    ★ 「今日」按**应用时区**算，不是数据库时区。""")
    @GetMapping("/stats")
    public Result<DashboardStatsVO> stats() {
        return Result.success(dashboardService.stats());
    }

    @Operation(
            summary = "最近 N 天的新增趋势（零填充）",
            description = """
                    返回日期轴 + 三条序列（新增作品 / 新增用户 / 新增评论）。

                    ★★ **没有数据的那天是 0，而且是完整返回的，一天不缺。**
                    这一点是必须的：`GROUP BY DATE(create_time)` 只返回有数据的天，
                    直接喂给折线图的话，ECharts 会把两个有数据的点**连成一条直线** ——
                    中间断档的 18 天在图上完全看不出来，
                    图会显示出一条不存在的「稳定增长」趋势。

                    ★ 四个数组**等长**，按**下标**一一对应，前端不要自己按日期去对。

                    ★ `days` 会被夹到 **1~90**：不夹的话 `?days=100000`
                    会在内存里造十万个日期对象，一个请求打满 CPU。
                    超限不报错，按 90 处理，并写一条 warn 日志。""")
    @GetMapping("/trend")
    public Result<TrendVO> trend(
            @Parameter(description = "天数，默认 30，夹到 1~90")
            @RequestParam(defaultValue = "30") int days) {
        return Result.success(dashboardService.trend(days));
    }

    @Operation(
            summary = "作品 / 章节的状态分布（零填充）",
            description = """
                    给两个饼图用。每一项是 `{status, label, count}`。

                    ★★ **某种状态一条都没有时，会返回一条 `count = 0`。**
                    饼图里跳过一个分类，那个分类会**从图例里彻底消失** ——
                    于是「已驳回 0 本」和「系统里没有这个状态」长得一模一样。

                    ★ 章节**没有**「已下架」（3），章节状态是 0/1/2/4。
                    所以两个数组长度不同（作品 5 项、章节 4 项），这是**正确**的。

                    ★ 前端转 ECharts 时保留 `status` 数字用于固定扇区颜色，
                    **不要**按数组下标分配颜色 —— 某天某个状态变成 0 之后，
                    后面所有扇区的颜色会整体错位。""")
    @GetMapping("/status-dist")
    public Result<StatusDistVO> statusDist() {
        return Result.success(dashboardService.statusDist());
    }
}
