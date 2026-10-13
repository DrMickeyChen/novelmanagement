package com.novel.modules.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 「排除」一次 AI 判定的入参（E1/S9）。
 *
 * <p><b>★★★ 为什么用 {@code (targetType, targetId)} 而不是 {@code ai_audit_result.id}</b>
 *
 * <p>本表的行 id 当然也能唯一定位一行，但那样会造出<b>第二套「哪一行该被处置」的定义</b>：
 * <pre>
 *   本接口 / 联动  → WHERE target_type=? AND target_id=? AND status=0
 *   按行 id 的写法 → WHERE id=? AND status=0
 * </pre>
 * 两者在<b>正常情况</b>下永远指向同一行 —— 所以这个差别平时看不出来。
 * 差别只在「有人往表里插了一条重复的待办行」这种异常形态下显形：
 * 那时按 target 会一次标记两行（正确：这个目标确实被处置了），
 * 按 id 只会标记一行，另一行<b>永远留在待办里</b>。
 * ★ 判据：**「什么算被处置过」只允许有一个定义**，它已经在
 * {@code AiAuditResultMapper#linkHumanDisposition} 里写死了，这里必须复用它。
 *
 * <p>★ 前端不需要记任何 id —— 队列页每一行本来就带着 {@code targetType} / {@code targetId}
 * （见 {@code AiAuditRowVO}），直接回传即可。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "排除 AI 判定入参")
public class AiAuditExcludeDTO {

    /**
     * 1小说 2章节 3评论。★ <b>不设 {@code @Min/@Max}</b>：
     * 这个字段的合法域由 {@code ai_audit_result.target_type} 的<b>实际数据</b>决定，
     * 而一个越界的值在这里只会命中 0 行 → {@code 5010}（「该审核已被处置，请刷新后查看」）。
     * ★ 那句文案对越界值<b>不准确</b>，但这是刻意的取舍：越界值只可能来自
     * 有人手动调接口，而为此加一条 {@code @Min(1) @Max(3)} 会让
     * 「AI 审核将来扩展到第 4 种目标」这件事<b>悄悄失效</b>（新类型被 400 挡住，
     * 而 400 的文案是「targetType 只能是 1~3」—— 排查方向指向调用方，不指向这里）。
     */
    @Schema(description = "目标类型：1小说 2章节 3评论", example = "3",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "目标类型不能为空")
    private Integer targetType;

    /** 目标 id（评论 id / 章节 id） */
    @Schema(description = "目标 id", example = "94", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "目标 id 不能为空")
    private Long targetId;
}
