package com.novel.modules.ai.controller;

import com.novel.common.result.Result;
import com.novel.common.security.LoginUser;
import com.novel.common.security.UserContext;
import com.novel.modules.ai.dto.AiRequestDTO;
import com.novel.modules.ai.dto.AiSummaryGenerateDTO;
import com.novel.modules.ai.enums.AiScene;
import com.novel.modules.ai.service.AiConfigService;
import com.novel.modules.ai.service.AiPreflight;
import com.novel.modules.ai.service.AiQuotaService;
import com.novel.modules.ai.service.AiStreamSource;
import com.novel.modules.ai.service.AiSummaryService;
import com.novel.modules.ai.vo.AiQuotaVO;
import com.novel.modules.ai.vo.AiSummaryVO;
import com.novel.modules.ai.vo.RenderedPromptVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 写作助手（SSE 流式）。
 *
 * <p><b>★★ /api/ai/** 的角色闸写在 {@code SecurityConfig} 里，不在这里。</b>
 * 这个前缀<b>不在 {@code /api/author/**} 下面</b>，所以那条规则盖不住它 ——
 * 漏掉的话，普通读者 READER 也能调这些接口。AI 是要烧配额的
 * （E1 接了真实模型之后是直接烧钱），而漏掉<b>不会有任何报错</b>，
 * 直到有人把额度刷完。P4 唯一一处动 {@code SecurityConfig} 的地方就是它。
 *
 * <p><b>★★ 这个类做的事情只有一件：把「生成器的回调」翻译成 SSE 帧。</b>
 * 它不知道内容是真的还是假的（那是 {@link AiStreamSource} 实现的事），
 * 也不知道用户点了什么按钮（URL 决定场景）。三层各管一件事。
 *
 * <p><b>SSE 的帧格式（前端 {@code utils/sse.js} 按这个解析）：</b>
 * <pre>
 *   data:我                                    ← 每个增量一帧，是【增量】不是全量
 *   data:们
 *   data:[DONE]                                ← 正常结束的哨兵
 *   event:error
 *   data:生成失败，请重试                        ← 中途失败的帧
 * </pre>
 *
 * <p><b>★ 为什么用 {@code SseEmitter} 而不是手写 {@code response.getWriter()}：</b>
 * 一是它自带「连接关闭 / 超时」的回调（这是中断能被感知的前提）；
 * 二是<b>它替我们处理了换行</b> —— SSE 规范要求 data 字段里的每个换行
 * 都要另起一行并重新加 {@code data:} 前缀，而大纲场景返回的是
 * <b>带缩进的 JSON，里面全是换行</b>。手写的话这里就是一个静默的格式 bug：
 * 前端收到的 JSON 少了几行，而且只在「恰好有换行的时候」出错。
 *
 * @author MickeyChen
 */
@Slf4j
@Tag(name = "18. AI 写作助手", description = "流式生成大纲 / 续写 / 灵感 / 润色。★ 需要 AUTHOR 或 ADMIN 角色")
@RestController
public class AiController {

    /**
     * SSE 连接的超时时间。
     *
     * <p><b>★ 必须显式设置。</b>Spring MVC 的默认异步超时是
     * {@code spring.mvc.async.request-timeout}，没配时用的是容器的默认值
     * （Tomcat 是 30 秒）。一段 500 字的大纲按每字 15ms 推，要 7~8 秒，
     * 30 秒看起来够 —— 但那是<b>本地</b>的估算，E1 接上真实模型后
     * 「首 token 时间」就有几秒，加上模型自己吐字的速度不受我们控制，
     * 超时是会真的发生的。
     *
     * <p>超时发生的表现值得记住：{@code onTimeout} 触发 → emitter 被完成 →
     * <b>前端看到流就那么断了，没有 {@code [DONE]}，也没有错误帧。</b>
     * 所以前端必须把「流结束但没有 [DONE]」当成失败处理
     * （见 {@code utils/sse.js} 里的判断），否则就是永远转圈。
     * 这里把它设长一点，是为了让默认配置下不会撞到它。
     */
    private static final long SSE_TIMEOUT_MS = 3 * 60 * 1000L;

    /**
     * 结束哨兵。
     *
     * <p>★ 用 OpenAI 兼容协议里的 {@code [DONE]} 而不是自定义一个结束事件：
     * E1 接上 DeepSeek（OpenAI 兼容接口）时，它的流也用 {@code data: [DONE]} 结尾，
     * <b>前端一个字都不用改</b>。自定义哨兵的话，那天就得同时改前后端，
     * 而「改了后端忘了改前端」的症状正好是「流永远不结束」。
     */
    private static final String DONE_SENTINEL = "[DONE]";

    /** 给用户看的失败提示。★ 不用上游的异常信息，见 {@code AiStreamSource.Handler#onError} */
    private static final String ERROR_MESSAGE = "生成失败，请稍后重试";

    private final AiStreamSource aiStreamSource;
    private final ThreadPoolTaskExecutor aiStreamExecutor;
    private final AiConfigService aiConfigService;
    private final AiQuotaService aiQuotaService;
    private final AiPreflight aiPreflight;
    /**
     * 前情摘要的写侧（E1/S10）。
     *
     * <p>★★ 它是本类唯一一个<b>不返回 {@code SseEmitter}</b> 的协作者的入口 ——
     * 也是 {@link AiScene} 那条不变式（枚举值个数 = {@code @PostMapping} 个数 + 1）
     * 在这条链上的落点：{@code SUMMARY} 是六个场景里唯一一个「有 URL 但不是流式」的。
     */
    private final AiSummaryService aiSummaryService;
    private final String provider;

    public AiController(AiStreamSource aiStreamSource,
                        @Qualifier("aiStreamExecutor") ThreadPoolTaskExecutor aiStreamExecutor,
                        AiConfigService aiConfigService,
                        AiQuotaService aiQuotaService,
                        AiPreflight aiPreflight,
                        AiSummaryService aiSummaryService,
                        @Value("${novel.ai.provider:deepseek}") String provider) {
        this.aiStreamSource = aiStreamSource;
        this.aiStreamExecutor = aiStreamExecutor;
        this.aiConfigService = aiConfigService;
        this.aiQuotaService = aiQuotaService;
        this.aiPreflight = aiPreflight;
        this.aiSummaryService = aiSummaryService;
        this.provider = provider;
    }

    /* ==================== 四个流式接口 ==================== */

    @Operation(
            summary = "生成大纲（SSE 流式）",
            description = """
                    **返回 `text/event-stream`**，逐字推送，以 `data: [DONE]` 结束。

                    ★ 产出是**严格合法的 JSON 树**（带缩进）：
                    ```json
                    {"title": "…", "children": [{"type": 3, "title": "…", "content": "…"}]}
                    ```
                    里面的 `type` **就是**大纲节点的类型码（3=章 4=细纲），
                    前端「采纳」时可以直接填进新建节点的请求里。

                    ★ 前端要**边收边解析**：不要等流结束才 `JSON.parse`
                    （那等于放弃了流式的意义）。收到 `[DONE]` 后再整体解析一次校验。

                    ★ 前端**必须处理两件事**：
                    - 流结束但**没有** `[DONE]` → 当成失败（超时/断开）
                    - `event: error` 帧 → 显示「生成失败」

                    ★ **`prompt` 里含 `__MOCK_FAIL__` 会在生成到三分之一处
                    模拟失败** —— 这是演示模式下的测试钩子，用来验证上面的错误分支。""")
    @PostMapping(value = "/api/ai/outline", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter outline(@Valid @RequestBody AiRequestDTO dto) {
        return stream(AiScene.OUTLINE, dto);
    }

    @Operation(
            summary = "续写正文（SSE 流式）",
            description = """
                    逐字推送一段散文，以 `data: [DONE]` 结束。

                    ★ 这是**纯文本**不是 JSON，前端直接追加到编辑器光标处即可
                    （对应大纲 §8.2 的「AI 生成的内容不直接入库，点采纳才插入」）。

                    ★ `context` 传当前章节已有的正文，`prompt` 传写作要求。""")
    @PostMapping(value = "/api/ai/write", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter write(@Valid @RequestBody AiRequestDTO dto) {
        return stream(AiScene.WRITE, dto);
    }

    @Operation(
            summary = "提供灵感（SSE 流式）",
            description = """
                    逐字推送 **3 个剧情走向**，每段以 `【走向N · 类型】` 开头。

                    ★ 前端按这个前缀切分成三块展示，不要自己按行数猜 ——
                    走向的正文里本来就有换行。""")
    @PostMapping(value = "/api/ai/inspire", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter inspire(@Valid @RequestBody AiRequestDTO dto) {
        return stream(AiScene.INSPIRE, dto);
    }

    @Operation(
            summary = "润色段落（SSE 流式）",
            description = """
                    逐字推送改写后的段落。

                    ★ `prompt` 传**选中的那段正文**，前端拿到结果后替换选区。
                    **原文要自己留着**（用于「撤销」）—— 服务端不存历史版本。""")
    @PostMapping(value = "/api/ai/polish", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter polish(@Valid @RequestBody AiRequestDTO dto) {
        return stream(AiScene.POLISH, dto);
    }

    /* ==================== 前情摘要（★ 唯一一个不是流式的场景） ==================== */

    @Operation(
            summary = "生成前情摘要（★ 不是 SSE，返回普通 JSON）",
            description = """
                    把 `ai_summary` 里【还没覆盖到】的那一段正文压缩成一段剧情梗概，
                    **生成完直接入库**，从下一章起 `{{summary}}` 就会带上它。

                    ★ **它和上面四个接口的区别不是「是不是 AI 生成」，而是「产出有没有消费者在实时看」** ——
                    摘要生成完就落库了，没有「边生成边看」的界面，也没有「中断」这个动作。

                    ★ `endChapterNo` 是**范围的上界（含）**，起点由服务端算
                    （上一条摘要的 `end_chapter_no` + 1）。它必须**大于**上一条摘要覆盖的章节号，
                    否则 400 —— 摘要只能一段段往前接。

                    ★★ 返回里的 `startChapterNo` / `endChapterNo` 是**实际覆盖**的区间，
                    可能小于请求的区间（尾部几章还没写正文），
                    `skippedChapterNos` 列出被跳过的章节号 —— **不要只看 `summary` 不看这三个字段**。

                    ★ 失败时返回**真实的 HTTP 状态码**（400 区间不合法 / 超预算，
                    429 额度用完，503 未启用 / 没配 Key），不是 200 + 业务码 ——
                    理由见 `AiPreflight` 的类注释。

                    ★★ **本接口会阻塞整整一次模型调用**（真实模型下可能几秒到几十秒）。
                    接真实模型之前请先看 `AiSummaryService` 类注释里那段「为什么是同步的」。""")
    @PostMapping("/api/ai/summary")
    public Result<AiSummaryVO> summary(@Valid @RequestBody AiSummaryGenerateDTO dto) {
        return Result.success(aiSummaryService.generate(dto));
    }

    /* ==================== 额度 ==================== */

    @Operation(
            summary = "今日剩余额度",
            description = """
                    返回剩余次数、总额度，以及**当前生效的实现**。

                    ★ `provider` 字段决定前端要不要显示「**演示模式**」标签：
                    `mock` = 内容由本地生成，不是真实模型。

                    ★ 这个字段**不由前端写死**是刻意的 —— E1 换成真实模型后
                    标签会自动消失，不需要有人记得去改前端。
                    **靠人记得摘掉的标识，一定会忘记摘**，
                    而一个没摘掉的假标识，和没有标识一样是诚信问题。

                    ★ E1 起 `remaining` 与 `total` 是**真的**：`total` 取自
                    `ai_config.daily_limit`（管理端可改），`remaining` 取自
                    Redis 计数器，**会随调用递减、次日自动满血**。
                    P4 那两个字段是同一个常量（恒等），E1 换掉了它们。

                    ★★ 本接口**在 AI 停用时也正常返回 200**（不抛 5001）——
                    它回答的是「上限是多少」，不是「能不能用」。""")
    @GetMapping("/api/ai/quota")
    public Result<AiQuotaVO> quota() {
        /*
         * ★★ 这里【不能】调 AiConfigService#requireUsable()。
         *   它会在 ai_config.enabled=0 时抛 5001 —— 而种子里的默认值就是 0，
         *   也就是「新克隆的仓库」的默认状态。抛出去的话，
         *   前端 AiPanel 一打开就会弹一个错误提示、并且把整块额度区 v-if 掉，
         *   表现为「额度那一块凭空消失」。
         *   ★ dailyLimit() 是那个「停用也能问」的读方法，见它的接口注释。
         */
        int total = aiConfigService.dailyLimit();

        LoginUser loginUser = UserContext.get();
        Long userId = loginUser == null ? null : loginUser.getUserId();
        /*
         * ★ 身份拿不到就按「一次都没用过」展示，而不是 0。
         *   走这条路径的只可能是过滤器没设身份的 bug，而额度是【给本人看的】
         *   一个展示数字；在这里编一个 0 出去，用户会以为自己的额度没了。
         *   ★ 与本行的「按满额展示」是同一件事的两面：**都不虚报消耗**。
         *
         * ★★ E1/S6 订正：本行原来接的是「真正的扣减闸门
         *   （AiQuotaService#acquire）在 AI 生成那侧，它拿不到身份时记一条 ERROR
         *   并落到哨兵桶 userId=0 上」—— <b>那句话现在不成立了，因为那个哨兵桶已经删掉。</b>
         *   S6 起 acquire 由 {@code AiPreflight} 在【请求线程】上调用，
         *   而它的第一步就是 {@code UserContext.requireUserId()}（拿不到直接 401）。
         *   ★ 也就是说：扣减那一刻身份【必然存在】，不存在就走不到扣减那一步。
         *   「往共享桶里记一笔」这种口径再也没有存在的理由 ——
         *   它本来就是个坏形状：所有身份异常的请求会合吃一份额度，
         *   而账面上看不出是几个人在用。
         */
        int remaining = userId == null ? total : aiQuotaService.remaining(userId, total);

        return Result.success(new AiQuotaVO(remaining, total, provider));
    }

    /* ==================== 私有：SSE 翻译层 ==================== */

    /**
     * 把一次生成翻译成一个 SSE 连接。
     *
     * <p><b>★★ 为什么必须换线程（本方法里最关键的一行是
     * {@code aiStreamExecutor.execute}）：</b>
     * <ul>
     *   <li>{@code SseEmitter} 的用法是「Controller <b>返回</b> emitter，
     *       Spring 拿它去接管这个响应」，返回之后才有 SSE 响应头被写出去。
     *       在方法里同步生成的话，{@code return} 发生在生成<b>之后</b> ——
     *       那时候响应还没被 Spring 接管，{@code send()} 会抛
     *       {@code IllegalStateException}，或者更糟：先攒满缓冲区再一次性吐出去，
     *       <b>流式效果完全没有</b>，而接口调用看起来「成功」了</li>
     *   <li>即使能在返回前发，那也意味着生成期间 Tomcat 的工作线程被占着 ——
     *       见 {@code AiAsyncConfig} 里那段「为什么必须另起一个池」</li>
     * </ul>
     *
     * <p><b>★★ 中断是怎么实现的（三条路径）：</b>
     * ★ <b>注意：这个标题 E1/S4 之前写的是「缺一不可」，那是错的</b> ——
     * 实测表明真正承重的是 ③（见下面的订正）。三条都留着，但别以为少一条就断不了。
     * <pre>
     *   ① 用户点「停止」/ 切节点 / 关页面
     *      → 前端 controller.abort() → TCP 连接断开
     *      → 服务端 emitter 的 onError/onCompletion 回调触发 → aborted = true
     *      → 下一次 onChunk 发现 aborted → 抛异常 → 生成循环被冲掉
     *   ② 超时（SSE_TIMEOUT_MS）→ onTimeout 回调 → 同上
     *   ③ 写失败（连接已经没了）→ send() 自己抛异常 → 同样冲掉循环
     * </pre>
     * ★ ③ 是「兜不住的那一层」：连接断开后数据可能先进了内核缓冲区，
     * 第一次 {@code send()} 不一定马上失败。而期间生成器还在跑 ——
     * 对 mock 无所谓，对 E1 就是白烧的 token。
     *
     * <p>★★★ <b>E1/S4 实测订正（原话写的是「不能只靠 ③，① 才是必须的」）：</b>
     * 在那次实验里，<b>真正让生成停下来的是 ③，而 ① 从头到尾没有被触发</b> ——
     * 探针打出的事实是「{@code send()} 失败的那一刻 {@code aborted=false}」，
     * 也就是说容器（Tomcat 经网关收到客户端断开）<b>还没</b>来得及回调
     * {@code onError/onCompletion}，写操作就已经失败了。
     * ★ 同一份实测里「上游停止推送」是真的（假端点在第 11 帧看到连接断开、
     * 没有跑到终点），<b>所以钱确实没白烧</b>；被推翻的只是「这该记在 ① 头上」。
     *
     * <p>★ ① 仍然保留：它零成本，而且在「缓冲区还没写满、容器尚未察觉」
     * 那种形状下（帧小、量大、推送密）是唯一能让停止提前生效的东西。
     * ★★ <b>但别再把「停止烧 token」归功于它</b> —— 一条被实测削弱的论证
     * 如果继续按原样留着，下一个人会照着它去改别的地方。
     * （做法同 M3 那条：实测推翻了初稿的断言，就把断言改成实测的样子。）
     *
     * <p><b>★★★ E1/S6：本方法的【第一行】是前置闸门，它改变了整个类的错误模型。</b>
     * 闸门抛出的异常会让这个方法<b>根本不返回 emitter</b>，于是响应体是普通 JSON、
     * HTTP 状态码是真实的 —— 这是刻意的，见 {@link AiPreflight} 的类注释
     * （走错出口的症状是「额度用完」被渲染成「请检查代理配置」）。
     * ★ <b>所以本方法里可以抛异常，下面的工作线程里不行</b> ——
     * 那条分界线不是风格，是「响应头有没有发出去」。
     */
    private SseEmitter stream(AiScene scene, AiRequestDTO dto) {
        /*
         * ★★★ 前置闸门：鉴权 / 停用 / 模板 / 组装+渲染 / 配额，全在【当前线程】上、
         *   全在【建 emitter 之前】。它失败时本方法抛出去，HTTP 状态码是真的。
         *
         * ★★ 它必须在创建 SseEmitter 之前 —— 一旦建了 emitter 并被 Spring 接管，
         *   响应头就发出去了，那时候只剩「发一个 event: error 帧」这一条路，
         *   而前端对那条路和「非 2xx」路的渲染【完全不同】。
         *
         * ★★ 它必须仍在请求线程上（不是搬到下面那个 executor 里）——
         *   它要过 NovelAccessGuard 的权限校验，而工作线程上没有身份。
         *   详见 AiContextAssembler 的类注释：那里的失败模式是
         *   「忘了搬身份 → 权限判成匿名 → 恰好全部放行」，且不报错。
         */
        RenderedPromptVO rendered = aiPreflight.check(scene, dto);
        /*
         * ★ 渲染结果就存在这个 DTO 上带进工作线程 —— 它是唯一的载体
         *   （两个字段都是 @JsonIgnore，客户端塞不进来，见 AiRequestDTO）。
         *   闸门是唯一生产它的地方，所以这里的赋值不可能漏。
         */
        dto.setRenderedSystemPrompt(rendered.getSystemPrompt());
        dto.setRenderedUserMessage(rendered.getUserMessage());

        /*
         * ★★ 用户身份必须在【当前线程】取好，再传给下面那些 lambda。
         *
         *   UserContext 是 ThreadLocal（由 JwtAuthenticationFilter 在【请求线程】上设的），
         *   而生成逻辑和所有回调都跑在 ai-stream-N 线程上 —— 那里没有请求上下文，
         *   UserContext.getUsername() 直接返回 null。
         *
         * ★ 这个 bug 的症状值得记住：生成一切正常、内容也对，只有日志里
         *   所有 AI 调用都是 user=null。等你真的要查「谁把额度刷完了」的时候，
         *   一条都用不上 —— 而那时候再去补日志，前面几天的记录已经没了。
         *
         * ★ 尤其注意 onTimeout 那个回调：它跑在【容器的另一个线程】上，
         *   连「请求线程」都不是。所以这个变量必须在注册任何回调【之前】取。
         *
         * ★ 这里取的是整个 LoginUser 而不是只有用户名，因为下面要把身份
         *   【传给工作线程】（见 aiStreamExecutor.execute 里的 UserContext.set）。
         */
        LoginUser loginUser = UserContext.get();
        String username = loginUser == null ? null : loginUser.getUsername();

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AtomicBoolean aborted = new AtomicBoolean(false);

        emitter.onCompletion(() -> aborted.set(true));
        emitter.onTimeout(() -> {
            aborted.set(true);
            log.info("AI 流超时 | scene={} | user={}", scene.getCode(), username);
        });
        emitter.onError(e -> aborted.set(true));

        aiStreamExecutor.execute(() -> {
            /*
             * ★★ 把身份【搬到工作线程上】。
             *
             *   ThreadLocal 是「每个线程一份」，不是「跟着任务走」的 ——
             *   请求线程上设的那个值和 ai-stream-N 线程毫无关系。
             *   不搬的话，AiStreamSource 的实现（现在的 mock、E1 的真实模型）
             *   里读 UserContext 全是 null：日志记不到人、配额算不到人头上。
             *
             * ★★★ 搬过来就必须【清掉】，见下面的 finally。
             *
             *   这是 UserContext 类注释里那个坑的【第二个入口】——
             *   第一个是 Tomcat 的请求线程池，那个已经由 JwtAuthenticationFilter
             *   的 finally 兜住了。但 aiStreamExecutor 是【我们自己建的池】，
             *   没人替我们兜：
             *     作者 A 的生成任务跑完 → 线程带着 A 的身份回到池里
             *     → 下一个任务（作者 B 的）直接读到 A 的 LoginUser
             *   而 AI 将来是要按账号算配额的，这个「串号」的后果是
             *   【A 的额度被算到 B 头上】。更糟的是它只在
             *   「同一个池线程连续处理两个不同用户」时出现 ——
             *   本地单账号调试永远碰不到。
             */
            UserContext.set(loginUser);
            try {
                aiStreamSource.stream(scene, dto, new AiStreamSource.Handler() {

                    @Override
                    public void onChunk(String text) {
                        if (aborted.get()) {
                            /*
                             * ★ 抛异常而不是 return：return 只是跳过这一帧，
                             *   生成器会继续跑完剩下的几百次循环。
                             *   必须让异常一路冲出去，把那个 for 循环打断。
                             */
                            throw new StreamAbortedException();
                        }
                        send(emitter, text);
                    }

                    @Override
                    public void onComplete() {
                        /*
                         * ★ 这里【不能】用会抛异常的 send()：如果连接已经断了，
                         *   抛出去会让下面的 emitter.complete() 永远不执行，
                         *   那个异步请求就挂在那儿直到超时（3 分钟）才被回收。
                         *   发不出 [DONE] 无所谓（前端已经断开），
                         *   但 emitter 必须被关闭。
                         */
                        try {
                            emitter.send(SseEmitter.event().data(DONE_SENTINEL));
                        } catch (Exception ignored) {
                            // 连接没了，这一帧本来就没人收
                        }
                        emitter.complete();
                    }

                    @Override
                    public void onError(Throwable cause) {
                        log.error("AI 生成失败 | scene={} | user={}", scene.getCode(), username, cause);
                        sendErrorFrame(emitter);
                    }
                });
            } catch (StreamAbortedException e) {
                /*
                 * 用户主动中断 / 超时 —— 这是【正常路径】，不是错误。
                 * ★ 什么都不做：emitter 已经结束了（超时）或者连接已经没了，
                 *   再调 complete()/completeWithError() 只会多打一条
                 *   「ResponseBodyEmitter has already completed」的噪音日志。
                 */
                log.info("AI 流被中断 | scene={} | user={}", scene.getCode(), username);
            } catch (Exception e) {
                /*
                 * 意料之外的异常（生成器里的 bug、序列化失败……）。
                 * ★ 这里【不】调用 emitter.completeWithError(e)：
                 *   流已经开始之后响应头早就发出去了，改不了 HTTP 状态码，
                 *   它只会让 Spring 记一条「响应已提交」的警告。
                 *   发一个错误帧才是前端真正能收到的东西。
                 */
                log.error("AI 流异常 | scene={} | user={}", scene.getCode(), username, e);
                sendErrorFrame(emitter);
            } finally {
                /*
                 * ★★★ 这一行是【必须】的，不是收尾洁癖。
                 *
                 *   ai-stream-N 是池里的线程，任务结束后【不会销毁】，
                 *   它带着这次请求的身份回到池里等下一个人用。
                 *   不清的话，下一个用这个线程的作者就会读到上一个人的 LoginUser ——
                 *   也就是 UserContext 类注释里说的那个「张三登录着，
                 *   李四的请求却以张三的身份执行」。
                 *
                 *   ★ 这里放在 finally 而不是各自的 catch 里：上面有两条 catch
                 *     外加「正常跑完」一共三条出口，而且随时可能再加一条。
                 *     写在 finally 里，出口再多也只清一次。
                 */
                UserContext.clear();
            }
        });

        return emitter;
    }

    /**
     * 往 SSE 连接写一帧，<b>失败时统一翻译成「这个流不要了」</b>。
     *
     * <p><b>★ 为什么要这个方法：</b>{@code SseEmitter.send()} 抛的是<b>受检的</b>
     * {@code IOException}，而 {@code AiStreamSource.Handler} 的三个方法签名上
     * 没有 {@code throws} —— 直接写编译就不过。
     * <ul>
     *   <li>给 Handler 加上 {@code throws IOException}？那等于<b>让 AI 模块
     *       认识 SSE / 认识 IO</b>，接口会被一个它不该关心的细节污染，
     *       E1 的真实实现（走 HTTP 客户端，不是一个 IOException 语义）也得跟着改</li>
     *   <li>所以在这里收口：把「写不出去」翻译成 {@link StreamAbortedException}，
     *       它是个纯粹的运行时信号，正好符合「把生成循环冲掉」的语义</li>
     * </ul>
     *
     * <p>★ 两种失败合成一种处理，是刻意的：
     * <pre>
     *   IOException          → 连接断了（用户中断 / 网络问题）
     *   IllegalStateException → emitter 已经 complete（超时了还在推）
     * </pre>
     * 两者的正确反应是同一件事：<b>停止生成</b>。而且都不是「生成失败」，
     * 不该走错误帧那条路、更不该记 error 日志 —— 用户每点一次「停止」
     * 都在日志里留一条错误堆栈的话，真正的故障就找不出来了。
     */
    private static void send(SseEmitter emitter, Object data) {
        try {
            emitter.send(data);
        } catch (Exception e) {
            throw new StreamAbortedException();
        }
    }

    /**
     * 发一个错误帧并关闭。
     *
     * <p>★ 用 {@code event: error} 而不是靠「连接断了」让前端自己猜：
     * 断了之后前端只知道「没收到 [DONE]」，分不清是超时、是服务端崩了、
     * 还是自己网络的问题。有一个显式的事件，提示语才能是准确的。
     *
     * <p>★ 整个方法吞掉异常：能走到这里说明流已经出问题了，
     * 再因为「发不出错误帧」抛异常，只会在日志里多一条毫无信息量的堆栈。
     */
    private void sendErrorFrame(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("error").data(ERROR_MESSAGE));
        } catch (Exception ignored) {
            // 连接已经没了，前端会通过「流结束但没有 [DONE]」感知到失败
        }
        emitter.complete();
    }

    /**
     * 内部信号：<b>「这个流已经不需要了」</b>。
     *
     * <p>★ 为什么要自定义一个异常类型而不是直接用 {@code RuntimeException}：
     * 它和「生成器真的出错了」是<b>相反</b>的两件事 ——
     * 前者要静默收场，后者要记 error 日志 + 发错误帧。
     * 用同一个类型的话，用户每点一次「停止」都会在日志里留一条 error 堆栈，
     * 真正的故障会被这些噪音淹掉。
     *
     * <p>★ 不填堆栈（{@code fillInStackTrace} 什么都不做）：这是一个纯粹的控制流信号，
     * 一天可能产生几万次，每次填栈都要遍历调用栈，而那个堆栈从来没人看。
     * 和 {@code BusinessException} 是同一个理由。
     */
    private static final class StreamAbortedException extends RuntimeException {
        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }
}
