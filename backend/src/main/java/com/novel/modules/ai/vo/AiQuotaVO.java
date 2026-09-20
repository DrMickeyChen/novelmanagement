package com.novel.modules.ai.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * AI 今日额度。
 *
 * <p><b>★ 这个 VO 里最重要的是 {@code provider} 字段，它不是一个装饰。</b>
 *
 * <p>P4 的 AI 是<b>「接口是真的、实现是假的」</b>：流式管道、中断、采纳全部真实可用，
 * 但生成内容是 mock 的。这件事必须让<b>看见界面的人</b>知道，
 * 而不是只写在文档里 —— 一个看起来像 AI、实际是假的数据流，
 * 在演示时如果不做任何标识，就是个诚信问题。
 *
 * <p>所以前端顶栏那个「演示模式」标签<b>不由前端写死</b>，
 * 而是读这个字段：{@code provider == "mock"} 时才显示。
 * E1 接上真实模型（配置改成 {@code deepseek}）后，
 * 那个标签会自动消失，<b>不需要人去改前端</b> ——
 * 这一点很关键：靠人去记得摘掉的标识，一定会忘记摘。
 *
 * @author MickeyChen
 */
@Data
@AllArgsConstructor
@Schema(description = "AI 额度与当前实现")
public class AiQuotaVO {

    @Schema(description = "今日剩余调用次数", example = "50")
    private Integer remaining;

    @Schema(description = "今日总额度", example = "50")
    private Integer total;

    /**
     * 当前生效的实现，{@code mock} 或 {@code deepseek}。
     *
     * <p>★ 前端据此决定要不要显示「演示模式」标签。
     */
    @Schema(description = "当前生效的实现：mock=演示数据，deepseek=真实模型", example = "mock")
    private String provider;
}
