package com.novel.internal.controller;

import com.novel.common.internal.CommentBriefDTO;
import com.novel.common.result.Result;
import com.novel.internal.service.InternalCommentQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.Map;

/**
 * <b>供兄弟服务（novel-content）读评论正文的内部接口。</b>
 *
 * <p>M2 新增。<b>★★ 它是全项目唯一一条「反向」的跨服务边</b> ——
 * 别的都是 interaction → content，只有它是 content → interaction。
 *
 * <p><b>★ 先回答「这条边凭什么存在」：铁律 1 禁的是【代码依赖】，
 * 而它禁的方向是 {@code system → content}，说的是「拆服务的顺序不能反」。</b>
 * 本接缝是铁律 1 原文里明确列出的例外
 * （原话：「{@code CommentTextLookup}（content 读 interaction）」）——
 * 改造前它是同进程 DI（接口在 content，实现在 interaction），
 * 数据在谁那儿、查询就归谁写。<b>M2 换掉的只是「怎么连」，不是「能不能连」。</b>
 * ★ 换个方向理解更清楚：这条边读的是{@code novel_comment}，而那张表是
 * interaction 的 —— content 自己<b>没有</b>任何办法回答「这条评论说了什么」。
 * 要么问一句，要么把评论正文抄一份到 content（那就是 DDL + 一致性债，
 * 见下面第 ① 种解法）。
 *
 * <p><b>★★ 但同一条边在【服务级】上确实是个环，这个环的完整论证就在这里
 * （不再有「别处」了）。</b>
 * M2/M3 期间它写在 content 侧的 {@code InternalAuditLogController} 类注释里，
 * 而那个类在 M3 被整个删除了（它最后的写端点也走了 MQ）。
 * <b>改动这一段之前先确认没有别的东西指过来</b>：
 * <pre>
 *   环 #1  novel-auth ⇄ novel-content         ← M1 造，★ M3 已解（见下面的对照）
 *   环 #2  novel-content ⇄ novel-interaction  ← M2 造，★ 就是本类
 * </pre>
 *
 * <h3>★★★ 对照：为什么环 #1 解掉了，环 #2 解不掉</h3>
 *
 * <p>两个环形状完全一样（A 读 B 一次、B 写 A 一次），结局却不同 ——
 * <b>差别不在环本身，在那条「回程」是读还是写</b>：
 * <pre>
 *   环 #1 的回程是【写】：auth 审完申请，让 content 记一条流水。
 *          → 写可以异步：改成「发一条事务消息」，auth 立刻返回，
 *            流水晚几百毫秒出现没有任何人受影响。★ 于是环断了。
 *
 *   环 #2 的回程是【读】：管理端审核日志列表要显示【评论正文】。
 *          → 读不能异步：那一刻页面就在等这个数据。
 *            异步化只会把「同步等」变成「同步等一个还没到的值」。
 * </pre>
 * <p>★ 所以本类的存在不是「M2 偷懒没做」：<b>它不可异步化</b>。
 * 真正的解法只有两个，都超出 M3 的范围：
 * <ol>
 *   <li><b>审核时把正文快照写进 {@code audit_log}</b> —— 那是一次 DDL
 *       （给流水表加一列），属于 M5；而且它会把「评论正文」变成一份会过期的副本，
 *       要考虑评论被删/被改之后流水显示什么，不是一个能顺手做的决定；</li>
 *   <li>接受它，并把它当作「拆服务的代价」记在文档里。</li>
 * </ol>
 * <b>本项目的选择是 ②，并且明确写下：它很可能活到 M5。
 * 别以为 M3 顺手把它解掉了</b> —— M3 只解掉了环 #1。
 *
 * <h3>★ 路径为什么是 {@code /internal/**}</h3>
 *
 * <p>同 content 那两个内部接口：{@code /internal/**} 刻意不在 {@code /api/**}
 * 之下，而网关的路由表里只有 {@code /api/**} 和 {@code /uploads/**} ——
 * <b>网关在结构上就配不出转发它的路由</b>。
 *
 * <h3>★ 为什么这个接口不需要身份</h3>
 *
 * <p>它是<b>只读</b>的，而且读的是「评论正文」这种<b>读者本来就能看到的东西</b>
 * （评论列表是匿名可读的，见 {@code ReaderCommentController} 的口径）。
 * 它不需要知道调用者是谁，也就没有伪造身份的问题。
 * <p>★ <b>「这次操作需不需要知道是谁干的」这条判据仍然成立，但它在 M3 之后
 * 已经【没有任何 HTTP 接口】可以印证了</b> —— content 整个 {@code /internal/**}
 * 在 M3 变成了只读（两个统计写回、一个审核流水，三条写路径全部改走消息）。
 * 它现在的落点在 MQ 那一侧：{@code AuditRecordConsumer} 收到缺 {@code operatorId}
 * 的流水消息时<b>宁可丢弃也不写一条不知道是谁的</b>。
 * <pre>
 *   HTTP 时代：判据 = 请求头里有没有身份（Feign 出站拦截器写的 X-User-*）
 *   MQ   时代：判据 = 载荷里有没有 operatorId
 * </pre>
 * ★ 形态变了，是因为 MQ 的消费线程上<b>永远没有</b> {@code UserContext}
 * （没有请求就没有过滤器），照搬「校验 UserContext 非空」会让每一条消息都抛异常。
 * 详细论证在 {@code AuditRecordConsumer} 的类注释里。
 *
 * @author MickeyChen
 */
@Slf4j
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalCommentController {

    private final InternalCommentQueryService queryService;

    /**
     * 批量取评论素材，返回 {@code {commentId: CommentBriefDTO}}。
     *
     * <p><b>★ 用 {@code POST} 而不是 {@code GET}：id 列表可能很长</b>
     * （一页审核日志最多 100 条），塞进 query string 会撞上 URL 长度限制，
     * 而那个限制在不同服务器上不一样（症状是「日志多了就报 400」，
     * 本地还复现不了）。镜像 {@code /internal/users/nicknames} 的既有范式。
     *
     * <p>★ {@code required = false}：空集合是合法输入（返回空 Map），
     * 让调用方不必为「这一页一条评论流水都没有」写特判。
     * <p><b>★ 查不到的 id 不会出现在 Map 里</b>，调用方按 {@code null} 处理 ——
     * 那就是「已删除」（{@code selectByIds} 走 {@code @TableLogic}，
     * 逻辑删除的行被滤掉），审核日志里显示成「评论 #5（已删除）」。
     */
    @PostMapping("/comment-briefs")
    public Result<Map<Long, CommentBriefDTO>> commentBriefs(
            @RequestBody(required = false) Collection<Long> commentIds) {
        return Result.success(queryService.briefsOf(commentIds));
    }
}
