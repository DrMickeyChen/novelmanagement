package com.novel.modules.content.controller;

import com.novel.common.enums.ChapterStatus;
import com.novel.common.result.Result;
import com.novel.modules.content.dto.ChapterCreateDTO;
import com.novel.modules.content.dto.ChapterSaveDTO;
import com.novel.modules.content.service.ChapterService;
import com.novel.modules.content.vo.ChapterDetailVO;
import com.novel.modules.content.vo.ChapterVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 作者端 —— 章节管理。
 *
 * <p><b>★ 这个类的 URL 有两段前缀，这是有意的，不是笔误：</b>
 * <pre>
 *   /api/author/novels/{novelId}/chapters   列表 + 新建（章节属于某本书，要带上书）
 *   /api/author/chapters/{id}               详情 / 保存 / 提交 / 删除（已经在操作具体一章了）
 * </pre>
 * 所以类上没有 {@code @RequestMapping}，每个方法自己写全路径。
 * 硬凑一个公共前缀就得写成 {@code /api/author}，那反而看不出这两组的区别。
 *
 * <p><b>★ 访问控制由 URL 前缀兜住</b>（{@code /api/author/**} → AUTHOR 或 ADMIN），
 * 所以这个类里没有 {@code @PreAuthorize}；<b>真正的边界是 Service 里的
 * {@code requireOwnedChapter}</b> —— 它经 {@code novel.author_id} 判定归属，
 * 不是自己的返回 {@code 3002} 并打一条 {@code [越权尝试]} 的 WARN。
 *
 * <p><b>★★ 这个类里没有一个方法接收 {@code status} 或 {@code chapterNo}</b>：
 * <ul>
 *   <li>序号由后端分配（{@code MAX+1}），前端传不了 —— 否则两个标签页同时
 *       建章会传同一个号，而且作者能填个 999 把目录撑出巨大空洞</li>
 *   <li>状态的唯一入口是 {@code /{id}/submit}（业务意图），不是
 *       {@code PUT /{id} {"status":2}}</li>
 * </ul>
 *
 * <p><b>★ 注意「保存」和「提交」是两步，这是刻意的</b>：
 * 保存草稿可以反复做几十次（写作过程），提交审核只有准备好时做一次，
 * 而且提交之后正文就锁住了（待审核期间不能改，改了审核员看到的就是另一版）。
 * 合成一个接口的话，作者每按一次 Ctrl+S 都会进一次审核队列。
 *
 * @author MickeyChen
 */
@Tag(name = "13. 作者端 · 章节", description = "作者的章节编辑。★ 需要 AUTHOR 或 ADMIN 角色")
@RestController
@RequiredArgsConstructor
public class AuthorChapterController {

    private final ChapterService chapterService;

    /* ==================== /api/author/novels/{novelId}/chapters ==================== */

    @Operation(
            summary = "某本作品的章节列表",
            description = """
                    **含全部状态**（草稿 / 待审 / 已发布 / 已驳回）—— 作者要能看到自己的全部章节。

                    ★ **不返回正文**：100 章 × 2 万字就是几 MB，正文只在
                    `GET /api/author/chapters/{id}` 里返回（那是打开编辑器时才调的）。

                    排好序（按章节序号升序），前端直接渲染目录即可。

                    作品不存在返回 `2001`；**不是自己的作品返回 `2002`**。""")
    @GetMapping("/api/author/novels/{novelId}/chapters")
    public Result<List<ChapterVO>> list(
            @Parameter(description = "作品 id") @PathVariable Long novelId,
            @Parameter(description = "状态筛选：0草稿 1待审核 2已发布 4已驳回。★ 没有 3。不传=全部")
            @RequestParam(required = false) Integer status) {

        // ★ 和 AuthorNovelController 一样显式调 fromCode：query 参数统一用数字，
        //   理由见那边 myList 里的注释（避免 body 传数字、query 传名字的割裂）
        return Result.success(chapterService.authorList(novelId, ChapterStatus.fromCode(status)));
    }

    @Operation(
            summary = "新建章节",
            description = """
                    **章节序号由后端分配**（`MAX(序号) + 1`），请求体里传不了 ——
                    DTO 上根本没有这个字段。

                    `content` **可以先不传**：允许「先建好章节占位，正文回头再写」。

                    ★ 新建的章节**永远是草稿**（status = 0），同样不接受入参。

                    ★ 注意这里**没有**「作品已上架就不能加章节」的限制，
                    这是刻意的 —— 连载中的作品天天在加新章。

                    并发下两个请求可能分到同一个序号，此时返回 `3004`
                    （该章节序号已存在），**作者重试一次即可**。""")
    @PostMapping("/api/author/novels/{novelId}/chapters")
    public Result<Long> create(@PathVariable Long novelId,
                               @Valid @RequestBody ChapterCreateDTO dto) {
        return Result.success("新建成功", chapterService.create(novelId, dto));
    }

    /* ==================== /api/author/chapters/{id} ==================== */

    @Operation(
            summary = "章节详情（含正文）",
            description = """
                    **这是全项目唯一给作者返回正文的接口** —— 编辑器就是靠它
                    把草稿重新打开。没有它，作者关掉浏览器就再也读不回自己写的正文。

                    `content` 为 `null` 时字段**不会出现在 JSON 里**
                    （全局配了 `default-property-inclusion: non_null`），
                    前端要写 `detail.content ?? ''`，否则 `undefined` 塞进
                    `<textarea>` 会显示成字符串 "undefined"。

                    同时返回 `auditRemark`（驳回理由），让作者知道上次为什么没过。""")
    @GetMapping("/api/author/chapters/{id}")
    public Result<ChapterDetailVO> detail(@PathVariable Long id) {
        return Result.success(chapterService.authorDetail(id));
    }

    @Operation(
            summary = "保存章节（标题 + 正文）",
            description = """
                    标题和正文一起存 —— 一次「保存」动作不该拆成两个请求。

                    **★★ 三种状态三种行为，这是本接口最需要注意的地方：**

                    | 当前状态 | 行为 |
                    |---|---|
                    | 草稿 / 已驳回 | 直接保存 |
                    | **已发布** | **自动退回草稿**（并重算作品的字数/章节数），然后保存 |
                    | **待审核** | **拒绝**，返回 `3003` |

                    ★ 已发布为什么会「自动退回」：改过内容的章节必须重新走一遍审核，
                    而大纲的接口表里没有「退回草稿」这个按钮 —— 让作者为改一个错别字
                    去找一个不存在的按钮，等于「已发布的章节永远不能改」。
                    这次退回会记一条 `REVERT` 流水（action = 6）。

                    ★ 待审核为什么拒绝：审核员弹窗预览的是 A 版正文，作者在审核期间
                    换成 B 版，审核员点通过，读者读到的是 B —— **审核被绕过**，
                    而日志上是一次完全正常的审核。

                    ★ 正文**可以存空串**（编辑器「全选删除、准备重写」那一步要能存）。
                    空正文的校验在提交审核那一步。""")
    @PutMapping("/api/author/chapters/{id}")
    public Result<Void> save(@PathVariable Long id, @Valid @RequestBody ChapterSaveDTO dto) {
        chapterService.saveContent(id, dto);
        return Result.success("保存成功", null);
    }

    @Operation(
            summary = "提交章节审核",
            description = """
                    草稿 / 已驳回 → **待审核**。

                    **正文为空时返回 `3005`**，这是唯一一道「不能交空章节」的关卡 ——
                    草稿阶段允许空正文（占位），但一个连正文都没有的章节送到审核员面前，
                    他打开弹窗只看得到一个标题。

                    当前状态不允许提交时返回 `3003`。""")
    @PostMapping("/api/author/chapters/{id}/submit")
    public Result<Void> submit(@PathVariable Long id) {
        chapterService.submit(id);
        return Result.success("已提交审核", null);
    }

    @Operation(
            summary = "删除章节",
            description = """
                    逻辑删除。**已发布的章节删除后会重算作品的字数/章节数**。

                    ★ 删除时后端会顺手把该章的**序号腾出来**
                    （内部把 `chapter_no` 改成 `-id`），
                    所以删掉「第 5 章」之后**还能再建一个第 5 章**。
                    直接用逻辑删除做不到这一点 —— 唯一键
                    `uk_novel_chapter_no(novel_id, chapter_no)` 不包含 `deleted`，
                    删掉的行会一直占着那个序号，再建就报 `Duplicate entry`。

                    ★ **重复删除不会报错**（幂等）：已经删掉的章节再删一次，
                    仍然返回成功。""")
    @DeleteMapping("/api/author/chapters/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        chapterService.delete(id);
        return Result.success("删除成功", null);
    }

    /*
     * ★ 注意这里没有 PUT /{id}/status 这类接口，也没有「把章节移到别的作品下」。
     *   章节的归属跟着作品走，移动章节会让它的序号在目标书里撞车 ——
     *   正确的做法是删掉重建。P2 不做。
     */
}
