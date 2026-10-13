package com.novel.modules.ai.client;

import com.novel.common.internal.CommentBriefDTO;
import com.novel.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Collection;
import java.util.Map;

/**
 * <b>content 侧调 novel-interaction 的 Feign 客户端（M2）—— 只为了取评论正文。</b>
 *
 * <p><b>★★ 这是全项目唯一一个「反向」的客户端</b>（content → interaction），
 * 而别的跨服务调用都是 interaction → content。
 *
 * <p>它为什么必须存在、以及它制造的那个<b>服务级的环</b>
 * （{@code novel-content ⇄ novel-interaction}），完整的论证写在
 * {@code InternalCommentController}（interaction，也就是对端那个端点）
 * 的类注释里 —— <b>M3 之后那是唯一一处从头讲清它的地方</b>
 * （content 侧原来那份写在 {@code InternalAuditLogController} 里，
 * 而那个类随它最后的写端点一起在 M3 删掉了）。
 * 这里只留一句结论：
 * <blockquote>
 * 铁律 1 禁的是<b>代码依赖</b>，而这个接缝是铁律 1 原文里明确列出的例外
 * （原话：「{@code CommentTextLookup}（content 读 interaction）」）。
 * M2 把「同进程 DI」换成了「一次 HTTP 调用」，接缝没变、
 * 服务级却成了环 —— <b>而这个环 MQ 不一定解得掉</b>，见上面那个类注释。
 * </blockquote>
 * ★★ <b>M3 的对照（值得记一笔，因为它解释了「为什么另一个环解掉了」）：</b>
 * 同期存在的环 #1（{@code novel-auth ⇄ novel-content}）<b>已经解掉了</b> ——
 * 它的回程是一次<b>写</b>（审核流水），而写可以异步化（改成一条事务消息）。
 * 本环的回程是一次<b>读</b>（要显示评论正文），页面就在等这个值，异步化没有意义。
 * <b>两个环形状相同、结局不同，差别只在回程是读还是写。</b>
 *
 * <p><b>★ 接口住在消费方（content）</b>，理由同 {@link AuthUserClient} ——
 * 「发一次 HTTP 请求」这件事必须住在调用发生的地方。
 * interaction 那边<b>根本不知道这个接口存在</b>，
 * 它只是恰好有一个路径相同的 {@code @RestController}。
 *
 * <p><b>★★ 它只有一个方法，这是刻意的。</b>
 * {@code AuthUserClient} 的注释里写过那条规矩：「内部接口一旦变成
 * 万能查询，它就成了一个绕过服务边界的后门」。这边更极端 ——
 * content 对评论表的<b>全部</b>需求就是这一个问题
 * （「这几十条评论分别说了什么、挂在什么对象上」），
 * 多一个方法都是在替 interaction 决定它的表该怎么被读。
 * <p>★★★ <b>E1 破了这个「只有一个方法」，而破它的理由必须写在这里：
 * 那条规矩禁的是「同一个问题多问几遍」，不是「有几个问题」。</b>
 * AI 违禁审核问的是<b>另一个</b>问题 ——
 * 「这条评论的<b>全文</b>是什么」（上面那个问的是「显示成什么样」，
 * 答案已经截断到 30 字）。两者只共享『评论 id → 它说了什么』这一层，
 * <b>下面需要的精度不同、失败语义也相反</b>：
 * <ul>
 *   <li>{@code commentBriefs}：少了 → 审核日志那一行显示「（已删除）」，坏但不致命；</li>
 *   <li>{@code commentTexts}：少了 → <b>审核链拿空正文去扫 → 无命中 → 判成「排除」</b>，
 *       一句对从没被审过的内容说的「通过」。所以它<b>更不能降级</b>。</li>
 * </ul>
 * ★ 判据仍然是「这个方法是替对端决定它的表怎么被读，还是问一个既有的事实」——
 * 后者成立，所以它该在这里。
 *
 * <p>★ {@code name = "novel-interaction"} 交给 Nacos + LoadBalancer 解析
 * （{@code lb://}），所以 novel-content 的 pom 里必须有
 * {@code spring-cloud-starter-loadbalancer} —— 缺了会在第一次调用时报
 * {@code No Feign Client for loadBalancing defined}，
 * 报错完全看不出和「少一个依赖」有关。
 *
 * @author MickeyChen
 */
/* ★★ M5：fallbackFactory 的两个必要条件（这里 + feign.sentinel.enabled），见 AuthUserClient */
@FeignClient(name = "novel-interaction", path = "/internal",
        fallbackFactory = CommentBriefClientFallbackFactory.class)
public interface CommentBriefClient {

    /**
     * 批量取评论素材，返回 {@code {commentId: CommentBriefDTO}}。
     *
     * <p>★ 用 {@code POST} 而不是 {@code GET}：一页审核日志最多 100 条评论，
     * id 列表塞进 query string 会撞上 URL 长度限制，而那个限制在不同服务器上
     * 不一样（症状是「日志多了就报 400」，本地还复现不了）。
     *
     * <p><b>★ 返回的 {@code CommentBriefDTO} 里正文是【截断过的】</b>
     * （截断规则在 interaction 的 {@code InternalCommentQueryService} 里，
     * 只那一处）。<b>不要拿它当完整内容用</b> —— 它是给审核日志列表
     * 渲染一行用的。
     * <p><b>★ 查不到的 id 不会出现在 Map 里</b>，调用方按 {@code null} 处理
     * （那就是「已删除」，审核日志里显示成「评论 #5（已删除）」）。
     */
    @PostMapping("/comment-briefs")
    Result<Map<Long, CommentBriefDTO>> commentBriefs(@RequestBody Collection<Long> commentIds);

    /**
     * 批量取评论<b>正文全文</b>，返回 {@code {commentId: "正文"}} —— <b>E1 新增。</b>
     *
     * <p>给 AI 违禁审核用（{@code AiAuditService}）。<b>★ 它与上面那个方法不是一回事，
     * 别拿上面那个的返回值凑合</b>：那个的正文截断到 30 字，
     * 送进审核等于让模型只看前 30 个字就下结论 —— 而<b>结果是「无风险」，
     * 没有任何一处报错</b>。
     *
     * <p>★★ <b>它沿用了本客户端的降级策略：不降级</b>（{@code CommentBriefClientFallbackFactory}
     * 里是 {@code logThenRethrow}）。这不是「顺手继承」——
     * 对这条接缝来说那正是唯一正确的选择：降级成空 Map 会让调用方
     * 把「取不到」读成「评论没了」，而降级成空串更坏，
     * <b>空正文扫不出任何词 → 判成机器排除 → 一句假的「通过」</b>（铁律 10）。
     * ★ 判据与 {@code ContentUserLookup#userIdsByNickname} 完全一致：
     * <b>参与筛选的输入一律不降级。</b>
     *
     * <p>★ 查不到的 id 不会出现在 Map 里 —— 那是「这条评论已不存在」
     * （作者提交后、审核前删掉了评论），处置是 ACK 且不写判定行。
     */
    @PostMapping("/comment-texts")
    Result<Map<Long, String>> commentTexts(@RequestBody Collection<Long> commentIds);
}
