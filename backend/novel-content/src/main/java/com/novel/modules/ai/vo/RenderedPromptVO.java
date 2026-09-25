package com.novel.modules.ai.vo;

import com.novel.modules.ai.enums.AiPlaceholder;
import com.novel.modules.ai.enums.AiVarRequirement;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 渲染结果。<b>它同时是 {@code PromptRenderer} 的返回值和管理端预览接口的响应体</b> ——
 * 没有中间映射层，因为两者要表达的是同一件事：<b>「这段提示词最终长什么样」</b>。
 *
 * <h3>★★★ 为什么要把「哪些变量被移除了」显式报出来</h3>
 *
 * <p>判断 2.2 的「可选变量无值 → 整行移除」是<b>一条静默的行为</b>：
 * 它既不是错误（不该报错），又在最终文本里<b>什么都不剩</b>（看不出被移除过）。
 * ★ 只给管理员看最终文本的话，他会看到一个「少了点什么」但说不清少了什么的结果，
 * 然后开始怀疑模板写错了。
 *
 * <p>★★ 所以预览必须把「移除」这件事变成一个<b>能被看见的东西</b>——
 * 这正是铁律 10 的同一取向：<b>「没有值」和「值被省略了」必须能被分开。</b>
 * 前端据此在编辑器里把那些行标灰、并显示一句「当前没有值，这 2 行会被移除」。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "提示词渲染结果")
public class RenderedPromptVO {

    @Schema(description = "渲染后的 system 消息")
    private String systemPrompt;

    @Schema(description = "渲染后的 user 消息 —— 这才是真正发给模型的那段")
    private String userMessage;

    @Schema(description = "本次【有值】的占位符")
    private List<String> usedVars;

    @Schema(description = "本次【无值、整行被移除】的占位符。★ 前端要把这些行标出来，不能默认它不存在")
    private List<String> removedVars;

    @Schema(description = "本场景的可用变量表：占位符 → required/optional。★ 模板编辑器的提示语与保存校验都用它")
    private Map<String, AiVarRequirement> availableVars;

    @Schema(description = "渲染后的总长度（字符）。★ 用于和上限比对")
    private Integer totalLength;

    @Schema(description = "总长度上限。★ 超限会【明确失败】而不是截断，见 PromptRenderer")
    private Integer maxLength;

    /**
     * 方便前端按 key 取用。
     *
     * <p>★ 用 {@link AiPlaceholder#getName()}（不含花括号的名字）而不是 {@code token()}：
     * 前端要拿它去和模板文本里扫出来的名字比对，带花括号会让前端不得不再剥一次。
     */
    public static List<String> namesOf(Iterable<AiPlaceholder> placeholders) {
        List<String> names = new java.util.ArrayList<>();
        for (AiPlaceholder p : placeholders) {
            names.add(p.getName());
        }
        return names;
    }
}
