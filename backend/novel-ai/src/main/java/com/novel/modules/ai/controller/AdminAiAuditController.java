package com.novel.modules.ai.controller;

import com.novel.common.result.PageResult;
import com.novel.common.result.Result;
import com.novel.modules.ai.dto.AiAuditExcludeDTO;
import com.novel.modules.ai.service.AiAuditQueueService;
import com.novel.modules.ai.vo.AiAuditRowVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 —— 违禁审核队列（E1/S9）。
 *
 * <p><b>★ 访问控制由 URL 前缀统一兜住，这个类里一个 {@code @PreAuthorize} 都不需要</b>
 * （{@code ContentSecurityConfig} 已有 {@code /api/admin/**} → {@code hasRole("ADMIN")}）。
 * 同 {@code AdminAiUsageController} / {@code AdminAiConfigController}。
 *
 * <p>★ 路由不冲突：{@code /api/admin/ai/**} 落在网关给 content 的 {@code order: 5} 那条上，
 * E1 对网关零改动。
 *
 * <h3>★★★ 这一页只有【一个】写入口，而它刻意不碰任何业务状态</h3>
 *
 * <p>{@code POST /exclude} 是<b>本页唯一</b>的写操作，它只标 {@code ai_audit_result} 一行。
 * 「确认违规」<b>没有</b>对应的按钮 —— 那件事必须去既有的处置页做
 * （章节 → 审核中心，评论 → 评论管理），因为只有那条路会过状态机、写 {@code audit_log}、
 * 补上趋势图的那个点。队列页若自己 {@code UPDATE chapter SET status=4}，
 * 就是绕开 {@code ChapterServiceImpl#transitStatus}，<b>而且不报错</b>。
 *
 * <p>★★ 于是本页的两个动作后果完全不同，页面<b>必须</b>说清楚
 * （见 {@code AiAuditQueueService#exclude} 的注释）：
 * <pre>
 *   去处置 → 内容状态真的变了，队列行由 audit_log 的联动标掉
 *   排除   → 只是「这条 AI 判定不用再看了」，内容【还在待审队列里】
 * </pre>
 * ★ 反过来，这也是「队列行不会永远停在待确认」的保证来源：
 * 处置页那一刀落下时，{@code AuditLogServiceImpl#record} 会把本行一起标掉
 * （判断 3.2 的反转：<b>写 {@code audit_log} 这件事本身就是确认</b>）。
 *
 * @author MickeyChen
 */
@Tag(name = "28. 管理端 · AI 违禁审核队列", description = "两层审核产出的人工待办。★ 需要 ADMIN 角色")
@RestController
@RequestMapping("/api/admin/ai/audit")
@RequiredArgsConstructor
public class AdminAiAuditController {

    private final AiAuditQueueService aiAuditQueueService;

    @Operation(
            summary = "分页查询 AI 审核判定",
            description = """
                    排序是**风险等级倒序 → id 倒序**：队列是给人办事的顺序，高风险排在前面。

                    | 参数 | 说明 |
                    |---|---|
                    | `targetType` | `2` 章节 / `3` 评论。**传 `1`（小说）只会得到空列表** —— AI 审核不覆盖作品简介 |
                    | `riskLevel` | `0`~`3` |
                    | `status` | `0` 待确认 / `1` 已确认违规 / `2` 已排除。**不传 = 全部** |

                    ★ 页面默认传 `status=0`（这一页叫「违禁审核」，默认视图是**待办**），
                    但服务端不写死 —— 已处置的记录同一条接口就能查，不需要第二个端点。

                    ## 每行的四个「未知」各说各的

                    | 字段 | 为 null 时表示 | 页面应当渲染成 |
                    |---|---|---|
                    | `targetTitle` | 目标被逻辑删除，或评论已被删 | `章节 #12（已删除）` |
                    | `novelId` / `novelTitle` | 书或章取不到 | **禁用**「去处置」按钮，不跳 404 |
                    | `auditUserName` | 昵称没取到（auth 挂了）／还没人处置 | `用户 #7` ／ `—` |
                    | `contentUpdatedAt` | ★ 这是**评论**（正文不可修改，没有「最后更新时间」可言） | 显示「评论不可修改」 |

                    ★★ `contentUpdatedAt` 为 null **不等于**「内容没更新过」——
                    所以它单独配了 `contentImmutable` 这个布尔量。
                    少了它，页面只能把「评论」和「章节但时间丢了」渲染成同一个样子。
                    **「不知道」和「不存在」不是一回事。**

                    ## ★ `aiState` —— 「这一行到底跑没跑模型」

                    `NOT_CALLED`（关键词层无命中，**一分钱没花**）/ `SKIPPED`（命中了但模型没复核，
                    风险等级来自本地词库）/ `NO_REASON`（模型回了但没有理由）/ `RAN`。
                    ★ 它和 `aiComment` 的文本是**同一件事的两种说法**，服务端只认
                    `AiAuditService` 里那两个常量 —— 页面不要自己去比字符串。""")
    @GetMapping
    public Result<PageResult<AiAuditRowVO>> page(
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数，上限 100（超出由 MP 的 maxLimit 夹住）")
            @RequestParam(defaultValue = "10") long size,
            @Parameter(description = "目标类型：2章节 3评论")
            @RequestParam(required = false) Integer targetType,
            @Parameter(description = "风险等级 0~3") @RequestParam(required = false) Integer riskLevel,
            @Parameter(description = "0待确认 1已确认违规 2已排除，不传=全部")
            @RequestParam(required = false) Integer status) {
        return Result.success(aiAuditQueueService.page(targetType, riskLevel, status, page, size));
    }

    @Operation(
            summary = "排除（判定为 AI 误杀）",
            description = """
                    把这条判定标成「已排除」，从待办里拿掉。

                    ## ★★★ 它【不写】`audit_log`，也不会改变内容的任何状态

                    判据是「有没有状态流转可记」（与「发表评论不记流水」同一条论证）：
                    这个动作什么状态都没变，只是「这条 AI 判定不用再看了」。

                    ★★ **所以调用方必须知道**：点了排除**不代表内容已经被放过**，
                    它还在原来的待审队列里。管理员若认为内容确实违规，
                    **仍然**要去处置页驳回。

                    ## 失败

                    | 情况 | 返回 |
                    |---|---|
                    | 这行已经不是待办了（别人先处置了 / 自己点了两次） | `code 5010`，**HTTP 200** |
                    | 该目标压根没有过 AI 判定 | 同上（`0` 行受影响） |
                    | 不是 ADMIN | `403` |

                    ★ `5010` 而不是「装作成功的 200」是刻意的：这一页是**追责页面**，
                    对一条「已确认违规」的行回一句「已排除」是在说反方向的假话。
                    「幂等」指的是**不产生额外副作用**，不是**必须告诉重复调用者成功**。""")
    @PostMapping("/exclude")
    public Result<Void> exclude(@Valid @RequestBody AiAuditExcludeDTO dto) {
        aiAuditQueueService.exclude(dto.getTargetType(), dto.getTargetId());
        return Result.success();
    }

    /*
     * ★ 本类【没有】「确认违规」端点，这是刻意的，不是没写完 ——
     *   见类注释与 AiAuditQueueService#exclude 的论证。
     *   ★ 加它之前先回答一个问题：这个端点如何在不绕开
     *   ChapterServiceImpl#transitStatus / CommentServiceImpl 审核路径的前提下写 audit_log？
     *   答不上来就不要加。
     */
}
