package com.novel.modules.ai.controller;

import com.novel.common.result.Result;
import com.novel.modules.ai.dto.AiPromptPreviewDTO;
import com.novel.modules.ai.dto.AiPromptSaveDTO;
import com.novel.modules.ai.enums.AiScene;
import com.novel.modules.ai.service.AiPromptService;
import com.novel.modules.ai.vo.AiPromptTemplateVO;
import com.novel.modules.ai.vo.RenderedPromptVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
 * 管理端 —— AI 提示词模板（E1）。
 *
 * <p><b>★ 访问控制由 URL 前缀统一兜住，这个类里一个 {@code @PreAuthorize} 都不需要。</b>
 * {@code ContentSecurityConfig} 里已有 {@code requestMatchers("/api/admin/**").hasRole("ADMIN")}，
 * 非管理员根本进不到这个类（在 filter chain 就被拦下，返回 HTTP 403）。
 * 同 {@code AdminCategoryController}。
 *
 * <p>★ 路由不冲突：{@code /api/admin/ai/**} 不与 auth 的五个前缀、
 * interaction 的 {@code /api/admin/comments}、search 的 {@code /api/admin/search} 重叠，
 * 全部落在网关给 content 的那条 {@code order: 5} 上 —— <b>E1 对网关是零改动。</b>
 *
 * <h3>★★ 这个类里最该读懂的是 {@code /preview}</h3>
 *
 * <p>它是<b>只读的</b>：不调模型、不扣配额、不写任何表。所以它是提示词这块唯一
 * 可以随便点、随便试的地方 —— 管理员能在这里把「把 {{summary}} 删掉会怎样」
 * 试到满意再保存。★ 而它渲染的是<b>库里那一份</b>，不是编辑器缓冲，理由见
 * {@code AiPromptPreviewDTO} 的类注释。
 *
 * @author MickeyChen
 */
@Tag(name = "21. 管理端 · AI 提示词模板", description = "模板的增改查 + 预览。★ 需要 ADMIN 角色")
@RestController
@RequestMapping("/api/admin/ai/prompt")
@RequiredArgsConstructor
public class AdminAiPromptController {

    private final AiPromptService aiPromptService;

    @Operation(
            summary = "模板列表",
            description = """
                    不传 `scene` 返回全部场景的模板，按 场景 → sort → id 排序
                    （**和「当前生效的是哪一条」用同一个排序**，两处不一致会让
                    管理端显示的和实际用的对不上，且不报错）。

                    **包含已停用的模板** —— 过滤掉它们，停用就变成一个改不回来的操作。""")
    @GetMapping
    public Result<List<AiPromptTemplateVO>> list(
            @Parameter(description = "场景编号：1大纲 2正文 3灵感 4润色 5违禁审核 6前情摘要，不传返回全部")
            @RequestParam(required = false) Integer scene) {
        return Result.success(aiPromptService.list(AiScene.fromCode(scene)));
    }

    @Operation(
            summary = "预览模板渲染结果",
            description = """
                    **只读**：不调模型、不扣配额、不写表。

                    - 传 `id` → 预览那一条（**可以是停用的，也可以是被改坏的**）
                    - 不传 `id` → 预览该场景**当前生效**的那一条（生成接口用的就是它）

                    `sampleVars` 是「占位符名 → 样本值」，键不带花括号。
                    没值的变量会被**整行移除**，被移除的是哪些会在 `removedVars` 里列出来 ——
                    这一步不是错误、也不会报错，所以必须显式告诉管理员，否则他只会觉得
                    「结果里少了点什么」而说不出少了什么。

                    失败语义（和后端渲染层逐条对应）：

                    | 情况 | HTTP | code |
                    |---|---|---|
                    | 该场景没有启用的模板 | 200 | `5005` |
                    | `sampleVars` 里有系统不认识的变量名 | 400 | `400` |
                    | 必填变量没有值（如润色没给 `selected`） | 400 | `400` |
                    | 渲染结果超过长度上限 | 400 | `400` |
                    | 模板里有未知占位符（**只可能来自绕过管理端改库**） | **500** | `500` |

                    ★ 最后一行是**故意的**：它不给好看的业务码 —— `5001+` 返 HTTP 200
                    会在监控上隐身，而这条恰恰是唯一需要在监控上响的。""")
    @PostMapping("/preview")
    public Result<RenderedPromptVO> preview(@Valid @RequestBody AiPromptPreviewDTO dto) {
        return Result.success(aiPromptService.preview(dto));
    }

    @Operation(
            summary = "新增模板",
            description = """
                    模板里的占位符**在写入之前**就校验：不认识的词、本场景没有的变量，
                    都返回 `400` 并指出是哪一个（不是等到作者点生成时才炸 ——
                    写时拒绝的半径是「一个管理员的下一次点击」，
                    读时拒绝的半径是「全体作者 × 该场景」）。

                    同一场景下重名返回 `5006`。""")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody AiPromptSaveDTO dto) {
        return Result.success("新增成功", aiPromptService.create(dto));
    }

    @Operation(
            summary = "编辑模板",
            description = """
                    校验规则同新增。模板不存在返回 `5005`，同场景重名返回 `5006`。

                    ★ 把它改成 `enabled = 0` 之后，该场景的作者点生成会立刻拿到 `5005`
                    —— **这是停用，不是删除**，随时可以改回来，所以是安全的操作。""")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody AiPromptSaveDTO dto) {
        aiPromptService.update(id, dto);
        return Result.success("修改成功", null);
    }
}
