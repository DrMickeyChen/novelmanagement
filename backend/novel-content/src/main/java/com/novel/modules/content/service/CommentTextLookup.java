package com.novel.modules.content.service;

import com.novel.common.internal.CommentBriefDTO;

import java.util.Collection;
import java.util.Map;

/**
 * 「按评论 id 取它在审核日志里用得上的素材」—— <b>给审核日志补可读信息用。</b>
 *
 * <p><b>★★ 为什么是一个【接口】而不是一个直接注入 Mapper 的类：</b>
 *
 * <p>{@code audit_log} 的 {@code target_type} 里有 {@code COMMENT}（评论是
 * 审核对象之一），而 {@code audit_log} 表和 {@code AuditLogService}
 * 都在 {@code modules/content} 里。要给一条评论流水补出「评论 #5」之外的
 * 可读信息，就得去查 {@code novel_comment} —— 那张表在
 * {@code modules/interaction}。
 *
 * <p>直接注入 {@code NovelCommentMapper} 会造出一条
 * <b>{@code content → interaction}</b> 的依赖。而二期的顺序是先摘 content（M2）、
 * 再摘 interaction（M3），interaction 本来就要调 content
 * （{@code NovelAccessGuard} / {@code NovelService}），
 * 于是两条边一合起来就是<b>双向依赖</b>，M3 拆不动 ——
 * 这正是 D-1 拒绝把大盘放进 {@code modules/system} 的同一个理由。
 *
 * <p>所以把方向拧回来：<b>接口定义在 content（消费方），实现在 interaction（提供方）</b>。
 * 依赖关系变成 {@code interaction → content}，单向。
 * 二期 M3 把 interaction 拆出去之后，content 侧只需要补一个
 * 「用 Feign 调 novel-interaction」的实现类，<b>接口和调用方一行都不用改</b>。
 *
 * <p><b>★★ M2 兑现了上面那句话，而且兑现得比预期更早：M2 摘 content 时
 * interaction 就跟着拆了（原计划是 M3）。</b> 变化恰好只有预告的那一处：
 * <ul>
 *   <li>这份接口<b>原地不动</b>（只改了返回类型里的 DTO 名字，见下）；</li>
 *   <li>实现类从 interaction 搬到 content，身子换成
 *       {@code CommentBriefClient} 的一次 Feign 调用
 *       —— 见 {@code CommentTextLookupImpl} 的类注释；</li>
 *   <li>interaction 里那份旧实现<b>已删</b>（留着就是死 bean）。</li>
 * </ul>
 * <p><b>★ 返回类型里的 {@code CommentBrief} 已改名为
 * {@link CommentBriefDTO} 并搬进 {@code novel-common}</b> ——
 * 因为现在<b>提供方（interaction）要构造它</b>，留在 content 就等于
 * interaction 依赖 content 的内部类型。★ record 的三个访问器名字
 * 一个没改，所以调用方的读取代码是零改动的。
 * <p>（对照 {@code ContentUserLookup}：那个是反向的场景 —— 它是 content
 * <b>去查</b> system，所以接口和实现都在 content 里，只是把 {@code SysUserMapper}
 * 挡在内部。判据是同一个：<b>跨模块的耦合只能有一个出入口</b>。）
 *
 * <p><b>★ 为什么需要这个方法的只有审核日志一个地方：</b>
 * 评论列表（读者侧 / 管理侧）不经过它 —— 那些查询本来就在 interaction 里，
 * 直接读实体就行。这里多绕一层<b>纯粹是因为消费方不在同一个模块</b>，
 * 不是因为「取正文」这件事需要抽象。
 *
 * @author MickeyChen
 */
public interface CommentTextLookup {

    /**
     * 批量取评论在<b>审核日志里用得上的素材</b>，返回 {@code commentId → 素材}。
     *
     * <p><b>★★ 返回的【不是】拼好的那一行，理由见 {@link CommentBriefDTO} 的注释</b> ——
     * 一句话：位置（{@code 《书名》第N章 ·}）是审核日志页的版式，
     * 而拼版式要用的 {@code Chapter} / {@code Novel} 本来就在
     * {@code AuditLogServiceImpl} 手里；让 interaction 去拼，
     * 它就得反过来调 content 的 Service，绕出一个 Bean 环。
     *
     * <p><b>★ 已删除的评论整条查不到，这是刻意的。</b>
     * 实现走的是 {@code BaseMapper#selectByIds}，{@code @TableLogic} 生效，
     * 于是逻辑删除的行被滤掉 —— 审核日志里那条记录会显示成
     * 「评论 #5（已删除）」，和作品 / 章节的处理完全一致
     * （见 {@code AuditLogVO#targetTitle} 的注释：
     * <b>为了补出已删除对象的标题而绕过逻辑删除是错误的修法</b>）。
     *
     * <p><b>★★ 正文是【截断过的】，别拿它当完整内容用。</b>
     * {@code audit_log} 的展示位只有一行，一条 500 字的评论会把那一行撑爆。
     * 这个截断刻意放在实现里而不是让调用方自己截：
     * 调用方只关心「显示什么」，让它去记「截到多少个字」
     * 就是让两个地方共同维护一个常量。
     * <p>★ M2 之后「实现」在 <b>interaction</b> 那一侧
     * （{@code InternalCommentQueryService}，截断常量也在那里）——
     * 但这条约定没变：<b>调用方仍然不该知道截了多少个字。</b>
     *
     * <p>★ <b>注意对比</b>：{@code targetId} 查不到时返回 {@code null}，
     * 是「已删除」；而这里返回空 Map 只有一种可能 —— 这一页日志里
     * 一条评论流水都没有。两件事不要混。
     *
     * @param commentIds 评论 id 集合，允许为空
     *                （<b>空集合直接返回空 Map，不能拿它去查库</b> ——
     *                那会生成 {@code WHERE id IN ()}，是语法错误）
     * @return {@code commentId → 素材}。<b>查不到的 id 不会出现在 Map 里</b>，
     *         调用方按 {@code null} 处理即可（那就是「已删除」）
     */
    Map<Long, CommentBriefDTO> briefsOf(Collection<Long> commentIds);
}
