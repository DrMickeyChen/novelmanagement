package com.novel.modules.content.controller;

import com.novel.common.enums.AuditAction;
import com.novel.common.enums.AuditTargetType;
import com.novel.common.result.PageResult;
import com.novel.common.result.Result;
import com.novel.modules.content.service.AuditLogService;
import com.novel.modules.content.vo.AuditLogVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 —— 审核流水查询。
 *
 * <p><b>★ 只有 GET，没有 POST / PUT / DELETE，以后也不该有。</b>
 * 流水是「只追加」的：能改的日志不能用来追责。这里不提供删除接口，
 * 不是因为「暂时没做」，而是因为「不该做」—— 要清理历史请连数据库，
 * 那会留下数据库层的操作记录，比在应用里点一下「删除」可追查得多。
 *
 * <p><b>★ 访问控制由 URL 前缀兜住</b>（{@code /api/admin/**} → {@code hasRole("ADMIN")}）。
 * 这一点在这里尤其重要：流水里有「谁在什么时候动了哪本书」，
 * 泄露给普通用户等于把整个审核体系的内情交出去。
 *
 * @author MickeyChen
 */
@Tag(name = "23. 管理端 · 审核日志", description = "审核流水查询。★ 需要 ADMIN 角色")
@RestController
@RequestMapping("/api/admin/logs")
@RequiredArgsConstructor
public class AdminAuditLogController {

    private final AuditLogService auditLogService;

    @Operation(
            summary = "审核日志（分页）",
            description = """
                    按时间倒序（内部按自增 id 倒序，等价于时间倒序且翻页稳定）。

                    三个筛选条件都可选：

                    - `targetType`：1作品 2章节 3评论 4作者申请
                    - `action`：**1提交 2通过 3驳回 4下架 5重新上架 6退回草稿**
                    - `operatorId`：查某个管理员审过什么

                    ★ **1/2/3 是数据库列注释里就有的三个值**，4/5/6 是本期扩展的。
                    将来若按这张表统计「审核通过率」，**只统计 1/2/3** ——
                    把上下架算进分母会让通过率虚低。

                    ★ 每行都会补上**书名 / 章节名**和**操作人昵称**。
                    `targetTitle` 为空是正常情况：那个对象被逻辑删除了，
                    前端应显示成「作品 #5（已删除）」。

                    ★ 本期（P2）走完整链路会产生这些流水：
                    `SUBMIT(1)`×2（作品提交、章节提交）、`APPROVE(2)`×2
                    （作品通过、章节通过）；如果再改一次已发布的章节正文，
                    还会多一条 `REVERT(6)`。""")
    @GetMapping
    public Result<PageResult<AuditLogVO>> page(
            @Parameter(description = "对象类型：1作品 2章节 3评论 4作者申请。不传=全部")
            @RequestParam(required = false) Integer targetType,
            @Parameter(description = "动作：1提交 2通过 3驳回 4下架 5重新上架 6退回草稿。不传=全部")
            @RequestParam(required = false) Integer action,
            @Parameter(description = "操作人用户 id，不传=全部")
            @RequestParam(required = false) Long operatorId,
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数，上限 100") @RequestParam(defaultValue = "10") long size) {

        /*
         * ★ 两个枚举参数都走 fromCode（数字 → 枚举），不是声明成枚举类型。
         *   ?action=SUBMIT 会落到 Spring 默认的按名转换上，而同一个值的
         *   JSON 表示是数字 —— 一个字段两种写法迟早有人写错。
         *   统一成数字，理由详见 AuthorNovelController#myList 的注释。
         */
        return Result.success(auditLogService.page(
                AuditTargetType.fromCode(targetType),
                AuditAction.fromCode(action),
                operatorId, page, size));
    }
}
