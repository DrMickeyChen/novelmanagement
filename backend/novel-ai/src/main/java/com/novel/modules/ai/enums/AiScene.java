package com.novel.modules.ai.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.ResultCode;
import lombok.Getter;

import java.util.Arrays;

/**
 * AI 写作助手的场景。
 *
 * <p><b>★ 全项目唯一一处「场景」的定义，而且它【不出现在任何请求体里】。</b>
 *
 * <p><b>五个【生成】场景对应五个 URL，场景由路径决定：</b>
 * <pre>
 *   POST /api/ai/outline   → OUTLINE   （SSE）
 *   POST /api/ai/write     → WRITE     （SSE）
 *   POST /api/ai/inspire   → INSPIRE   （SSE）
 *   POST /api/ai/polish    → POLISH    （SSE）
 *   POST /api/ai/summary   → SUMMARY   （★ 不是 SSE，见下）
 * </pre>
 * <b>所以 {@code AiRequestDTO} 上没有 {@code scene} 字段。</b>
 *
 * <p>★★ <b>{@link #SUMMARY}（E1/S10）是唯一一个「有 URL 但不是流式」的场景。</b>
 * 前四个是「作者在编辑器里等打字机」，必须有连接、必须可中断；
 * 而摘要是<b>作者手动触发的一次压缩</b>，产出要<b>同步落库</b>（{@code ai_summary}），
 * 没有「边生成边看」的界面 —— 给它套一层 SSE 只会多出「流断了但摘要是好的」
 * 这种半成品状态。判据是<b>产出有没有消费者在实时看</b>，不是「是不是 AI 生成」。
 * 如果把它放进请求体（{@code {"scene": 2, ...}}），
 * 就会出现「URL 说是 outline、body 说是 write」这种自相矛盾的请求 ——
 * 而那时以谁为准，是一个没有正确答案的问题。让 URL 做唯一的真相来源，
 * 这类矛盾压根构造不出来。
 *
 * <p>★★ <b>这条规矩的作用域是 {@code /api/ai/**} 那四个生成端点，不是整个项目</b>
 * （E1 订正：原话写的是「这个枚举也没有 {@code @JsonValue}」，说过头了）。
 * 管理端的<b>模板管理</b>接口（{@code /api/admin/ai/prompt/**}）<b>必须</b>接受 body 里的
 * {@code scene} —— 那里<b>没有</b>「一个场景一个 URL」，
 * 模板 CRUD 是一组通用端点，除了 body 没有别的地方能表达「改的是哪个场景的模板」。
 * <b>判据是「这个端点上有没有一个能替代它的 URL 维度」，不是「枚举能不能进 body」</b> ——
 * 有 URL 维度就用 URL（生成端点），没有就用 body（管理端点）。
 *
 * <h3>★★★ {@link #AUDIT} 是【唯一一个没有 URL 的场景】</h3>
 *
 * <p><b>它【刻意不注册任何端点】</b>，只由服务端内部调用（MQ 消费者）。
 * 理由是硬的：{@code ContentSecurityConfig} 给 {@code /api/ai/**} 的闸是
 * <b>{@code hasAnyRole("AUTHOR","ADMIN")}</b> —— 一旦给审核场景加一个 URL，
 * <b>任何作者都能拿任意文本去跑违禁审核</b>，用自己的额度探测关键词判定，
 * 而这正是审核系统最不该提供的接口。
 *
 * <p>★ 判据（结构式，不靠记）：<b>「本枚举的枚举值个数」必须【大于】
 * 「{@code AiController} 里 {@code @PostMapping} 的个数」</b>，差值是 1。
 * 谁把 AUDIT 接成了一个端点，这个差值就变成 0。
 *
 * <p>★ 它仍然需要一个 {@code code}：<b>{@code ai_usage_log.scene} 与
 * {@code ai_prompt_template.scene} 都按它落库</b>，所以「今天 outline 调了 3 次、
 * write 调了 40 次」这种账才分得开 —— 审核跑掉的 token 也因此能和作者的创作
 * 用量分开统计，否则管理员永远不知道钱花在谁身上。
 *
 * @author MickeyChen
 */
@Getter
public enum AiScene {

    /** 生成大纲：产出<b>严格 JSON 树</b>，前端流式解析后长成大纲节点 */
    OUTLINE(1, "生成大纲"),

    /** 续写正文：产出散文，前端追加到当前章节末尾 */
    WRITE(2, "续写正文"),

    /** 提供灵感：产出 3 个剧情走向 */
    INSPIRE(3, "提供灵感"),

    /** 润色：产出改写后的段落，前端替换选中的那段 */
    POLISH(4, "润色段落"),

    /**
     * 违禁审核（E1）：产出<b>严格 JSON</b>（{@code riskLevel} / {@code hitWords} / {@code comment}）。
     *
     * <p>★★ <b>没有 URL，只由 {@code AiAuditConsumer} 内部调用</b>，理由见类注释。
     * ★ 它<b>不是流式</b>的：{@code AiStreamSource} 的契约（恰好一次终结、必须可被打断）
     * 是为 SSE 连接的断开语义服务的，而审核没有连接可断。
     */
    AUDIT(5, "违禁审核"),

    /**
     * 前情摘要（E1/S10）：产出<b>一段压缩后的剧情梗概</b>，<b>同步</b>落库 {@code ai_summary}。
     *
     * <p>★★ <b>它是唯一一个「有 URL 但不是流式」的场景</b>（理由见类注释）：
     * {@code POST /api/ai/summary} 返回一个普通 JSON，不是 {@code SseEmitter}。
     *
     * <p>★ <b>作者手动触发</b>，不做「每 N 章自动压一次」—— 那要定时任务 +
     * 分布式锁，而摘要的价值在「下一章开写之前回顾一眼」，人工点的时机比定时准。
     *
     * <p>★★ 它与 {@link #WRITE}/{@link #OUTLINE} 的另一个区别：
     * <b>它的产出【没有「采纳」这一步】</b>—— 生成完直接进库，下一章的
     * {@code {{summary}}} 就会带上它。所以它的输出<b>不能是演示数据</b>，
     * 见 {@code MockAiStreamSource#summary} 里那段自我标识的论证。
     */
    SUMMARY(6, "前情摘要");

    /**
     * 编号。<b>★ 它在 E1 同时是【落库值】和【对外 JSON 值】</b>：
     * <pre>
     *   {@code @EnumValue} → ai_prompt_template.scene / ai_usage_log.scene / ai_audit_result 的 scene 语义
     *   {@code @JsonValue} → 管理端模板接口 body 里的 {"scene": 1}
     * </pre>
     * ★★ 加这两个注解【对 P4 的四个生成端点零影响】—— 它们把场景放在 URL 里，
     * 而 {@code AiRequestDTO} 上没有 scene 字段，所以序列化根本走不到这里。
     * 与 {@code AuditTargetType} / {@code ChapterStatus} 等既有枚举的写法一致。
     * <p>★ 编号必须与 {@code docs/sql/seed.sql} 里 {@code ai_prompt_template.scene} 的
     * 取值逐字一致（1大纲 2正文 3灵感 4润色 5违禁审核 6前情摘要）：这份对应关系一半在 SQL 里、
     * 一半在 Java 里，<b>两边都不报错</b>，对不上时的症状是「模板串了场景」而界面一切正常。
     */
    @EnumValue
    @JsonValue
    private final Integer code;

    /** 给用户看的场景名（前端「生成中…」的提示语会用到） */
    private final String label;

    AiScene(Integer code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * 按编号查场景，{@code null} 进 {@code null} 出。
     *
     * <p>★ 非法值抛 {@code PARAM_ERROR(400)} 而不是 {@code IllegalArgumentException}
     * —— 与 {@code AuditTargetType.fromCode} / {@code ChapterStatus.fromCode} 同形，
     * 理由见 {@code NovelStatus.fromCode}：那些是用户输入，该是 400 而不是 500。
     *
     * <p>★ 它只在管理端用得上（列表的 {@code ?scene=2} 查询参数）。
     * 生成端点走 URL 路径，不需要码表转换。
     */
    public static AiScene fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(s -> s.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.PARAM_ERROR,
                        "未知的 AI 场景：" + code));
    }
}
