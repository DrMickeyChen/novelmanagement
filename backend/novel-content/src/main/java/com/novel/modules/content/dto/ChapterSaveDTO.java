package com.novel.modules.content.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 作者保存章节内容的入参（标题 + 正文一起存）。
 *
 * <p><b>为什么标题和正文合在一个接口里：</b>作者在编辑器里改完正文，
 * 顺手把标题也从「第一章」改成「第一章 觉醒」，这两个改动是一次「保存」动作，
 * 拆成两个接口会让前端要发两个请求、还要自己处理「第一个成功第二个失败」的半成功状态。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "作者保存章节内容入参")
public class ChapterSaveDTO {

    private Long expectedVersion;

    @com.fasterxml.jackson.annotation.JsonIgnore
    private Long savedVersion;

    @Schema(description = "章节标题", example = "第一章 测试", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "章节标题不能为空")
    @Size(max = 200, message = "章节标题最多 200 个字")
    private String title;

    /**
     * 正文。<b>这里可以为空</b>，理由同 {@code ChapterCreateDTO#content}：
     * 保存草稿时允许先清空再写，过早拦住它只会让编辑器的「全选删除重写」很别扭。
     *
     * <p>真正的「正文不能为空」校验在<b>提交审核</b>那一步
     * （{@code POST /api/author/chapters/{id}/submit}），那里才是该较真的地方。
     */
    @Schema(description = "正文")
    private String content;
}
