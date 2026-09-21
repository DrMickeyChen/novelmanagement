package com.novel.modules.content.controller;

import com.novel.common.result.Result;
import com.novel.modules.content.service.CategoryService;
import com.novel.modules.content.vo.CategoryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 读者端 —— 分类列表。<b>匿名可访问</b>（在 {@code SecurityConfig.WHITELIST} 里）。
 *
 * <p><b>★ 为什么要单独开一个 controller 而不是把 {@code /api/categories} 挂在
 * 管理端那个类的 {@code @RequestMapping} 下：</b>
 * {@code @RequestMapping} 的路径前缀是这个类的<b>唯一标识</b>，
 * 一个类只能有一个。读者端和作者端/管理端的路径前缀不同
 * （{@code /api} vs {@code /api/author} vs {@code /api/admin}），
 * 而 <b>URL 前缀就是安全闸的判据</b>（见 {@code SecurityConfig} 的两条角色闸）——
 * 把 {@code /api/categories} 和管理端的接口塞进同一个类，
 * 只能靠每个方法写全路径来区分，而「哪个方法该写哪个前缀」就变成了人肉记忆。
 * 分成两个类之后，<b>看类名就知道这个类的接口归哪条规则管</b>。
 *
 * @author MickeyChen
 */
@Tag(name = "10. 读者端 · 分类", description = "读者端的分类列表，无需登录")
@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class ReaderCategoryController {

    private final CategoryService categoryService;

    @Operation(
            summary = "分类列表",
            description = """
                    返回所有**启用**的分类，按 sort 升序（相同则按 id）。
                    停用的分类不会出现在这里 —— 过滤发生在 SQL 的 WHERE 里，不是返回后前端筛。

                    **无需登录。**""")
    @GetMapping
    public Result<List<CategoryVO>> list() {
        return Result.success(categoryService.listEnabled());
    }
}
