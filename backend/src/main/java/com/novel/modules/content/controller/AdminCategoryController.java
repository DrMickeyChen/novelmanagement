package com.novel.modules.content.controller;

import com.novel.common.result.Result;
import com.novel.modules.content.dto.CategorySaveDTO;
import com.novel.modules.content.service.CategoryService;
import com.novel.modules.content.vo.CategoryVO;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端 —— 分类管理。
 *
 * <p><b>★ 访问控制由 URL 前缀统一兜住，这个类里一个 {@code @PreAuthorize} 都不需要。</b>
 * {@code SecurityConfig} 里有一条 {@code requestMatchers("/api/admin/**").hasRole("ADMIN")}，
 * 非管理员<b>根本进不到这个类</b>（在 filter chain 就被拦下，返回 HTTP 403）。
 * <p>这也是「角色挡 API，perms 只管前端菜单」这个决定的直接体现 ——
 * 每个写接口都挂一个 {@code @PreAuthorize("hasAuthority('content:category:add')")}
 * 看着更"细"，代价是：<b>给分类加个新接口，得先去 {@code sys_menu} 造一个权限点、
 * 再挂到角色上，否则连 ADMIN 都会被自己的权限系统挡住</b>。
 * 接口和菜单数据被绑死，而菜单数据是能在后台点的。见 P2 方案「决定 2」。
 *
 * @author MickeyChen
 */
@Tag(name = "20. 管理端 · 分类管理", description = "分类的增删改查。★ 需要 ADMIN 角色")
@RestController
@RequestMapping("/api/admin/categories")
@RequiredArgsConstructor
public class AdminCategoryController {

    private final CategoryService categoryService;

    @Operation(
            summary = "分类列表（含停用）",
            description = """
                    返回**全部**分类，含已停用的。

                    和读者端的 `GET /api/categories` 是两个接口，不要合并 ——
                    读者端必须过滤掉停用分类，管理端必须能看到停用分类
                    （否则停用之后就改不回来了，这个操作会变成不可逆的）。""")
    @GetMapping
    public Result<List<CategoryVO>> list() {
        return Result.success(categoryService.listAll());
    }

    @Operation(
            summary = "新增分类",
            description = """
                    名称重复返回 `6002`。sort 和 status 不传时默认 0 / 1。""")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody CategorySaveDTO dto) {
        return Result.success("新增成功", categoryService.create(dto));
    }

    @Operation(
            summary = "编辑分类",
            description = """
                    改名称 / 排序 / 启停。分类不存在返回 `6001`，改成别人的名字返回 `6002`。

                    **停用（status = 0）和删除是两件事**：停用只影响读者端列表，
                    已有作品照常挂着；删除会被下面还有作品的检查挡住。""")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody CategorySaveDTO dto) {
        categoryService.update(id, dto);
        return Result.success("修改成功", null);
    }

    @Operation(
            summary = "删除分类",
            description = """
                    分类不存在返回 `6001`；**名下还有作品时返回 `6003`，且不限作品状态**
                    （草稿、已下架的书也算，否则它们会变成没有分类的孤儿）。

                    想「下架这个分类但保留数据」，请改用编辑接口把 status 设为 0。""")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return Result.success("删除成功", null);
    }
}
