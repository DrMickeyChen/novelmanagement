package com.novel.modules.content.vo;

import com.novel.common.enums.ChapterStatus;
import com.novel.modules.content.entity.Chapter;
import com.novel.modules.content.entity.OutlineNode;
import com.novel.modules.content.enums.OutlineNodeType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 大纲树的一个节点，<b>含子节点</b>（递归结构）。
 *
 * <p><b>★★ {@link #children} 永远是 List，绝不会是 {@code null} —— 这条不能破。</b>
 * 全局配了 {@code spring.jackson.default-property-inclusion: non_null}，
 * <b>为 null 的字段会整个从 JSON 里消失</b>。如果叶子节点的 {@code children}
 * 是 null，前端拿到的对象里<b>根本没有 children 这个键</b>，
 * {@code node.children.length} 就是 {@code TypeError: Cannot read properties of undefined}
 * → <b>整棵大纲树白屏</b>。
 * <p>而这个 bug <b>只在「恰好没有子节点的节点」上出现</b> ——
 * 一棵树里只要有一个叶子（必然有），就必现；但测试时如果只看有子节点的那些，
 * 会以为一切正常。<b>这是 P4 最容易漏掉的一条</b>，所以在字段声明上就把初值给成空 List。
 *
 * <p><b>★ 为什么接口返回嵌套结构，而不是扁平列表让前端组树：</b>
 * 树节点上的状态图标长在<b>另一张表</b>（{@code chapter}）上。
 * 让前端组树就等于让前端做一次跨表 join，还要自己维护一份
 * {@code nodeId → chapter} 的映射 —— 而后端一次 {@code selectByIds}
 * 就顺手拼上了，没有 N+1。
 * <p>（大纲 §7.3⑦ 写的「一次查全量」和 §6.3 写的「整棵大纲树」不矛盾：
 * 前者说的是<b>传输策略</b>（不要懒加载），后者说的是<b>响应形状</b>。）
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "大纲树节点（含子节点）")
public class OutlineNodeVO {

    @Schema(description = "节点 id", example = "1")
    private Long id;

    @Schema(description = "所属作品 id", example = "1")
    private Long novelId;

    @Schema(description = "父节点 id，根节点为空", example = "1")
    private Long parentId;

    @Schema(description = "节点类型：1全书 2卷 3章 4细纲", example = "2")
    private OutlineNodeType nodeType;

    /** 类型文案。★ 同 {@code ChapterVO#statusDesc}，只用于显示，判断一律用上面的数字 */
    @Schema(description = "类型文案，★ 仅供显示", example = "卷")
    private String nodeTypeDesc;

    @Schema(description = "节点标题", example = "第一卷 初入宗门")
    private String title;

    /**
     * 剧情笔记 —— <b>不是正文</b>。
     *
     * <p>它回答「这一段要写什么」，正文在 {@code chapter_content} 里。
     * 由大纲节点创建章节时<b>故意不拷这个字段</b>，理由见
     * {@code OutlineNode} 的类注释。
     *
     * <p>★ 为 null 时这个键在 JSON 里不出现（{@code non_null}），
     * 前端的编辑器要按「空字符串」处理，别直接 {@code v-model} 到 {@code undefined}。
     */
    @Schema(description = "节点内容（剧情笔记，不是正文）")
    private String content;

    @Schema(description = "同级排序，0 起", example = "0")
    private Integer sort;

    /* ==================== 关联的章节（跨表拼上来的） ==================== */

    /**
     * 关联的正文章节 id，<b>为空表示还没建正文</b>。
     *
     * <p><b>★ 前端判断「有没有正文」就看这一个字段</b>，
     * 不要去看 {@code novel_outline.status} —— 那一列是死的（见 {@code OutlineNode} 的类注释）。
     */
    @Schema(description = "关联的正文章节 id，为空表示还没建正文", example = "5")
    private Long chapterId;

    @Schema(description = "关联章节的序号", example = "3")
    private Integer chapterNo;

    @Schema(description = "关联章节的标题", example = "第三章 拜师")
    private String chapterTitle;

    /**
     * 关联章节的状态：<b>0草稿 1待审核 2已发布 4已驳回</b>。
     *
     * <p><b>★★ 树上的状态图标一律显示这个字段，不显示大纲节点自己的状态。</b>
     * <p>{@code chapterId} 为 null 时它也为 null —— 前端的判断只有一行：
     * <pre>
     *   const icon = (n) =&gt; !n.chapterId ? '未创建正文' : n.chapterStatusDesc
     * </pre>
     *
     * <p>★ 取值 0/1/2/4，<b>没有 3</b>（章节不能单独下架）——
     * 别从作品的状态映射表里复制。
     */
    @Schema(description = "关联章节状态：0草稿 1待审核 2已发布 4已驳回。★ 没有 3", example = "1")
    private ChapterStatus chapterStatus;

    /** 关联章节的状态文案。★ 后端给这个字段是为了让前端不用维护第二份映射表 */
    @Schema(description = "关联章节状态文案，★ 仅供显示", example = "待审核")
    private String chapterStatusDesc;

    /* ==================== 子节点 ==================== */

    /**
     * 子节点。<b>★★ 永远是 List，绝不是 null</b> —— 见类注释里那段。
     * <p>这里直接给初值 {@code new ArrayList<>()}，而不是在转换方法里补 ——
     * 补的话，将来新增一条构造路径忘掉就会重现那个白屏 bug。
     */
    @Schema(description = "子节点，★ 空数组而不是 null")
    private List<OutlineNodeVO> children = new ArrayList<>();

    /* ==================== 构造 ==================== */

    /**
     * 从实体转换，<b>不填章节信息</b>（子节点和章节由 Service 组装）。
     *
     * <p>和 {@code ChapterVO#of} 一样用静态工厂 + {@code protected fillFrom} 的写法，
     * 但这里不设 {@code chapter} 参数：章节是<b>批量</b>查出来再拼的
     * （见 {@code OutlineServiceImpl#tree}），逐条传进来就会变成 N+1。
     *
     * @param chapter 关联的章节，可以为 {@code null}（表示还没建正文，或链接已悬空）
     */
    public static OutlineNodeVO of(OutlineNode entity, Chapter chapter) {
        OutlineNodeVO vo = new OutlineNodeVO();
        vo.setId(entity.getId());
        vo.setNovelId(entity.getNovelId());
        vo.setParentId(entity.getParentId());
        vo.setNodeType(entity.getNodeType());
        vo.setNodeTypeDesc(entity.getNodeType() == null ? null : entity.getNodeType().getDesc());
        vo.setTitle(entity.getTitle());
        vo.setContent(entity.getContent());
        vo.setSort(entity.getSort());

        /*
         * ★ chapterId 用【实体上的值】而不是 chapter.getId()。
         *
         * 这一条很关键：章节被删掉之后实体上的 chapterId 还指向那个
         * 已不存在的 id（悬空）。如果这里写 chapter != null ? chapter.getId() : null，
         * 前端看到 chapterId 为空会显示「未创建正文」—— 看起来更"对"，
         * 但那掩盖了「库里有一条悬空链接」这个事实，
         * 而这正是 createChapter 的悬空自愈分支要处理的情况。
         * 让 chapterId 如实反映库里存的值，前端显示「已有正文」，
         * 用户点进去时才由 createChapter 自愈 —— 那条路径有日志。
         */
        vo.setChapterId(entity.getChapterId());

        if (chapter != null) {
            vo.setChapterNo(chapter.getChapterNo());
            vo.setChapterTitle(chapter.getTitle());
            vo.setChapterStatus(chapter.getStatus());
            vo.setChapterStatusDesc(
                    chapter.getStatus() == null ? null : chapter.getStatus().getDesc());
        }
        return vo;
    }
}
