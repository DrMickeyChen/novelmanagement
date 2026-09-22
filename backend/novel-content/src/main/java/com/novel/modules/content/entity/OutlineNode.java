package com.novel.modules.content.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.novel.modules.content.enums.OutlineNodeType;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 大纲树节点，对应 {@code novel_outline}（DDL 见 {@code docs/sql/schema.sql}）。
 *
 * <p>这张表从 P0 就建好了，但<b>一直到 P4 才有第一行 Java 代码</b> ——
 * 所以实体上的每个字段都要交代清楚它到底有没有人写。
 *
 * <p><b>★★ {@link #status} 是一个死列 —— P4 不写也不读，请勿使用。</b>
 * <p>树上的状态图标一律显示 {@code chapter.status}（外加「未创建正文」这一个派生状态）。
 * 三条理由：
 * <ol>
 *   <li><b>它是冗余的，所有取值都能从别的字段推出来：</b>
 *       {@code 0未开始} ≡ {@code chapterId == null}；
 *       {@code 1写作中} ≡ {@code chapterId != null} 且章节未发布；
 *       {@code 2已完成} ≡ {@code chapterStatus == 2}。</li>
 *   <li><b>维护它会造出双向依赖。</b>章节的状态流转在
 *       {@code ChapterServiceImpl#transitStatus}，要让这一列跟着变，
 *       章节模块就得<b>反向写 novel_outline</b>；而大纲模块本身又在读章节。
 *       两个包互相依赖，<b>将来拆微服务时拆不开</b> ——
 *       这正是「一个包 = 一个微服务」这条约定最怕的东西。</li>
 *   <li><b>UI 上放不下。</b>大纲 §7.3 只给了一组图标，就是章节状态那四个，
 *       再加一个前端临时态「当前编辑中」。没有第二列图标的位置。</li>
 * </ol>
 * <p>★ 不维护它就必然会静默腐烂（审核通过了一章，大纲图标还停在「写作中」），
 * 所以干脆不读 —— 读一个永远不更新的字段，比没有这个字段更坏。
 * <p><b>为什么保留这一列不删：</b>删列要改 P2 已经落地的 DDL，而它零成本。
 * 留着，但它在这里被明确标记为死的。
 *
 * <p><b>★ {@link #content} 是「剧情笔记」，不是正文。</b>
 * 它回答的是「这一段要写什么」，而正文在 {@code chapter_content} 里。
 * 两者很容易被混为一谈 —— 特别是从大纲节点创建章节的时候
 * （见 {@code OutlineServiceImpl#createChapter}）：<b>正文故意不从 content 拷</b>。
 * 拷了有两个后果：① 作者打开新章节看到一堆自己的笔记；
 * ② 更隐蔽 —— 它让「空正文」永远不成立，
 * {@code ChapterService#submit} 里那道 {@code CHAPTER_CONTENT_EMPTY} 从此形同虚设。
 *
 * <p><b>★ 这张表上没有唯一键</b>（只有 {@code idx_novel_parent_sort} 和
 * {@code idx_chapter_id} 两个普通索引），和 {@code chapter.chapter_no} 完全不同。
 * 所以 {@code sort} 可以直接改写、不用先挪到临时值 ——
 * 但也因此<b>不能靠数据库保证「一本书只有一个根」和「同一父节点下 sort 不重复」</b>，
 * 这两条只能由 Service 层保证。反过来，<b>千万别给 sort 加唯一键</b>：
 * 加了之后 {@code renumber} 重排时会立刻开始撞。
 *
 * @author MickeyChen
 */
@Data
@TableName("novel_outline")
public class OutlineNode implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属小说 {@code novel.id} */
    private Long novelId;

    /**
     * 父节点 id。<b>{@code null} = 根节点（全书大纲）</b>。
     *
     * <p><b>★★ 这个字段是 {@code null} 这件事，是 P4 最容易踩的一个坑：</b>
     * {@code Collectors.groupingBy} 在 key 为 {@code null} 时直接抛
     * {@code NullPointerException}。而「根节点的 parentId 是 null」正是最正常的情况 ——
     * 也就是说，只要某本书有一个根节点，用它分桶的 {@code GET /outline} 就 500。
     * 组树必须用 {@code LinkedHashMap#computeIfAbsent}（见
     * {@code OutlineServiceImpl#tree}）。
     */
    private Long parentId;

    /** 节点类型 1全书 2卷 3章 4细纲。★ 不参与校验，只用于显示和「新建子节点」的默认值 */
    private OutlineNodeType nodeType;

    /** 节点标题 */
    private String title;

    /** 剧情笔记（<b>不是正文</b>，见类注释）。可空 —— 传 null 表示清空 */
    private String content;

    /**
     * 关联的正文章节 {@code chapter.id}。<b>可空</b> ——
     * 支持「先写大纲，后建正文」，也支持某些节点永远不建正文
     * （比如「全书」根节点、「卷」节点）。
     *
     * <p>★ 这个引用是<b>单向且可能悬空</b>的：作者可以在章节列表里
     * 单独删掉一章，那条路径不经过大纲树。所以读它的地方都必须
     * 容忍「指向一个已不存在的章节」（见 {@code OutlineServiceImpl#createChapter}
     * 的悬空自愈分支）。
     */
    private Long chapterId;

    /** ★★ 死列 —— P4 不写不读，理由见类注释。别在这里加逻辑 */
    private Integer status;

    /**
     * 同级排序。★ 由 Service 层的 {@code renumber} 统一重排成 0,1,2...
     *
     * <p>查询必须写成 {@code ORDER BY sort ASC, id ASC} —— 只按 sort 排的话，
     * 历史数据里 sort 全是 0（DDL 默认值），顺序就由 MySQL 的返回顺序决定，
     * <b>同一个请求刷新两次可能看到两种顺序</b>。
     * （{@code NovelServiceImpl#applyReaderSort} 里已经踩过一次同样的坑。）
     */
    private Integer sort;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间。
     *
     * <p>★ 用 {@code update(null, wrapper)} 这种「只 set 指定列」的写法时，
     * <b>MyBatis-Plus 的自动填充不会触发</b>，必须自己
     * {@code .set(OutlineNode::getUpdateTime, LocalDateTime.now())}。
     * 这在 P2 的 {@code NovelServiceImpl} 里已经踩过。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    @TableField(select = false)
    private Integer deleted;
}
