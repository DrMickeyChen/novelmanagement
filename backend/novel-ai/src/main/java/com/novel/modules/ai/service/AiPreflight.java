package com.novel.modules.ai.service;

import com.novel.common.exception.BusinessException;
import com.novel.common.security.UserContext;
import com.novel.modules.ai.dto.AiRequestDTO;
import com.novel.modules.ai.entity.AiPromptTemplate;
import com.novel.modules.ai.enums.AiPlaceholder;
import com.novel.modules.ai.enums.AiScene;
import com.novel.modules.ai.vo.RenderedPromptVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * AI 生成的<b>前置闸门</b>（E1/S6）：一切能在建 {@code SseEmitter} 之前判定的事。
 *
 * <h3>★★★ 判据：错误走哪个出口，由「能不能在建 emitter 之前判定」决定</h3>
 *
 * <p>不是由「这个错误重不重要」决定，也不是由「用户看不看得见」决定 ——
 * <b>理由是结构性的：SSE 一旦开始，响应头就已经发出去了，HTTP 状态码改不了。</b>
 *
 * <pre>
 *   建 emitter 之前能判定的          → 真实 HTTP 状态码（由 sse.js 的「非 2xx」出口渲染 body.message）
 *   流已经开始之后才知道的（上游超时 / 5xx / 解析失败） → event: error 帧
 * </pre>
 *
 * <h3>★★★ 走错出口的后果：一句【指向完全无关的系统】的假话</h3>
 *
 * <p>这也是本类存在的全部理由。{@code sse.js} 有<b>两条互不相同</b>的错误出口
 * （实测，见判断 1）：
 * <pre>
 *   :215-221  非 2xx              → 把响应体里的 message 原样显示  ✓
 *   :233-241  2xx 但 Content-Type 不是事件流
 *             → 「响应不是事件流（Content-Type: …），请检查代理配置」
 * </pre>
 * ★★ 所以「额度用完」如果按 {@code ResultCode} 的老规矩返 <b>200 + code 5004</b>，
 * 用户看到的是<b>「请检查代理配置」</b> —— 他不是收到了一句含糊的话，
 * 而是被指去查一个<b>与整件事毫无关系</b>的地方。
 * 这比 M5 那句「评论 #94（已删除）」更误导：那一句至少还在说评论。
 *
 * <p><b>★ 阳性对照（这条护栏必须能证明它会失败）：</b>
 * 故意让额度用完<b>仍返 {@code 200 + code 5004}</b>，断言面板显示的正是
 * 「请检查代理配置」。这一条必须真的跑一次 —— 它同时证明了
 * 「200 那条路是坏的」和「429 那条路是必需的」。
 *
 * <h3>★★ 状态码对照表（它只换 HTTP 状态，body 里的 code 一个都不改）</h3>
 *
 * <table border="1">
 *   <tr><th>建 emitter 前判定</th><th>code</th><th>HTTP</th><th>用户看到</th></tr>
 *   <tr><td>额度用完</td><td>5004</td><td><b>429</b></td><td>今日 AI 额度已用完</td></tr>
 *   <tr><td>功能未启用 / 没配 Key / 模板缺失</td><td>5001 / 5002 / 5005</td><td><b>503</b></td>
 *       <td>指向<b>管理员</b>的三句不同的话</td></tr>
 *   <tr><td>必填素材缺失 / 渲染超长 / id 自相矛盾</td><td>400</td><td><b>400</b></td>
 *       <td>指名道姓说缺什么（作者自己能修好）</td></tr>
 *   <tr><td>动了别人的作品 / 章节 / 节点</td><td>2002 / 3002 / 3007</td><td><b>403</b></td>
 *       <td>「这不是你的作品」</td></tr>
 *   <tr><td>那些东西根本不存在</td><td>2001 / 3001 / 3006</td><td><b>404</b></td><td>同上</td></tr>
 * </table>
 *
 * <p>★★ <b>为什么 403/404 那两行非做不可：</b>{@code AiContentLookup} <b>刻意</b>给
 * 「不是你的」返 {@code 200 + 2002}（它的类注释论证过：那是防探测，不该在状态码上
 * 区分「不存在」和「不属于你」）。那条设计在 JSON 接口上是对的 ——
 * 而在 SSE 端点上，200 会被渲染成「请检查代理配置」。
 * ★ 所以这里<b>只提级 HTTP 状态码，code 与文案原样保留</b>
 * （用 {@link BusinessException#withStatus}）：防探测的性质没变，
 * 变的只是「这条响应在协议层诚不诚实」。
 *
 * <h3>★★ 为什么两半闸门的适用范围不同</h3>
 *
 * <p><b>「停用 / 没配 Key」只在【会花钱】的实现下才拦。</b>
 * 理由与「默认是谁」无关：mock 是纯本地的零成本实现，对它要求 Key、扣配额没有意义。
 *
 * <p>★★ 但这句话的【实际后果】随默认值一起翻转了，值得记一笔：默认是 mock 时（E3 之前），
 * 这条闸门配上种子里的 {@code ai_config.enabled = 0}，在默认配置下<b>根本拦不到人</b> ——
 * 那正是铁律 8 说的「一条不会失败的护栏」。现在 provider 默认是 deepseek
 * （见 {@code application.yml}），它<b>默认就在拦</b>，这才是它被设计出来要待的位置。
 * ★ 原来这段还引用过一条「新克隆的仓库不配任何东西就能跑（P4 用 {@code matchIfMissing=true}
 * 换来的）」的不变量 —— 那个兜底已经挪给真实实现，所以这里不再引用它。
 *
 * <p><b>而配额【两个实现都扣】。</b>它是一个「今天还能用多少次」的限流，
 * 与花不花钱无关；只在真实调用时扣的话，这条闸门在<b>默认配置下永远走不到</b> ——
 * 那就是铁律 8 说的「一条不会失败的护栏」：它会安静地腐坏，
 * 而发现它的时机是「第一次接上真模型、发现 429 从来没生效」。
 *
 * @author MickeyChen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiPreflight {

    private final AiConfigService configService;
    private final AiQuotaService quotaService;
    private final AiPromptService promptService;
    private final AiContextAssembler assembler;

    /**
     * YAML {@code novel.ai.provider} —— <b>决定哪个 {@code AiStreamSource} Bean 存在</b>。
     *
     * <p>★ 用字段注入而不是构造器参数，与 {@code AiConfigServiceImpl} 的取法一致
     * （两处读的是同一个配置项）。
     */
    @Value("${novel.ai.provider:deepseek}")
    private String provider;

    /**
     * 跑完整条闸门。<b>必须在请求线程上调用，且必须在创建 emitter 之前。</b>
     *
     * @return 渲染好的提示词（system + user）。★ 调用方要把它交给
     *         {@code AiRequestDTO} 带进工作线程 —— 闸门是唯一生产它的地方，
     *         见那个 DTO 上两个 {@code @JsonIgnore} 字段的注释
     * @throws BusinessException 见类注释那张表。★ 状态码已被本方法替换成真实的
     */
    public RenderedPromptVO check(AiScene scene, AiRequestDTO dto) {
        try {
            return doCheck(scene, dto);
        } catch (BusinessException e) {
            /*
             * ★★★ 唯一的出口翻译：所有业务码在这里统一换成「真实的 HTTP 状态码」。
             *   ★ 只包这一层，因为下面每一个抛点都要翻译 —— 逐个 try 会把
             *     闸门写成五段重复的代码，而漏掉一处的症状是静默的
             *     （那个失败又退回 200 → 变成「请检查代理配置」）。
             */
            throw e.withStatus(httpStatusOf(e.getCode()));
        }
    }

    private RenderedPromptVO doCheck(AiScene scene, AiRequestDTO dto) {
        /*
         * ---------- ① 鉴权 ----------
         * ★ 用 requireUserId()（拿不到就抛 401）而不是 getUserId() + 判空：
         *   /api/ai/** 的角色闸在 ContentSecurityConfig 里，正常情况下来不到这一支。
         *   而真走到了，401 就是准确的答案 —— 不是「随便记一个哨兵桶」
         *   （那会让所有身份异常的请求共用一份额度，见 AiUsageRecorder 的注释）。
         */
        Long userId = UserContext.requireUserId();

        /*
         * ---------- ② 停用 / 没配 Key（只在会花钱时拦） ----------
         * ★ 零成本就能判掉，所以排在模板与渲染【前面】：
         *   功能压根没开的时候，不该为了报一句「渲染超长」去查三次库。
         */
        int dailyLimit = metered()
                ? configService.requireUsable().dailyLimit()
                : configService.dailyLimit();

        /* ---------- ③ 模板 ---------- */
        AiPromptTemplate template = promptService.requireForScene(scene);

        /* ---------- ④ 组装（★ 过权限校验）+ 渲染 ---------- */
        Map<AiPlaceholder, String> varValues = assembler.assemble(scene, dto);
        RenderedPromptVO rendered = PromptRenderer.render(
                scene, template.getSystemPrompt(), template.getUserTemplate(), varValues);

        /*
         * ---------- ⑤ 配额 ----------
         * ★★ 必须在渲染【之后】才扣：模板缺失、必填缺值、渲染超长都会在
         *   ④ 抛出去，用户什么都没拿到却白掉一次额度的形状是这个项目一直在防的
         *   （同 P5「被跳过的条目必须报出来」的取向：失败不能有隐性代价）。
         * ★ 而它必须在渲染【之后、建 emitter 之前】—— 建了 emitter 就只能发错误帧了。
         */
        quotaService.acquire(userId, dailyLimit);

        logRendered(scene, dto, rendered);
        return rendered;
    }

    /**
     * 这次调用会不会花钱 —— <b>全项目唯一一处判断「哪个 provider 在跑」的地方</b>。
     *
     * <p>★★ 用 {@code !"mock".equals(provider)}（白名单的写法）而不是
     * {@code "deepseek".equals(provider)}：反过来的话，一个<b>拼错的 provider</b>
     * （{@code "deepsek"}）会落进「不花钱」那一支 —— 而它同时<b>找不到任何
     * {@code AiStreamSource} Bean</b>，服务连启动都起不来。
     * 写成白名单，未知取值一律按「会花钱」处理，方向是 fail-closed。
     */
    private boolean metered() {
        return !"mock".equals(provider);
    }

    /**
     * 把闸门失败的业务码翻译成真实的 HTTP 状态码。
     *
     * <p>★ 默认那一支是<b>有意的</b>：闸门里任何一个没被列出来的业务码
     * （比如 {@code SYSTEM_ERROR(500)}）都会落到那里 —— 在 400~599 段内的
     * 原样透传（{@code UNAUTHORIZED(401)} 走的就是这条），
     * 其余一律 <b>500</b>。
     *
     * <p>★★ <b>为什么「其余一律 500」是对的，而不是「没列出来就返 200 吧」：</b>
     * 这条链上的每一个失败都发生在 SSE 开始之前，也就是说
     * <b>返 200 等于允许前端把它渲染成「请检查代理配置」</b>。
     * 一个我们没预料到的错误，宁可在监控上是一个响亮的 5xx，
     * 也不要变成一句指着别的系统的假话。
     */
    private static int httpStatusOf(Integer code) {
        if (code == null) {
            return 500;
        }
        return switch (code) {
            // 功能未启用 / 没配 Key / 该场景没模板：都是「服务端没准备好」，重试无用，等管理员
            case 5001, 5002, 5005 -> 503;
            case 5004 -> 429;
            // ★ AiContentLookup 的「不是你的」与「不存在」：200/300 段，见类注释
            case 2002, 3002, 3007 -> 403;
            case 2001, 3001, 3006 -> 404;
            default -> (code >= 400 && code <= 599) ? code : 500;
        };
    }

    /**
     * 把<b>这次真正发给模型的那段话</b>记下来。
     *
     * <h4>★★★ 为什么这条日志是 S6 的验收载体</h4>
     *
     * <p>「服务端拼装了三段内容」这件事，在没有真实 Key 的时候本来是<b>验不了</b>的 ——
     * 唯一的证据在模型的输入侧，而我们看不到。把它打进日志之后，
     * <b>{@code provider=mock} 下一次调用就能 100% 验证整条渲染链，花费 0</b>。
     *
     * <h4>★★ 为什么在 mock 模式下是 INFO，在真实模型下是 DEBUG</h4>
     *
     * <p>这段文本<b>含作者自己的稿件正文</b>。mock 模式下它不出这台机器，
     * 而它是唯一能看见拼装结果的窗口 —— 值得一条 INFO。
     * 真实模型下每一次调用都会把同样的文本发给上游，日志里再存一份
     * 就把「作者的原稿」变成了一个<b>长期留存、会被备份和转发</b>的副本，
     * 而那并不是排查所必需的。
     * ★ 两档都保留、由 provider 决定，比「统一打到 INFO」或「干脆不打」都好：
     * 前者的代价是长期的内容留存，后者会让这条链重新变得不可验证。
     */
    private void logRendered(AiScene scene, AiRequestDTO dto, RenderedPromptVO rendered) {
        boolean verbose = !metered();
        if (!verbose && !log.isDebugEnabled()) {
            return;
        }
        /*
         * ★ 三个数字一起记：总长、以及「哪些变量无值、整行被移除」。
         *   后者是判断 2.2 那条【静默行为】的唯一痕迹 ——
         *   它既不是错误，又在文本里什么都不剩（见 RenderedPromptVO 的类注释）。
         */
        String detail = """
                [AI 提示词] scene={}({}) | user={} | 总长={}/{} | 移除={}
                ----- system -----
                {}
                ----- user -----
                {}""";
        Object[] args = {scene.getCode(), scene.getLabel(), UserContext.getUserId(),
                rendered.getTotalLength(), rendered.getMaxLength(), rendered.getRemovedVars(),
                rendered.getSystemPrompt(), rendered.getUserMessage()};
        if (verbose) {
            log.info(detail, args);
        } else {
            log.debug(detail, args);
        }
        // ★ 单独留一句「上游会看到哪几个变量」：只在需要翻的时候有用，且不含正文
        log.debug("AI 提示词变量 | scene={} | 有值={} | novelId={} | nodeId={} | chapterId={}",
                scene.getCode(), rendered.getUsedVars(),
                dto.getNovelId(), dto.getNodeId(), dto.getChapterId());
    }
}
