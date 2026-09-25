package com.novel.modules.ai.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一次前情摘要生成的结果（E1/S10）。
 *
 * <h3>★★★ 它最重要的不是 {@link #summary}，而是后面那四个「实际发生了什么」的字段</h3>
 *
 * <p>生成接口是<b>同步</b>的：作者点一次、等一次模型调用、拿回一段文字。
 * 而这段文字会被<b>直接存库</b>，从下一章起当成「已有的前情」喂给模型 ——
 * 中间<b>没有人工采纳那一步</b>。于是「它到底压了哪几章」这件事，
 * 唯一的记录就是这次响应和那一行 {@code ai_summary}。
 *
 * <p>★★ <b>为什么必须把「实际覆盖的区间」和「跳过了谁」返回出来</b>：
 * 请求里给的 {@code endChapterNo} 是<b>意图</b>，不是结果 ——
 * 区间里可能有还没写正文的章节（被跳过）、也可能整段章节号根本不存在。
 * 如果只回一个「成功」，作者会以为第 11~20 章都进了摘要，
 * 而实际上只有 11~18 —— <b>下一章开写时模型手里的前情缺了一段，且没有任何地方提示过</b>。
 * ★ 这与项目铁律「被跳过的条目<b>必须报出来</b>，绝不静默过滤」是同一条，
 * 只不过那次说的是批量操作，这次说的是批量压缩。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "生成好的前情摘要")
public class AiSummaryVO {

    @Schema(description = "摘要 id（ai_summary 主键）")
    private Long id;

    @Schema(description = "作品 id")
    private Long novelId;

    /**
     * 实际覆盖的起始章节号。
     * ★ 它<b>不是</b>请求里算出来的起点（上一条摘要的 end + 1）—— 起点那几章可能没有正文。
     * 落库的是这个「实际值」，因为链条的接续靠的就是它。
     */
    @Schema(description = "实际覆盖的起始章节号")
    private Integer startChapterNo;

    /**
     * 实际覆盖的结束章节号（含）。
     * ★ 可能<b>小于</b>请求里的 {@code endChapterNo}（尾部几章还没写正文）。
     */
    @Schema(description = "实际覆盖的结束章节号（含）")
    private Integer endChapterNo;

    /** ★ 这段文字会被直接存库、并成为后续章节的上下文 —— 它不是「建议」，是「事实」 */
    @Schema(description = "生成的前情摘要正文")
    private String summary;

    /** 实际参与压缩的章节数（= covered，不含被跳过的） */
    @Schema(description = "实际参与压缩的章节数")
    private Integer chapterCount;

    /**
     * 本次送进模型的正文总字数（{@code String.length()}，与预算护栏同一把尺）。
     * ★ 有了它，「差点超预算」是可观察的，而不是等某天突然被 400 拦下来才发现。
     */
    @Schema(description = "本次送进模型的正文总字数")
    private Integer sourceChars;

    /**
     * 区间内<b>存在但还没有正文</b>的章节号（升序）。
     *
     * <p>★★ 空列表是正常情形（区间内每一章都有正文）。
     * ★ 它只列<b>库里真实存在</b>的章节 —— 「第 21~999999 章」这种不存在的编号不在这里，
     * 那属于「作者把 endChapterNo 传大了」，用 {@link #endChapterNo} 与请求值的差就能看出来。
     */
    @Schema(description = "区间内存在但没有正文、被跳过的章节号")
    private List<Integer> skippedChapterNos;

    /** 入库时间。★ 后续章节引用的就是这一行，它是摘要链上的时间锚点 */
    @Schema(description = "入库时间")
    private LocalDateTime createTime;
}
