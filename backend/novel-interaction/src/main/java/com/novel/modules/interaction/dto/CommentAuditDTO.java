package com.novel.modules.interaction.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 评论审核入参。管理端通过 / 隐藏一条评论。
 *
 * <p><b>★ 这里【没有】{@code commentId}</b> —— 它在 URL 路径上。
 * body 里再放一份就会产生「路径说 A、body 说 B」时以谁为准的问题，
 * 而无论选哪个，另一份从此就是骗人的（同 {@code CommentCreateDTO}）。
 *
 * <p><b>★ 也【没有】{@code auditUserId}</b> —— 审核人只能来自
 * {@code UserContext}。让它可传，等于「记成别人审的」，
 * 而审核留痕的全部价值就在于「谁做的」不可伪造
 * （同 {@code AuditLogService#record} 刻意不收 {@code operatorId} 的理由）。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "评论审核入参")
public class CommentAuditDTO {

    /**
     * 审核结果：<b>1 已显示（通过）、2 已隐藏（驳回）</b>。
     *
     * <p><b>★ 只有这两个值，传 0（待审核）会被拒。</b>
     * 「把一条已经审过的评论改回待审」不是审核动作 ——
     * 它的效果是让评论从读者眼前消失、同时在待审队列里再多出一条，
     * 而 {@code CommentStatus.canTransitTo} 里没有任何一条边指向 PENDING
     * （状态机是单向的，见那个枚举）。
     *
     * <p><b>★ 这里不加 {@code @Min/@Max}，把判断留给 Service</b>：
     * 加了的话，越界会返回 Spring 的通用校验失败文案（「参数校验失败」），
     * 而管理员真正需要知道的是「审核结果只能是已显示或已隐藏」。
     * 规矩只有一处实现（{@code CommentStatus.fromCode} + {@code canTransitTo}），
     * 错误文案也就在那一处。
     *
     * <p>★ <b>刻意没有 {@code 3 已删除}</b>：方案里最初写的是
     * 「0待审 → 1通过 / 2驳回 / 3删除」，那是 {@code CommentStatus} 定稿之前的说法。
     * 枚举最终是 0待审 / 1已显示 / 2已隐藏 —— <b>没有 DELETED</b>，
     * 因为「已隐藏」（管理员的处置）和「已删除」（{@code deleted} 逻辑删除字段）
     * 是两个维度，删评论是用户自己的权利，不在管理端的审核动作里。
     */
    @Schema(description = "审核结果：1 已显示（通过）/ 2 已隐藏", example = "1",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "审核结果不能为空")
    private Integer status;

    /**
     * 审核备注。<b>可选</b>，最长 255 字（对齐 {@code audit_remark VARCHAR(255)}）。
     *
     * <p><b>★★ 和章节审核（D-12）不同，这里没有强制必填，理由是「理由写给谁看」：</b>
     * <ul>
     *   <li>章节驳回必须写理由，是因为<b>作者会看到它</b> ——
     *       不写的话作者收到一个「已驳回」却不知道改哪里，
     *       而「改哪里」正是他下一步要做的全部事情。</li>
     *   <li>评论隐藏写理由，<b>评论人看不到</b> —— P5 的读者端只有公告横幅
     *       （决定 11），没有任何「你的评论被隐藏了」的反馈通道。
     *       强制必填只会让管理员在每一行上填一个「违规」凑数，
     *       而凑出来的理由比不填更糟：它看起来像一条有效记录。</li>
     * </ul>
     * <p>所以它的定位是<b>管理员自己的备注</b>：同一批违规评论连续处理时，
     * 它是「上次为什么隐藏这一条」的唯一记录。填了会进 {@code audit_remark}，
     * 也会进 {@code audit_log.remark}（审核日志页能看到）。
     *
     * <p>★ {@code @Size} 必须有：{@code audit_remark} 只有 255 字符，
     * 超长在严格 SQL 模式下是 500，非严格模式下静默截断。
     */
    @Schema(description = "审核备注，可选，最长 255 字", example = "含广告引流内容")
    @Size(max = 255, message = "审核备注最多 255 个字")
    private String remark;
}
