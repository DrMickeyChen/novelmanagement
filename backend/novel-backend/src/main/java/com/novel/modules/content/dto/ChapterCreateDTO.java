package com.novel.modules.content.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 作者新建章节的入参。
 *
 * <p><b>★ 没有 {@code chapterNo} 字段，而且不能加。</b>
 * 章节序号由后端按 {@code MAX(chapter_no) + 1} 分配。
 * 让前端传序号的话：两个标签页同时建章会传同一个号（撞唯一键），
 * 而且作者可以随手填个 999 把目录撑出一个巨大的空洞。
 *
 * <p><b>{@code content} 可以为空</b>：允许「先建好章节占个位，
 * 正文回头再写」—— 大纲 §629 的「先写大纲后建正文」就是这个意思。
 * 但<b>提交审核时正文不能为空</b>（{@code CHAPTER_CONTENT_EMPTY}），
 * 这个校验放在提交那一步，不在这里。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "作者新建章节入参")
public class ChapterCreateDTO {

    @Schema(description = "章节标题", example = "第一章 测试", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "章节标题不能为空")
    @Size(max = 200, message = "章节标题最多 200 个字")
    private String title;

    /**
     * 正文，可为空。
     *
     * <p><b>★ 没有 {@code @Size} 限制，这是刻意的。</b>
     * 正文存的是 {@code LONGTEXT}（能放 4GB），一章网文两三万字是常态，
     * 而 Jakarta Validation 的 {@code @Size} 是<b>按字符数在内存里比较</b>的，
     * 给一个几万字的字段加限制并不能防住什么，真正的防线是
     * Tomcat 的请求体大小上限（{@code server.tomcat.max-http-form-post-size}）
     * 和 Nginx 的 {@code client_max_body_size}，那些在网络层就拦住了。
     */
    @Schema(description = "正文，可以先不填，回头再写")
    private String content;

    @Schema(description = "是否免费：1 免费，0 收费。★ 预留字段，P2 不参与任何逻辑", example = "1", defaultValue = "1")
    @Min(value = 0, message = "isFree 只能是 0 或 1")
    @Max(value = 1, message = "isFree 只能是 0 或 1")
    private Integer isFree = 1;
}
