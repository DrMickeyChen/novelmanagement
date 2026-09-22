package com.novel.common.internal;

import com.novel.common.enums.AuditAction;
import lombok.Data;

/**
 * 「记一条评论审核流水」的请求体 —— 跨服务传输用（M2）。
 *
 * <p><b>★★ 它是 {@link AuthorApplyAuditDTO} 的同构兄弟，收窄的理由一模一样。</b>
 * 那边写了完整论证（「接口比实现窄」、为什么没有操作人字段），这里只写差异。
 *
 * <h3>★ 为什么 M2 必须新开这一个，而不是把旧的加宽成通用的</h3>
 *
 * <p>{@link AuthorApplyAuditDTO} 的类注释里有这么一句预言：
 * <blockquote>
 *   将来如果真出现了第二种跨服务的流水（比如 M3 摘 interaction 之后
 *   评论审核也要记），那时再开一个，而不是把这个 DTO 加宽成通用的。
 * </blockquote>
 * <b>M2 就是那一刻，而且比预言来得更早</b> —— 原以为评论审核会在 M3
 * 摘 interaction 时才跨服务，实际上 M2 摘 content 时它就已经跨了：
 * 评论表在 interaction，流水表在 content。
 * <p>★ 所以这里就是按那句预言办的：<b>新开一个窄接口，不动旧的</b>。
 * 「多一个窄接口的成本，远低于一个宽接口被用错的成本。」
 *
 * <h3>★ 为什么字段叫 {@code commentId} 而不是 {@code targetId}</h3>
 *
 * <p>同 {@code AuthorApplyAuditDTO#applyId} 的理由：<b>名字就说明了它只能装评论 id</b>。
 * 叫 {@code targetId} 的话，调用方迟早会往里塞一个作品 id ——
 * 而 {@code targetType} 是服务端写死的 {@code COMMENT}，
 * 塞错了就会往流水表里写一条「对象 id 是作品、类型是评论」的脏数据，
 * 而且没有任何报错。
 *
 * <p><b>★ 没有 {@code targetType} 字段</b>：它<b>恒等于</b>
 * {@code AuditTargetType.COMMENT}，由服务端写死。少一个字段就少一个能被用错的面。
 *
 * <p><b>★★ 没有操作人字段，这是有意的</b>（同 {@code AuthorApplyAuditDTO}）：
 * 流水的 {@code operator_id} 由 content 从 {@code UserContext} 取，
 * 不接受调用方传。而 {@code UserContext} 里之所以有值，是因为
 * interaction 的 Feign 出站拦截器把当前登录态一并写成了 {@code X-User-*} 头。
 * <p>★ 这条链路在 M2 之后是<b>三段</b>，值得记一笔：
 * <pre>
 *   浏览器 --Authorization--> 网关（验签、剥掉客户端伪造的身份头、写自己的）
 *        --X-User-*--> interaction（HeaderAuthenticationFilter 读进 UserContext）
 *        --Feign 出站拦截器再写一遍 X-User-*--> content（再读进 UserContext）
 * </pre>
 * 任何一段漏了，症状都是「操作人变成匿名 / 审核日志里操作人是空的」，
 * 而不会报错。
 *
 * @author MickeyChen
 */
@Data
public class CommentAuditLogDTO {

    /** 评论 id（{@code novel_comment.id}） */
    private Long commentId;

    /**
     * 审核动作（通过 / 驳回）。
     *
     * <p>★ 用枚举而不是 {@code String}：两边共用同一个
     * {@link AuditAction}（它在 novel-common 里），Jackson 按<b>名字</b>
     * 序列化，拼错一个字母在<b>序列化的那一刻</b>就炸。
     */
    private AuditAction action;

    /** 审核备注（驳回理由等）。★ 允许为空 —— 「驳回必须填理由」是前端规则，后端刻意不拦 */
    private String remark;
}
