package com.novel.modules.ai.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 小说前情摘要，对应 {@code ai_summary}（E1）。
 *
 * <p><b>★ 它解决的是「长篇塞不进 prompt」</b>：写到几百章之后，
 * 「把前面发生过什么告诉模型」这件事不可能靠全文（那是几十万字）。
 * 摘要按 {@code end_chapter_no} 滚动往前压，每次只带最新的一条。
 *
 * <h3>★★ 三个必须知道的点</h3>
 * <ol>
 *   <li><b>唯一键是 {@code uk_novel_end(novel_id, end_chapter_no)}</b> ——
 *       也就是说<b>同一个截止章节只能有一条摘要</b>。
 *       ★★ <b>S10 起它是一道【拒答的闸门】，不是一条「覆盖」的规则</b>：
 *       生成接口要求 {@code endChapterNo} 严格大于上一条的 {@code end_chapter_no}，
 *       等于或小于直接 400（见 {@code AiSummaryService}）——
 *       因为重压时组装器能读到的「已有前情摘要」恰好就是你要重压的那一段，
 *       模型收到的标签在说假话。★ 所以「覆盖」这个分支<b>到不了</b>，
 *       唯一键在这里的作用是：并发两次同区间压缩时让第二条<b>写不进去</b>
 *       （抛 DuplicateKeyException → 500），而不是悄悄覆盖掉第一条。
 *       ★ 取「最新的一条」= {@code ORDER BY end_chapter_no DESC LIMIT 1}，
 *       <b>不是</b> {@code ORDER BY id DESC}：两者只在「有人补压了一段更早的区间」时
 *       分道扬镳，而那时按 id 取到的是更晚写、却覆盖更早剧情的那条 ——
 *       模型拿着它续写，接的是一段已经被跳过的前情，<b>且看不出来</b>。</li>
 *   <li><b>没有 {@code deleted}、没有 {@code update_time}</b>。
 *       ★ 别顺手加 {@code @TableLogic}：那会让每次查询都带 {@code deleted = 0}，
 *       而这一列<b>不存在</b> → SQL 报错。同 {@code AiPromptTemplate} 的记法。</li>
 *   <li>{@code summary} 是 {@code TEXT NOT NULL}，长度由历史决定 ——
 *       所以它<b>没有任何 {@code @Size} 管着</b>，进入提示词的总长度
 *       只能靠 {@code PromptRenderer} 那道 20000 字的护栏兜（见那个常量）。</li>
 * </ol>
 *
 * <p>★ 读侧是 S6 的 {@code {{summary}}}（{@code AiContextAssembler} 按 {@code novelId} 取最新一条）；
 * <b>写侧是 S10 的「作者手动触发压缩」</b>（{@code AiSummaryService}，同步、生成完直接落库）。
 * ★ 读写分两步交付，是因为读侧要先能工作 —— 判断 2 的「③ 与 ⑤ 解耦」就是靠这个：
 * {@code {{summary}}} 先是「可选无值 → 整行移除」，S10 上线后同一份模板自动开始带上摘要，
 * <b>渲染侧一行代码都不用改</b>。
 * ★★ 也正因为写侧【不经人工采纳】，它的失败必须表达为「没有新行」，
 * 绝不能存一行半成品 —— 见 {@code AiSummaryService} 第 ⑪ 步的注释。
 *
 * @author MickeyChen
 */
@Data
@TableName("ai_summary")
public class AiSummary implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long novelId;

    /** 这段摘要覆盖的起始章节号（含） */
    private Integer startChapterNo;

    /**
     * 这段摘要覆盖的结束章节号（含）—— <b>「哪一条最新」就按它排</b>。
     *
     * <p>★ 它是唯一键的一半，所以服务端不用（也不能）维护一个「当前摘要」指针。
     */
    private Integer endChapterNo;

    /** 摘要正文。★ {@code TEXT NOT NULL}，长度无上限，见类注释第 3 条 */
    private String summary;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
