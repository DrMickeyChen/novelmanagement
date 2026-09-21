package com.novel.modules.content.vo;

import com.novel.common.enums.ChapterStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 「由大纲节点创建正文章节」的返回值。
 *
 * <p><b>★★ {@link #created} 是这个接口最重要的一个字段，前端必须用它来区分提示语。</b>
 * <p>这个接口是<b>幂等</b>的：节点已经有正文时不报错，而是返回
 * {@code created: false} + 已有章节的信息。理由是 ——
 * 用户点第二下时，他的意图和第一下<b>完全相同</b>
 * （「我要编辑这一章的正文」），报错等于告诉他
 * 「你想做的事已经做完了，这是个错误」。
 * <p>但前端不能把两种情况显示成一样：
 * <pre>
 *   created = true   → 「已创建章节，正在打开」      （他刚刚造出了新东西）
 *   created = false  → 「这一章已有正文，正在打开」  （什么都没发生，别让他以为多了一章）
 * </pre>
 *
 * <p><b>★ 为什么这是一个独立的小 VO，而不是直接复用 {@code ChapterVO}：</b>
 * {@code ChapterVO} 里有一个 {@code novelTitle} 字段，
 * 它是为管理端的跨作品列表加的（见那个类的注释）。
 * 这个接口的场景里书名是已知的，带上它只会是个 null，
 * 而 {@code non_null} 会把它整个抹掉 —— 一个「声明了但永远不出现」的字段
 * 比没有更让人困惑。
 *
 * <p>★ 这里也<b>不带正文</b>（{@code content}）：创建出来的章节正文一定是空的
 * （正文故意不从 {@code node.content} 拷，见 {@code OutlineNode} 的类注释），
 * 所以没有可返回的东西。前端拿到 {@link #chapterId} 之后
 * 自己去章节编辑器加载正文。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "由大纲节点创建正文的结果")
public class OutlineChapterVO {

    /**
     * <b>{@code true} = 这次真的新建了一章；{@code false} = 之前就有了，直接返回。</b>
     *
     * <p>★ 前端<b>必须</b>用它区分提示语，理由见类注释。
     */
    @Schema(description = "是否本次新建。false 表示该节点已有正文，直接返回", example = "true")
    private Boolean created = false;

    @Schema(description = "正文章节 id", example = "5")
    private Long chapterId;

    @Schema(description = "章节序号", example = "3")
    private Integer chapterNo;

    @Schema(description = "章节标题", example = "第三章 拜师")
    private String title;

    /** 状态：0草稿 1待审核 2已发布 4已驳回。★ 没有 3 */
    @Schema(description = "章节状态：0草稿 1待审核 2已发布 4已驳回。★ 没有 3", example = "0")
    private ChapterStatus status;

    @Schema(description = "章节状态文案，★ 仅供显示", example = "草稿")
    private String statusDesc;

    @Schema(description = "正文字数。★ 刚创建时一定是 0", example = "0")
    private Integer wordCount;
}
