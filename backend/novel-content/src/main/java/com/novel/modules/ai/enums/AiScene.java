package com.novel.modules.ai.enums;

import lombok.Getter;

/**
 * AI 写作助手的场景。
 *
 * <p><b>★ 全项目唯一一处「场景」的定义，而且它【不出现在任何请求体里】。</b>
 *
 * <p>四个场景对应四个 URL，场景由<b>路径</b>决定：
 * <pre>
 *   POST /api/ai/outline   → OUTLINE
 *   POST /api/ai/write     → WRITE
 *   POST /api/ai/inspire   → INSPIRE
 *   POST /api/ai/polish    → POLISH
 * </pre>
 * <b>所以 {@code AiRequestDTO} 上没有 {@code scene} 字段，这个枚举也没有
 * {@code @JsonValue}。</b>如果把它放进请求体（{@code {"scene": 2, ...}}），
 * 就会出现「URL 说是 outline、body 说是 write」这种自相矛盾的请求 ——
 * 而那时以谁为准，是一个没有正确答案的问题。让 URL 做唯一的真相来源，
 * 这类矛盾压根构造不出来。
 *
 * <p>★ 它仍然需要一个 {@code code}，因为<b>日志和配额统计要用</b>：
 * 「今天 outline 调了 3 次、write 调了 40 次」比四个方法名混在一起可读得多。
 * 第二期拆出 {@code novel-ai} 微服务后，这个 code 会进监控埋点。
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
    POLISH(4, "润色段落");

    /** 日志与埋点用的编号 */
    private final Integer code;

    /** 给用户看的场景名（前端「生成中…」的提示语会用到） */
    private final String label;

    AiScene(Integer code, String label) {
        this.code = code;
        this.label = label;
    }
}
