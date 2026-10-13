package com.novel.modules.ai.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 配置的读模型（E1）。<b>★★★ 它【永不】携带 API Key —— 连密文都不带。</b>
 *
 * <h3>★★★ 掩码：{@code sk-****abcd}，且【长度不泄露】</h3>
 *
 * <p>本类的验收项写着「掩码长度不泄露真实 Key 长度」。这条的实现是硬的：
 * <b>无论真实 Key 多长，掩码都是固定 11 个字符</b>（{@code sk-} + {@code ****} + 末 4 位）。
 * 反例是把中间打成一串和真实长度相同的星号 —— 那等于把长度这个信息白送出去，
 * 而长度对暴力搜索是有用的。
 *
 * <p>★ <b>为什么保留末 4 位而不是全打星：</b>运维手上有好几把 Key 时，
 * 「末四位是哪一把」是他唯一能用来确认「我填的是这一把」的信息。
 * 全打星会让「换 Key 有没有生效」变成一个无法确认的问题。
 * ★ 反过来，<b>绝不保留前 4 位</b>（除了 {@code sk-} 这个公共前缀）：
 * 前缀的熵最低，而 DeepSeek 的 Key 前几位本来就是固定格式，保留它没有信息量、只有风险。
 *
 * <h3>★★ {@code provider} 与 {@code effectiveProvider} 是【两件事】，别合并</h3>
 *
 * <p>见 {@code AiConfig} 的类注释：前者是库里那一行的厂商名（恒为 {@code DEEPSEEK}），
 * 后者是<b>YAML 里 {@code novel.ai.provider} 的值（{@code mock} / {@code deepseek}），
 * 它才决定哪个 Bean 存在、也就是 AI 面板背后到底是真模型还是假数据</b>。
 *
 * <p>★★★ <b>两个都返回，是为了让「填了 Key 却不生效」这件事【可见】。</b>
 * 只返回前者时，管理员填完 Key 什么都不会发生，而且<b>页面上看不出原因</b> ——
 * 他会以为自己填错了，然后反复重填。
 * 页面据此显示横幅：「当前后端配置为 <b>mock</b>，此处填写的 Key 不会生效」。
 *
 * <p>★ 前端判据是 {@code effectiveProvider !== 'deepseek'}，
 * <b>不是</b>把这两个字段拿去互相比较 —— 它们大小写不同（{@code DEEPSEEK} vs {@code deepseek}），
 * 比较会掉进「顺手统一两套词汇表」的坑里（要比较就得大小写折叠，而那一折叠就把
 * 「它们不是一回事」这个信息抹掉了）。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "AI 配置（★ 不含 API Key，只有掩码）")
public class AiConfigVO {

    @Schema(description = "配置行 id，恒为 1")
    private Long id;

    /**
     * 库里记录的厂商名，恒为 {@code DEEPSEEK}。
     *
     * <p>★★ <b>它不代表哪个实现在跑</b> —— 那是 {@code effectiveProvider}。
     * 这一栏在管理端是<b>只读展示</b>：让它可编辑就会造出「改了这个却不生效」的假旋钮。
     */
    @Schema(description = "配置行里记录的厂商名（只读，不决定哪个实现在跑）", example = "DEEPSEEK")
    private String provider;

    /**
     * 掩码后的 Key，如 {@code sk-****abcd}。
     *
     * <p>★ 没配 Key 时是 {@code null} —— <b>不是空串</b>。
     * 空串会被前端渲染成一个「有值但看不见」的框，而 {@code null} 能被诚实地渲染成「未配置」。
     * （这条依赖全局的 {@code default-property-inclusion: non_null}。）
     */
    @Schema(description = "掩码后的 API Key，未配置时为 null", example = "sk-****abcd")
    private String apiKeyMasked;

    /**
     * 库里到底有没有 Key。
     *
     * <p>★ 单看 {@code apiKeyMasked} 也能判断，但这个布尔是<b>给页面用的显式状态</b>：
     * 「未配置」与「配置了但掩码渲染失败」在界面上必须是两句话。
     * ★ 它<b>不含任何长度信息</b>，可以安全地给到前端。
     */
    @Schema(description = "是否已配置 API Key")
    private Boolean apiKeyConfigured;

    /**
     * ★★★ 关于这把 Key 的<b>异常说明</b>，正常时为 {@code null}。
     *
     * <p>目前只有一种取值：服务端的 {@code AI_KEY_SECRET} 与录入 Key 时不一致，
     * 导致那把 Key <b>解不开</b>。
     *
     * <h4>★★★ 为什么是「一个字段」而不是让接口直接 500 —— 验收实测逼出来的</h4>
     *
     * <p>最初的实现里，{@code mask()} 解密失败会让整个接口 500（理由是
     * 「不要渲染一个看起来正常、却配着一把解不开的 Key 的配置页」）。
     * ★ <b>那个理由对 GET 成立，对 PUT 不成立</b> —— 而 PUT 的响应体里也有这个 VO。
     * 实测症状：
     *
     * <blockquote>
     * 管理员在没配 {@code AI_KEY_SECRET} 的机器上改温度 → <b>数据库改成功了</b>，
     * 但响应是 500 → 界面报「保存失败」→ 他重试，每次再写一遍。
     * </blockquote>
     *
     * ★★ 这是本项目一路在猎的那种「看起来正常的假结果」的<b>反向</b>形状：
     * 不是「假成功」，是<b>「真成功被报成失败」</b>，而后果一样 ——
     * 人都做了错误的下一步。
     *
     * <h4>★ 所以改成：解密失败【不抛】，而是把它变成一个能被渲染出来的事实</h4>
     *
     * <p>此时 {@code apiKeyConfigured = true}（确实存着密文）、{@code apiKeyMasked = null}
     * （拿不出掩码）、本字段带一句人话。
     * <b>前端契约只有一条：{@code apiKeyNotice} 非空时原样显示它。</b>
     *
     * <p>★★ 比 500 更好的地方在于<b>它不是死胡同</b>：页面照常加载，
     * 管理员能看到 {@code baseUrl}/{@code model}，直接重新录入一次 Key 就能修好
     * （写路径只需要加密，不需要解密那把旧的）。
     * 而 500 的话，他连「端点配的是哪个」都看不到。
     *
     * <p>★ 注意这与 {@code AiCrypto#decrypt} 的纪律<b>不冲突</b>：
     * 加密层<b>仍然抛异常</b>（那正是「绝不要把乱码当 Key 用」这条护栏的落点），
     * 是<b>这一层把它接住并翻译成人话</b>。判据是
     * 「谁来承担这个失败」——加密层负责不产生假 Key，API 层负责不谎报成功。
     */
    @Schema(description = "Key 的异常说明，正常时为 null；非空时前端应原样显示",
            example = "已保存的 API Key 无法解密：服务端 AI_KEY_SECRET 与录入时不一致，请重新录入")
    private String apiKeyNotice;

    @Schema(description = "OpenAI 兼容端点", example = "https://api.deepseek.com/v1")
    private String baseUrl;

    @Schema(description = "模型名", example = "deepseek-chat")
    private String model;

    @Schema(description = "温度", example = "0.7")
    private BigDecimal temperature;

    @Schema(description = "单次生成最大 token 数", example = "2048")
    private Integer maxTokens;

    @Schema(description = "每用户每日调用上限", example = "50")
    private Integer dailyLimit;

    @Schema(description = "1 启用，0 停用", example = "1")
    private Integer enabled;

    /**
     * ★★ 后端<b>实际生效</b>的实现，取值 {@code mock} 或 {@code deepseek}（小写）。
     *
     * <p>它来自 YAML {@code novel.ai.provider}，<b>只读、改不了</b>（改它要改配置并重启）。
     * 见类注释：这个字段存在的唯一目的，是让「填了 Key 不生效」变得可见。
     */
    @Schema(description = "实际生效的 provider（来自 YAML，只读）：mock / deepseek", example = "mock")
    private String effectiveProvider;

    @Schema(description = "最后更新时间")
    private LocalDateTime updateTime;
}
