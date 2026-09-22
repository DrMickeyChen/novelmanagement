package com.novel.modules.content.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.novel.common.enums.ChapterStatus;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 章节元信息，对应 {@code chapter}。<b>正文不在这张表</b>，在 {@code chapter_content}（见 {@link ChapterContent}）。
 *
 * <p><b>★★ {@code uk_novel_chapter_no(novel_id, chapter_no)} 是本项目最危险的一个约束。</b>
 * 它<b>不包含 {@code deleted}</b>，所以逻辑删除一章之后，那一行还占着那个序号 ——
 * 「删过第 5 章，就再也建不出第 5 章」，报 {@code Duplicate entry}。
 * 这是<b>必然复现</b>的，不是理论风险。
 * <p>解法：删除时顺手把序号改成负数（见 {@code ChapterMapper#logicDeleteAndFreeChapterNo}）。
 *
 * <p><b>★ {@link #status} 只能通过 {@code ChapterService#transitStatus} 修改</b>，理由同
 * {@link Novel#getStatus()} —— 绕开状态机就会漏记审核日志。
 *
 * <p>注意章节<b>没有「已下架」状态</b>：章节不能单独下架，只能跟着作品一起下架
 * （作品下架后读者根本进不来，见 {@code ChapterStatus} 的类注释）。
 *
 * @author MickeyChen
 */
@Data
@TableName("chapter")
public class Chapter implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属小说 {@code novel.id} */
    private Long novelId;

    /**
     * 第几章。★ 见类注释 —— 这个值被唯一键绑着，删除时会被改成 {@code -id}。
     *
     * <p>所以<b>已删除章节的 {@code chapter_no} 不再等于它的真实序号</b>。
     * 将来若要做「回收站 / 恢复已删除章节」，恢复时<b>必须重新分配序号</b>，
     * 不能简单地把负数取回正数（会和期间新建的章节撞号）。
     */
    private Integer chapterNo;

    /** 章节标题 */
    private String title;

    /** 正文字数。★ 保存正文时按「非空白字符数」算出来，不是手填的 */
    private Integer wordCount;

    /** 状态机：0草稿 1待审核 2已发布 4已驳回。★ 只能走 transitStatus 改 */
    private ChapterStatus status;

    /** 驳回理由 */
    private String auditRemark;

    /** 是否免费。★ 预留的 VIP 字段，P2 不参与任何逻辑 */
    private Integer isFree;

    /** 冗余：阅读量。★ P2 不维护，P3 做阅读器时再说 */
    private Integer viewCount;

    /** 通过审核的时间。★ 只有已发布的章节才有值，读者端目录按它排序 */
    private LocalDateTime publishTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    @TableField(select = false)
    private Integer deleted;
}
