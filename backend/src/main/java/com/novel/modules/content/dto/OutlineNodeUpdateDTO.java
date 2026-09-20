package com.novel.modules.content.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改大纲节点的入参 —— <b>全量覆盖</b>，不是部分更新。
 *
 * <p><b>★★ {@code content} 传 {@code null} 表示「清空」，而不是「不改」。</b>
 * 这一点在实现上是有代价的：MyBatis-Plus 的 {@code updateById}
 * <b>会跳过 null 字段</b>，所以「把 content 清空」用它做不到 ——
 * 保存成功、返回 200、重新打开内容还在，<b>而且不报任何错</b>。
 * 必须写成 {@code update(null, wrapper.set(...))}。
 * 见 {@code OutlineServiceImpl#update}。
 *
 * <p><b>★ 没有 {@code parentId} / {@code sort} 字段：</b>
 * 位置的变化一律走 {@code PUT /outline/nodes/{id}/move}。
 * 两个接口都能改位置的话，「新建时算一次 sort、移动时再算一次」两套逻辑
 * 迟早会不一致，而它们的 bug 都表现为「顺序不对」，分不清是哪一套错的。
 *
 * <p><b>★ 没有 {@code nodeType} 字段：</b>节点的类型是它的语义
 * （「这一卷 / 这一章」），改类型等于换了一个节点 ——
 * 那应该删掉重建。允许改类型会带来一个没有答案的问题：
 * 把一个「卷」改成「细纲」，它下面已经建好的正文章节怎么办？
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "修改大纲节点入参（全量覆盖）")
public class OutlineNodeUpdateDTO {

    @Schema(description = "节点标题", example = "第一卷 初入宗门", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "节点标题不能为空")
    @Size(max = 200, message = "节点标题最多 200 个字")
    private String title;

    /** ★ 传 null = 清空（不是「不改」），见类注释 */
    @Schema(description = "节点内容（剧情笔记）。传 null 表示清空")
    private String content;
}
