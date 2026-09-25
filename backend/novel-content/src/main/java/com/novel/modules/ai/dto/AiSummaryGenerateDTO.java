package com.novel.modules.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 生成前情摘要的请求（E1/S10）。
 *
 * <h3>★★★ 只有两个字段，而且【没有正文】—— 这是它和 {@link AiRequestDTO} 的根本区别</h3>
 *
 * <pre>
 *   四个生成场景（AiRequestDTO）：作者拿【手上的东西】去问 AI
 *                                 → prompt / context 由客户端给
 *   摘要（本类）：                让 AI 去读【库里的东西】
 *                                 → 正文由服务端按 novelId + endChapterNo 自己读
 * </pre>
 *
 * <p>★ 三条理由，缺一条这个形状就不成立：
 * <ol>
 *   <li>要压的正文可能有几千字，让客户端传上来等于<b>把「读什么」的权威交给客户端</b>
 *       —— 而摘要是要长期留库、被以后每一章引用的东西；</li>
 *   <li>作者手上真正有的是「把第 11~20 章压一下」这个意图，不是那 20 章的全文
 *       （他的编辑器里只开着当前那一章）；</li>
 *   <li>服务端读的那一份是<b>当时的真值</b>，客户端传上来的可能是半小时前的草稿。
 *       ★ 这个区别在别处无所谓（生成完还要人工采纳），在摘要这里是硬要求 ——
 *       它<b>不经采纳直接落库</b>，一份过期副本会变成一条长期错误的前情。</li>
 * </ol>
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "生成前情摘要。★ 正文由服务端按 novelId + endChapterNo 读，请求里不带正文")
public class AiSummaryGenerateDTO {

    @Schema(description = "作品 id", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "请指定作品")
    private Long novelId;

    /**
     * 压缩到第几章（<b>含</b>）。
     *
     * <p>★★ 它是<b>范围的上界</b>，不是「压几章」—— <b>起点由服务端算</b>：
     * 上一条摘要的 {@code end_chapter_no} + 1（该书还没有摘要时从第 1 章起）。
     * ★ 让客户端传起点就等于允许它压一段<b>中间会断</b>的区间，
     * 而摘要链是一条滚动往前接的链，断一节后面全部接不上
     * （见 {@code AiSummaryService} 里那段区间计算的注释）。
     *
     * <p>★★ 还必须<b>严格大于</b>上一条摘要的 {@code end_chapter_no}：
     * 等于或小于都会被 400 拒掉，理由不是「不许重做」，而是
     * <b>那条链只有一个方向</b> —— 重压时组装器能读到的「已有前情摘要」
     * 恰好就是你要重压的那一段，于是模型收到的标签（「更早的剧情」）在说假话，
     * 而产出的摘要会被当成新的前情存下来。<b>宁可 400，不写一条自欺的链。</b>
     *
     * <p>★ 传一个比实际章节数大的值不会报错，也<b>不会</b>被「夹」到最后一章：
     * 服务端只取<b>库里真实存在</b>的那几章，区间照实回报（见 {@code AiSummaryVO}）。
     */
    @Schema(description = "压缩到第几章（含）。必须大于上一条摘要覆盖的章节号",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "20")
    @NotNull(message = "请指定压缩到第几章")
    @Min(value = 1, message = "章节号从 1 开始")
    private Integer endChapterNo;
}
