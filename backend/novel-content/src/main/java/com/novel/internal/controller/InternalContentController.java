package com.novel.internal.controller;

import com.novel.common.enums.ChapterStatus;
import com.novel.common.internal.NovelCollectCountDTO;
import com.novel.common.result.Result;
import com.novel.common.vo.ChapterBriefVO;
import com.novel.common.vo.NovelBriefVO;
import com.novel.modules.content.service.ChapterService;
import com.novel.modules.content.service.NovelAccessGuard;
import com.novel.modules.content.service.NovelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * <b>供兄弟服务（M2 起是 novel-interaction）读 / 写小说的内部接口。</b>
 *
 * <p>M2 新增。它存在的原因是「小说 / 章节表在 content 这儿，而 interaction
 * 要用」—— 改造前那是一句同进程的 {@code novelService.requirePublished(id)}，
 * 拆服务之后必须变成一次 HTTP 调用。
 *
 * <h3>★★ 和 {@code InternalUserController}（novel-auth）的一处结构差别</h3>
 *
 * <p>那个 Controller 把逻辑放在 {@code InternalUserQueryService} 里，
 * 这里<b>直接注入了三个领域服务、没有中间层</b>。这不是不一致，是刻意：
 * <ul>
 *   <li>auth 那边「怎么查用户」本身就是一套决策（条数上限、单列投影、
 *       {@code selectByIds}），那些取舍不归 Controller，得有地方放。</li>
 *   <li>这边每一个方法都是<b>把已有领域服务的规则原样暴露出去</b> ——
 *       {@code requirePublished} 的规则在 {@code NovelServiceImpl} 里，
 *       {@code requireChapterInNovel} 的规则在 {@code NovelAccessGuard} 里。
 *       再包一层 {@code InternalContentQueryService} 只会是
 *       <b>三个纯转发的空壳方法</b>，而且下一次有人改规则时，
 *       会分不清该改哪一层。</li>
 * </ul>
 * <p><b>判据：中间层只有在「它自己有决策」时才值得存在。</b>
 *
 * <h3>★★ 为什么每个方法都【不复刻】一遍业务判断</h3>
 *
 * <p>这是本类最容易写错的地方。看着「内部接口嘛，检查一下就好」的直觉，
 * 会让人在这里重新写一遍 {@code if (novel.getStatus() != PUBLISHED)} ——
 * 于是「什么算可读的作品」就有了<b>第二处实现</b>，
 * 而它和第一处一旦漂移，症状是「读者端读得到、发评论说书不存在」。
 *
 * <p>所以这里一律<b>调它本来那一个方法</b>，让规则只有一处。
 * 见 {@code NovelService#requirePublished} 的注释：「不存在」和「未上架」
 * 返回同一个码（2001），所以这个接口也<b>不是一个可以枚举作品状态的探针</b>。
 *
 * <h3>★★ 失败走的是「业务码」，不是 HTTP 状态码 —— 这一点调用方必须明白</h3>
 *
 * <p>下面 {@code requirePublished} 校验不过时会抛 {@code BusinessException(2001)}，
 * 而 2001 不在 400~599 段（见 {@code ResultHttpStatus}），
 * 所以对端收到的是 <b>HTTP 200 + {@code code: 2001}</b>。
 *
 * <p>★ <b>于是调用方【绝不能】只看 HTTP 200 就认为成功</b> ——
 * Feign 那一侧必须在解包时把 2001 原样抛回业务层，
 * 否则「给未上架的书发评论」会静默变成「允许」。
 * 这件事由 {@code ContentGateway} 负责，理由写在那里。
 *
 * <h3>★ 为什么路径是 {@code /internal/**} 而不是 {@code /api/**}</h3>
 *
 * <p>同 {@code InternalUserController}：{@code /internal/**} <b>刻意不在
 * {@code /api/**} 之下</b>，而网关的路由表只有 {@code /api/**} 和
 * {@code /uploads/**} 两类前缀 —— 于是<b>网关在结构上就转发不出这个接口</b>。
 * 不是「记得别配路由」，是「配不出这条路由」。
 *
 * <h3>★ M3 之前这里有两个【写】接口，现在一个都不剩了</h3>
 *
 * <p>改造前 {@code refreshCollectCount} / {@code refreshScore} 会改
 * {@code novel.collect_count} / {@code novel.score}，它们<b>不需要身份</b> ——
 * 写的是「重算出来的绝对值」，和谁在操作无关。
 * <p><b>★★ M3 把这两个写端点整个删了</b>：写回改走 {@code NOVEL_STAT} 消息，
 * 消费者是 content 自己的 {@code NovelStatConsumer}（<b>自发自消</b>）。
 * 于是本类<b>只剩读</b>，而「只读接口不需要身份」这件事不再需要论证 ——
 * 它本来就是查询。
 * <p>★ <b>判据「这次写入需不需要知道是谁干的」没有消失，但它在 content 里的落点换了。</b>
 * M2 时期 content 有三个 {@code /internal} 写端点，这条判据由
 * {@code InternalAuditLogController} 里那句「校验 {@code UserContext} 非空」印证。
 * <b>M3 把那三个写端点全部删掉了</b>（两个统计写回 → {@code NOVEL_STAT} 消息，
 * 审核流水 → {@code AUDIT_RECORD} 消息），于是：
 * <pre>
 *   content 的 /internal/**  从此【全是读】—— 本类就是全部内容
 *   审核流水那条写              搬进了 AuditRecordConsumer，判据跟着变成
 *                              「载荷里有没有 operatorId」
 * </pre>
 * ★ 那一步不是「换个地方写同一句话」，是<b>判据的形态变了</b>：
 * HTTP 时代问的是「请求头里有没有身份」，MQ 时代问的是「消息里有没有操作人」——
 * 因为消费者线程上<b>永远没有</b> {@code UserContext}（MQ 没有请求），
 * 想照搬原来的检查会得到「每一条消息都抛异常」。
 * 完整的论证在 {@code AuditRecordConsumer} 的类注释里（那段「绝不能伪造 UserContext」）。
 *
 * @author MickeyChen
 */
@Slf4j
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalContentController {

    private final NovelService novelService;
    private final ChapterService chapterService;
    private final NovelAccessGuard novelAccessGuard;

    /* ==================== 校验类（调用方只要「过没过」） ==================== */

    /**
     * 校验作品存在且已上架。过了返回成功，没过返回 <b>2001</b>。
     *
     * <p><b>★★ 返回值刻意是 {@code Result<Void>}：这个接口的全部信息都在
     * 成功 / 失败里，没有任何数据要带回去。</b>
     * <p>★ 但正因为如此，<b>调用方绝不能把它当 {@code void} 用</b> ——
     * 它的三个调用点（发书评 / 发章评 / 加书架 / 上报进度）在改造前都是
     * 「抛异常就中断」的语义。调用方那边必须把失败重新抛成
     * {@code BusinessException(2001)}，否则这个校验等于没做。
     */
    @GetMapping("/novels/{id}/published")
    public Result<Void> requirePublished(@PathVariable("id") Long id) {
        // ★ 抛出的 BusinessException 由 GlobalExceptionHandler 转成
        //   HTTP 200 + code=2001，原样流回调用方
        novelService.requirePublished(id);
        return Result.success();
    }

    /**
     * 取「这一章在不在这本书里、以及它是什么状态」。
     *
     * <p><b>★★ 返回的是 {@code ChapterStatus}，不是 {@code Chapter} 实体。</b>
     * 两个调用点（发章评 / 上报阅读进度）都只读 {@code getStatus()} ——
     * 传实体就等于把正文（{@code LONGTEXT}）和审核备注一起搬过网络，
     * 而审核备注 {@code auditRemark} <b>是审核内部话术</b>，
     * 根本没有理由离开 content 的进程（同 {@code ChapterService#readerDetail}
     * 「不返回 auditRemark」那条规则）。
     * <p>★ 只暴露「状态」这个字段，还顺带把接口面收到最小：
     * 调用方即使想用别的字段也无从下手，将来 content 改章节表结构
     * 不会牵动 interaction。
     *
     * <p>★ 章节不存在、或不属于这本书 → <b>3001</b>，两种情况不区分
     * （理由见 {@code NovelAccessGuard#requireChapterInNovel}：
     * 区分了就等于确认「这个 id 是真的，只是不属于这本书」）。
     */
    @GetMapping("/novels/{novelId}/chapters/{chapterId}/status")
    public Result<ChapterStatus> chapterStatus(@PathVariable Long novelId,
                                              @PathVariable Long chapterId) {
        return Result.success(novelAccessGuard.requireChapterInNovel(novelId, chapterId).getStatus());
    }

    /* ==================== 批量取摘要（列表页渲染用） ==================== */

    /**
     * 批量取作品摘要，返回 {@code {novelId: NovelBriefVO}}。
     *
     * <p><b>★ 列表接口走这一个，不要循环调单个的。</b>
     * 一页 20 本 = 20 次跨进程调用 —— 这是 M1 之后 N+1 的新形态
     * （从 20 次 SQL 变成 20 次 HTTP），代价更高。
     * <p>★ 用 {@code POST} 而不是 {@code GET}：id 列表可能很长，
     * 塞进 query string 会撞上 URL 长度限制，而那个限制在不同服务器上
     * 不一样（症状是「书多了就报 400」，本地还复现不了）。
     *
     * <p>★ {@code required = false}：空集合是合法输入（返回空 Map），
     * 让调用方不必为「这一页一个 id 都没有」写特判。
     * <p>★ <b>只返回已上架的</b> —— 规则在 {@code NovelServiceImpl#briefsOf}，
     * 调用方在 map 里查不到就是 {@code null}，那代表「这本书现在读不了」。
     */
    @PostMapping("/novels/briefs")
    public Result<Map<Long, NovelBriefVO>> novelBriefs(
            @RequestBody(required = false) Collection<Long> ids) {
        return Result.success(novelService.briefsOf(ids));
    }

    /**
     * 批量取章节摘要，返回 {@code {chapterId: ChapterBriefVO}}。
     *
     * <p>★ <b>只返回已发布的</b>，口径同 {@code briefsOf}（读者侧只见已发布内容）。
     */
    @PostMapping("/chapters/briefs")
    public Result<Map<Long, ChapterBriefVO>> chapterBriefs(
            @RequestBody(required = false) Collection<Long> ids) {
        return Result.success(chapterService.briefsOf(ids));
    }

    /**
     * 某本作品<b>全部章节的 id</b>（含未发布、含已删除）。
     *
     * <p><b>★★ 这是 M2 唯一一个「为了合表归属而新开」的读接口</b> ——
     * 管理端评论列表要「按作品筛选」，而章评的 {@code target_id} 是 chapterId，
     * 那条 SQL 原来内联了一个 {@code SELECT id FROM chapter WHERE novel_id = ?}
     * 子查询。子查询本身没问题，问题是它跨了表归属：
     * {@code chapter} 是 content 的表，而那条 SQL 住在 interaction。
     *
     * <p>★ 两个 id 空间撞号是常态（作品 105 和章节 105 都存在），
     * 所以这个筛选<b>必须</b>真的拿到章节 id 列表，不能拿 novelId 去凑。
     *
     * <p>★ <b>返回空列表是正常结果</b>（新书一章都没有），
     * 调用方拿到空列表后必须短路 —— {@code IN ()} 是语法错误，
     * 不是「返回空」（同 {@code briefsOf} 对空集合的处理）。
     */
    @GetMapping("/novels/{id}/chapter-ids")
    public Result<List<Long>> chapterIdsOfNovel(@PathVariable("id") Long id) {
        return Result.success(chapterService.idsOfNovel(id));
    }

    /* ==================== 对账扫描（M3） ==================== */

    /**
     * 按 id 升序取「收藏数非 0 的作品」的一页，给 interaction 的对账任务翻。
     *
     * <p><b>★★ 这是本类唯一一个「给机器看、不给人看」的接口。</b>
     * 上面几个都是「interaction 要渲染页面，来问这边拿数据」，
     * 而它的调用方是 {@code NovelStatReconcileJob} —— 一个每 10 分钟跑一次的定时任务，
     * 拿这些数字去和 interaction 自己的 {@code COUNT(*)} 真值比对，修漂移。
     *
     * <p><b>★ 为什么这个端点【没有】条数上限 / 告警阈值：</b>
     * 它一次只回答一页（≤ {@code PaginationInnerInterceptor} 的 100 行），
     * 一次调用本身没有任何「太大了」的风险 —— 有风险的是<b>循环调用</b>它的人。
     * 所以「扫了多少轮 / 超过多少行该去看看了」那条线画在调用方
     * （{@code NovelStatReconcileJob} 的 {@code SCAN_WARN_THRESHOLD}），
     * 那里才是循环发生的地方。
     * <p>★ 两边各画一条线的坏处是具体的：两个阈值，改一个忘一个，
     * 而它们谁都不会报错 —— 一个静默截断、一个静默不告警。
     *
     * <p><b>★★ 不能拿它当「批量查收藏数」的通用接口用。</b>
     * 它一次最多 100 行、且只报非 0 的书 —— 想查「这几本书的收藏数」应该去
     * {@link #novelBriefs}（那边带书名封面，是给人看的）。
     * 用错它的症状是：书架上收藏数为 0 的书<b>查不到</b>，
     * 而调用方多半会把「查不到」当成「作品已下架」—— 一个静默的错分类。
     *
     * <p>★ 匿名可调（{@code /internal/**} 走内部令牌，见 {@code ContentSecurityConfig}）：
     * 它只读、且读的是「已经公开给读者看的收藏数」（作品详情页上就有）。
     */
    @GetMapping("/novels/collect-counts")
    public Result<List<NovelCollectCountDTO>> collectCounts(
            @RequestParam(value = "afterId", required = false) Long afterId,
            @RequestParam(value = "size", required = false) Integer size) {
        return Result.success(novelService.collectCountsAfter(afterId, size));
    }

    /*
     * ==================== ★★ M3：两个【写】端点全部删掉了 ====================
     *
     * 原来是 PUT /novels/{id}/collect-count 与 PUT /novels/{id}/score，
     * 由 interaction 的 ContentGateway 同步 Feign 调过来。
     * M3 起它们走 NOVEL_STAT 消息，消费者是 content 自己的 NovelStatConsumer ——
     * ★ 「跨服务写」变成了「自发自消」，这条边界上的写路径没有了。
     *
     * ★★ 所以本类现在【只剩读】，上面那段类注释里
     *   「这里的接口有【写】的，和 auth 那边不一样」已经不成立了 ——
     *   而且 M3 之后 content 整个 {@code /internal/**} 都没有写了
     *   （{@code InternalAuditLogController} 连同它最后那个作者申请流水端点一起删掉了，
     *   见类注释里那段「判据的落点换了」）。
     *   **留着那句会变成假话，所以改在这里而不是删掉整段**：
     *   读接口不需要身份这件事，理由从「写要身份、读不要」
     *   变成了更简单的一条 —— 它们本来就是只读查询。
     *
     * ★ 那两个端点的入参约束（count 用 long 不用 Long、score 用 BigDecimal
     *   不用 double）**没有丢** —— 它们跟着方法搬到了
     *   NovelService#refreshCollectCount / #refreshScore 的签名和注释上，
     *   而 NovelStatConsumer 调的就是那两个方法。MqConstant 和
     *   NovelStatMessage 里各有一段讲「绝对值 vs 增量」的论证，也是从这里搬过去的。
     */
}
