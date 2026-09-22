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
 * <p>完整论证写在 {@code InternalAuditLogController}（content 侧）的类注释里，
 * 这里只留结论：
 * <blockquote>
 * 铁律 1 禁的是<b>代码依赖</b>，而这个接缝是铁律 1 原文里明确列出的例外
 * （原话：「{@code CommentTextLookup}（content 读 interaction）」）。
 * M2 把「同进程 DI」换成了「一次 HTTP 调用」，接缝没变、
 * 服务级却成了环（{@code novel-content ⇄ novel-interaction}）。
 * </blockquote>
 *
 * <p><b>★★ 记一笔：这个环比 M1 那个更难解。</b>
 * M1 的 {@code auth ⇄ content} 环靠大纲 §M3 的 MQ 就能断（审核事件异步化）。
 * 这一个是**同步读** —— 「管理端审核日志列表要显示评论正文」，
 * 那一刻页面就在等这个数据，异步化没有意义。真正的解法只有两个：
 * ①审核时把正文快照写进 {@code audit_log}（那是 DDL，属于 M5）；
 * ②接受它。<b>所以它很可能活到 M5，别以为 M3 一定会顺手解掉。</b>
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
 * <p>对照 {@code InternalAuditLogController}（content 侧那个）——
 * 那是个<b>写</b>接口，流水要记操作人，所以那边必须把身份传过去，
 * 还得显式校验 {@code UserContext} 非空。<b>判据不是「读还是写」，
 * 而是「这次操作需不需要知道是谁干的」。</b>
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
