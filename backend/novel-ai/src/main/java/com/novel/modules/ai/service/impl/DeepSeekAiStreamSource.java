package com.novel.modules.ai.service.impl;

import com.novel.common.exception.BusinessException;
import com.novel.common.security.LoginUser;
import com.novel.common.security.UserContext;
import com.novel.modules.ai.dto.AiRequestDTO;
import com.novel.modules.ai.enums.AiFailure;
import com.novel.modules.ai.enums.AiScene;
import com.novel.modules.ai.service.AiChatUsage;
import com.novel.modules.ai.service.AiConfigService;
import com.novel.modules.ai.service.AiModelClient;
import com.novel.modules.ai.service.AiRuntimeConfig;
import com.novel.modules.ai.service.AiStreamSource;
import com.novel.modules.ai.service.AiUsageRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 的<b>真实实现</b>（E1）：把一次生成转成一次真实的模型调用。
 *
 * <h3>★★★ 这个类就是 P4 那句「接真实模型只加一个类」的兑现处</h3>
 *
 * <p>{@code AiStreamSource} 的类注释里写着：
 * <pre>
 *   P4（现在）：AiStreamSource ← MockAiStreamSource    （演示数据）
 *   E1（以后）：AiStreamSource ← DeepSeekAiStreamSource（真实模型）
 *                              ↑ 只加这一个类，改一行配置
 * </pre>
 * ★ <b>流式那条路径确实只加了这一个类</b>（{@code AiModelClient} 是它和审核共用的
 * 底层客户端，不是第二份实现）。Controller、SSE 管道、前端 {@code sse.js}、
 * 打字机渲染、中断、采纳 —— <b>一个字都没改</b>。
 *
 * <h3>★★ 切换方式：一行环境变量，零文件改动</h3>
 *
 * <pre>
 *   novel.ai.provider = deepseek → 本类（★ 默认，见 application.yml）
 *   novel.ai.provider = mock     → {@link MockAiStreamSource}（必须显式选，零成本验证用）
 * </pre>
 * ★ 本类现在带 {@code matchIfMissing = true}：配置项整个缺失时兜底到【真实实现】。
 *   原来是 mock 兜底，那条论证与它的代价记在 {@link MockAiStreamSource} 的类注释里。
 * ★ 用环境变量 {@code NOVEL_AI_PROVIDER=deepseek} 即可覆盖 yml ——
 * Spring Boot 的环境变量优先级<b>高于</b>配置文件，所以
 * <b>验收时不需要改任何文件、也不需要重新打包</b>。
 * 这是本期的测试钩子，与 M4 用 {@code ES_URIS} 指向本地 TCP 代理做「ES 挂掉/恢复」
 * 是同一个手法。
 *
 * <h3>★★★ 顺序：闸门（在别处）→ 读参数 → 解密 → 调用</h3>
 *
 * <pre>
 *   ① AiPreflight.check()      ← ★ 在 AiController 的【请求线程】上，本类之前
 *   ② requireUsable()          ← 这里只当作「读参数」，见下
 *   ③ decryptCurrentApiKey()   ← 明文 Key 的作用域只有下面那一次调用
 *   ④ modelClient.streamChat() ← 真金白银
 * </pre>
 * ★ <b>② 在 ③ 之前</b>：解密失败（{@code AI_KEY_SECRET} 没配 / 换过）是 500，
 * 而它<b>不该吃掉一个配额</b> —— 那是服务端没配好，不是用户用了一次。
 * ★★ 但更重要的顺序是 <b>③ 必须在 ④ 之前</b>：拿不到 Key 就不该发请求。
 *
 * <h3>★★★ E1/S6：闸门已经搬走了，本类【进来就假定闸门过了】</h3>
 *
 * <p>S4/S5 阶段，停用/没配 Key/配额这三道闸门住在本类里 —— 也就是跑在
 * {@code ai-stream-N} 线程上，而<b>那时候 SSE 响应头早就发出去了</b>。
 * 后果是「额度用完」只能表达成一个 {@code event: error} 帧，
 * 用户读到的是「生成失败，请稍后重试」——
 * <b>一句对额度用完说的、永远不会成真的话</b>（今天重试一万次也不会成功）。
 *
 * <p>★★★ 更糟的一条：如果按 {@code ResultCode} 的老规矩把它做成「HTTP 200 + code 5004」，
 * 前端 {@code sse.js} 会把它读成「响应不是事件流」，
 * 用户看到的是<b>「请检查代理配置」</b> —— 被指去查一个与整件事毫无关系的地方。
 * 完整论证见 {@link com.novel.modules.ai.service.AiPreflight} 的类注释。
 *
 * <p><b>所以 S6 把整条闸门搬到了请求线程上、建 {@code SseEmitter} 之前。</b>
 * 本类因此有两个推论，都不是可选的：
 * <ol>
 *   <li><b>本类里再抛 {@code 5001/5002/5004} 就是异常情况</b> ——
 *       它意味着「闸门通过之后、调用之前，管理员恰好改了配置」。
 *       ★ 那是真实的竞态（管理端与生成端在同一个 JVM 上），所以
 *       {@code catch (BusinessException)} 那一支必须留着；但它已经<b>不是</b>
 *       主路径，走到那里会发出一个错误帧而不是状态码，这是可接受的降级。</li>
 *   <li><b>提示词不再由本类拼</b> —— {@code request.getRenderedSystemPrompt()}
 *       / {@code getRenderedUserMessage()} 由闸门填好带进来。见下面那个 null 检查。</li>
 * </ol>
 *
 * <p>★ <b>为什么解密仍然留在这里、没有跟着搬走：</b>
 * 搬到请求线程意味着明文 Key 要跨线程传递 —— 那只能存在 {@code AiRequestDTO} 上，
 * 而那个对象是<b>会被日志打印、会被序列化</b>的。Key 的明文作用域必须尽可能小，
 * 小到「只有下面那一次 HTTP 调用」这一层，所以它<b>紧挨着调用</b>。
 * ★ 这条与「闸门必须早」并不矛盾：闸门判的是<b>能不能调</b>（与密钥无关，读的是
 * {@code enabled} 与 {@code api_key} 是否为空），而解密是<b>拿密钥</b>，两件事。
 *
 * <h3>★★★ 中断：不碰 {@code AiStreamSource} 接口，靠【包一层回调】识别</h3>
 *
 * <p>契约要求「客户端断开后，{@code onChunk} 抛出的异常不许被 catch，让它把生成循环冲掉」。
 * 但中断本身我们还<b>要记一笔账</b>（见 {@link AiFailure#ABORTED}：那一刻 token 已经烧了）。
 * 麻烦在于「怎么知道刚才那个异常是中断」——
 * {@code AiController.StreamAbortedException} 是 <b>private</b> 的，按类型 catch 不到；
 * 而 catch 一个宽泛的 {@code RuntimeException} 会把真正的 NPE 也记成「被中断」，
 * 那是<b>账本上的一句假话</b>。
 *
 * <p>★ 解法是<b>在唯一可能抛出的那个调用点上包一层</b>：
 * <pre>
 *   delta -&gt; {
 *       try { handler.onChunk(delta); }
 *       catch (RuntimeException e) { aborted.set(true); throw e; }
 *   }
 * </pre>
 * ★★ 这个 try <b>只包住 {@code handler.onChunk} 这一行</b>，它里面在
 * {@code AiController} 侧只有两个出口（连接写失败 / {@code aborted} 标志已置位），
 * <b>两个都是中断</b>。所以「这里抛出来 = 用户中断了」是精确的，不是猜的 ——
 * 而我们自己的代码（拼 URL、解析 JSON）全都在这行<b>之外</b>，
 * 它们的异常不会被打上 ABORTED 的标签。
 * ★ 并且<b>原样 rethrow</b>：异常要继续往上冲，才能冲掉读取循环、
 * 也才能被 {@code AiController} 那句静默的 {@code catch (StreamAbortedException)} 接住。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "novel.ai.provider", havingValue = "deepseek", matchIfMissing = true)
public class DeepSeekAiStreamSource implements AiStreamSource {

    private final AiConfigService configService;
    private final AiUsageRecorder usageRecorder;
    private final AiModelClient modelClient;

    /*
     * ★ S6 起这里【没有】AiQuotaService —— 配额归 AiPreflight 管。
     *   留在本类的话就是两处扣减，而症状是「额度掉得比实际快一倍」，
     *   且账面上每一条都看着正常。
     */
    public DeepSeekAiStreamSource(AiConfigService configService,
                                  AiUsageRecorder usageRecorder,
                                  AiModelClient modelClient) {
        this.configService = configService;
        this.usageRecorder = usageRecorder;
        this.modelClient = modelClient;
        log.info("★★ AI 当前使用【真实模型】(DeepSeekAiStreamSource) —— 会产生真实费用。"
                + "端点与模型取自 ai_config 表，配额取自 ai_config.daily_limit");
    }

    @Override
    public void stream(AiScene scene, AiRequestDTO request, Handler handler) {
        /*
         * ★★★ AUDIT 不是流式场景，走到这里说明有人接错了端点。
         *   ★ 与 MockAiStreamSource 里那一支【故意写成抛异常】是同一个理由，
         *     也【故意不写 default】—— 见那个类里更长的论证。
         *   ★ 放在 try 之外：这是一条不该存在的调用路径，不该在账本上留一行。
         */
        if (scene == AiScene.AUDIT) {
            throw new IllegalStateException(
                    "AUDIT 不是流式场景，不该走到 DeepSeekAiStreamSource —— 见 AiScene.AUDIT 的注释");
        }

        /*
         * ★★★ 提示词必须由闸门填好带进来 —— 这里【没有】兜底路径。
         *
         *   AiPreflight 是唯一生产它们的地方，而它在建 emitter 之前必然跑过一次。
         *   所以拿到 null 只可能是「有人新加了一条绕过闸门的调用路径」，
         *   那是代码错误，不该用 prompt 现拼一份凑合的提示词顶上 ——
         *   后者会【静默地】少掉作品名 / 大纲 / 摘要三段上下文，
         *   而模型照样能生成一段像模像样的东西。
         *
         * ★ 放在 try 【之外】，与上面那条 AUDIT 守卫同一个理由：
         *   这是一条不该存在的调用路径，不该在用量账上留一行
         *   （记成 UNKNOWN 的话，看账的人会以为「调用发生了但失败了」）。
         */
        if (request == null || request.getRenderedUserMessage() == null) {
            throw new IllegalStateException(
                    "渲染后的提示词为空 —— 这条调用路径绕过了 AiPreflight，见 AiPreflight 的类注释");
        }

        /*
         * ★ 用户身份靠【调用方】搬过来（AiController 在 aiStreamExecutor.execute
         *   里做了 UserContext.set）。本方法跑在 ai-stream-N 线程上，
         *   ThreadLocal 在那是空的 —— 不搬就全程是 null。
         *
         * ★★ S6 起身份缺失【不再有哨兵桶】：配额扣减已经在闸门里
         *   用 UserContext.requireUserId() 判过（拿不到就 401，走不到这里）。
         *   这里读到的 userId 只用于【记账】，为 null 只说明搬运出了 bug ——
         *   而 AiUsageRecorder 自己会把 null 记成 UNKNOWN_USER_ID，
         *   那是账本上的事，不是配额上的事。
         */
        LoginUser loginUser = UserContext.get();
        String username = loginUser == null ? null : loginUser.getUsername();
        Long userId = loginUser == null ? null : loginUser.getUserId();

        long start = System.currentTimeMillis();
        AtomicBoolean aborted = new AtomicBoolean(false);
        AtomicInteger pushed = new AtomicInteger();

        AiRuntimeConfig cfg = null;
        AiChatUsage usage = AiChatUsage.ZERO;
        AiFailure failure = null;

        try {
            /*
             * ---------- ① 读运行参数 ----------
             * ★ 它顺带会再判一次「停用 / 没配 Key」——闸门已经判过了，
             *   所以这一支现在【只会】在「闸门通过之后管理员恰好改了配置」
             *   那个竞态下抛出。留着它是为了拿到 baseUrl/model/temperature/maxTokens，
             *   不是为了当闸门（见类注释）。
             */
            cfg = configService.requireUsable();

            /* ---------- ② 明文 Key：作用域只有下面这一次调用 ---------- */
            String apiKey = configService.decryptCurrentApiKey();

            /* ---------- ③ 提示词：闸门渲染好的那一份，本类一个字都不拼 ---------- */
            String systemPrompt = request.getRenderedSystemPrompt();
            String userPrompt = request.getRenderedUserMessage();

            log.info("AI 真实调用开始 | scene={}({}) | user={} | model={} | 上下文={}字",
                    scene.getCode(), scene.getLabel(), username, cfg.model(),
                    userPrompt.codePointCount(0, userPrompt.length()));

            /* ---------- ④ 真金白银（★ 上面每一步都过了才走到这） ---------- */
            usage = modelClient.streamChat(cfg, apiKey, systemPrompt, userPrompt, delta -> {
                try {
                    /*
                     * ★★★ 见类注释「中断」那一节：
                     *   这个 try 只包住 onChunk 一行，所以「这里抛出来 = 用户中断了」
                     *   是精确的。原样 rethrow，让异常继续冲掉读取循环。
                     */
                    handler.onChunk(delta);
                } catch (RuntimeException e) {
                    aborted.set(true);
                    throw e;
                }
                pushed.addAndGet(delta.codePointCount(0, delta.length()));
            });

            handler.onComplete();
            log.info("AI 生成完成 | scene={}({}) | user={} | 耗时={}ms | 推送={}字 | tokens={}/{}/{}",
                    scene.getCode(), scene.getLabel(), username,
                    System.currentTimeMillis() - start, pushed.get(),
                    usage.promptTokens(), usage.completionTokens(), usage.totalTokens());

        } catch (AiModelClient.UpstreamException e) {
            /*
             * ★ 上游侧的一切失败。★ e.getMessage() 是我们自己拼的
             *   （失败码 + 状态码），不含上游原文 —— 见 AiModelClient 的类注释。
             */
            failure = e.failure();
            log.error("AI 生成失败 | scene={} | user={} | 失败={} | 已推送={}字",
                    scene.getCode(), username, failure.getCode(), pushed.get(), e);
            handler.onError(e);

        } catch (BusinessException e) {
            /*
             * ★★ S6 起这条已经【不是闸门】了 —— 闸门在 AiPreflight 里，
             *   跑在请求线程上、返真实状态码。走到这里只剩两种情况：
             *   <pre>
             *     ① 竞态：闸门通过之后、这次调用之前，管理员关掉了 AI / 清空了 Key
             *     ② 解密失败（AI_KEY_SECRET 没配或换过）—— 那是 500 而不是业务码
             *   </pre>
             *   ★ 两者都极少发生，而且都<b>确实已经在流里了</b>（emitter 已建），
             *     所以只能发错误帧。这是可接受的降级：状态码那条路走不通了，
             *     但至少用户不会永远转圈。
             *
             * ★ 仍然不记进 error_msg —— 账本记的是「上游调用怎么了」，这里压根没调。
             *   这也是闸门在 S6 搬走的收益之一：常态下这类请求
             *   根本走不到本类，账本里连一行都不会有，语义更干净。
             */
            log.warn("AI 生成在闸门之后被拒（竞态）| scene={} | user={} | code={} | {}",
                    scene.getCode(), username, e.getCode(), e.getMessage());
            handler.onError(e);

        } catch (RuntimeException e) {
            if (aborted.get()) {
                /*
                 * ★★★ 用户中断（或 SSE 超时、或连接已断）。
                 *
                 *   它不是上游故障，但【必须记一笔账】：挂断的那一刻 token
                 *   已经真的烧掉了，模型那边不会因为我们挂断就退钱。
                 *   不记的话用量账会【系统性地少算】，而少算的方向恰好是
                 *   「看起来更省钱」—— 那是最不该出错的方向。
                 *
                 *   ★ 必须原样 rethrow，不能吞掉：
                 *     异常要继续往上冲，才能被 AiController 那句静默的
                 *     catch (StreamAbortedException) 接住并记一行 INFO。
                 *     吞掉的话，用户每点一次「停止」都会在日志里留一条 error 堆栈。
                 *
                 *   ★★ token 列会是 0（中断时上游还没发 usage 帧，我们【不知道】烧了多少）——
                 *      这一行读作「未统计」而不是「没消耗」，判据是 error_msg=aborted。
                 *      见 AiFailure#ABORTED。
                 */
                failure = AiFailure.ABORTED;
                log.info("AI 生成被中断 | scene={} | user={} | 已推送={}字（token 未统计）",
                        scene.getCode(), username, pushed.get());
                usage = AiChatUsage.ZERO;
                throw e;
            }
            /*
             * ★ 我们自己的 bug（NPE、状态错乱……）。
             *   ★ 不给它编一个好看的失败码：UNKNOWN 就是「去看日志」的意思。
             */
            failure = AiFailure.UNKNOWN;
            log.error("AI 生成出现未预期的异常 | scene={} | user={} | 已推送={}字",
                    scene.getCode(), username, pushed.get(), e);
            handler.onError(e);

        } finally {
            /*
             * ★★★ 三种结局（成功 / 上游失败 / 被中断）【都要记】。
             *   放在 finally 而不是各个 catch 里：上面有四条出口，
             *   而且「被中断」那条是 rethrow —— 只有 finally 能保证它也被记上。
             *
             * ★ record() 自己永不抛异常（见 AiUsageRecorder 的类注释），
             *   所以放在 finally 里不会把一次成功的生成变成一个错误帧。
             */
            usageRecorder.record(new AiUsageRecorder.Entry(
                    userId, scene,
                    request == null ? null : request.getNovelId(),
                    request == null ? null : request.getChapterId(),
                    cfg == null ? null : cfg.model(),
                    usage, System.currentTimeMillis() - start, failure));
        }
    }

    /*
     * ==================== 提示词拼装去哪儿了 ====================
     *
     * ★★★ S4/S5 阶段这里有两个方法：buildSystemPrompt（返回 null）与
     * buildUserPrompt（把 prompt 和 context 直拼）。<b>S6 把它们删了。</b>
     *
     * 现在的提示词来自 ai_prompt_template 表（管理端可编辑），由
     * AiPreflight → AiContextAssembler + PromptRenderer 组装，
     * 再从 AiRequestDTO 的两个 @JsonIgnore 字段带进来。
     *
     * ★ 值得留下的是 buildSystemPrompt 当初那条论证，因为它在 E1 依然成立：
     * 曾考虑过「先编一句『你是一个小说写作助手』当占位」，
     * 那是错的 —— 内置兜底提示词会<b>悄悄改变模型行为</b>，
     * 而管理员以为自己改掉的就是生效的那份。判断 2.2 的
     * 「模板缺失 → 5005，绝不用内置兜底」是同一条。
     */
}
