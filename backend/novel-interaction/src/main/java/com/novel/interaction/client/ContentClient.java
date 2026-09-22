package com.novel.interaction.client;

import com.novel.common.enums.ChapterStatus;
import com.novel.common.internal.CommentAuditLogDTO;
import com.novel.common.result.Result;
import com.novel.common.vo.ChapterBriefVO;
import com.novel.common.vo.NovelBriefVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * <b>interaction 侧调 novel-content 的 Feign 客户端（M2）。</b>
 *
 * <p><b>★★ 这个接口有 8 个方法，它们【都不是】通用的 CRUD，
 * 而是 8 个具体的业务问题。</b> 这是有意维持的 —— 同
 * {@code AuthUserClient} 的规矩：内部接口一旦变成「万能查询」，
 * 它就成了一个绕过服务边界的后门（那样还不如不拆）。
 * <p>★ 加方法之前先问一句：<b>「这个问题是 content 该回答的吗」</b>，
 * 以及「我是不是只是想省一层」。
 *
 * <h3>★★ 这是 M2 里最大的一条接缝：9 条跨服务边里有 8 条在这里</h3>
 *
 * <p>改造前这 8 件事都是同进程 DI 注入（{@code NovelService} /
 * {@code NovelAccessGuard} / {@code ChapterService} / {@code AuditLogService}），
 * M2 全部换成 HTTP。调用方的<b>逻辑一行没动</b>，只换了类型 ——
 * 但「换成 HTTP」带来了一件本地调用没有的事：<b>失败可以被静默忽略</b>。
 * 所以真正调用它们的是 {@link ContentGateway}，不是业务代码直接注入本接口。
 * 理由（以及漏掉它的后果）写在那里，<b>改这个接口之前先读那一段</b>。
 *
 * <h3>★ 为什么它们住 {@code /internal} 而不是 {@code /api}</h3>
 *
 * <p>同 content 那两个内部 Controller：{@code /internal/**} 刻意不在
 * {@code /api/**} 之下，而网关的路由表只有 {@code /api/**} 和
 * {@code /uploads/**} 两类前缀 —— <b>网关在结构上就转发不出这些接口</b>。
 * <p>★ 所以 {@code path = "/internal"} 这一行不是「省得写」，
 * 它和 {@link FeignIdentityConfig} 里那个内部令牌是一套东西：
 * <b>网络隔离是第一道锁，共享密钥只是第二道</b>（见 {@code docs/M1} R9）。
 *
 * <p>★ {@code name = "novel-content"} 交给 Nacos + LoadBalancer 解析，
 * 所以 novel-interaction 的 pom 里必须有
 * {@code spring-cloud-starter-loadbalancer} —— 缺了会在第一次调用时报
 * {@code No Feign Client for loadBalancing defined}，
 * 报错完全看不出和「少一个依赖」有关。
 * <p><b>★★ 这个名字还和 content 的 {@code spring.application.name} 硬绑死。</b>
 * 改名要同时改四处（网关路由 / 本行 / content 的 yml / content 的 pom），
 * 漏一处的症状是 {@code Unable to find instance for novel-content}。
 *
 * @author MickeyChen
 */
@FeignClient(name = "novel-content", path = "/internal")
public interface ContentClient {

    /* ==================== 校验类 ==================== */

    /**
     * 校验作品存在且已上架。失败时对端返回 <b>code 2001</b>（HTTP 仍是 200）。
     *
     * <p><b>★★ 这个方法的返回值是一颗地雷：它是 {@code Result<Void>}，
     * 而它的四个调用点在改造前都是「抛异常就中断」的语义。</b>
     * <p>因为业务码一律返 HTTP 200（铁律 3），<b>Feign 不会因为 2001 抛异常</b>
     * —— 它安安静静地返回一个「失败的信封」。谁要是写了
     * {@code contentClient.requirePublished(id);} 然后不看返回值，
     * <b>这个校验就静默失效了</b>：给未上架的书发评论、给不存在的书加书架，
     * 全部会成功，没有任何报错。
     * <p>★ 所以<b>不要直接注入本接口</b>，用 {@link ContentGateway} 的同名方法 ——
     * 它把这个信封解开并把 2001 抛回业务层。
     */
    @GetMapping("/novels/{id}/published")
    Result<Void> requirePublished(@PathVariable("id") Long id);

    /**
     * 取「这一章在不在这本书里、以及它是什么状态」。失败时对端返回 <b>3001</b>。
     *
     * <p><b>★ 返回 {@code ChapterStatus} 而不是 Chapter 实体</b> ——
     * 两个调用点都只读状态；而章节正文和 {@code auditRemark}
     * （审核内部话术）没有理由离开 content 的进程。
     * <p>★ 同 {@link #requirePublished}：失败在信封里，必须用
     * {@link ContentGateway} 调。
     */
    @GetMapping("/novels/{novelId}/chapters/{chapterId}/status")
    Result<ChapterStatus> chapterStatus(@PathVariable("novelId") Long novelId,
                                        @PathVariable("chapterId") Long chapterId);

    /* ==================== 批量取摘要 ==================== */

    /**
     * 批量取作品摘要，返回 {@code {novelId: NovelBriefVO}}。
     *
     * <p>★ 用 {@code POST} + body 而不是 {@code GET} + query：
     * id 列表可能很长，URL 长度是硬约束，而那个限制在不同服务器上不一样
     * （症状是「书多了就报 400」，本地还复现不了）。
     * <p><b>★ 只返回已上架的</b>：查不到就是 {@code null}，
     * 那代表「这本书现在读不了」—— 书架/历史列表要留一个「已下架」占位分支，
     * 不能让那一行从列表里消失（消失会让读者以为自己的书架丢了东西）。
     */
    @PostMapping("/novels/briefs")
    Result<Map<Long, NovelBriefVO>> novelBriefs(@RequestBody Collection<Long> novelIds);

    /**
     * 批量取章节摘要，返回 {@code {chapterId: ChapterBriefVO}}。
     *
     * <p>★ <b>只返回已发布的</b>：读者读过的章节后来被作者撤回了，
     * 那一行会退化成只显示书名，而不是继续显示一个读者已经看不到的章节标题。
     */
    @PostMapping("/chapters/briefs")
    Result<Map<Long, ChapterBriefVO>> chapterBriefs(@RequestBody Collection<Long> chapterIds);

    /**
     * 某本作品全部章节的 id（含未发布、含已删除）。
     *
     * <p><b>★★ 唯一用途是「管理端评论列表按作品筛选」</b> ——
     * 那条 SQL 原来内联了一个 {@code SELECT id FROM chapter WHERE novel_id = ?}
     * 子查询，而 {@code chapter} 是 content 的表。M2 把它拆成两步：
     * 先跨服务拿 id，再用 {@code IN (...)} 传进去。
     *
     * <p><b>★ 两个 id 空间撞号是常态</b>（作品 105 和章节 105 都存在），
     * 所以绝不能拿 novelId 去凑 {@code target_id}。
     * <p>★ <b>返回空列表是正常结果</b>（新书一章都没有），
     * 调用方拿到空列表后<b>必须短路</b> —— {@code IN ()} 是语法错误，
     * 不是「返回空」。见 {@code ContentGateway#chapterIdsOfNovel}。
     */
    @GetMapping("/novels/{id}/chapter-ids")
    Result<List<Long>> chapterIdsOfNovel(@PathVariable("id") Long id);

    /* ==================== 写回（重算出来的绝对值） ==================== */

    /**
     * 写回收藏数。<b>绝对值覆盖</b>，不是 +1 / -1。
     *
     * <p>★ 第二参是 <b>{@code long}</b>（基本类型）而不是 {@code Long} ——
     * 别顺手改：收藏数不可能是 null，用包装类型等于凭空多一个要处理的空态。
     * <p>★ 因为写的是绝对值，重试是幂等的；但 {@code Retryer.NEVER_RETRY}
     * 仍然必要（见 {@link FeignIdentityConfig}）—— <b>那是运气好，不是可以依赖的性质。</b>
     */
    @PutMapping("/novels/{id}/collect-count")
    Result<Void> refreshCollectCount(@PathVariable("id") Long id,
                                     @RequestParam("count") long count);

    /**
     * 写回作品评分。<b>绝对值覆盖</b>，且只在书评上做。
     *
     * <p>★ 第二参是 <b>{@code BigDecimal}</b> 而不是 {@code double}：
     * {@code novel.score} 是 {@code DECIMAL}，用 double 会留下
     * 「算出来 7.3、存进去 7.299999999999999」这种永远对不平的尾差。
     * <p><b>★ 这个方法的调用点在【事务里】</b>（评论审核）。
     * Feign 的 read-timeout 是 5000ms，所以 content 慢一次，
     * 评论审核的数据库事务就会被挂住最长 5 秒 ——
     * <b>M2 接受这个代价，它正是 M3 要做 MQ 的动机之一</b>。
     */
    @PutMapping("/novels/{id}/score")
    Result<Void> refreshScore(@PathVariable("id") Long id,
                              @RequestParam("score") BigDecimal score);

    /* ==================== 写流水 ==================== */

    /**
     * 记一条「评论审核」流水。
     *
     * <p><b>★ 请求体里刻意没有操作人</b>：操作人由 content 从
     * {@code UserContext} 取，而那个 {@code UserContext} 是
     * {@link FeignIdentityConfig} 把当前身份写成 {@code X-User-*} 头带过去的。
     * <b>拦截器一漏，症状不是报错，是流水里操作人是空的</b>
     * （content 侧会记一条 error 日志，见 {@code InternalAuditLogController}）。
     *
     * <p>★ 对端路径是 {@code /internal/comment-audit-logs}，
     * 和 {@code /author-apply-audit-logs} 是<b>两个各自收窄的入口</b> ——
     * 那边只收作者申请，这边只收评论，没有通用入口。
     */
    @PostMapping("/comment-audit-logs")
    Result<Void> recordCommentAudit(@RequestBody CommentAuditLogDTO dto);
}
