package com.novel.modules.content.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理端审核入参（作品和章节共用）。
 *
 * <p><b>★ 用 {@code pass} 布尔值而不是让前端传目标状态（如 {@code status: 2}），这是有意的：</b>
 * 「通过」和「上架」是两件事，虽然作品审核通过后确实变成已上架，
 * 但让前端直接传目标状态，就等于把状态机的判断权交给了前端 ——
 * 前端传个 {@code status: 0}（草稿）进来，后端要是照做，
 * 一次「审核」就把书打回了草稿，而且日志上记的是一次通过。
 * <p>这里只收「通过 / 驳回」这个业务意图，<b>它对应到哪个状态由状态机决定</b>。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "审核入参")
public class AuditDTO {

    @Schema(description = "true 通过，false 驳回", example = "true",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "请指明是通过还是驳回")
    private Boolean pass;

    /**
     * 备注 —— 驳回时是给作者看的理由，通过时一般留空。
     *
     * <p><b>★ 驳回时不校验它必填，是刻意的取舍。</b>
     * 从产品角度「驳回必须写理由」更好（作者才知道要改什么），
     * 但这条规则属于审核规范，不是数据完整性要求 ——
     * 数据库里 {@code audit_remark} 本来就可空。
     * 如果哪天要强推这个规范，加一个 {@code @AssertTrue} 的自定义校验即可，
     * 位置就在这个类里，不用改 Service。
     */
    @Schema(description = "备注：驳回时是驳回理由", example = "简介与正文不符，请修改后重新提交")
    @Size(max = 255, message = "备注最多 255 个字")
    private String remark;
}
