package com.novel.modules.content.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 状态分布 —— 给两个饼图用（作品状态 / 章节状态）。
 *
 * <pre>
 * {
 *   "novels":   [{"status":0,"label":"草稿","count":12},
 *                {"status":1,"label":"待审核","count":3},
 *                {"status":2,"label":"已上架","count":68},
 *                {"status":3,"label":"已下架","count":2},
 *                {"status":4,"label":"已驳回","count":1}],
 *   "chapters": [...]
 * }
 * </pre>
 *
 * <p><b>★★ 两个数组都是「零填充」的：某种状态一个都没有，也会出现
 * 一条 {@code count = 0}。</b>
 *
 * <p>这里的理由和折线图的<b>零填充</b>（见 {@link TrendVO}）同源，
 * 但坏掉的方式不一样，值得单独说一次：
 * <ul>
 *   <li>折线图跳过 0 的那天 → <b>把两点连成斜线</b>，图上多出一条不存在的趋势。</li>
 *   <li>饼图跳过 count = 0 的那类 → 这个分类<b>从图例里彻底消失</b>。
 *       于是「已驳回 0 本」和「这个状态在系统里根本不存在」
 *       在界面上长得一模一样，而这两种情况的含义完全不同。</li>
 * </ul>
 * 所以这里是「补齐所有枚举值」而不是「有什么返回什么」——
 * 值的来源是 {@code NovelStatus.values()} 和 {@code ChapterStatus.values()}，
 * <b>不是</b> SQL 里查出来的那几行。
 *
 * <p><b>★ 章节没有「已下架」（3）。</b>章节状态是 0/1/2/4，
 * 3 是作品独有的。所以两个数组的长度不一样（作品 5 个、章节 4 个），
 * 这是<b>正确的</b>，不是漏了一个。见 {@code ChapterStatus} 的类注释。
 *
 * <p>★ 前端喂给 ECharts 饼图时转一下即可：
 * {@code data.map(i => ({ name: i.label, value: i.count }))}。
 * <b>但保留 {@code status} 数字</b> —— 颜色要按状态码固定
 * （草稿灰、待审橙、已上架绿…），不能按数组下标，
 * 否则某天「已下架」变成 0 之后，后面所有扇区的颜色会整体错位。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "作品 / 章节的状态分布（零填充）")
public class StatusDistVO {

    /** 作品状态分布，按 {@code NovelStatus} 的声明顺序（0/1/2/3/4） */
    @Schema(description = "作品状态分布")
    private List<Item> novels;

    /** 章节状态分布，按 {@code ChapterStatus} 的声明顺序（0/1/2/4）—— ★ 没有 3 */
    @Schema(description = "章节状态分布（★ 没有「已下架」）")
    private List<Item> chapters;

    /**
     * 一项分布：某个状态有多少个。
     *
     * <p><b>★ {@code status} 和 {@code label} 都要给。</b>
     * 只给 label 的话前端没法按状态码固定扇区颜色；只给 status 的话
     * 前端就得自己维护一份「0 是草稿」的映射 —— 而那份映射
     * 和后端的枚举迟早会对不上，且对不上时不报错，只是图例写错。
     */
    @Data
    @Schema(description = "单个状态的计数")
    public static class Item {

        @Schema(description = "状态码（对应枚举的 code）", example = "2")
        private Integer status;

        @Schema(description = "状态的显示名（来自枚举的 desc，不是前端硬编码的）", example = "已上架")
        private String label;

        @Schema(description = "该状态的数量。★ 0 也会返回", example = "68")
        private Long count;
    }
}
