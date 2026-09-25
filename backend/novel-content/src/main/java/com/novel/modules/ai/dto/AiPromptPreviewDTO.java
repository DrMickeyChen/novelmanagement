package com.novel.modules.ai.dto;

import com.novel.modules.ai.enums.AiScene;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

/**
 * 预览提示词模板的入参（E1）。
 *
 * <h3>★★★ 为什么预览渲染的是【库里的那一份】，而不是编辑器里的缓冲</h3>
 *
 * <p>管理端的模板编辑器当然想「边改边看」。但那会让预览的检验力<b>归零</b>，
 * 而且归零的方式很隐蔽：
 *
 * <ul>
 *   <li>保存接口已经有 {@code validateTemplate}（未知占位符一律 400），
 *       所以<b>编辑器缓冲里根本构造不出未知占位符</b> ——
 *       一个只能预览「合法内容」的预览器，<b>永远不会告诉你库里那份已经坏了</b>。</li>
 *   <li>而「库里那份坏了」正是模板唯一的真实故障模式：
 *       有人<b>绕过管理端直接改库</b>（判断 2.2 的最后一档）。</li>
 * </ul>
 *
 * <p>★ 所以判据是：<b>预览必须渲染「真正会被执行的那一份」</b>。
 * 传 {@code id} 就预览那一条（含已被停用的、含被改坏的），
 * 不传就预览<b>该场景当前生效的那一条</b>（生成接口会用到的正是它）。
 * ★ 两种模式合起来，管理员能在<b>作者按按钮之前</b>看见作者会遇到什么。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "提示词模板预览入参")
public class AiPromptPreviewDTO {

    /** 场景。★ 即使传了 {@code id} 也要传 —— 它用来校验那条模板确实属于这个场景 */
    @Schema(description = "场景：1大纲 2正文 3灵感 4润色 5违禁审核 6前情摘要", example = "2",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "场景不能为空")
    private AiScene scene;

    /**
     * 要预览的模板 id。<b>不传 = 预览该场景当前生效的那一条。</b>
     *
     * <p>★ 传了但那条模板不存在／不属于该场景 → 400，而不是静默回退到「生效的那条」：
     * 静默回退会让管理员以为自己看的是刚改的那条，<b>实际看的是另一条</b>，
     * 然后据此判断「模板没问题」。这正是本项目一直在防的形状。
     */
    @Schema(description = "模板 id。不传 = 预览该场景当前生效的那一条", example = "2")
    private Long id;

    /**
     * 样本变量值：<b>占位符名字（不带花括号）→ 值</b>。
     *
     * <p>★ 键用不带花括号的名字，与 {@code RenderedPromptVO.availableVars} 的键一致 ——
     * 前端把一个对象原样传回来即可，中间不需要再做一次剥壳。
     *
     * <p>★★ <b>「没有这个变量」和「这个变量没值」在这里是同一个表达</b>：
     * 键不存在、值为 {@code null}、值为空串、值为纯空白，四种写法<b>结果完全一样</b>。
     * 这是刻意的 —— 预览是给人看的，不该要求他区分四种「没有」的写法。
     * ★ 但<b>键写错了（一个系统不认识的变量名）会 400</b>：
     * 那不是「没值」，是「你在给一个不存在的变量赋值」，静默忽略等于骗他。
     */
    @Schema(description = "样本变量值：占位符名 → 值。缺的键按「没有值」处理")
    private Map<String, String> sampleVars;
}
