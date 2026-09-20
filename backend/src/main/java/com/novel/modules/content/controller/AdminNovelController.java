package com.novel.modules.content.controller;

import com.novel.common.enums.NovelStatus;
import com.novel.common.result.PageResult;
import com.novel.common.result.Result;
import com.novel.modules.content.dto.AuditDTO;
import com.novel.modules.content.dto.NovelStatusDTO;
import com.novel.modules.content.service.NovelService;
import com.novel.modules.content.vo.NovelDetailVO;
import com.novel.modules.content.vo.NovelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 —— 作品审核。
 *
 * <p><b>★ 访问控制由 URL 前缀兜住</b>（{@code /api/admin/**} → {@code hasRole("ADMIN")}），
 * 所以这个类里没有 {@code @PreAuthorize}。
 *
 * <p><b>★★ 这里【没有】归属校验，这是它和 {@code AuthorNovelController} 最本质的区别。</b>
 * 管理员审的就是别人的书 —— 给他做 {@code requireOwnedNovel} 等于审核功能不存在。
 * 所以 Service 层用的是 {@code requireNovel}（只判存在），不是 {@code requireOwnedNovel}。
 * <pre>
 *   URL 闸管门    /api/admin/** 要 ADMIN      → 挡住作者
 *   归属校验管物  requireOwnedNovel            → 挡住别的作者（★ 只对作者端生效）
 * </pre>
 *
 * <p><b>★ 审核和上下架是两个接口，不是因为有区别，是因为日志要分开记。</b>
 * 「审核通过」和「重新上架」在状态机上是同一个流转（都到 PUBLISHED），
 * 但一个是被动审批、一个是主动调整，日志里混成一条就再也说不清
 * 「这本书是自己上架的还是被审出来的」。
 *
 * @author MickeyChen
 */
@Tag(name = "21. 管理端 · 作品审核", description = "管理员审核 / 上下架作品。★ 需要 ADMIN 角色")
@RestController
@RequestMapping("/api/admin/novels")
@RequiredArgsConstructor
public class AdminNovelController {

    private final NovelService novelService;

    @Operation(
            summary = "作品列表（审核队列）",
            description = """
                    **不过滤状态** —— 含草稿、待审核、已驳回、已下架，全部能查到。

                    最常用的用法是 `?status=1` 拉出待审核队列。
                    结果**按最近改动倒序**（`update_time` DESC），
                    刚提交审核的书一定在最前面 —— 这正是管理员要找的。

                    和读者端的区别只有一个「加不加 `status = 2` 这个条件」，
                    但这一个差别决定了审核功能能不能用：加了就永远看不到待审核的书。""")
    @GetMapping
    public Result<PageResult<NovelVO>> list(
            @Parameter(description = "状态筛选：0草稿 1待审核 2已上架 3已下架 4已驳回。不传=全部")
            @RequestParam(required = false) Integer status,
            @Parameter(description = "分类 id，不传=全部")
            @RequestParam(required = false) Long categoryId,
            @Parameter(description = "书名关键词，模糊匹配")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数，上限 100") @RequestParam(defaultValue = "10") long size) {

        return Result.success(novelService.adminPage(
                NovelStatus.fromCode(status), categoryId, keyword, page, size));
    }

    @Operation(
            summary = "作品详情（审核用）",
            description = """
                    含**简介**和**全部状态的章节目录** —— 审核员要看完这些才能判断。

                    ★ 和作者端的区别：这里**不做归属校验**（管理员审的就是别人的书），
                    也**保留 `auditRemark`**（审核员要看历史驳回理由，
                    才能判断「上次为什么没过、这次改了没有」）。

                    作品不存在返回 `2001`。""")
    @GetMapping("/{id}")
    public Result<NovelDetailVO> detail(@PathVariable Long id) {
        return Result.success(novelService.adminDetail(id));
    }

    @Operation(
            summary = "审核作品：通过 / 驳回",
            description = """
                    通过 → **已上架**（读者立刻能查到）；驳回 → **已驳回**（作者需修改后重新提交）。

                    **★ 只接受「待审核」的作品**，其余状态一律返回 `2003`。

                    为什么状态机之外还要加这道前置判断：状态机是允许
                    `已下架 → 已上架` 的（那是「重新上架」，走
                    `PUT /{id}/status`），所以光靠状态机挡不住
                    「对一本已下架的书点审核通过」—— 这个判断是必须的，不是冗余。
                    驳回一本已下架的书也一样：它的状态不会变成「已驳回」。

                    ★ 通过时**同一个事务内**会重算作品的章节数/字数并写一条审核流水，
                    流水和状态变更要么都成、要么都不成。""")
    @PutMapping("/{id}/audit")
    public Result<Void> audit(@PathVariable Long id, @Valid @RequestBody AuditDTO dto) {
        novelService.audit(id, dto.getPass(), dto.getRemark());
        return Result.success(dto.getPass() ? "审核通过，作品已上架" : "已驳回", null);
    }

    @Operation(
            summary = "上架 / 下架",
            description = """
                    **只接受 `{"status": 2}`（上架）和 `{"status": 3}`（下架）**，
                    传别的（含 0 草稿、1 待审核）返回 `400`。

                    ★ `status` **传数字，不要传 `"PUBLISHED"` 这类枚举名** ——
                    实测：传字符串会得到 HTTP 400。前端把列表接口返回的
                    `status` 原样回传即可，不用做任何映射。

                    **★ 下架会连带影响读者端（约束 2）：**
                    作品一下架，它的**所有章节正文也立刻读不到了**
                    （`GET /api/novels/{id}/chapters/{cid}` 返回 `2001`），
                    整本书从读者端消失。这是下架和下架章节的区别 ——
                    章节不能被单独下架，只能跟着作品走。

                    ★ 「审核通过」和「重新上架」在状态机上都到「已上架」，
                    但日志里分别记成 `APPROVE(2)` 和 `REPUBLISH(5)`，
                    这样才说得清这本书是怎么上架的。""")
    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @Valid @RequestBody NovelStatusDTO dto) {
        novelService.changeStatus(id, dto.getStatus(), dto.getRemark());
        return Result.success(dto.getStatus() == NovelStatus.PUBLISHED ? "已上架" : "已下架", null);
    }
}
