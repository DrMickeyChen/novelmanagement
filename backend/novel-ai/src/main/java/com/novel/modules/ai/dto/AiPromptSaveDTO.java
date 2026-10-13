package com.novel.modules.ai.dto;

import com.novel.modules.ai.enums.AiScene;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增 / 编辑提示词模板的入参（E1）。两个接口共用 —— 字段完全一样。
 *
 * <p>★★ <b>这个 DTO 上的 {@code scene} 是刻意存在的</b>：{@code AiScene} 的类注释写着
 * 「场景不出现在任何请求体里」，那条规矩的作用域是 <b>{@code /api/ai/**} 的四个生成端点</b>
 * （那里一个场景一个 URL）。模板 CRUD 是一组通用端点，
 * <b>除了 body 没有别的地方能表达「改的是哪个场景」</b>。
 * <p>判据是「这个端点上有没有一个能替代它的 URL 维度」，不是「枚举能不能进 body」。
 *
 * <p>★★ <b>字段长度校验必须和库里的列对齐</b>（同 {@code CategorySaveDTO} 的论证）：
 * 不对齐就是「一直传到 MySQL 才报 {@code Data too long}」，那是个 500，
 * 管理员看到的是「服务器开小差了」，完全不知道是自己写长了。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "提示词模板新增 / 编辑入参")
public class AiPromptSaveDTO {

    /**
     * 场景：1大纲 2正文 3灵感 4润色 5违禁审核 6前情摘要。
     *
     * <p>★ 用枚举而不是 {@code Integer}：Jackson 反序列化时非法编号会<b>直接 400</b>，
     * 而裸 {@code Integer} 会让 {@code scene=7} 一路进到库里 ——
     * 那之后 {@code AiVarResolver} 查不到第 7 个场景，会静默退化成
     * 「所有变量都不适用」，模板里的占位符全部被整行移除。
     */
    @Schema(description = "场景：1大纲 2正文 3灵感 4润色 5违禁审核 6前情摘要", example = "2",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "场景不能为空")
    private AiScene scene;

    /** 模板名。★ 与 scene 组成唯一键 {@code uk_scene_name}，同场景重名返回 5006 */
    @Schema(description = "模板名", example = "续写正文-默认", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "模板名不能为空")
    @Size(max = 50, message = "模板名最多 50 个字")
    private String name;

    /**
     * system 消息模板。
     *
     * <p>★ 允许空串，但<b>不允许 {@code null}</b>：库里这一列是 {@code NOT NULL}，
     * 传 {@code null} 会变成 500「服务器开小差了」而不是 400。
     * <p>★ 空串是合法的 —— 有些场景不需要 system 消息，那就不发。
     * <b>不许自作主张填一句默认的 system 提示词</b>：管理员看到的是自己留的空，
     * 而模型收到的是别的，两端都不知道对方存在。
     */
    @Schema(description = "system 消息模板，可以留空")
    @NotNull(message = "system 模板不能为 null（可以传空串）")
    @Size(max = 8000, message = "system 模板最多 8000 个字")
    private String systemPrompt;

    /**
     * user 消息模板，<b>带占位符的那一份</b>。
     *
     * <p>★ 上限 8000 的来历：渲染结果的总上限是 {@code PromptRenderer.MAX_RENDERED_LENGTH}
     * （20000，含变量值）。<b>模板本身就超过一半的话，它永远渲染不出一份完整的提示词</b> ——
     * 与其在渲染时撞长度护栏（那时管理员已经以为保存成功了），不如在保存时就说清楚。
     */
    @Schema(description = "user 消息模板，带 {{占位符}}",
            example = "{{request}}\n\n已写正文：\n{{selected}}",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "user 模板不能为空")
    @Size(max = 8000, message = "user 模板最多 8000 个字")
    private String userTemplate;

    /** 1 启用 0 停用。不传按 1 */
    @Schema(description = "1 启用，0 停用", example = "1", defaultValue = "1")
    @Min(value = 0, message = "启用状态只能是 0 或 1")
    @Max(value = 1, message = "启用状态只能是 0 或 1")
    private Integer enabled = 1;

    /** 同场景多模板时的排序，小的优先。不传按 0 */
    @Schema(description = "排序值，越小越靠前", example = "0", defaultValue = "0")
    @Min(value = 0, message = "排序值不能为负数")
    @Max(value = 9999, message = "排序值最大 9999")
    private Integer sort = 0;
}
