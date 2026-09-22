package com.novel.common.internal;

import lombok.Data;

/**
 * 「一条作者申请的最少信息」—— 跨服务传输用（M1）。
 *
 * <p><b>★★ 它为什么存在，而不是让 novel-auth 直接把 {@code AuthorApply}
 * 实体传过来：</b>
 *
 * <p>{@code AuthorApply} 是 novel-auth 的<b>表实体</b>：它带
 * {@code @TableName}、带逻辑删除字段、带一堆和这张表结构绑死的字段
 * （{@code reason}、{@code audit_remark}、{@code create_time}……）。
 * 把它当跨服务 DTO 用，等于<b>把 auth 的表结构变成两个服务之间的契约</b> ——
 * 以后给那张表加一列、改个字段名，novel-content 那边会跟着一起变，
 * 哪怕它根本不关心那个字段。<b>那就等于没有拆服务。</b>
 *
 * <p>所以这里只取「消费方真的要用」的那三列。判据很简单：
 * <b>消费方 {@code ContentUserLookup#authorApplyTitlesOf} 需要什么，
 * 这里就有什么</b> —— 一个都不多。
 *
 * <p><b>★ 为什么要有 {@code penName} 和 {@code userId} 两个字段：</b>
 * 目标标题是「笔名（昵称）」两跳拼出来的：
 * <pre>
 *   applyId ──①──→ penName        （本表的列，直接给）
 *           └─②──→ userId ──→ nickname   （另一张表，靠 nicknamesOf 批量取）
 * </pre>
 * 少任何一个都拼不出那个标题。详见
 * {@code ContentUserLookup#authorApplyTitlesOf} 的注释 —— 那里还写了
 * 「为什么不能拿 applyId 直接当 userId 用」（那会显示成别人的申请，
 * 而且不报错）。
 *
 * <p><b>★★ 这个类的包名为什么是 {@code com.novel.common.internal}
 * 而不是某个服务的包：</b>
 * 它必须被<b>两边同时</b>编译到，而且必须<b>是同一个类</b>。
 * 如果 novel-auth 写一份、novel-content 写一份，两份的字段迟早会不一致
 * —— 而 Jackson 反序列化时「对端多一个字段」是静默忽略的，
 * 于是「新加的那个字段永远是 null」会一直潜伏到有人去查。
 *
 * <p>★ 顺带一提，这个包名叫 {@code internal} 不是装饰：
 * 它对应的 HTTP 前缀 {@code /internal/**} <b>刻意不在 {@code /api/**} 之下</b>，
 * 所以网关的路由表在结构上就不可能把它转发到公网
 * （见 {@code IdentityHeaders} 里关于内部接口的说明）。
 *
 * @author MickeyChen
 */
@Data
public class AuthorApplyBriefDTO {

    /** 申请 id（{@code author_apply.id}），即 {@code audit_log.target_id} 里存的那个值 */
    private Long id;

    /** 申请人 id（{@code author_apply.user_id}），用来取昵称 */
    private Long userId;

    /** 笔名（{@code author_apply.pen_name}）。★ 数据库里是 NOT NULL，所以一定给得出 */
    private String penName;
}
