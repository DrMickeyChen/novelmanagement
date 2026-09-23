package com.novel.search.controller;

import com.novel.common.result.PageResult;
import com.novel.common.result.Result;
import com.novel.common.vo.NovelVO;
import com.novel.search.service.SearchService;
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
 * <p><b>★★ 这个类是 M4 从 {@code novel-content} 搬过来的</b>
 * （原 {@code ReaderSearchController}）。★ <b>路径一个字没改</b>
 * （仍然是 {@code GET /api/search}），所以前端零改动 ——
 * 这正是「拆服务」能做到对用户透明的原因：搬的是<b>实现</b>，不是接口。
 *
 * <p>★ 原来那个类的注释里有一句预言，M4 兑现了：
 * <blockquote>
 *   二期 M4 搜索会上 ES，那时它会变成一个真正独立的服务（{@code novel-search}），
 *   现在单独一个类正好是那个接缝。
 * </blockquote>
 * ★ <b>「单独一个类」这个决定被证明是对的</b>：因为它已经是一个独立的资源
 * （不挂在 {@code /api/novels} 下、不共用任何 Controller 状态），
 * 搬家时只是换了个包名。
 *
 * <p><b>★★ 可见性条件现在有【两道】，而且分布在两个进程里：</b>
 * <ol>
 *   <li>本服务检索时在 ES 侧 {@code filter status = 已上架} —— 保证
 *       {@code total} 和页内容对得上（见 {@code SearchService}）；</li>
 *   <li>content 装配时再 {@code eq(status, 已上架)} —— 它是真正的那道闸门，
 *       因为 <b>ES 里的文档可能陈旧</b>（书下架了、文档还在）。</li>
 * </ol>
 * ★★ 原来的注释写着「这个类的每一条查询都必须自己带可见性条件」——
 * M4 之后这句话<b>升了一级</b>：从「每个查询自己带条件」变成了
 * <b>「每个进程各自带条件，而且缺一不可」</b>。
 * 只留一道的后果：留 ES 那道，下架的书仍然能被搜到（内容泄露）；
 * 留 content 那道，会出现「共 1050 本」+ 页内空洞。
 *
 * @author MickeyChen
 */
@Tag(name = "14. 读者端 · 搜索", description = "全站搜索作品。**无需登录**")
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @Operation(
            summary = "搜索作品",
            description = """
                    按 **书名 / 作者昵称 / 简介** 匹配，命中任一即可，按**相关性**排序。

                    ★ **M4 起匹配范围多了「简介」** —— 在此之前只搜书名和作者昵称。
                      所以「只在简介里提过一次」的词现在也搜得到了。

                    ★ **只返回已上架的作品** —— 未上架、已下架、待审核的书都搜不到。

                    ★ `keyword` 为空（或不传）时返回**空列表**，不是错误，也不是全部作品。

                    ★ **排序是相关性**：书名命中 > 作者名命中 > 简介命中（权重 3:2:1），
                      同分时「最近更新的」靠前。

                    ★ 按作者名搜索时**不再有 100 个作者的召回上限**：
                      那个上限来自「昵称没有索引、模糊查是全表扫描」，
                      而 ES 用的是倒排索引，不存在这个问题。

                    ★ **翻页深度上限 10000 条**（ES 的 `max_result_window`）。
                      超过时返回空列表而不是报错 —— 正常使用翻不到那么深。

                    **无需登录。**""")
    @GetMapping
    public Result<PageResult<NovelVO>> search(
            @Parameter(description = "关键词，匹配书名 / 作者昵称 / 简介；为空返回空列表")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数，上限 100") @RequestParam(defaultValue = "10") long size) {

        return Result.success(searchService.search(keyword, page, size));
    }
}
