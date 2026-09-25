package com.novel.modules.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 保存 AI 配置的入参（E1）。
 *
 * <h3>★★★ {@code apiKey} 的「空 = 保持不变」是一条必须写死的规矩</h3>
 *
 * <p>读接口<b>永远不返回 Key</b>（只返回掩码），所以管理端那个输入框在打开页面时是<b>空的</b>。
 * 如果「空」被理解成「把 Key 清掉」，那么管理员<b>只想改一下温度</b>、
 * 顺手点保存 —— Key 就没了，而且<b>界面上没有任何变化</b>（输入框本来就是空的）。
 * 下一次作者点生成才会发现「尚未配置 API Key」，中间隔着不知道多少天。
 *
 * <p>所以：<b>不传 / 空串 = 保持库里那条不变</b>。
 * ★ 要清掉 Key 请显式传 {@code clearApiKey = true} ——
 * 不用「传个特殊字符串」那种约定：<b>魔法字符串是看不出来的</b>，
 * 而一个布尔字段在 Knife4j 上、在请求日志里都明明白白。
 *
 * <p>★★ 两者同时传是<b>自相矛盾</b>的请求，返回 400 而不是「按 true 处理」——
 * 猜用户想要什么，正是本项目一直在避免的那种「看起来正常的假结果」。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "AI 配置保存入参")
public class AiConfigSaveDTO {

    /**
     * 新的 API Key 明文。<b>不传 = 保持原值。</b>
     *
     * <p>★ 上限 200 是防手滑（把一整段配置文件粘进来），不是业务限制 ——
     * DeepSeek 的 Key 是 35 个字符左右。
     * ★ <b>校验放在这里而不是等到加密</b>：明文进了内存、进了 Knife4j 的请求体回显，
     * 才发现太长，那是白担了一次泄漏风险。
     */
    @Schema(description = "新的 API Key 明文。不传则保持原值不变；传 clearApiKey=true 则清空",
            example = "sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")
    @Size(max = 200, message = "API Key 最长 200 个字符")
    private String apiKey;

    /** 显式清空 Key。★ 与 {@code apiKey} 互斥，同时给返回 400 */
    @Schema(description = "是否清空已保存的 API Key。与 apiKey 互斥", defaultValue = "false")
    private Boolean clearApiKey = false;

    /**
     * OpenAI 兼容端点。
     *
     * <p>★ <b>必须带 http/https 前缀</b>，否则 {@code java.net.http.HttpClient} 会在调用时才抛
     * 「invalid URI scheme」—— 那时错误已经离「管理员少写了 https://」很远了。
     * ★★ <b>这个字段同时是 E1 的测试钩子</b>：指向本地假端点（{@code docs/tools/fake-openai.py}）
     * 就能在<b>不改代码、不重新打包</b>的前提下做各种上游故障实验，与 M4 把 {@code ES_URIS}
     * 做成环境变量是同一个手法。
     */
    @Schema(description = "OpenAI 兼容端点", example = "https://api.deepseek.com/v1",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "base_url 不能为空")
    @Size(max = 200, message = "base_url 最长 200 个字符")
    @Pattern(regexp = "^https?://.+", message = "base_url 必须以 http:// 或 https:// 开头")
    private String baseUrl;

    /** 模型名。★ 与 {@code baseUrl} 一样是真实调用的必要条件，所以也是 {@code @NotBlank} */
    @Schema(description = "模型名", example = "deepseek-chat", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "模型名不能为空")
    @Size(max = 50, message = "模型名最长 50 个字符")
    private String model;

    /**
     * 温度，0.0 ~ 2.0。
     *
     * <p>★★ <b>上限是 2.0 而不是列定义的 {@code DECIMAL(2,1)} 的上限 9.9</b> ——
     * 列宽能装下 9.9，但 OpenAI 兼容协议里 temperature 的合法区间就是 [0, 2]，
     * 超了上游直接 400。<b>「数据库装得下」和「这个值有意义」是两件事</b>，
     * 校验要按后者写，否则又是一个「保存成功、一调用就 400」。
     */
    @Schema(description = "温度，0.0~2.0", example = "0.7", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "温度不能为空")
    @DecimalMin(value = "0.0", message = "温度不能小于 0")
    @DecimalMax(value = "2.0", message = "温度不能大于 2")
    private BigDecimal temperature;

    /**
     * 单次生成的最大 token 数。
     *
     * <p>★★ <b>上限 8192 是这一期少有的「直接拦住花钱」的护栏</b>（风险清单 R6）：
     * 没有上限时，一个管理员把这里填成 100000，每次生成都会真的按 10 万 token 计费 ——
     * <b>而界面上一切正常</b>。8192 是 deepseek-chat 的输出上限。
     * ★ 下限 1：0 会让上游直接 400。
     */
    @Schema(description = "单次生成最大 token 数，1~8192", example = "2048",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "最大 token 数不能为空")
    @Min(value = 1, message = "最大 token 数不能小于 1")
    @Max(value = 8192, message = "最大 token 数不能大于 8192")
    private Integer maxTokens;

    /**
     * 每用户每日调用上限。
     *
     * <p>★ 下限是 1 而不是 0：想「谁都不许用」应该关 {@code enabled}（返 5001，文案明确），
     * 而不是把上限调成 0（用户看到的是「额度已用完」，<b>指向一个完全不同的原因</b>）。
     */
    @Schema(description = "每用户每日调用上限，≥1", example = "50",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "每日上限不能为空")
    @Min(value = 1, message = "每日上限不能小于 1")
    @Max(value = 100000, message = "每日上限过大")
    private Integer dailyLimit;

    /**
     * 1 启用 0 停用。
     *
     * <p>★ 关闭时四个生成端点一律返 {@code AI_DISABLED(5001)} + HTTP 503。
     * ★★ <b>这是「停用」不是「删除」</b>：Key、模板、配额全都原样留着，随时可以开回来。
     */
    @Schema(description = "1 启用，0 停用", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "启用状态不能为空")
    @Min(value = 0, message = "启用状态只能是 0 或 1")
    @Max(value = 1, message = "启用状态只能是 0 或 1")
    private Integer enabled;
}
