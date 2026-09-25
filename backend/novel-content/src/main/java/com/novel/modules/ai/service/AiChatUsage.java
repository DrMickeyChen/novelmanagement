package com.novel.modules.ai.service;

/**
 * 一次模型调用的 token 消耗（E1）。
 *
 * <p>★ <b>它是一个独立的 record，不是 {@code AiModelClient} 的内部类</b>：
 * 因为消费它的是 {@code AiUsageRecorder}（记账）而不是 {@code AiModelClient}（发请求）——
 * 记账这件事不该为了拿一个三元组去依赖 HTTP 客户端。
 *
 * <h3>★★★ 关于「全是 0」这件事，必须有一条纪律</h3>
 *
 * <p>{@link #ZERO} 是「<b>上游没告诉我们</b>」的表示，而
 * {@code ai_usage_log.prompt_tokens} 这些列是 {@code INT NOT NULL DEFAULT 0} ——
 * <b>数据库里「没统计」和「消耗了 0 个 token」长得一模一样</b>。
 *
 * <p>这是判断 4 那个「静默为 0」陷阱的落点：
 * DeepSeek 的流式响应<b>只在最后一块</b>带 {@code usage}，而那一块的
 * {@code choices} 是<b>空数组</b>。为了躲开 {@code choices[0]} 的越界而
 * 「跳过 choices 为空的帧」，就会把 usage 一起跳过 →
 * <b>库里 token 全是 0，而且不报错</b>。
 *
 * <p>★ 判据：<b>只跑假端点验不出这一条</b> —— 假端点返回的是<b>我们以为的</b>形状，
 * 用它验等于<b>用假设验假设</b>。唯一能证明它的是一次真实调用（见真实调用预算 R1）。
 *
 * @param promptTokens     输入 token
 * @param completionTokens 输出 token
 * @param totalTokens      合计
 * @author MickeyChen
 */
public record AiChatUsage(int promptTokens, int completionTokens, int totalTokens) {

    /**
     * 「上游没给 usage」。★ 三个 0 <b>读作「未统计」而不是「没消耗」</b>。
     *
     * <p>★ 刻意<b>不</b>写成 {@code null}：那样每个使用点都要判空，
     * 而判空写漏的后果是把一次失败变成一次 NPE（500），
     * 也就是「记账的小事把生成的大事搞挂了」。
     */
    public static final AiChatUsage ZERO = new AiChatUsage(0, 0, 0);

    /** 有没有拿到真实的用量。★ 用量页据此把「0」渲染成「未统计」而不是「0」 */
    public boolean isUnknown() {
        return promptTokens == 0 && completionTokens == 0 && totalTokens == 0;
    }
}
