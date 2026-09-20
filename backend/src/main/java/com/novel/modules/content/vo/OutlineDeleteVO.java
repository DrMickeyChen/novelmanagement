package com.novel.modules.content.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 删除大纲子树的返回值。
 *
 * <p><b>★ 为什么要返回计数，而不是一个空的成功：</b>
 * 前端要在删完之后给一句提示，而这句话的内容取决于数字 ——
 * 特别是 {@link #linkedChapterCount}：
 * <blockquote>
 *   其中 3 个节点已有正文，<b>正文未被删除</b>，可在章节列表中找到
 * </blockquote>
 * <b>后半句是必须的。</b>用户的直觉一定是「我把大纲删了，那几章的正文也没了」——
 * 不说清楚，他会跑去章节列表里找，然后以为稿子丢了。
 *
 * <p><b>★★ 删除的是【节点子树】，绝不包含正文章节</b>（P4 方案的决定 5）。
 * 大纲 §7.3 ② 原文说的是「级联删除<b>子树</b>」，指的是节点。
 * 删写作计划不该毁掉已经写好的正文 —— 那两件事的代价完全不对等：
 * 重建大纲是几分钟的事，正文是几小时。
 *
 * <p><b>★ 为什么没有单独的「预览删除数量」接口：</b>
 * 整棵树已经在浏览器里了，前端递归求和一行就够：
 * <pre>
 *   const count = (n) =&gt; 1 + (n.children || []).reduce((s, c) =&gt; s + count(c), 0)
 * </pre>
 * 再加一个接口是多一次往返、多一处可能不一致
 * （前端数出来的和后端数出来的不一样时该信谁？）。
 * 所以计数只在<b>删除的返回值</b>里给一次，那个数字是权威的。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "删除大纲子树的结果")
public class OutlineDeleteVO {

    /** 被删除的节点数（含传入的那个节点自己） */
    @Schema(description = "被删除的节点数（含自身）", example = "6")
    private Integer deletedNodes = 0;

    /**
     * 这些节点里<b>已有正文</b>的个数。★ <b>正文章节一个都没被删除</b>，
     * 这个数字只是用来告诉用户「有 N 章正文还留着」。
     */
    @Schema(description = "其中已有正文的节点数。★ 正文未被删除", example = "3")
    private Integer linkedChapterCount = 0;
}
