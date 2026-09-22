package com.novel.common.internal;

import com.novel.common.enums.AuditAction;
import lombok.Data;

/**
 * 「记一条作者申请审核流水」的请求体 —— 跨服务传输用（M1）。
 *
 * <p><b>★★ 这个 DTO 是刻意【收窄】的，收窄本身就是它的设计。</b>
 *
 * <p>novel-content 那一侧的 {@code AuditLogService#record} 收三个参数：
 * {@code (AuditTargetType, Long, AuditAction, String)} —— 它能记
 * <b>任何</b>类型的流水（作品、章节、评论、作者申请……）。
 * 如果内部接口直接暴露那个签名，就等于<b>把「往流水表里写任意一行」的能力
 * 交给了另一个服务</b>，而流水表是这个系统的追责依据。
 *
 * <p>所以内部接口只收这一个形状：<b>「一条作者申请审核」</b>。
 * <ul>
 *   <li>{@code targetType} 不需要传 —— 它<b>恒等于</b> {@code AUTHOR_APPLY}，
 *       由服务端写死。少一个字段，就少一个能被用错的面。</li>
 *   <li>{@code targetId} 在这里叫 {@link #applyId} —— 名字就说明了它只能装申请 id。
 *       叫 {@code targetId} 的话，调用方迟早会往里塞一个作品 id。</li>
 * </ul>
 *
 * <p><b>★ 这是一次「接口比实现窄」的取舍</b>：将来如果真出现了第二种跨服务的
 * 流水（比如 M3 摘 interaction 之后评论审核也要记），那时再开一个
 * {@code POST /internal/comment-audit-logs}，而不是把这个 DTO 加宽成通用的。
 * <b>多一个窄接口的成本，远低于一个宽接口被用错的成本。</b>
 *
 * <p><b>★★ 注意这里【没有】操作人字段，这是有意的：</b>
 * 流水的 {@code operator_id} 由 novel-content 从 {@code UserContext} 取
 * （见 {@code AuditLogServiceImpl#record} 里那句
 * {@code UserContext.requireUserId()}），<b>不接受调用方传</b>。
 * 那「调用方的登录态怎么过去的」呢 —— 靠 Feign 出站拦截器把当前身份
 * 一并写成 {@code X-User-*} 请求头，和网关做的是同一件事
 * （复用 {@code IdentityHeaders}）。
 * <p>为什么不干脆在这里加一个 {@code operatorId} 字段：那会把
 * 「操作人不可伪造」这条不变量变成一个<b>纯靠调用方自觉</b>的约定。
 * 而现在的做法里，能伪造操作人的前提是「你已经能调通内部接口了」——
 * 那本来就已经是整个系统的最高权限。
 *
 * @author MickeyChen
 */
@Data
public class AuthorApplyAuditDTO {

    /** 作者申请 id（{@code author_apply.id}） */
    private Long applyId;

    /**
     * 审核动作（通过 / 驳回）。
     *
     * <p>★ 用枚举而不是 {@code String}：两边共用同一个
     * {@link AuditAction}（它在 novel-common 里），Jackson 按<b>名字</b>
     * 序列化，所以拼错一个字母在<b>序列化的那一刻</b>就炸，
     * 而不是变成一个下游「查不到这个动作」的静默 null。
     */
    private AuditAction action;

    /** 审核备注（驳回理由等）。★ 允许为空 —— 「驳回必须填理由」是前端规则，后端刻意不拦 */
    private String remark;
}
