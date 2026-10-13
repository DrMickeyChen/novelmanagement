package com.novel.modules.ai.enums;

import lombok.Getter;

/**
 * 某个占位符在某个场景里的「有没有」—— 场景与占位符叉乘之后的取值（E1）。
 *
 * <p>词汇表本身在 {@link AiPlaceholder}，这张叉乘表在
 * {@code AiVarResolver}。<b>本枚举只回答「是哪一档」，不含任何具体场景。</b>
 *
 * <h3>★★★ 为什么必须区分「不适用」和「可选」—— 它们在渲染时【收敛】，在保存时【分道】</h3>
 *
 * <table border="1">
 *   <tr><th>取值</th><th>渲染时（值缺失）</th><th>保存模板时</th></tr>
 *   <tr><td>{@link #REQUIRED}</td><td><b>失败</b>（HTTP 400，说不清缺什么就等于没说）</td><td>允许</td></tr>
 *   <tr><td>{@link #OPTIONAL}</td><td>该行整行移除 + INFO</td><td>允许</td></tr>
 *   <tr><td>{@link #NOT_APPLICABLE}</td><td>★ 同 {@code OPTIONAL}：整行移除</td>
 *       <td><b>拒绝</b>（400，告诉管理员「这个场景没有这个变量」）</td></tr>
 * </table>
 *
 * <p>★ 渲染时让 {@code NOT_APPLICABLE} 与「可选但无值」<b>收敛到同一个分支</b>，是刻意的：
 * 「这个场景不提供这个值」和「这个值这次没有」在<b>产出提示词的那一刻是同一件事</b> ——
 * 都是「没有」。给它们两个不同的渲染结果，只会多出一条没人能解释的分支。
 *
 * <p>★★ 但保存时<b>必须分开</b>：{@code NOT_APPLICABLE} 是<b>管理员写错了</b>
 * （给审核模板写 {@code {{outline}}}），在他按下保存的那一刻告诉他，
 * 半径是「一个管理员的下一次点击」；放到渲染时才炸，半径是「全体作者 × 该场景」。
 * <b>同一条判断，在两个时刻的正确答案不同 —— 这不是不一致，是「谁有能力修好它」不同。</b>
 *
 * @author MickeyChen
 */
@Getter
public enum AiVarRequirement {

    /** 参与生成的输入。缺了它模型只能瞎猜 —— 必须失败，不能降级 */
    REQUIRED("必填"),

    /** 有了更好，没有也跑得通。缺失时整行移除（不留空串、不留占位符原文） */
    OPTIONAL("可选"),

    /** 这个场景压根没有这个变量。渲染时按「没有」处理，保存时拒绝 */
    NOT_APPLICABLE("不适用");

    private final String label;

    AiVarRequirement(String label) {
        this.label = label;
    }

    /** 缺值时渲染是否必须失败。★ 只有 {@code REQUIRED} 会失败 */
    public boolean blocksRendering() {
        return this == REQUIRED;
    }

    /** 保存模板时是否允许出现。★ 只有 {@code NOT_APPLICABLE} 不允许 */
    public boolean allowedInTemplate() {
        return this != NOT_APPLICABLE;
    }
}
