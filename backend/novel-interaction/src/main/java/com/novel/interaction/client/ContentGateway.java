package com.novel.interaction.client;

import com.novel.common.enums.ChapterStatus;
import com.novel.common.internal.InternalResults;
import com.novel.common.internal.NovelCollectCountDTO;
import com.novel.common.vo.ChapterBriefVO;
import com.novel.common.vo.NovelBriefVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * <b>interaction 望向 content 的那【唯一一个窗口】—— M2 起它是一条 HTTP 接缝。</b>
 *
 * <p>本类存在的理由和 {@code ContentUserLookup}（content 望向 system 的那个窗口）
 * 完全一样：<b>把跨模块的耦合收进一个类</b>，出入口只有一个。
 * 区别只是方向相反，而且它多了一层职责（见下面那条 ★★）。
 *
 * <h3>★★ 它为什么不是「纯转发」—— 这才是本类存在的第二个理由</h3>
 *
 * <p>{@link ContentClient} 的每个方法都返回 {@code Result<T>} 信封，
 * 而<b>「HTTP 200」不等于「成功」</b>（铁律 3：业务码一律返 200）。
 * 于是每个调用点都要做同一件事：<b>拆信封，失败就抛</b>。
 *
 * <p><b>★★ 漏掉它的后果不是「报错」，是「静默失效」：</b>
 * <pre>
 *   contentClient.requirePublished(novelId);   // ← 返回值没人看
 * </pre>
 * 这一行<b>能编译、能跑、不报错</b>，而「作品已上架」这个校验
 * <b>彻底不存在了</b>：给未上架的书发评论、给不存在的书加书架、
 * 给别人的章节刷章评 —— 全部成功。
 * <p>而它坏掉的样子非常隐蔽：功能测试全绿（正常路径本来就该成功），
 * 只有真的去点一本下架的书才会发现。这就是为什么拆信封这件事
 * <b>必须有唯一一处实现</b>，而不是靠每个调用点各自记得。
 * （M2 时是 8 个，M3 删掉 3 个写端点后是 5 个 —— 都在下面这一个类里。）
 *
 * <h3>★★ 两个解包方法的选择不是风格问题</h3>
 *
 * <ul>
 *   <li>{@link #requirePublished} / {@link #chapterStatus} 用
 *       {@link InternalResults#unwrapBusiness} —— <b>失败时把对端的业务码
 *       原样抛回</b>。因为对端执行的<b>就是调用方要转达给前端的那条业务规则</b>，
 *       2001 / 3001 的语义两边完全一致（{@code ResultCode} 在 novel-common，
 *       共用一份）。用错了症状是：<b>用户点一本下架的书，看到的是
 *       「服务器开小差了」，而不是「作品不存在或未上架」</b>，
 *       而且监控上会凭空多出一批假的 5xx。</li>
 *   <li>其余方法用 {@link InternalResults#unwrap} —— 那些是「对端替我查一次数据」，
 *       失败只可能是「兄弟服务不可用」，翻成 500 是对的。
 *       ★ 对端的业务码在那些场景里<b>没有意义</b>（比如「评论查不到」
 *       不是错误，是「被删了」，以「不在 Map 里」表达）。</li>
 * </ul>
 *
 * <h3>★ 失败不降级</h3>
 *
 * <p>任何一个方法失败都<b>抛</b>，不返回空值兜底。理由同
 * {@code ContentUserLookup}：降级的症状是「书架页所有书都显示成已下架」
 * 或「审核日志里所有评论都显示成已删除」，它<b>看起来像数据坏了</b>，
 * 排查方向会一路跑到数据库上去，而真正的原因是内容服务不可用。
 * <p><b>宁可 500，也不要一个看起来正常但内容是错的页面。</b>
 * <p>★ 代价：<b>content 挂掉时，凡是要渲染书名的读者接口也会挂</b>
 * （书架、阅读历史、发评论前的校验）。这是拆服务换来的<b>可用性耦合</b>，
 * M2 接受它，记为 M5 治理项（缓存兜底 / 熔断降级）——
 * 和 M1 那边「auth 挂掉连匿名首页也 500」是同一笔账。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentGateway {

    private final ContentClient contentClient;

    /* ==================== 校验类 ==================== */

    /**
     * 校验作品存在且已上架。<b>没过就抛，码是 2001。</b>
     *
     * <p>★ 改造前这是 {@code NovelService#requirePublished(id)}，
     * 调用方那四行代码<b>一个字都没改</b>（返回值本来就被丢弃）——
     * 但语义完全不同了：现在是<b>一次跨进程往返</b>，
     * 而且必须靠 {@code unwrapBusiness} 才能保住那个 2001。
     *
     * @throws com.novel.common.exception.BusinessException 2001（作品不存在或未上架）；
     *         拿不到响应信封时是 500
     */
    public void requirePublished(Long novelId) {
        InternalResults.unwrapBusiness(contentClient.requirePublished(novelId),
                "校验作品已上架 novelId=" + novelId);
    }

    /**
     * 取章节状态。<b>章节不存在、或不属于这本书，都抛 3001。</b>
     *
     * <p><b>★★ 它只回答「这一章在不在这本书里、它是什么状态」，
     * 不回答「读者能不能看到它」</b> —— 两个调用点都必须在拿到状态之后
     * 自己判 {@code isVisibleToReader()}。那两段判断<b>不能并进这里</b>：
     * 判据不同（一个查表内归属，一个是可见性口径），
     * 合并之后「未发布」和「不存在」就分不开了，
     * 而它们返回同一个码正是刻意的（见 {@code NovelAccessGuard}）。
     *
     * <p>★ <b>调用方的局部变量类型从 {@code Chapter} 换成了
     * {@code ChapterStatus}</b>，那两段长注释逐字保留 —— 它们论证的是
     * 「为什么这一步不能并入上一步」，和类型无关。
     *
     * @throws com.novel.common.exception.BusinessException 3001
     */
    public ChapterStatus requireChapterStatus(Long novelId, Long chapterId) {
        return InternalResults.unwrapBusiness(contentClient.chapterStatus(novelId, chapterId),
                "校验章节归属 novelId=" + novelId + " chapterId=" + chapterId);
    }

    /* ==================== 批量取摘要 ==================== */

    /**
     * 批量取作品摘要，返回 {@code {novelId: NovelBriefVO}}。
     *
     * <p>★ 空集合<b>在本地就短路</b>，不发这次 HTTP：一页书架的 id 是去重过的，
     * 「这一页一本都没有」虽然少见但合法，而对端判空省的是它的 SQL，
     * 这里判空省的是<b>这次往返本身</b>（同 {@code ContentUserLookup#nicknamesOf}）。
     *
     * <p>★ <b>查不到的 id 不会出现在 Map 里</b>（作品下架 / 已删），
     * 调用方按 {@code null} 处理 —— 书架/历史那一行要显示成「已下架」，
     * 不能让整行消失。
     */
    public Map<Long, NovelBriefVO> novelBriefsOf(Collection<Long> novelIds) {
        if (novelIds == null || novelIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, NovelBriefVO> result = InternalResults.unwrap(
                contentClient.novelBriefs(novelIds), "批量取作品摘要 count=" + novelIds.size());
        // ★ 对端返回 null 时兜成空 Map：HTTP 这层多出来的「什么都没返回」状态
        return result == null ? Map.of() : result;
    }

    /**
     * 批量取章节摘要，返回 {@code {chapterId: ChapterBriefVO}}。
     *
     * <p>★ 同 {@link #novelBriefsOf}：本地判空、查不到就是 {@code null}
     * （章节被作者撤回了 → 那一行只显示书名）。
     */
    public Map<Long, ChapterBriefVO> chapterBriefsOf(Collection<Long> chapterIds) {
        if (chapterIds == null || chapterIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ChapterBriefVO> result = InternalResults.unwrap(
                contentClient.chapterBriefs(chapterIds), "批量取章节摘要 count=" + chapterIds.size());
        return result == null ? Map.of() : result;
    }

    /**
     * 某本作品全部章节的 id。<b>可能为空列表。</b>
     *
     * <p><b>★★ 调用方拿到空列表之后【必须短路】，不能拿去拼 {@code IN (...)}。</b>
     * {@code IN ()} 是 SQL 语法错误 —— 注意它<b>不是</b>「返回空」，是直接抛异常，
     * 于是「一本还没有章节的新书」在管理端评论列表里会变成一个 500。
     * <p>★ 这个坑改造前不存在（原来是个子查询，空子查询合法），
     * 是 M2 把子查询拆成两次查询<b>新造出来</b>的。
     * 所以这里返回的 {@code List} 语义上带了一个警告：
     * <b>「怎么用它」比「它是什么」更需要小心。</b>
     */
    public List<Long> chapterIdsOfNovel(Long novelId) {
        List<Long> ids = InternalResults.unwrap(
                contentClient.chapterIdsOfNovel(novelId), "取作品章节 id novelId=" + novelId);
        return ids == null ? List.of() : ids;
    }

    /* ==================== 对账扫描（M3） ==================== */

    /**
     * 【对账专用】按 id 升序取「收藏数非 0 的作品」的一页。
     *
     * <p><b>★★ 唯一调用点是 {@code NovelStatReconcileJob}</b>，不是任何请求路径。
     * 这也决定了它的失败语义：<b>抛出去是对的</b> ——
     * 对账任务在这一轮少扫一页，就等于这一轮不做判断（下一轮 10 分钟后再来），
     * 而不是「拿一份不完整的账本去修」（那会把没扫到的书全判成漂移，
     * 甚至可能用一份半截数据去覆盖正确的值）。
     * <p>★ 所以这里<b>绝不</b>返回「空列表兜底」…… 注意下面那行 {@code List.of()}
     * 兜的是<b>对端返回 null</b>（HTTP 这层多出来的「什么都没返回」），
     * <b>不是</b>「调用失败」。两者看着像，处置完全相反：
     * 调用失败必须抛（见上），而对端真的返回了空列表是正常结果 ——
     * 它表示<b>扫到底了</b>，循环该结束。
     *
     * <p>★ 页大小必须 ≤ 100，理由在 {@link ContentClient#collectCounts} 的注释里
     * （请求超过 100 时返回的是被静默截断的一页，而调用方靠
     * 「返回条数 &lt; 请求条数」判断扫到底 —— 会提前结束、静默漏书）。
     */
    public List<NovelCollectCountDTO> collectCountsAfter(Long afterId, int size) {
        List<NovelCollectCountDTO> page = InternalResults.unwrap(
                contentClient.collectCounts(afterId, size),
                "对账扫描 afterId=" + afterId + " size=" + size);
        return page == null ? List.of() : page;
    }

    /*
     * ==================== ★★ M3：三个【写】方法全部从这里删掉了 ====================
     *
     * 原来是 refreshCollectCount / refreshScore / recordCommentAudit，
     * 现在换成了两条消息：
     *   refreshCollectCount + refreshScore  →  interaction/mq/producer/NovelStatProducer
     *   recordCommentAudit                  →  interaction/mq/producer/CommentAuditProducer
     *
     * ★★ 注意这里删掉的是【整个方向】：本类从此只读不写。
     *   interaction 对 content 的依赖还剩 5 条（两次校验 + 两次批量取摘要 +
     *   一次按作品取章节 id），全是【读】—— M3 的对账又加了第 6 条
     *   （collectCountsAfter，也是读，而且不来自任何请求路径）。
     *   ★ 服务级环 #2（content ⇄ interaction）**没有因此解开**，
     *   因为 content 反过来还要读 interaction 一次（管理端审核日志要显示评论正文）。
     *   那一条是同步读，不是消息能解的，见 docs/M3-引入MQ.md 的「记一笔」。
     *
     * ★ 删掉的两个债（M2 记的 R8 / R9）以及「为什么换成 MQ 就消掉了」，
     *   论证跟着代码搬到了 NovelStatProducer / NovelStatMessage 的类注释里 ——
     *   它们讲的是「这条消息为什么长这样」，留在一个已经不存在的方法上没意义。
     *
     * ★ 而原来 recordCommentAudit 那段注释里有一条【不该丢】的论证：
     *   「少一个参数就少一个能用错的面」—— 改造前 {@code AuditTargetType} 是调用方传的，
     *   手滑写成 record(AUTHOR_APPLY, commentId, ...) 会往流水表里写一条
     *   「作者申请 #5 通过」的假记录，能编译、不报错，而且那张表只追加不可改。
     *   现在这个面由 CommentAuditProducer 收口（它在内部写死 AuditTargetType.COMMENT，
     *   调用方连类型都传不了）—— 约束还在，只是换了地方。
     */
}
