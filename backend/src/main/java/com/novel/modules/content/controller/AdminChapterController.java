package com.novel.modules.content.controller;

import com.novel.common.enums.ChapterStatus;
import com.novel.common.result.PageResult;
import com.novel.common.result.Result;
import com.novel.modules.content.dto.AuditDTO;
import com.novel.modules.content.service.ChapterService;
import com.novel.modules.content.vo.ChapterDetailVO;
import com.novel.modules.content.vo.ChapterVO;
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
 * 管理端 —— 章节审核。
 *
 * <p><b>★ 访问控制由 URL 前缀兜住</b>（{@code /api/admin/**} → {@code hasRole("ADMIN")}），
 * 类里没有 {@code @PreAuthorize}。
 *
 * <p><b>★ 章节审核和作品审核有一个真实的不对称，别以为是漏写：</b>
 * <pre>
 *   作品审核  → Service 里必须先判「当前是不是待审核」  （因为状态机允许 已下架 → 已上架）
 *   章节审核  → 不用判                                  （章节没有「已下架」状态）
 * </pre>
 * 章节的 {@code PUBLISHED} 和 {@code REJECTED} 都只能由 {@code PENDING} 到达，
 * 状态机本身就已经把它限死了。所以在章节这边，「审核通过」不可能和别的流转撞车。
 *
 * <p><b>★★ 这里没有「下架章节」的接口，而且不该有。</b>
 * 章节不能单独下架，只能跟着作品一起下架 —— 因为读者是顺着作品进来的，
 * 作品在架上、某一章却不见了，读者看到的是「目录里有一章点不开」。
 * 要撤掉某一章的内容，用**驳回**（章节会退回给作者改）。
 * 要把整本书撤下来，用 {@code PUT /api/admin/novels/{id}/status}。
 *
 * @author MickeyChen
 */
@Tag(name = "22. 管理端 · 章节审核", description = "管理员审核章节。★ 需要 ADMIN 角色")
@RestController
@RequestMapping("/api/admin/chapters")
@RequiredArgsConstructor
public class AdminChapterController {

    private final ChapterService chapterService;

    @Operation(
            summary = "章节列表（审核队列）",
            description = """
                    **跨作品**，含全部状态。最常用的用法是 `?status=1` 拉出待审章节。

                    ★ 每一行都带 **`novelTitle`（书名）** —— 列表里混着好几本书的章节，
                    不显示书名审核员根本不知道该审的是哪本。

                    ★ 结果**按最近改动倒序**（`update_time` DESC）：
                    筛「待审核」时，刚提交的一定在最前面，不用从第 1 章开始翻。

                    **不返回正文** —— 正文只在 `GET /{id}` 里返回（点开某一章时才调）。

                    可以配合 `?novelId=` 只看某一本书的章节。""")
    @GetMapping
    public Result<PageResult<ChapterVO>> list(
            @Parameter(description = "作品 id，不传=全部作品")
            @RequestParam(required = false) Long novelId,
            @Parameter(description = "状态筛选：0草稿 1待审核 2已发布 4已驳回。★ 没有 3。不传=全部")
            @RequestParam(required = false) Integer status,
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数，上限 100") @RequestParam(defaultValue = "10") long size) {

        return Result.success(chapterService.adminPage(
                novelId, ChapterStatus.fromCode(status), page, size));
    }

    @Operation(
            summary = "章节详情（含正文）",
            description = """
                    **审核员必须先看到正文才能审** —— 只看标题点通过等于没审。
                    对应大纲里「弹窗预览正文后通过 / 驳回」那一步。

                    ★ 保留 `auditRemark`（历史驳回理由），审核员据此判断
                    「上次为什么没过、这次改了没有」。

                    章节不存在返回 `3001`。""")
    @GetMapping("/{id}")
    public Result<ChapterDetailVO> detail(@PathVariable Long id) {
        return Result.success(chapterService.adminDetail(id));
    }

    @Operation(
            summary = "审核章节：通过 / 驳回",
            description = """
                    通过 → **已发布**（读者立刻能在目录里看到、能读正文）；
                    驳回 → **已驳回**（作者需修改后重新提交）。

                    ★ 通过时会在**同一个事务内**重算作品的章节数和总字数 ——
                    所以审核通过的瞬间，作品详情页上的「共 12 章 / 25.6 万字」就变了。
                    流水和状态变更要么都成、要么都不成。

                    ★ **章节不能单独下架**，所以这个接口没有「下架」这个选项。
                    要撤掉某一章，用驳回；要把整本书撤下来，用作品的上下架接口。""")
    @PutMapping("/{id}/audit")
    public Result<Void> audit(@PathVariable Long id, @Valid @RequestBody AuditDTO dto) {
        chapterService.audit(id, dto.getPass(), dto.getRemark());
        return Result.success(dto.getPass() ? "审核通过，章节已发布" : "已驳回", null);
    }
}
