package com.novel.modules.ai.enums;

/**
 * AI 调用失败的<b>封闭词汇表</b>（E1）—— 写进 {@code ai_usage_log.error_msg} 的唯一来源。
 *
 * <h3>★★★ 为什么是一个枚举，而不是一个 String 常量表</h3>
 *
 * <p>这一列存的是<b>上游调用失败的原因</b>，而那个原因最自然的写法是
 * {@code e.getMessage()} —— 而它<b>绝对不能落库</b>：
 * <ul>
 *   <li>上游 4xx/5xx 的响应体里可能带 <b>API Key 的片段</b>。
 *       ★ 这不是假设：真实 DeepSeek 的 401 响应体就长这样 ——
 *       {@code {"error":{"message":"Authentication Fails, Your api key is: sk-xxx..."}}}。
 *       把它写进 {@code error_msg}，等于把一把（错的）Key 存进了数据库、
 *       还会被用量页渲染到浏览器上。</li>
 *   <li>它还会带上内网地址、代理名这类不该出现在业务表里的东西。</li>
 * </ul>
 *
 * <p>★ <b>「记得不要写 e.getMessage()」是一条纪律；而「error_msg 的类型是枚举」
 * 是一条护栏。</b>纪律会被下一个不知道这件事的人破掉，护栏不会 ——
 * 用枚举之后，<b>结构上就没有一个位置能塞进上游的字符串</b>。
 * 这是铁律 8 的正向用法：把「忘了一处」从运行时错误变成编译错误。
 *
 * <h3>★ 为什么分得这么细（而不是一个笼统的 upstream_error）</h3>
 *
 * <p>因为<b>它们指向的下一步动作完全不同</b>，而用量页存在的意义就是让人知道下一步做什么：
 * <table border="1">
 *   <tr><th>取值</th><th>真实含义</th><th>该去找谁</th></tr>
 *   <tr><td>{@link #UPSTREAM_401}</td><td>Key 无效 / 已被删</td><td>管理员重新录一把</td></tr>
 *   <tr><td>{@link #UPSTREAM_404}</td><td>路径不对</td><td>管理员检查 {@code base_url}（少写了 {@code /v1} 之类）</td></tr>
 *   <tr><td>{@link #UPSTREAM_429}</td><td>被上游限流</td><td>等一会儿；或提高账户级别</td></tr>
 *   <tr><td>{@link #UPSTREAM_5XX}</td><td>上游自己挂了</td><td>什么都别做，等</td></tr>
 *   <tr><td>{@link #TIMEOUT}</td><td>连不上 / 太久没响应</td><td>检查网络与 {@code base_url}</td></tr>
 * </table>
 * ★ 把它们合成一句「调用失败」，用量页就退化成一张<b>只能看、不能行动</b>的表。
 *
 * <p>★ 与 {@code AiStreamSource.Handler#onError} 的分工：那里规定了
 * <b>不把 cause 的 message 给前端</b>，这里规定了<b>连数据库也不给</b>。
 * 两边是同一条纪律的两个出口。
 *
 * @author MickeyChen
 */
public enum AiFailure {

    /** 401：Key 无效。★ 真实 DeepSeek 的 401 响应体里带 Key 片段，见类注释 */
    UPSTREAM_401("upstream_401", "API Key 无效或已被删除"),

    /** 403：Key 有效但没权限（比如余额耗尽时有的厂商会返 403） */
    UPSTREAM_403("upstream_403", "API Key 无权访问该模型"),

    /** 404：路径不对。★ 单独列出来，因为它最常见的原因是 base_url 少写了 /v1 */
    UPSTREAM_404("upstream_404", "端点路径不存在（请检查 base_url 是否带 /v1）"),

    /** 429：被上游限流。★ 与本系统的「每日配额」是两回事，不要合并 */
    UPSTREAM_429("upstream_429", "被上游限流，请稍后重试"),

    /** 其余 4xx：多半是请求体不合法（max_tokens 超了、模型名写错） */
    UPSTREAM_4XX("upstream_4xx", "上游拒绝了本次请求（4xx）"),

    /** 5xx：上游自己的问题 */
    UPSTREAM_5XX("upstream_5xx", "上游服务异常（5xx）"),

    /** 连接层失败：DNS 不通、连不上、TLS 握手失败 */
    CONNECT_FAILED("connect_failed", "无法连接到 AI 服务端点"),

    /** 连接或读取超时 */
    TIMEOUT("timeout", "AI 服务响应超时"),

    /** 流读到一半断了（chunked 编码提前结束）—— 与「连不上」是不同的故障 */
    STREAM_BROKEN("stream_broken", "响应流中途断开"),

    /** 上游没发 {@code [DONE]} 就正常结束了。★ 「结束」和「完成」不是一回事 */
    INCOMPLETE_STREAM("incomplete_stream", "响应流未正常结束（缺少 [DONE]）"),

    /** SSE 帧里的 JSON 解析不了 */
    PARSE_FAILED("parse_failed", "无法解析上游返回的数据"),

    /** 流正常结束但一个字都没有。★ 它必须与 {@link #PARSE_FAILED} 分开：
     *  一个是「没读懂」，一个是「读懂了、内容是空的」 */
    EMPTY_RESPONSE("empty_response", "上游返回了空内容"),

    /**
     * 上游在<b>响应流里</b>报告了错误（HTTP 200，然后一帧 {@code {"error":{...}}}）。
     *
     * <p>★ 它和 {@link #UPSTREAM_4XX} / {@link #UPSTREAM_5XX} 是两条不同的路：
     * 那两个是<b>HTTP 状态码</b>，这个是<b>协议内</b>的错误。
     * 合并的话，「上游返回 400」和「上游在流里说 context 太长」会被记成同一件事，
     * 而前者多半是 Key/模型名的问题、后者是请求内容的问题。
     *
     * <p>★★ <b>只记 error.type，绝不记 error.message</b>：某些网关会把
     * 「invalid api key」连同 Key 片段放在这一帧的 message 里 ——
     * 和 401 那条是同一个风险。见 {@code AiModelClient} 里的落点。
     */
    UPSTREAM_ERROR("upstream_error", "上游在响应流中报告了错误"),

    /**
     * 调用方在推送过程中要求停止（用户点了「中断」、关了页面、或 SSE 超时）。
     *
     * <p>★★ <b>它不是上游故障，但必须记一笔账</b>：中断的那一刻，
     * token <b>已经真的烧掉了</b>（模型那边不会因为我们挂断就退钱）。
     * 不记的话，用量账会<b>系统性地少算</b>，而少算的方向恰好是「看起来更省钱」——
     * 那正是最不该出错的方向。
     *
     * <p>★ <b>诚实说明它的 token 列是 0：</b>中断时上游还没来得及发 usage 帧，
     * 我们<b>不知道</b>烧了多少。而 {@code prompt_tokens} 这些列是
     * {@code INT NOT NULL DEFAULT 0}，<b>结构上放不下「未知」</b>。
     * 所以这一行的三个 token 列读作「未统计」而不是「没消耗」——
     * 判据是 {@code error_msg}，不是 token 数。见 {@code docs/E1-AI能力接入.md}。
     */
    ABORTED("aborted", "调用被中断（token 未统计）"),

    /** 兜底。★ 落到这里说明有一个我们没预料到的异常类型，看到它应该去看日志 */
    UNKNOWN("unknown", "未知错误");

    private final String code;
    private final String label;

    AiFailure(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** 落库的取值（{@code ai_usage_log.error_msg}），固定小写下划线 */
    public String getCode() {
        return code;
    }

    /** 给人看的一句话。★ 用量页展示它，不要让页面自己维护一张映射表 */
    public String getLabel() {
        return label;
    }

    /**
     * 按落库的 code 反查枚举。<b>认不出来时返回 {@code null}，不抛异常。</b>
     *
     * <p>★★★ <b>这个「不抛」是刻意的，而且与 {@code AiScene.fromCode} 故意相反</b> ——
     * 两者看着同形，判据却完全不一样：
     * <ul>
     *   <li>{@code AiScene.fromCode} 的输入来自<b>当前请求</b>（查询参数 / 请求体）。
     *       认不出来 = 调用方给错了 → 抛 {@code PARAM_ERROR} 是准确的，
     *       而且<b>用户改一下就能好</b>。</li>
     *   <li>这里的输入来自<b>数据库里的历史行</b>。认不出来只说明一件事：
     *       这行是<b>另一个版本</b>的代码写的（枚举加过值、或改过 code）。
     *       ★ 而它唯一现实的处理方式是<b>把这一行照常显示出来</b> ——
     *       抛异常会让整页 500，于是一行历史的措辞问题变成「用量页打不开了」，
     *       而管理员对那行数据其实毫无办法。</li>
     * </ul>
     *
     * <p>★ 判据是<b>「输入是调用方给的、还是我们自己的历史」</b>，不是「是不是枚举反查」。
     * 所以调用方拿到 {@code null} 之后要回落成<b>把 code 原文显示出来</b>
     * （{@code ai_usage_log.error_msg} 那一列本身就是给人读的），
     * <b>而不是显示成「未知错误」</b> —— 那等于把两种「不知道」合并了：
     * 一种是「这个 code 我不认识」，另一种是 {@link #UNKNOWN}「我当时也不知道出了什么事」。
     *
     * <p>★ 实现上用 {@code values()} 线性扫即可：这个枚举十几个值、调用点在分页列表里
     * （一页最多 100 行），换成 Map 的唯一效果是让人以为这里性能敏感。
     */
    public static AiFailure fromCode(String code) {
        if (code == null || code.isEmpty()) {
            return null;
        }
        for (AiFailure f : values()) {
            if (f.code.equals(code)) {
                return f;
            }
        }
        return null;
    }
}
