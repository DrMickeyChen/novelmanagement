package com.novel.modules.ai.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.common.exception.BusinessException;
import com.novel.modules.ai.enums.AiFailure;
import com.novel.modules.ai.enums.AiScene;
import com.novel.modules.ai.service.AiAuditEvaluator;
import com.novel.modules.ai.service.AiAuditVerdict;
import com.novel.modules.ai.service.AiChatUsage;
import com.novel.modules.ai.service.AiConfigService;
import com.novel.modules.ai.service.AiModelClient;
import com.novel.modules.ai.service.AiRuntimeConfig;
import com.novel.modules.ai.service.AiUsageRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * AI 审核的<b>真实实现</b>（E1）：一次非流式调用换一条判定。
 *
 * <h3>★★ 切换方式：一行环境变量，零文件改动</h3>
 *
 * <pre>
 *   novel.ai.provider = mock     → {@link MockAiAuditEvaluator}（默认，新克隆的仓库什么都不配就能跑）
 *   novel.ai.provider = deepseek → 本类（流式那条同时切到 DeepSeekAiStreamSource）
 * </pre>
 * ★ 用环境变量 {@code NOVEL_AI_PROVIDER=deepseek} 覆盖 yml 即可 —— 环境变量优先级高于配置文件，
 * <b>验收时不改任何文件、不重新打包</b>（与 M4 用 {@code ES_URIS} 指本地代理是同一个手法）。
 *
 * <h3>★★★ 顺序：参数 → 解密 → 调用，与流式那条逐字同形</h3>
 *
 * <pre>
 *   ① requireUsable()          ← 顺带再判一次「停用 / 没配 Key」
 *   ② decryptCurrentApiKey()   ← 明文 Key 的作用域只有 ③ 那一次调用
 *   ③ modelClient.chat(...)    ← 真金白银
 * </pre>
 * ★ <b>明文 Key 【不跨线程传递】</b>：它在这一帧里出生、在这一帧里用完，
 * 没有进入任何会被日志打印或被序列化的对象。
 * ★★ 这是审核与流式<b>唯一必须逐字照抄</b>的一段：两条路都会烧钱，
 * 也就都要守同一条纪律。区别只在①在这里<b>不是闸门</b>（见下）。
 *
 * <h3>★★ 这里抛 5001/5002 是【异常情况】，不是主路径</h3>
 *
 * <p>生成端的闸门（{@code AiPreflight}）住在请求线程上，因为那边<b>有用户等着</b>、
 * 要返一个漂亮的状态码。审核端<b>没有请求、没有连接</b> ——
 * 背后是一条 MQ 消息，能表达的只有「ACK」或「重投」。
 * 所以本类不做前置闸门，而是<b>把 {@code requireUsable()} 当作「读参数」</b>：
 * 它顺带会把「管理员把 AI 关了 / 把 Key 清了」变成一次 {@code BusinessException}，
 * 由消费者<b>按永久失败处置（ACK + ERROR + 不写行）</b>——
 * 重投一万次也不会让一个停用的 AI 变得可用。
 *
 * <h3>★★ 配额：审核【不扣任何人的额度】</h3>
 *
 * <p>本类里没有 {@code AiQuotaService}，而且是刻意的：
 * <ul>
 *   <li>审核是<b>系统行为</b> —— 提交章节的作者并没有「点一次生成」，
 *       扣他的额度等于<b>罚他做对了事</b>；</li>
 *   <li>它也<b>没有对象可扣</b>：{@code ai_usage_log.user_id} 记的是
 *       {@code UNKNOWN_USER_ID(0)}，背后根本没有请求上下文。</li>
 * </ul>
 * ★ 所以「审核花了多少钱」这件事只能靠 {@code ai_usage_log} 里 {@code scene=5} 的行来看，
 * <b>不体现在任何一个用户的 {@code remaining} 上</b>。这条要写进文档，
 * 否则下一个人会把它当成漏扣配额的 bug。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "novel.ai.provider", havingValue = "deepseek")
public class DeepSeekAiAuditEvaluator implements AiAuditEvaluator {

    private final AiConfigService configService;
    private final AiUsageRecorder usageRecorder;
    private final AiModelClient modelClient;
    private final ObjectMapper objectMapper;

    public DeepSeekAiAuditEvaluator(AiConfigService configService,
                                    AiUsageRecorder usageRecorder,
                                    AiModelClient modelClient,
                                    ObjectMapper objectMapper) {
        this.configService = configService;
        this.usageRecorder = usageRecorder;
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
        log.info("★★ AI 违禁审核当前使用【真实模型】(DeepSeekAiAuditEvaluator) —— 会产生真实费用。"
                + "端点与模型取自 ai_config 表；★ 审核不扣任何用户的配额（系统行为）");
    }

    @Override
    public AiAuditVerdict.Parsed evaluate(String systemPrompt, String userMessage,
                                          Long novelId, Long chapterId) {
        long start = System.currentTimeMillis();

        AiRuntimeConfig cfg = null;
        AiChatUsage usage = AiChatUsage.ZERO;
        AiFailure failure = null;

        try {
            /* ---------- ① 读运行参数（顺带判停用 / 没配 Key） ---------- */
            cfg = configService.requireUsable();

            /* ---------- ② 明文 Key：作用域只有下面那一次调用 ---------- */
            String apiKey = configService.decryptCurrentApiKey();

            log.info("AI 审核真实调用开始 | novelId={} | chapterId={} | model={} | 待审正文={}字",
                    novelId, chapterId, cfg.model(), codePointCount(userMessage));

            /* ---------- ③ 真金白银 ---------- */
            AiModelClient.ChatResult result = modelClient.chat(cfg, apiKey, systemPrompt, userMessage);
            usage = result.usage();

            /*
             * ---------- ④ 解析 ----------
             * ★ 放在本类里而不是调用方，是为了让下面那行账目说真话 ——
             *   见 AiAuditEvaluator 的类注释（success 读作「换来了一条能用的判定」）。
             * ★★ 空内容不必单独判：parse 对 null / blank 的第一条分支就是
             *   UnparsableException("模型返回了空内容")，落到的失败码与
             *   「答了一段读不懂的话」相同。两者要分开的话，
             *   判据是日志里那句原文片段，不是账本 —— 账本要回答的是
             *   「这次调用有没有换来判定」。
             */
            AiAuditVerdict.Parsed parsed = AiAuditVerdict.parse(objectMapper, result.content());

            log.info("AI 审核真实调用完成 | novelId={} | chapterId={} | 耗时={}ms | 等级={} | tokens={}/{}/{}",
                    novelId, chapterId, System.currentTimeMillis() - start, parsed.riskLevel(),
                    usage.promptTokens(), usage.completionTokens(), usage.totalTokens());
            return parsed;

        } catch (AiAuditVerdict.UnparsableException e) {
            /*
             * ★★ 上游答了、钱花了、答复没法用。它【不是】上游故障，
             *   但在账本上必须是失败：模型那边不会因为我们读不懂就退钱。
             *   记 PARSE_FAILED 而不是 UNKNOWN —— 前者指向「去看提示词工程 /
             *   看解析器的容忍度」，后者指向「去看日志有没有堆栈」，两个方向不一样。
             * ★ 原样往上抛：调用方按【永久失败】处置（ACK + 不写行），
             *   重投只会一模一样地再烧一次钱。
             */
            failure = AiFailure.PARSE_FAILED;
            log.error("AI 审核返回的内容解析不出判定（本条不计入队列，也不重投）| novelId={} | chapterId={}",
                    novelId, chapterId, e);
            throw e;

        } catch (AiModelClient.UpstreamException e) {
            /*
             * ★ 上游侧的一切失败。★ e.getMessage() 是我们自己拼的（失败码 + 状态码），
             *   不含上游原文 —— 见 AiModelClient 的类注释。
             */
            failure = e.failure();
            log.error("AI 审核调用失败 | novelId={} | chapterId={} | 失败={} | status={}",
                    novelId, chapterId, failure.getCode(), e.status(), e);
            throw e;

        } catch (BusinessException e) {
            /*
             * ★ 停用 / 没配 Key / 解密失败（{@code AI_KEY_SECRET} 没配或换过）。
             *   ★ 不记 error_msg：账本记的是「上游调用怎么了」，而这三种情况
             *     压根没发出请求 —— 记进去会让用量页显示一笔并不存在的消耗。
             *   ★ 与流式那条同一口径（{@code DeepSeekAiStreamSource} 的 catch 里写着同样一句）。
             */
            log.warn("AI 审核被前置条件拒绝（未调用上游，不计账）| novelId={} | chapterId={} | code={} | {}",
                    novelId, chapterId, e.getCode(), e.getMessage());
            throw e;

        } catch (RuntimeException e) {
            /* ★ 我们自己的 bug（NPE、状态错乱……）。UNKNOWN 就是「去看日志」的意思 */
            failure = AiFailure.UNKNOWN;
            log.error("AI 审核出现未预期的异常 | novelId={} | chapterId={}", novelId, chapterId, e);
            throw e;

        } finally {
            /*
             * ★★ 只有真的发出过请求才记账 —— 用 failure == null && cfg == null 区分不出来，
             *   所以判据是「cfg 有没有读到」：cfg 为 null 说明在 ① 就抛了，
             *   一次 HTTP 都没发出去。
             *   ★ 这与流式那条不同（那里无条件记），因为流式进了方法就一定会调上游；
             *     而这里 ① 自己就可能抛（停用/没Key），那种情况记一行 0 token 的
             *     「失败」会让用量页上出现一个凭空多出来的调用。
             * ★ record() 自己永不抛异常（见 AiUsageRecorder 的类注释）。
             */
            if (cfg != null) {
                usageRecorder.record(new AiUsageRecorder.Entry(
                        AiUsageRecorder.UNKNOWN_USER_ID, AiScene.AUDIT, novelId, chapterId,
                        cfg.model(), usage, System.currentTimeMillis() - start, failure));
            }
        }
    }

    /** 按码点数，不是 {@code length()}：中英混排下两者差得不小（同 {@code MockAiStreamSource}） */
    private static int codePointCount(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }
}
