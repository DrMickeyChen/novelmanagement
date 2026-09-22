package com.novel.modules.content.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建大纲节点的入参。
 *
 * <p><b>★ 没有 {@code sort} 字段。</b>新节点一律<b>追加到同级末尾</b>
 * （由后端 {@code renumber} 分配序号）—— 这和 {@code ChapterCreateDTO}
 * 没有 {@code chapterNo} 是同一个理由：让前端传序号，
 * 两个标签页同时建节点就会传出同一个值，而 {@code novel_outline} 上
 * 没有唯一键能拦住它，结果是两条 sort 相同的记录，
 * 于是「同一请求刷新两次看到两种顺序」。
 *
 * <p><b>★ 没有 {@code chapterId} 字段。</b>关联正文章节<b>只能</b>通过
 * {@code POST /outline/nodes/{nodeId}/chapter} 建立 ——
 * 那条路径带着幂等和悬空自愈（见 {@code OutlineServiceImpl#createChapter}）。
 * 在这里开一个能直接写 {@code chapterId} 的口子，就等于绕过那两件事，
 * 而且可以把自己的节点指向<b>别人书里的章节</b>。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "新建大纲节点入参")
public class OutlineNodeCreateDTO {

    /**
     * 父节点 id。<b>为空 = 建根节点（全书大纲）</b>。
     *
     * <p><b>★★ 这个值来自请求体，所以必须校验它的归属</b>
     * （{@code requireNodeInNovel}）—— 不校验就能把自己的节点挂到别人的节点下，
     * 而那条 UPDATE 语法完全合法。详见
     * {@code NovelAccessGuard#requireNodeInNovel} 的注释。
     *
     * <p>★ 一本书只能有一个根节点，重复建根返回 {@code 3008}。
     */
    @Schema(description = "父节点 id。不传 = 建根节点（全书大纲）")
    private Long parentId;

    /**
     * 节点类型 1全书 2卷 3章 4细纲。
     *
     * <p>不传时由后端按「父节点的 {@code defaultChildOf()}」推导
     * （全书 → 卷 → 章 → 细纲）。传了就用传的 ——
     * <b>这个字段不做等级校验</b>，理由见 {@code OutlineNodeType} 的类注释。
     * <p>★ 唯一的例外：建<b>根</b>节点时类型只能是「全书」，传别的会被忽略。
     */
    @Schema(description = "节点类型：1全书 2卷 3章 4细纲。不传则按父节点推导", example = "2")
    private Integer nodeType;

    @Schema(description = "节点标题", example = "第一卷 初入宗门", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "节点标题不能为空")
    @Size(max = 200, message = "节点标题最多 200 个字")
    private String title;

    /**
     * 剧情笔记，可为空。
     *
     * <p><b>★ 没有 {@code @Size} 限制，和 {@code ChapterCreateDTO#content} 同理</b>
     * （见那里的注释）：{@code content} 是 {@code TEXT} 列，
     * Jakarta Validation 的 {@code @Size} 按字符数在内存里比较，
     * 真正的防线在网络层。
     */
    @Schema(description = "节点内容（剧情笔记，不是正文）")
    private String content;
}
