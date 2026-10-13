package com.novel.modules.ai.vo;

import com.novel.modules.ai.enums.AiScene;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 提示词模板（管理端读出用）。
 *
 * <p>★ 有一层 VO 而不是直接返回实体，是本项目一贯的做法
 * （{@code CategoryVO} / {@code RoleVO} / {@code NoticeVO} 同形）——
 * 它挡住的是「实体加了个内部字段，管理端接口<b>自动</b>多返回一列」这种被动泄漏。
 * <p>★ 这里多一个 {@link #sceneLabel}：场景的中文名来自 {@link AiScene#getLabel()}，
 * 让前端表格不必自己再维护一份「1 → 大纲」的映射 ——
 * 那份映射一旦漏改，症状是<b>表格里显示错的场景名</b>，而接口一切正常。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "提示词模板")
public class AiPromptTemplateVO {

    @Schema(description = "模板 id")
    private Long id;

    @Schema(description = "场景编号：1大纲 2正文 3灵感 4润色 5违禁审核 6前情摘要")
    private Integer scene;

    /** ★ 场景中文名。由服务端从枚举取，前端不要自己映射 */
    @Schema(description = "场景名")
    private String sceneLabel;

    @Schema(description = "模板名")
    private String name;

    @Schema(description = "system 消息模板，可能为空串")
    private String systemPrompt;

    @Schema(description = "user 消息模板，带占位符")
    private String userTemplate;

    @Schema(description = "1 启用，0 停用")
    private Integer enabled;

    @Schema(description = "排序值，越小越靠前")
    private Integer sort;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "最后修改时间。★ 模板被绕过管理端改坏时，这里是唯一的改动线索")
    private LocalDateTime updateTime;
}
