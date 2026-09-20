package com.novel.modules.content.controller;

import com.novel.common.result.PageResult;
import com.novel.common.result.Result;
import com.novel.modules.content.service.NovelService;
import com.novel.modules.content.vo.NovelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 读者端 —— 全站搜索。<b>匿名可访问</b>。
 *
 * <p><b>★ 为什么单独一条 {@code /api/search}，而不是给
 * {@code /api/novels} 加一个 {@code authorName} 参数：</b>
 * 两者语义不同 —— 列表页的 {@code keyword} 是「<b>在当前分类里</b>筛书名」，
 * 这里是「<b>跨全站</b>搜书名<b>或作者名</b>」。混成一个接口，分类页的筛选框
 * 在用户输入作者名时会返回一堆不在该分类的书，而且看起来「挺合理」，不报错。
 *
 * <p><b>★ 为什么不放进 {@link ReaderNovelController}：</b>
 * 那个类挂在 {@code @RequestMapping("/api/novels")} 下，路径形状是
 * 「作品集合 + 作品 id」。搜索的结果虽然也是作品，但它不属于任何一个
 * 作品资源 —— 它是<b>一次跨资源的查询</b>。硬塞进去要么写成
 * {@code /api/novels/-/search} 这种为了凑路径而凑的东西，
 * 要么就得把 {@code @RequestMapping} 拆掉、在每个方法上写全路径。
 * <p>二期 M4 搜索会上 ES，那时它会变成一个真正独立的服务（{@code novel-search}），
 * 现在单独一个类正好是那个接缝。
 *
 * <p><b>★★ 这个类的每一条查询都必须自己带可见性条件。</b>
 * 它在 {@code SecurityConfig} 里是匿名放行的（{@code GET /api/search}），
 * 没有登录态可以兜底 —— 条件漏了就是<b>直接对外</b>的内容泄露。
 * 具体的括号陷阱见 {@code NovelServiceImpl#search} 的注释。
 *
 * @author MickeyChen
 */
@Tag(name = "14. 读者端 · 搜索", description = "全站搜索作品。**无需登录**")
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class ReaderSearchController {

    private final NovelService novelService;

    @Operation(
            summary = "搜索作品",
            description = """
                    按 **书名 或 作者昵称** 模糊匹配，命中任一即可。

                    ★ **只返回已上架的作品** —— 未上架、已下架、待审核的书都搜不到。

                    ★ `keyword` 为空（或不传）时返回**空列表**，不是错误，也不是全部作品。

                    ★ 排序按**阅读量降序**。这不是相关性排序 —— `LIKE '%关键词%'`
                      只能回答「命中 / 不命中」，回答不了「谁更相关」。真正的相关性
                      要等二期接入 ES。

                    ★ 按作者名搜索时，**匹配到的作者数量有上限（100）**：
                      `sys_user.nickname` 没有索引，昵称模糊查是一次全表扫描，
                      不设上限会让下游 SQL 的 `IN` 列表失控。

                    **无需登录。**""")
    @GetMapping
    public Result<PageResult<NovelVO>> search(
            @Parameter(description = "关键词，匹配书名或作者昵称；为空返回空列表")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数，上限 100") @RequestParam(defaultValue = "10") long size) {

        return Result.success(novelService.search(keyword, page, size));
    }
}
