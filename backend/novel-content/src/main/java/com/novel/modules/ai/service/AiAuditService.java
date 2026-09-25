package com.novel.modules.ai.service;

import com.novel.common.constant.RedisKeyConstant;
import com.novel.common.enums.AuditTargetType;
import com.novel.common.exception.BusinessException;
import com.novel.common.mq.MqErrors;
import com.novel.modules.ai.dto.AiRequestDTO;
import com.novel.modules.ai.entity.AiAuditResult;
import com.novel.modules.ai.entity.AiPromptTemplate;
import com.novel.modules.ai.enums.AiPlaceholder;
import com.novel.modules.ai.enums.AiScene;
import com.novel.modules.ai.mapper.AiAuditResultMapper;
import com.novel.modules.ai.vo.RenderedPromptVO;
import com.novel.modules.ai.vo.SensitiveWordHitVO;
import com.novel.modules.ai.vo.SensitiveWordScanVO;
import com.novel.modules.content.entity.Chapter;
import com.novel.modules.content.entity.ChapterContent;
import com.novel.modules.content.mapper.ChapterContentMapper;
import com.novel.modules.content.mapper.ChapterMapper;
import com.novel.modules.content.service.CommentTextLookup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * <b>一条待审内容的完整判定流程：关键词层 → （命中才）AI 层 → 落 {@code ai_audit_result}。</b>
 *
 * <pre>
 *   章节 submit()   ─┐
 *   评论 insertComment() ─┴→ MQ(AI_AUDIT) → {@code AiAuditConsumer} → ★本类
 * </pre>
 *
 * <p>它是 E1 违禁审核的<b>全部业务逻辑</b>；消费者只剩「校验载荷 → 调本类 → 决定 ACK 还是重投」
 * 那几行，与 {@code AuditRecordConsumer} 的分工完全同形。
 *
 * <h3>★★★ 为什么两层都在消费端，而不是「提交时同步跑关键词、命中才投 MQ」</h3>
 *
 * <p>后者看起来更省（免费的那一层不就在请求线程上顺手做完吗），代价是
 * <b>筛选逻辑住在两个进程里</b>：{@code 投不投} 在请求路径、{@code 调不调 AI} 在消费端，
 * 中间隔着一个 MQ —— <b>没有任何一条日志能同时看到这两个判断</b>。
 * 更坏的是「无命中」这个结论<b>不会落库</b>：提交了 N 条、审了 M 条，
 * N−M 是多少、少的是哪些，<b>查不出来</b>。
 * ★ 全投之后每一档都看得见，左连接即可定位：
 * <pre>
 *   源行的 status（人工那道门） + 有没有 ai_audit_result 行 + 有没有失败的 ai_usage_log 行
 * </pre>
 * ★ 附带好处：词库升级之后队列<b>可以重放</b>（重发消息即可，消费端重跑）；
 * 同步预筛过的存量内容压根没进过队。
 *
 * <h3>★★★ 幂等键是【内容指纹】，不是消息键 —— 这是本类最值钱的一行</h3>
 *
 * <p>这条消息的载荷形状与 {@code NOVEL_INDEX} 一模一样（只有一个 id），
 * 但<b>它不幂等</b>：调一次 AI 是<b>非纯函数</b>（温度 &gt; 0），
 * 同一个 {@code {targetType:3, targetId:94}} 消费两次会得到两份不同的判定。
 * <blockquote>
 * {@code NOVEL_INDEX} 幂等免费，<b>不是因为「载荷只有一个 id」，
 * 而是因为「读真值之后做的那件事是纯函数」</b>。
 * </blockquote>
 * ★ 所以这里沿用既有的 {@code novel:mq:idempotent:} 命名空间，但键的构成不同：
 * <pre>
 *   key = mq:idempotent:ai-audit:{targetType}:{targetId}:{sha256(正文) 前 16 位}
 * </pre>
 * <ul>
 *   <li><b>为什么不用消息键</b>：那是「同一次发送重投」级别的幂等，
 *       <b>挡不住「内容没变却又被审了一遍」</b>——而那正是花钱的那条路；
 *       反过来它还会<b>误伤</b>「改了内容重新提交」，那<b>应该</b>重审。</li>
 *   <li><b>为什么不是 {@code hashCode()}</b>：32 位、碰撞概率在几万条量级上
 *       就不再可以忽略，而碰撞的后果是<b>一次审核被静默跳过</b> ——
 *       一句「没审过却显示审过了」的假话。SHA-256 取前 16 位（64 位）足够。</li>
 *   <li>★ 粒度是<b>「内容版本」</b>：内容没变 → 只审一次（这就是省钱的那道闸门）；
 *       内容变了 → 新指纹、新键、应该重审。</li>
 * </ul>
 * <p>★ 不写时间戳、不用 ORDERLY：乱序不会让结论变错（不同版本各自留一次判定，
 * 同版本被 SETNX 挡住）。★ 24 小时 TTL，与 {@code AuditRecordConsumer} 取同一个值。
 *
 * <h3>★★★ 认领（SETNX）什么时候可以【退回去】—— 判据是「钱花出去了没有」</h3>
 *
 * <p>{@code AuditRecordConsumer} 的规矩是「认领之后写库失败要 DEL 再抛」，
 * 因为那笔流水还没写、重试是有意义的。<b>本类的判据更细一层</b>：
 * <pre>
 *   钱【还没】可能花出去（词库不可用 / 取不到正文）→ 退回认领 + 抛（或 ACK）
 *   钱【已经】可能花出去（调过 AI 了）              → 认领【永不退回】
 * </pre>
 * ★ 后者听起来像 bug，其实是唯一正确的选择：退回之后重投会<b>再花一次钱</b>，
 * 而这次重投换来的是什么？—— 一张同样的判定，或者又一次失败。
 * <b>把「可能重复计费」换成一个「少一行判定」，方向是对的</b>：
 * 审核链上少一行判定的后果是「人工少一条提示」（见下方承重事实），
 * 而重复计费没有任何人兜底。24 小时后 TTL 自然过期，重投即可重审 —— <b>自愈的</b>。
 *
 * <h3>★★★ AI 层的【一切】失败都是 ACK，不是重投</h3>
 *
 * <p>这偏离了「永久失败 ACK、瞬时失败抛」那个模板，理由只有一条：
 * <b>重试的成本是钱，收益是「人工多一条提示」</b>。
 * 一次上游超时重投三次 = 三笔费用换三条一样的失败。
 * ★ 所以 {@link #askAiQuietly} 把 {@code Exception} 全接住、记 ERROR、返回 {@code null}，
 * 判定的其余部分照走（见下）。
 *
 * <h3>★★★★ 「没审成 = 没有行」这条规矩在本类的【准确边界】</h3>
 *
 * <p>{@code ai_audit_result} 的类注释写着：{@code risk_level} 是 {@code NOT NULL}
 * 且没有「未知」取值，<b>所以「没审成」只能表达为「没有这一行」</b>。
 * ★★ 而本类里 <b>AI 层失败【不构成】「没审成」</b> —— 因为审核是两层的，
 * 关键词层那一层是真的跑过了、结果是真的：命中过哪几个词、最高几级，
 * 都还在手里。<b>因为一个后续步骤失败而丢掉一个已经算出来的真事实，是错的。</b>
 * <pre>
 *   词库不可用（5007） / 正文本取不到  → ★「没审成」→ ACK + 【不写行】
 *   无命中                            → 写行：risk_level=0 + status=2(机器排除) + 不调 AI
 *   有命中、AI 层失败                  → 写行：risk_level=词库等级 + ai_comment=固定那句话
 *   有命中、AI 层成功                  → 写行：risk_level=max(词库, AI) + ai_comment=模型给的理由
 * </pre>
 * ★★ 「有命中但 AI 失败」那一行，{@code ai_comment} 写的是
 * {@link #AI_SKIPPED_COMMENT} 而不是留空，理由是<b>留空会被读成「模型看过、没说什么」</b>：
 * 这一格只有「有内容」和「空」两个状态，空不携带信息。
 * 那句话自报家门（【】前缀 + 说明等级来自词库），与
 * {@code MockAiAuditEvaluator.DEMO_COMMENT} 是同一个手法。
 * <p>★ 注意它与「写一行 {@code risk_level=0}」的区别，后者是 Plan S8 里点名的错法：
 * <b>把「审核失败」渲染成「审核通过」</b>。本类在结构上写不出那个形状 ——
 * 走到写行这一步的前提就是 {@code scan} 命中了词，而命中了等级必然 ≥ 1。
 *
 * <h3>★ 本类【不碰】任何业务状态机</h3>
 *
 * <p>判定只写 {@code ai_audit_result}。章节/评论的状态由人工在既有入口处置，
 * 而「被人处置了」这个信号就是那条 {@code audit_log} —— 联动做在
 * {@code AuditLogServiceImpl.record} 那一个汇聚点上（S9），
 * 见 {@code AiAuditResult} 的类注释与判断 3.2。
 * <b>在这里顺手改 {@code chapter.status} 就会绕开 {@code transitStatus} 这个唯一入口</b>
 * （{@code audit_log} 缺一条、趋势图少一个点，而且不报错）。
 *
 * <p>★★ <b>承重事实：这条链上 AI 不是「门」，是「给人工审核员的提示」。</b>
 * 评论落库即 {@code status=0}、章节 {@code submit()} 后是 {@code status=1 待审核} ——
 * 两者都必须经人工才可见。所以 AI 层挂掉、延迟、漏审的后果都止于
 * 「人工少了一条提示」，<b>不是「违规内容被放出去」</b>。
 * 这条同时是整条链 fail-closed 的依据。
 *
 * @author MickeyChen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAuditService {

    /**
     * 认领 key 的存活时间。★ 与 {@code AuditRecordConsumer#IDEMPOTENT_TTL} 取同一个值
     * （24 小时，短于 broker 的死信保留期 48 小时），理由见那个类的注释。
     */
    private static final Duration CLAIM_TTL = Duration.ofHours(24);

    /** 认领 key 的业务前缀，跟在 {@code novel:mq:idempotent:} 之后 */
    private static final String CLAIM_NAMESPACE = "ai-audit:";

    /** 指纹取 SHA-256 的前 8 字节（16 个十六进制字符）。见类注释的碰撞论证 */
    private static final int FINGERPRINT_BYTES = 8;

    /** {@code hit_words} 最多列几个词。★ 超出部分不静默丢弃，见 {@link #hitWordsOf} */
    private static final int MAX_HIT_WORDS = 20;

    /** {@code hit_words} 里词表部分的字数上限（列宽 500，留出后缀的余量） */
    private static final int MAX_HIT_WORDS_LENGTH = 450;

    /** {@code ai_comment} 的字数上限（列宽 1000，留出后缀的余量） */
    private static final int MAX_AI_COMMENT_LENGTH = 950;

    /**
     * AI 层没跑成时写进 {@code ai_comment} 的那句话。
     *
     * <p>★★ <b>不留空</b>：这一格只有「有内容」和「空」两个状态，
     * 空<b>不携带信息</b> —— 管理员会把它读成「模型看过、没说什么」，
     * 于是把一条只有关键词层的判定当成两层都判过的。
     * <p>★ 它必须<b>自报家门</b>（【】前缀 + 说清等级来自哪里），
     * 否则就成了往「模型说的话」这一列里塞我们自己的话。
     * 与 {@code MockAiAuditEvaluator.DEMO_COMMENT} 同一个手法。
     */
    public static final String AI_SKIPPED_COMMENT =
            "【AI 复核未完成】本次未取得模型判定，本行的风险等级与命中词来自本地关键词词库，请人工判断。";

    /**
     * AI 跑成了、但答复里没带 {@code comment} 时写进去的那句话。
     *
     * <p>★★ <b>它存在是为了让「空」不再是三种意思共用的一个格子。</b>
     * 这一格空着时，管理员无法区分：
     * <pre>
     *   ① AI 没跑成（等级只来自词库）      → {@link #AI_SKIPPED_COMMENT}
     *   ② AI 跑了、没说理由（等级可能高于词库）→ ★本句
     * </pre>
     * 而这两件事指向的动作完全不同：①是「去看上游怎么了」，②是「人来判」。
     * ★ 模板里明确要了 {@code comment}，但真实模型漏字段是常事
     * （S11 的真实调用预算里 R2 那一次就是专门看它输出形状的）。
     */
    public static final String AI_NO_REASON_COMMENT =
            "【模型未给出理由】本次判定未附说明，风险等级取模型判定与本地关键词词库中的较高者。";

    private final SensitiveWordScanner scanner;
    private final AiPromptService promptService;
    private final AiContextAssembler assembler;
    private final AiAuditEvaluator evaluator;
    private final AiAuditResultMapper auditResultMapper;

    /**
     * ★ 必须是 {@code StringRedisTemplate}（纯字符串），理由同 {@code AuditRecordConsumer}。
     */
    private final StringRedisTemplate stringRedisTemplate;

    private final CommentTextLookup commentTextLookup;
    private final ChapterMapper chapterMapper;
    private final ChapterContentMapper chapterContentMapper;

    /**
     * 审一条。<b>本方法是消费者唯一的业务入口。</b>
     *
     * <p>★ 它<b>不抛异常</b>给「已经没救」的情况（返回即 ACK）：
     * 目标已删、类型不支持、词库不可用、AI 层失败 —— 全部在这里就地了结。
     * 抛出去的只有一种：<b>重投真的可能有不同结果的瞬时故障</b>
     * （互动服务不可用、写库失败且钱还没花）。
     *
     * @param targetType 见 {@link AuditTargetType}，<b>只有 2(章节) 和 3(评论) 在范围内</b>
     * @param targetId   目标 id
     */
    public void audit(Integer targetType, Long targetId) {
        if (targetId == null) {
            // ★ 载荷不合法 = 永久失败（重投一万次还是 null）→ ACK
            log.error("AI 审核消息缺少 targetId，已丢弃 | targetType={} targetId=null", targetType);
            return;
        }

        /* ==================== ① 取待审正文（★ 在认领之前） ==================== */
        /*
         * ★★ 顺序是有意的：取正文排在认领之前，于是「跨服务读失败」抛出去时
         *   还没有任何认领需要退回 —— 少一个必须写对的地方。
         */
        PendingText pending = readPendingText(targetType, targetId);
        if (pending == null) {
            // 目标已删除 / 类型不在范围内：已在 readPendingText 里记了日志
            return;
        }
        String body = pending.body();

        /* ==================== ② 内容指纹 + 认领 ==================== */
        String claimKey = RedisKeyConstant.MQ_IDEMPOTENT_PREFIX
                + CLAIM_NAMESPACE + targetType + ":" + targetId + ":" + fingerprint(body);
        if (!claim(claimKey)) {
            /*
             * ★ DEBUG 而不是 ERROR：重复消费是【设计内的正常事件】
             *   （重投、重平衡、以及「同一份内容被重新提交」都会走到这里），
             *   打成 ERROR 会让真正的故障淹在噪音里。同 AuditRecordConsumer。
             */
            log.debug("同一条内容已经审过，跳过（内容没变，不必再花一次钱）| targetType={} targetId={}",
                    targetType, targetId);
            return;
        }

        /* ==================== ③ 关键词层（免费） ==================== */
        SensitiveWordScanVO scan;
        try {
            scan = scanner.scan(body);
        } catch (BusinessException e) {
            /*
             * ★★ 词库不可用（AI_WORD_LIB_UNAVAILABLE 5007）：这是【拒审】，不是「没有命中」。
             *   两条不同的路必须走不同的分支 —— 把「不可用」当成「空词库继续」
             *   等于让违禁内容进库，而且日志上没有任何异常（铁律 8 的最坏形态：
             *   一条连「我没在工作」都不说的护栏）。
             *
             * ★ 退回认领 + ACK：重投解决不了「词库是空的 / 加载失败了」，
             *   而认领留着会让「词库修好之后重投」被幂等闸门挡掉 —— 那才是真丢东西。
             *   钱一分没花，所以退回是安全的。
             */
            release(claimKey);
            log.error("敏感词库不可用，本条审核中止（未调用 AI，不写判定行）"
                            + " | targetType={} targetId={} code={} {}",
                    targetType, targetId, e.getCode(), e.getMessage());
            return;
        } catch (RuntimeException e) {
            /*
             * ★ 未预期的异常：此刻一定还没调过 AI（钱没花），退回认领再抛，
             *   让重投回到「从没来过」的状态。同 AuditRecordConsumer 的那段论证。
             */
            release(claimKey);
            throw e;
        }

        /* ==================== ④ 无命中：写「机器排除」，不调 AI ==================== */
        if (scan.getHits().isEmpty()) {
            try {
                writeRow(targetType, targetId, 0, null, null, AiAuditResult.STATUS_EXCLUDED);
            } catch (RuntimeException e) {
                /*
                 * ★ 这一支退回认领：一次 AI 调用都没发生，重投的代价是零。
                 *   （下面那两支就不退了 —— 见类注释「认领什么时候可以退回去」。）
                 */
                release(claimKey);
                throw e;
            }
            /*
             * ★ INFO 而不是 DEBUG：这一行是「提交了 N 条、AI 只调了 M 次」那个
             *   差值的唯一解释来源（免费的那一层替我们挡掉了 N−M 次付费调用）。
             */
            log.info("关键词层无命中，已机器排除（未调用 AI，未产生费用）"
                            + " | targetType={} targetId={} 词条数={} 词库版本={}",
                    targetType, targetId, scan.getWordCount(), scan.getVersion());
            return;
        }

        /* ==================== ⑤ AI 层（命中才走，钱从这里开始花） ==================== */
        AiAuditVerdict.Parsed verdict = askAiQuietly(body, pending.novelId(), pending.chapterId(),
                targetType, targetId);

        /*
         * ★★ 等级取两者的较大值，而不是「AI 说几就是几」：
         *   两层是【互相补充】的 —— 词库抓的是「这个词出现过」（确定的、可能误杀的），
         *   AI 抓的是「这段话在说什么」（模糊的、可能漏的）。
         *   取 max 是 fail-closed 方向：任一层认为有问题，人都该看一眼。
         * ★ 而 ai_comment 只在 AI 真跑成时才填 —— 否则那句「模型说……」就是假的。
         */
        int riskLevel = scan.getMaxLevel();
        String aiComment = AI_SKIPPED_COMMENT;
        if (verdict != null) {
            riskLevel = Math.max(riskLevel, verdict.riskLevel());
            /*
             * ★ 模型漏了 comment 时补一句自报家门的话，而不是留空 ——
             *   留空会和「AI 没跑成」撞成同一个样子，而两者指向的动作不同。
             *   见 AI_NO_REASON_COMMENT 的注释。
             */
            String reason = verdict.comment();
            aiComment = (reason == null || reason.isBlank()) ? AI_NO_REASON_COMMENT : reason;
        }

        /*
         * ★★ 这一支【不】catch：写库失败时认领留在原地，
         *   代价是「这一条判定丢了」，换来的是「绝不重复计费」。见类注释。
         *   （丢了也不会静默 —— 下面那行 WARN 由消费者打，且源行的 status 是 fail-closed 的锚点。）
         */
        writeRow(targetType, targetId, riskLevel, hitWordsOf(scan), truncateComment(aiComment),
                AiAuditResult.STATUS_PENDING);

        log.info("AI 审核判定已入队 | targetType={} targetId={} 词库等级={} AI等级={} 最终等级={} 命中词={} AI是否跑成={}",
                targetType, targetId, scan.getMaxLevel(),
                verdict == null ? "无" : verdict.riskLevel(), riskLevel,
                scan.getHitCount(), verdict != null);
    }

    /* ==================== 取正文 ==================== */

    /**
     * 待审正文 + 它的归属。
     *
     * @param novelId   ★ 只用于记账（评论审核拿不到作品号，为 {@code null}）
     * @param chapterId ★ 同上
     */
    private record PendingText(String body, Long novelId, Long chapterId) {
    }

    /**
     * 取待审正文。
     *
     * <p>★ 返回 {@code null} 一律表示<b>永久失败</b>（ACK 且不写行）：
     * 目标不存在、正文行不存在、类型不在范围内。
     * <b>抛出去的只有一种</b>：跨服务读失败（评论那条）—— 重投真的可能有不同结果。
     *
     * <p>★★ <b>本方法【不】返回空正文</b>：空正文会在关键词层扫出「无命中」，
     * 于是被机器判成「排除」—— 一句对一条<b>从没被审过的内容</b>说的「通过」。
     * 宁可没有这一行（「没审成」= 没有行），也不要一个看起来正常的错结论。
     */
    private PendingText readPendingText(Integer targetType, Long targetId) {
        /*
         * ★ 直接比 code，不用 AuditTargetType.fromCode(...)：
         *   那个方法对未知取值抛 BusinessException(PARAM_ERROR)，
         *   而「不认识的目标类型」在这里是【永久失败】——
         *   抛出去会被消费者当成瞬时故障重投，投到死信里也不会变对。
         */
        if (AuditTargetType.CHAPTER.getCode().equals(targetType)) {
            Chapter chapter = chapterMapper.selectById(targetId);
            if (chapter == null) {
                // ★ 提交后、审核前被删掉是完全可能的，这是常态不是故障
                log.info("章节已不存在（可能已被删除），无需 AI 审核 | chapterId={}", targetId);
                return null;
            }
            /*
             * ★★ 正文在另一张表（chapter_content）里，章节表没有 content 列 ——
             *   这是 P2 的拆分（正文体积大，列表查询不该带着它走）。
             *   所以「章节存在」与「正文存在」是两件事，要分别判。
             */
            ChapterContent content = chapterContentMapper.selectById(targetId);
            if (content == null) {
                /*
                 * ★ 正常情况下不可能：章节与正文同时创建。真出现说明数据异常，
                 *   而它是【永久失败】（重投不会长出正文来）→ ACK，但值得 WARN。
                 */
                log.warn("章节存在但没有正文行，本条无法审核（不写判定行） | chapterId={} novelId={}",
                        targetId, chapter.getNovelId());
                return null;
            }
            return new PendingText(content.getContent(), chapter.getNovelId(), chapter.getId());
        }

        if (AuditTargetType.COMMENT.getCode().equals(targetType)) {
            /*
             * ★ 走既有的 content → interaction 接缝（铁律 1 里点名的那条边）。
             *   ★★ 用的是 E1 新加的 textsOf（正文全文），不是 briefsOf：
             *      后者截断到 30 字，送审 = 让模型只看前 30 个字就下结论，
             *      而结果是「无风险」，没有任何一处报错。
             *   ★ 这次调用【失败会抛】（不降级）—— 空正文的后果见方法注释，
             *     所以这里没有 catch：让它抛出去重投。
             */
            Map<Long, String> texts = commentTextLookup.textsOf(List.of(targetId));
            String body = texts.get(targetId);
            if (body == null) {
                // ★ 不在 Map 里 = 查不到 = 已删除（见 CommentTextLookup#textsOf 的约定）
                log.info("评论已不存在（可能已被删除），无需 AI 审核 | commentId={}", targetId);
                return null;
            }
            if (body.isBlank()) {
                log.warn("评论正文为空，本条无法审核（不写判定行） | commentId={}", targetId);
                return null;
            }
            /*
             * ★ novelId / chapterId 传 null：评论审核拿不到作品号（评论只挂在书或章上），
             *   而这两个参数【只用于记账】—— 判定本身不允许用到它们
             *   （见 AiAuditEvaluator#evaluate 的参数注释）。
             */
            return new PendingText(body, null, null);
        }

        /*
         * ★ NOVEL(1) 与 AUTHOR_APPLY(4) 不在 E1 的范围内（范围是「评论 + 章节」）。
         *   写 WARN 而不是 ERROR：这是一条【已知的、设计内的】边界，
         *   不是故障 —— 但要有痕迹，否则以后有人给作品简介投了消息会查不出来。
         *   （AI_AUDIT 的两个生产者只发 2 和 3，所以走到这里说明有人手工投了消息。）
         */
        log.warn("不支持的审核目标类型，已丢弃（E1 的范围是 chapters/comments） | targetType={} targetId={}",
                targetType, targetId);
        return null;
    }

    /* ==================== AI 层 ==================== */

    /**
     * 走一遍「取模板 → 组装 → 渲染 → 交给模型 → 解析」，<b>失败一律返回 {@code null}。</b>
     *
     * <p>★★ <b>把 {@code Exception} 全接住是本类的核心取舍</b>，理由只有一条：
     * 重试的成本是<b>钱</b>，收益是「人工多一条提示」。
     * ★ 与 {@code DeepSeekAiAuditEvaluator} 内部那个 {@code finally} 记账配合起来看：
     * 这里返回 {@code null} 的那一刻，账本里已经有一行 {@code success=0} 的
     * {@code ai_usage_log} 了 —— <b>「钱花了、没结论」这件事在账上看得见</b>，
     * 这正是那个接口把解析放进实现类里的原因。
     *
     * <p>★ <b>组装放在这里而不是调用方</b>：AUDIT 场景组装不碰库、不需要权限
     * （{@code AiVarResolver} 里 AUDIT 只有 {@code {{selected}}} 一个变量，
     * 其余全部 NOT_APPLICABLE），所以它可以安心地跑在 MQ 线程上。
     * <b>哪天有人给 AUDIT 加了需要「当前用户」的变量，这里会立刻暴露</b>
     * （MQ 线程上没有 {@code UserContext}，权限校验会抛）。
     */
    private AiAuditVerdict.Parsed askAiQuietly(String body, Long novelId, Long chapterId,
                                               Integer targetType, Long targetId) {
        try {
            AiPromptTemplate template = promptService.requireForScene(AiScene.AUDIT);

            /*
             * ★ ★★ 只填 context（→ {{selected}}），三个 id 一个都不给：
             *   审核的判据只能是正文本身 —— 让它知道作品名/大纲，
             *   模型就会开始「结合上下文判断」，而违规与否与作者写的是哪本书无关。
             */
            AiRequestDTO dto = new AiRequestDTO();
            dto.setContext(body);

            Map<AiPlaceholder, String> varValues = assembler.assemble(AiScene.AUDIT, dto);
            RenderedPromptVO rendered = PromptRenderer.render(
                    AiScene.AUDIT, template.getSystemPrompt(), template.getUserTemplate(), varValues);

            AiAuditVerdict.Parsed parsed = evaluator.evaluate(
                    rendered.getSystemPrompt(), rendered.getUserMessage(), novelId, chapterId);

            /*
             * ★ 模型的命中词【只进日志】。落库的 hit_words 取我们自己词库扫出来的词
             *   （见 hitWordsOf 的注释）—— 这两者的差值恰恰是下一次调词库的依据：
             *   模型反复报一个词库里没有的词，说明该把它加进去。
             */
            log.info("AI 复核完成 | targetType={} targetId={} | AI等级={} | 模型报的命中词={}",
                    targetType, targetId, parsed.riskLevel(), parsed.hitWords());
            return parsed;

        } catch (Exception e) {
            /*
             * ★★ 全部 ACK，不重投。分成两类只为让日志指向正确的下一步动作：
             *   ① BusinessException —— 模板缺失(5005) / AI 停用(5001) / 没配 Key(5002) /
             *      渲染超长或必填缺失(400)。都是【配置与参数】问题，
             *      去看管理端，别去看上游；
             *   ② 其余（上游 4xx/5xx、解析失败、我们自己的 bug）—— 看日志里的堆栈。
             * ★ 都用 ERROR：这一条判定是彻底没有了（不像词库层还留了一半），
             *   而它不会自己好 —— 每一条提交都会再打一行，行数就是「哑了多久」的刻度。
             */
            if (e instanceof BusinessException be) {
                log.error("AI 复核未开始（配置或参数问题，非上游故障；本条只保留关键词层的判定）"
                                + " | targetType={} targetId={} code={} {}",
                        targetType, targetId, be.getCode(), be.getMessage());
            } else {
                log.error("AI 复核未完成（本条只保留关键词层的判定，不重投）"
                                + " | targetType={} targetId={} error={}",
                        targetType, targetId, MqErrors.describe(e), e);
            }
            return null;
        }
    }

    /* ==================== 落库 ==================== */

    /**
     * 写判定行：<b>先就地覆盖该目标的待办行，影响 0 行再 INSERT。</b>
     *
     * <p>★ 为什么是这个顺序、为什么不是 {@code updateById}、为什么 {@code create_time}
     * 由应用时钟传进去 —— 三条论证都在 {@link AiAuditResultMapper#overwritePending} 上，
     * 不在这里重复。
     *
     * @param riskLevel 0~3，<b>必然 ≥ 1</b>（走到这里的都是命中过词的，见类注释）
     * @param hitWords  我们自己的词库扫出来的词，可能为 {@code null}（无命中那一支）
     * @param aiComment ★ 可以是 {@link #AI_SKIPPED_COMMENT}（AI 层没跑成），
     *                  也可以是 {@code null}（无命中那一支，不调 AI 就没有「AI 的说法」）
     * @param status    {@link AiAuditResult#STATUS_EXCLUDED}（机器排除）
     *                  或 {@link AiAuditResult#STATUS_PENDING}（进人工队列）
     */
    private void writeRow(Integer targetType, Long targetId, int riskLevel,
                          String hitWords, String aiComment, int status) {
        /*
         * ★ 判定时间用【应用】的时钟，而不是 MySQL 的 NOW()：
         *   INSERT 那条路径的 create_time 来自 MybatisMetaObjectHandler（也是应用的时钟），
         *   两条路径用两个时钟的话，同一张表里会混着两个时间源，
         *   而它们的偏差在跨时区部署时是【小时级】的，且没有任何报错。
         *   （同 AiAuditResultMapper#overwritePending 的 @param 注释。）
         */
        LocalDateTime now = LocalDateTime.now();

        int rows = auditResultMapper.overwritePending(
                targetType, targetId, riskLevel, hitWords, aiComment, status, now);
        if (rows > 0) {
            log.info("判定已覆盖该目标原有的待办行 | targetType={} targetId={} risk={} status={}",
                    targetType, targetId, riskLevel, status);
            return;
        }

        /*
         * ★ 0 行 = 这个目标没有待办行（第一次审，或上一次已经被人处置过）→ INSERT 一条。
         *   这不是错误，是最常见的一条路径。
         */
        AiAuditResult row = new AiAuditResult();
        row.setTargetType(targetType);
        row.setTargetId(targetId);
        row.setRiskLevel(riskLevel);
        row.setHitWords(hitWords);
        row.setAiComment(aiComment);
        row.setStatus(status);
        /*
         * ★ audit_user_id / audit_time 留空 = 还没有人处置过。
         *   ★★ 它与「机器排除」共用一个表达式（status=2 且 audit_user_id IS NULL），
         *   判别式写在 AiAuditResult 的类注释里 —— 页面据此显示「机器排除」
         *   而不是「已排除」（后者是「人看过说没问题」，两者不能混）。
         * ★ create_time 由 MybatisMetaObjectHandler 填（FieldFill.INSERT）。
         */
        auditResultMapper.insert(row);
        log.info("判定已入队 | targetType={} targetId={} risk={} status={} hitWords={}",
                targetType, targetId, riskLevel, status, hitWords);
    }

    /* ==================== 命中词 / 理由的裁剪 ==================== */

    /**
     * 把命中的词拼成 {@code hit_words} 的值。
     *
     * <p>★★ <b>只放我们自己的词库扫出来的词，【不】放模型报的。</b>
     * 模型的 {@code hitWords} 是它自己说的，可能与我们的词表对不上
     * （它爱报「刀」「枪」这种词库里根本没有的单字）——
     * 而这一列在页面上是要和词库对照着看的。模型的那些进日志。
     *
     * <p>★★ <b>超出上限【必须可见】</b>：静默截断会让管理员以为
     * 「就命中了这 20 个词」，从而低估这篇内容的违规程度。
     * 所以末尾拼一句「（共N个词，此处列前M个）」——
     * 同一个手法在 {@code InternalCommentQueryService#truncate} 里也用了（省略号）。
     */
    private static String hitWordsOf(SensitiveWordScanVO scan) {
        List<SensitiveWordHitVO> hits = scan.getHits();

        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (SensitiveWordHitVO hit : hits) {
            if (kept >= MAX_HIT_WORDS) {
                break;
            }
            String piece = (kept == 0 ? "" : "，") + hit.getWord();
            if (sb.length() + piece.length() > MAX_HIT_WORDS_LENGTH) {
                // ★ 列宽兜底：词特别长时先撞到的是字数上限，而不是词数上限
                break;
            }
            sb.append(piece);
            kept++;
        }

        if (kept < hits.size()) {
            sb.append("（共").append(hits.size()).append("个词，此处列前").append(kept).append("个）");
        }
        /*
         * ★ 正常永远不为空：只有命中才调这里，而 hits 非空。
         *   唯一的例外是「单个词比字数上限还长」—— 那种词表本身有问题，
         *   返回 null（这一格显示为空）好过拼一句读不懂的截断。
         */
        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * 裁 {@code ai_comment}（列宽 1000）。
     *
     * <p>★ 这里可以截断，而 {@code hit_words} 那边的截断必须带标记 —— 两者都要
     * <b>看得见</b>，但理由不同：{@code hit_words} 截断会让人低估违规程度，
     * 而这一段是模型啰嗦（它有时会把正文复述一遍），截掉尾巴不影响判定本身。
     * ★ 仍然加上「（已截断）」：没有它，管理员会以为模型的话到此为止。
     */
    private static String truncateComment(String comment) {
        if (comment == null) {
            return null;
        }
        return comment.length() <= MAX_AI_COMMENT_LENGTH
                ? comment
                : comment.substring(0, MAX_AI_COMMENT_LENGTH) + "…（理由过长已截断）";
    }

    /* ==================== 幂等闸门 ==================== */

    /**
     * 认领。{@code true} = 这次由我处理。
     *
     * <p>★ 一条命令 {@code SET key 1 NX EX 86400}（{@code setIfAbsent + Duration} 就是它）：
     * 拆成「先 GET 判断、再 SET」在并发下会双双通过；
     * 拆成「SETNX + EXPIRE」一旦中间断了，这把 key 就<b>永远不会过期</b>。
     * 同 {@code CommentServiceImpl} 里评论 10 秒频控那段论证。
     */
    private boolean claim(String claimKey) {
        return Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                .setIfAbsent(claimKey, "1", CLAIM_TTL));
    }

    /**
     * 退回认领（<b>只在钱一定没花出去时调</b>，见类注释）。
     *
     * <p>★ 自己吞掉异常：能走到这里说明上面已经有一个原因要抛出去了，
     * 而「Redis 也挂了」时那把 key 无论如何都留不住（TTL 会兜底），
     * <b>不能让它把原来的原因替换掉</b> —— 那会把一次 MySQL 故障报成 Redis 故障。
     */
    private void release(String claimKey) {
        try {
            stringRedisTemplate.delete(claimKey);
        } catch (Exception e) {
            log.warn("退回幂等键失败（这一条要等 24 小时 TTL 过期才能重审） | key={} error={}",
                    claimKey, MqErrors.describe(e));
        }
    }

    /**
     * 正文指纹：SHA-256 的前 8 字节，16 个十六进制字符。
     *
     * <p>★ 为什么不是 {@code String#hashCode()}：32 位、且分布很差，
     * 几万条内容里出现碰撞就不再是可以忽略的事 —— 而<b>碰撞的后果是一次审核被静默跳过</b>。
     * 一次静默跳过的代价是「一条内容永远没有 AI 提示」，而没有任何地方会报错。
     */
    private static String fingerprint(String body) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, FINGERPRINT_BYTES);
        } catch (NoSuchAlgorithmException e) {
            // ★ SHA-256 是 JDK 必须实现的算法，走不到这里；真走到了说明 JVM 有问题，就该 500
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }
}
