package com.novel.modules.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 试扫入参（E1/S7）：<b>拿一段文本过一遍当前的词库快照</b>。
 *
 * <h3>★★ 这个端点为什么必须存在</h3>
 *
 * <p>词库是一个<b>判定输入</b>，而它唯一的真实调用方是 S8 的 MQ 消费者 ——
 * 也就是说，没有它的话，「第一层到底有没有在工作」<b>只能靠翻日志和翻库</b>。
 * ★ 而本项目的取向一贯是：<b>判定性的东西必须有一个能主动问一次的入口</b>
 * （同 {@code /api/admin/ai/prompt/preview} 相对模板渲染层的地位）。
 * 它是 S7 的验收载体，也是一个管理员真正会用的功能 ——
 * 「我加的这个词到底会不会命中」在这里按一下就知道了。
 *
 * <p>★★ <b>它只读、不写任何表、不扣配额、不调模型</b> —— 所以可以随便试。
 * ★ 但它是<b>读内存快照</b>，不是读库：刚保存的词<b>还没 reload 之前扫不到</b>，
 * 这不是 bug，是两个动作的分工（见 {@code SensitiveWordSaveDTO} 的类注释）。
 *
 * <p>★★★ <b>「命中 0 个词」和「词库是空的」必须在结果里长得不一样。</b>
 * 后者在 S7 里是<b>拒审</b>（fail-closed），所以它压根不会以 200 返回 ——
 * 若它哪天以 200 + 「命中 0」返回，那就意味着铁律 8 说的「不会失败的护栏」回来了。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "违禁词试扫入参")
public class SensitiveWordScanDTO {

    /**
     * 要扫的文本。所有章正文都按这个形状送进来，只是长度不同。
     *
     * <p>★ 上限 20000 是<b>成本护栏</b>而不是正确性约束：DFA 扫描是 O(文本长度)，
     * 单线程毫秒级，但一个 HTTP body 不该是无限的 —— 它是管理端的「试一段」，
     * 不是「扫全站」。★ 真正的审核走 MQ，正文<b>由消费端自己从库里读</b>，
     * 从不经过这个 DTO。
     */
    @Schema(description = "要扫描的文本", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "要扫描的文本不能为空")
    @Size(max = 20000, message = "试扫文本最多 20000 个字")
    private String text;
}
