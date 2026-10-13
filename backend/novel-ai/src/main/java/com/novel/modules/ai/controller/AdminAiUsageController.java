package com.novel.modules.ai.controller;

import com.novel.common.result.PageResult;
import com.novel.common.result.Result;
import com.novel.modules.ai.service.AiUsageService;
import com.novel.modules.ai.vo.AiUsageSummaryVO;
import com.novel.modules.ai.vo.AiUsageVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 管理端 —— AI 用量（E1）。
 *
 * <p><b>★ 访问控制由 URL 前缀统一兜住，这个类里一个 {@code @PreAuthorize} 都不需要</b>
 * （{@code ContentSecurityConfig} 已有 {@code /api/admin/**} → {@code hasRole("ADMIN")}）。
 * 同 {@code AdminAiConfigController} / {@code AdminAuditLogController}。
 *
 * <p>★ <b>本类只有 {@code @GetMapping}</b>：用量账是<b>只读</b>的。
 * 往里写只有一条路（{@code AiUsageRecorder}，在生成线程的 {@code finally} 里），
 * 而且那条路不在 HTTP 上 —— <b>没有任何接口能伪造一次调用记录</b>。
 * 这不是巧合，是这张表作为「记账表」的底线。
 *
 * <p>★ 路由不冲突：{@code /api/admin/ai/**} 落在网关给 content 的 {@code order: 5} 那条上，
 * E1 对网关零改动（同 {@code AdminAiConfigController} 的论证）。
 *
 * <h3>★★ 页面必须能分清「没有数据」和「暂时拿不到」</h3>
 *
 * <p>本类的两个端点各自带着这件事的一半：
 * <ul>
 *   <li>{@code /usage/summary} 的 {@code untrackedTokenCalls} ——
 *       「有多少次调用的 token 没统计到」，见 {@code AiUsageSummaryVO} 的类注释；</li>
 *   <li>{@code /usage} 每行的 {@code tokenUntracked}，以及<b>可以为空</b>的 {@code userName} ——
 *       null 不是「没有这个人」，是「昵称这次没取到」（auth 挂了），
 *       页面回落显示「用户 #7」。</li>
 * </ul>
 * ★ 两者的共同点：<b>都是让「拿不到」变成界面上一个能被看见的状态，而不是一个看着正常的值。</b>
 *
 * @author MickeyChen
 */
@Tag(name = "27. 管理端 · AI 用量", description = "调用流水与汇总。★ 需要 ADMIN 角色")
@RestController
@RequestMapping("/api/admin/ai/usage")
@RequiredArgsConstructor
public class AdminAiUsageController {

    private final AiUsageService aiUsageService;

    @Operation(
            summary = "分页查询 AI 调用流水",
            description = """
                    按 id 倒序（从新到旧）。筛选参数**全部可空 = 不筛**。

                    | 参数 | 说明 |
                    |---|---|
                    | `scene` | 场景码 1大纲 2续写 3灵感 4润色 5审核。**收数字不收枚举名** |
                    | `success` | `true` 只看成功 / `false` 只看失败。**不传 = 全都要** |
                    | `userId` | 调用人 |
                    | `startDate` / `endDate` | `yyyy-MM-dd`，**两端都含** |

                    ★ **`endDate` 是含当天的**：服务端会把它换算成次日 0 点再取左闭右开。
                    直接传当天日期就能查到当天的记录。

                    ★ 起止写反 → `400`（不是空列表）。空列表在这里
                    和「这段时间没人用」长得一模一样。

                    ## 每行里两个「可能不出现」的字段

                    - **`userName`**：昵称来自 auth。它挂了 → 这个字段**从 JSON 里消失** →
                      页面回落显示 `用户 #7`（**不是「佚名」** —— 那句话在这里没说真话）。
                    - **`tokenUntracked = true`**：这次调用**真的发生了**，但用量没统计到
                      （用量帧在流的末尾，中断 / 报错走不到）。此时
                      `totalTokens` 读作**「未统计」而不是「没消耗」**，
                      页面必须显示成 `未统计`，**不能显示成 0** —— 除非你想让账目
                      系统性地偏小，而且偏小的方向恰好是「看起来更省钱」。

                    ★ 失败行的 token 列不显示数字：那三列在失败行上**不承担「消费量」的语义**
                    （有的是真 0，有的是不知道），页面渲染成 `—`，不假装分得出来。""")
    @GetMapping
    public Result<PageResult<AiUsageVO>> page(
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数，上限 100（超出由 MP 的 maxLimit 夹住）")
            @RequestParam(defaultValue = "10") long size,
            @Parameter(description = "场景码：1大纲 2续写 3灵感 4润色 5审核")
            @RequestParam(required = false) Integer scene,
            @Parameter(description = "true=只看成功，false=只看失败，不传=全部")
            @RequestParam(required = false) Boolean success,
            @Parameter(description = "调用人 id") @RequestParam(required = false) Long userId,
            @Parameter(description = "起始日期（含），yyyy-MM-dd")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "结束日期（含），yyyy-MM-dd")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return Result.success(aiUsageService.pageUsage(page, size, scene, success, userId, startDate, endDate));
    }

    /*
     * ★ @DateTimeFormat 是【显式】写的，没有依赖任何全局的日期格式配置：
     *   本项目的 yml 里没有 spring.mvc.format.date，靠默认行为会得到一个
     *   「能跑，但换个 Spring 版本就可能变」的绑定。写死 ISO.DATE 之后，
     *   yyyy-MM-dd 这件事就钉在这两行上，读的人不用去翻配置。
     * ★ 传了 yyyy/MM/dd 这种格式会怎样：MethodArgumentTypeMismatchException →
     *   GlobalExceptionHandler 已经接住了，返回 400 +「参数「startDate」的值不合法：…」。
     *   不需要在这里补 try/catch。
     */

    @Operation(
            summary = "AI 用量汇总",
            description = """
                    顶部几张卡片：**今日** + **累计**，两组的字段口径完全相同。

                    | 字段 | 说明 |
                    |---|---|
                    | `calls` / `successCalls` / `failedCalls` | 调用次数 |
                    | `totalTokens` | **统计到的** token 合计 |
                    | `untrackedTokenCalls` | ★★ **成功但用量没统计到的次数** |
                    | `distinctUsers` | 有多少个不同的人用过 |

                    ## ★★ 为什么必须有 `untrackedTokenCalls`

                    因为它**大于 0 时 `totalTokens` 一定偏小**，而偏小的方向
                    恰好是「看起来更省钱」—— 最不该出错的方向。

                    页面要写成 **「今日 3,240 token（其中 4 次未统计）」**。
                    只说 `3,240` 的话，读的人会默认这是个完整的数，
                    而真相可能是「3,240 + 4 次不知道」。

                    ★ 它和明细列表里每行的 `tokenUntracked` 是**同一个判据**
                    （`success = 1 AND total_tokens = 0`）。这不是顺手写成一样的，
                    而是必须一样：卡片说「4 次未统计」，点开明细就该能数出正好 4 行。
                    **对不上的汇总比没有汇总更坏** —— 它会让人开始怀疑每一个数字。

                    `statDay` 是「今日」指的是哪一天（**服务端时区**，与配额计数器同口径）。
                    页面直接显示它，可以免掉「浏览器在别的时区时『今日』指什么」的歧义。""")
    @GetMapping("/summary")
    public Result<AiUsageSummaryVO> summary() {
        return Result.success(aiUsageService.summary());
    }
}
