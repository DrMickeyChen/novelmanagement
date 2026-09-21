package com.novel.modules.system.service;

import com.novel.common.enums.AuditAction;

/**
 * 「给一条作者申请记一笔审核流水」—— <b>接口在 system（消费方），实现在 content（提供方）。</b>
 *
 * <p><b>★★ 为什么需要这么一个只有一行的方法：因为方向。</b>
 *
 * <p>{@code audit_log} 表和 {@code AuditLogService} 都在 {@code modules/content} 里，
 * 而审核作者申请这件事在 {@code modules/system} 里。让
 * {@code AuthorApplyServiceImpl} 直接注入 {@code AuditLogService}，
 * 就造出一条 <b>{@code system → content}</b> 的依赖。
 *
 * <p>这条边为什么不能有：二期拆服务的顺序是 <b>M1 先摘 auth（{@code modules/system}）、
 * M2 再摘 content</b>。摘 auth 的时候，如果 auth 里有一处 import 了 content 的类，
 * 就得把 content 一起搬过去或者改成跨服务调用 —— 而那时 content 还没拆，
 * 它自己还是一大坨。{@code ContentUserLookup} 的类注释把这条规矩写成了
 * 「只能 content → system，绝不能反过来」，这里是它的另一面。
 *
 * <p><b>★ 解法是把方向拧回来</b>（和 {@code CommentTextLookup} 完全同一个手法）：
 * 接口定义在<b>消费方</b>（本包），实现在<b>提供方</b>
 * （{@code modules/content/service/impl/AuthorApplyAuditRecorderImpl}）。
 * 于是编译期的箭头是 {@code content → system} —— 方向正确地指向下层。
 * 二期把 content 拆出去之后，被替换掉的只有那个实现类，
 * {@code AuthorApplyServiceImpl} 一行都不用动。
 *
 * <p><b>★ 对照 {@code ContentUserLookup}（同一个方向、相反的做法）：</b>
 * 那个是 content <b>去查</b> system，所以接口和实现都在 content 里，
 * 只是把 {@code SysUserMapper} 挡在内部。
 * 判据是同一个：<b>跨模块的耦合只能有一个出入口</b> ——
 * 出入口在谁那一侧，取决于谁是消费方。
 *
 * <p><b>★★ 为什么入参里没有 {@code AuditTargetType}：</b>
 * 有了它，system 侧就能记「作品通过」这种它根本不该知道的事。
 * 写死成 {@code AUTHOR_APPLY} 之后，这个接口<b>只能表达一件事</b>，
 * 误用的可能性被类型系统消掉了。窄接口不是「还没想好通用化」，
 * 是「通用化在这里没有价值，只有风险」。
 *
 * <p><b>★ 为什么入参里也没有 {@code operatorId}：</b>
 * 和 {@code AuditLogService#record} 同一条规矩 ——
 * 操作人从 {@code UserContext} 取，让调用方传就等于允许「记成别人干的」。
 *
 * @author MickeyChen
 */
public interface AuthorApplyAuditRecorder {

    /**
     * 记一条作者申请的审核流水。
     *
     * <p>调用它之前<b>必须已经确定状态流转合法</b>，它不校验任何东西。
     *
     * <p><b>★ 没有 {@code @Transactional}</b>，这是有意的：它加入调用方
     * （{@code AuthorApplyServiceImpl#audit}）的事务，于是「申请状态真的改了」
     * 和「流水写进去了」要么都成、要么都不成。
     * 单独开 {@code REQUIRES_NEW} 会留下「状态回滚了、流水还在」的假记录，
     * 而<b>一条不真实的流水比没有流水更糟</b>。
     *
     * @param applyId 申请的 id。★ <b>不是申请人的 userId</b> ——
     *                {@code audit_log.target_id} 存的是对象自己的 id，
     *                传错会记成「另一个人的申请」，而且两份申请都真实存在，
     *                没有任何办法从流水上看出记错了
     * @param action  动作。★ 只可能是 {@code APPROVE} / {@code REJECT}
     * @param remark  备注，驳回时是理由，可为 {@code null}
     */
    void record(Long applyId, AuditAction action, String remark);
}
