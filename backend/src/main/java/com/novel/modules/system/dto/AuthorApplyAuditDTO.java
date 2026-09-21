package com.novel.modules.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 作者申请审核入参。
 *
 * <p><b>★★ 为什么不复用 {@code modules/content/dto/AuditDTO}（那个也是 pass + remark）：</b>
 * 那个类在 {@code modules/content} 里，而这个 Service 在 {@code modules/system}。
 * 让 system 去 import 它，就造出了一条 <b>{@code system → content}</b> 的依赖 ——
 * 而 {@code ContentUserLookup} 的类注释里写得很清楚：这条边绝对不能有，
 * 否则二期 M1 先拆 auth 的时候，auth 服务会反向依赖 content，
 * 「M1 先摘 auth、M2 再摘 content」的顺序直接作废。
 *
 * <p><b>★ 那把它挪到 {@code common/dto} 里共用呢？</b>也不行，而且项目里已经有反例：
 * {@code modules/interaction} 的 {@code CommentAuditDTO} 本来就是另一个类 ——
 * 评论审核收的是<b>目标状态码</b>（要支持「改判」），
 * 而不是 {@code pass} 布尔。三个模块三种审核语义，
 * 硬合成一个类的话，那个类会变成一个「什么都能传」的袋子，
 * 而每加一个用法的代价是所有人重新读一遍它的字段。
 * <p>所以规矩是：<b>审核入参由「发起这次审核的模块」自己拥有。</b>
 * 代价是两个类长得有点像；收益是每个模块的入参只说自己那一件事。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "作者申请审核入参")
public class AuthorApplyAuditDTO {

    /**
     * {@code true} 通过并授予 AUTHOR 角色，{@code false} 驳回。
     *
     * <p>★ 和 {@code AuditDTO#pass} 一样，收的是「业务意图」而不是目标状态码 ——
     * 前端传个 {@code status: 0}（待审）进来的话，后端要是照做，
     * 一次「审核」就把申请打回了未处理，而日志上记的是一次通过。
     */
    @Schema(description = "true 通过，false 驳回", example = "true",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "请指明是通过还是驳回")
    private Boolean pass;

    /**
     * 备注。<b>驳回时是给申请人看的理由。</b>
     *
     * <p>★ 和 {@code AuditDTO#remark} 一样不在后端强校验「驳回必填」：
     * 那是审核规范，不是数据完整性要求，库里这一列本来就可空。
     * 但前端<b>必须</b>拦（见 {@code AuditDialog} 的 {@code remarkRequired}）——
     * 一个空白的驳回理由，申请人除了重新点一次提交什么也做不了。
     */
    @Schema(description = "备注：驳回时是驳回理由（申请人能看到）",
            example = "创作说明过于简单，请补充后再提交")
    @Size(max = 255, message = "备注最多 255 个字")
    private String remark;
}
