package com.novel.modules.ai.service;

import java.math.BigDecimal;

/**
 * 「一次真实调用需要的全部参数」（E1）—— 由 {@link AiConfigService#requireUsable()} 产出。
 *
 * <h3>★★★ 这个 record【故意不包含 apiKey】—— 这是本类存在的全部理由</h3>
 *
 * <p>record 自动生成的 {@code toString()} 会打印<b>所有</b>字段。只要 apiKey 在这个 record 里，
 * 任何一处 {@code log.debug("本次调用参数={}", cfg)}、或者一次异常栈里的对象打印，
 * 都会把<b>明文 Key 写进日志文件</b> —— 而日志文件是要被收集、被转发、被翻看的。
 *
 * <p>★ 所以分成两条路：
 * <ul>
 *   <li>参数走 {@code requireUsable()} → 本 record（<b>可以随便打印</b>）</li>
 *   <li>Key 走 {@link AiConfigService#decryptCurrentApiKey()} → 一个 {@code String}，
 *       <b>调用点在真正发 HTTP 请求的前一行</b>，作用域尽可能小</li>
 * </ul>
 * ★★★ 判据：<b>「这个值能不能被安全地打印」应当由【类型】回答，而不是由【读注释的人】记得。</b>
 * 把 Key 放进一个「看起来什么都能装」的 DTO 里，就是把它交给后者。
 *
 * <h3>★ 为什么 {@code enabled} 不在这个 record 里</h3>
 *
 * <p>因为 {@code requireUsable()} <b>只在启用时才返回</b>（停用直接抛 5001）——
 * 一个恒为真的字段是没用的字段，而它会让人以为「拿到 record 之后还要再判一次」。
 *
 * @param baseUrl     OpenAI 兼容端点
 * @param model       模型名
 * @param temperature 温度
 * @param maxTokens   单次生成的最大 token 数
 * @param dailyLimit  每用户每日调用上限
 * @author MickeyChen
 */
public record AiRuntimeConfig(
        String baseUrl,
        String model,
        BigDecimal temperature,
        Integer maxTokens,
        Integer dailyLimit) {
}
