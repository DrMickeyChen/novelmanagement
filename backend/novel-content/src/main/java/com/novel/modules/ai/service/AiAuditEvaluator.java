package com.novel.modules.ai.service;

/**
 * 「把一份待审文本交给模型判一次，并拿到一条【能用的】判定」—— E1/S8 的第二层。
 *
 * <h3>★★ 为什么它是一个【接口】—— 而这不是「别人都这么写」</h3>
 *
 * <p>{@code AiUsageRecorder} 的类注释里立过一条规矩：
 * <blockquote>「有第二个实现」才是接口的理由，「别人都这么写」不是。</blockquote>
 * 这里<b>确实有两个实现</b>，而且它们的选择方式是<b>结构性的</b>：
 * {@code @ConditionalOnProperty(name = "novel.ai.provider", ...)} 决定哪个 Bean 存在 ——
 * 与 {@link AiStreamSource} 完全同一套开关。
 * ★ 顺着那条规矩：<b>本接口上不应再有第三个方法</b>。
 * 「这次调用花不花钱」由实现自己处理（mock 忽略配置，DeepSeek 实现自己去
 * {@code requireUsable()}），调用方一行特判都不写 ——
 * 多一个 {@code metered()} 就是在两个地方共同维护同一个判据。
 *
 * <h3>★★ 它【不是】{@link AiStreamSource} 的第二个实现 —— E1 的订正 5</h3>
 *
 * <p>大纲 §8.2 说「接真实模型只加一个类」。流式那条成立
 * （P4 的接缝本来就是为它设计的），<b>但审核是第二个实现</b>，理由：
 * <ul>
 *   <li>{@code AiStreamSource.Handler} 的契约（恰好一次终结、必须可被打断）
 *       <b>是为 SSE 连接服务的</b>，而审核<b>没有连接可断</b> ——
 *       消费者线程调一次、拿一个结论、结束；</li>
 *   <li>流式下 usage 要靠 {@code stream_options.include_usage} 才在末帧出现，
 *       而非流式一次给全（{@code AiModelClient#chat} 的注释写了这条）。</li>
 * </ul>
 * <p>★ 两条路径共用的是更下面那一层（{@code AiModelClient}）—— 那才是真正只写一次的东西。
 *
 * <h3>★★★ 返回 {@link AiAuditVerdict.Parsed} 而不是模型原文 —— 一个关于【账本诚实】的选择</h3>
 *
 * <p>「解析」这一步<b>故意放在实现类里</b>，而不是留给调用方。判据是：
 * <blockquote>账本 {@code ai_usage_log} 里那一行 {@code success=1} 应该读作
 * <b>「这次调用换来了一条能用的判定」</b>，而不是「HTTP 通了」。</blockquote>
 *
 * <p>如果解析留在调用方，就会出现这一格：<b>上游 200、token 照烧、模型返回了一段
 * 解析不出判定的文字</b>（围栏、废话、JSON 截断）。那一刻调用方手里只有一个
 * 已经写完的「成功」行 —— 而管理员在用量页上看到的是「花了一笔钱，成功了」，
 * 队列里却什么都没有。<b>钱花了、结论没有</b>这件事<b>在账上必须是失败</b>。
 *
 * <p>★ 所以实现类在 {@code finally} 里按<b>整件事</b>的结局记账：
 * 拿到 {@code Parsed} ⇒ 成功；解析抛异常 ⇒ {@link AiFailure#PARSE_FAILED}；
 * 上游 4xx/5xx ⇒ 那一个具体的上游码。见 {@link AiFailure} 的类注释（错误码指向下一步动作）。
 *
 * <h3>★★ 实现方必须自己记用量账</h3>
 *
 * <p>形状照 {@code DeepSeekAiStreamSource}：<b>实现类在 {@code finally} 里调
 * {@code AiUsageRecorder}</b>。调用方（{@code AiAuditService}）因此不需要知道 token 数 ——
 * 它连 {@code AiRuntimeConfig} 都拿不到（那是实现自己读的）。
 *
 * <p>★★ <b>账本里的 {@code user_id} 是 {@code 0}（{@code UNKNOWN_USER_ID}），
 * 而且这【不是】「身份没搬过来」那个 bug</b>：审核跑在 MQ 消费线程上，
 * 背后<b>根本没有请求</b>（{@code AuditRecordConsumer} 的类注释记过同一件事）。
 * 它是一次<b>系统行为</b>，不是某个作者的消耗 —— 所以也<b>不扣任何人的配额</b>。
 * {@code scene=AUDIT(5)} 这个字段就是用来把「系统花的」和「作者花的」分开的。
 *
 * <p>★★ <b>mock 实现也记一笔</b>（{@code model="mock"}、token 全 0、{@code success=1}）。
 * 这不是「为了对称」：S8 那条<b>「幂等闸门真的挡住了第二次调用」</b>的阳性对照
 * <b>只能靠数调用次数来断言</b>（就地覆盖的 upsert 让「行数」这个判据永远为真，
 * 见 {@code AiAuditResultMapper#overwritePending}），而数调用次数最干净的载体
 * 就是这张表 —— 有了它，<b>那条对照不用花一分钱就能反复跑</b>。
 *
 * @author MickeyChen
 */
public interface AiAuditEvaluator {

    /**
     * 判一次。<b>一次调用 = 一次真实花费</b>（mock 实现除外）。
     *
     * @param systemPrompt 闸门渲染好的 system 消息（来自 {@code ai_prompt_template}）
     * @param userMessage  渲染好的 user 消息 —— <b>待审正文就在这里面</b>
     * @param novelId      ★ 只用于记账，可为 {@code null}（评论审核拿不到作品号）。
     *                     判定本身<b>不允许</b>用到它：审核的判据只能是正文本身，
     *                     见 {@code AiVarResolver} 里 AUDIT 那一行
     * @param chapterId    ★ 同上，只用于记账。评论审核传 {@code null}
     * @return 解析好的判定。<b>{@code riskLevel} 已经夹在 {@code 0..3}</b>
     * @throws AiAuditVerdict.UnparsableException
     *         上游答了、钱花了，但那段答复里没有一条能用的判定。
     *         ★ 调用方按<b>永久失败</b>处置：<b>ACK 且不写行</b> ——
     *         重试会一模一样地再烧一次钱
     * @throws com.novel.modules.ai.service.AiModelClient.UpstreamException
     *         上游侧的一切失败（超时 / 4xx / 5xx / 空 choices）
     * @throws com.novel.common.exception.BusinessException
     *         {@code AI_DISABLED(5001)} / {@code AI_API_KEY_MISSING(5002)} ——
     *         ★ 只可能来自真实实现（mock 不读配置，所以「新克隆的仓库不配任何东西就能跑」仍然成立）
     */
    AiAuditVerdict.Parsed evaluate(String systemPrompt, String userMessage, Long novelId, Long chapterId);
}
