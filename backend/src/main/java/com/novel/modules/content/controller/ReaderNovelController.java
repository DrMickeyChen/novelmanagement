package com.novel.modules.content.controller;

import com.novel.common.result.PageResult;
import com.novel.common.result.Result;
import com.novel.modules.content.service.ChapterService;
import com.novel.modules.content.service.NovelService;
import com.novel.modules.content.vo.ChapterDetailVO;
import com.novel.modules.content.vo.NovelDetailVO;
import com.novel.modules.content.vo.NovelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 读者端 —— 作品浏览。<b>匿名可访问</b>（在 {@code SecurityConfig.WHITELIST} 里）。
 *
 * <p><b>★★ 这个类是整个系统唯一「不登录也能拿数据」的业务接口，
 * 所以它的每一条查询都必须自己带可见性条件（大纲 §5.3 约束 1 和 2）。</b>
 * 它不依赖任何登录态 —— 没有 {@code UserContext} 可以兜底，
 * 一旦条件漏了，泄露是<b>直接对外</b>的，没有任何一层能拦住。
 *
 * <p><b>★ 为什么白名单是 {@code /api/novels/**} 而不是 {@code /api/**}：</b>
 * 后者会把 {@code /api/admin/**} 一起放行，管理端接口就匿名可调了，
 * 而且<b>不报错、不崩溃</b>，只是所有人都能调。见 {@code SecurityConfig} 的注释。
 *
 * <p><b>★ 没有写接口。以后也不该有</b> —— 读者端一旦出现
 * {@code POST} / {@code PUT}，它就不再是「纯读」的，白名单这个大口子也就
 * 从「可接受的公开数据」变成了「可写的公开接口」。
 * 读者侧将来要写的数据（评论、书架、阅读历史）属于 P6，
 * 那些接口必须放在需要登录的前缀下（{@code /api/reader/**}），
 * <b>不要往这个已经免登录的路径下加</b>。
 *
 * @author MickeyChen
 */
@Tag(name = "12. 读者端 · 作品", description = "读者浏览作品。**无需登录**")
@RestController
@RequestMapping("/api/novels")
@RequiredArgsConstructor
public class ReaderNovelController {

    private final NovelService novelService;
    private final ChapterService chapterService;

    @Operation(
            summary = "作品列表",
            description = """
                    **只返回已上架的作品**，而且这个条件写在 SQL 的 WHERE 里 ——
                    不是查出来再过滤。后者在列表接口上必然漏：
                    混进一本未上架的书不会报错，只是列表里悄悄多一行。

                    参数全部可选。`sort` 支持 `latest`（默认，最后更新）/ `new`（最新创建）/
                    `words`（字数最多），**传不认识的值不会报错，按默认排序处理**。

                    **无需登录。**""")
    @GetMapping
    public Result<PageResult<NovelVO>> list(
            @Parameter(description = "分类 id，不传=全部")
            @RequestParam(required = false) Long categoryId,
            @Parameter(description = "书名关键词，模糊匹配")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "是否完结：0 连载，1 完结，不传=全部")
            @RequestParam(required = false) Integer isFinish,
            @Parameter(description = "排序：latest（默认）/ new / words")
            @RequestParam(required = false) String sort,
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数，上限 100") @RequestParam(defaultValue = "10") long size) {

        return Result.success(novelService.readerPage(categoryId, keyword, isFinish, sort, page, size));
    }

    @Operation(
            summary = "作品详情（含目录）",
            description = """
                    目录**只列已发布的章节** —— 草稿章、待审章、被驳回的章都不会出现。

                    ★ **刻意不区分「作品不存在」和「作品未上架」，两者都返回 `2001`。**
                    否则匿名调用者可以靠错误码的差别枚举出「哪些 id 是真实存在的书」——
                    这个接口不需要登录，谁都能扫。

                    作者要看自己没上架的书，请走 `GET /api/author/novels/{id}`。

                    **无需登录。**""")
    @GetMapping("/{id}")
    public Result<NovelDetailVO> detail(@PathVariable Long id) {
        return Result.success(novelService.readerDetail(id));
    }

    @Operation(
            summary = "章节正文（阅读器核心接口）",
            description = """
                    **这是读者端唯一返回正文的接口** —— 阅读器就是靠它翻页的。

                    **★★ 必须同时满足两个条件，少一个都是真实的越权读：**

                    1. **作品已上架**。不查作品、只查章节的话，作品下架后它的正文
                       **照常匿名可读** —— 而下架往往正是因为这本书的内容有问题。
                    2. **章节已发布，且确实属于 URL 上的那本书**。只按 `cid` 查的话，
                       把 `cid` 换成别的书的章节 id，就能从一本已上架的书
                       **读到另一本书（可能未上架）的章节**，URL 上的 `{id}` 形同虚设。

                    以上任一不满足都返回 `2001` / `3001` 这类「不存在」——
                    **不区分「未上架」**，理由同详情接口：这个接口也免登录，
                    错误码的差别会变成一把枚举探针。

                    ★ **不返回 `auditRemark`**（驳回理由是审核内部话术，不该给读者看）。

                    **无需登录。**""")
    @GetMapping("/{id}/chapters/{cid}")
    public Result<ChapterDetailVO> chapterDetail(@PathVariable Long id, @PathVariable Long cid) {
        return Result.success(chapterService.readerDetail(id, cid));
    }
}
