package com.novel.modules.ai.service;

import com.novel.modules.ai.dto.AiPromptPreviewDTO;
import com.novel.modules.ai.dto.AiPromptSaveDTO;
import com.novel.modules.ai.entity.AiPromptTemplate;
import com.novel.modules.ai.enums.AiScene;
import com.novel.modules.ai.vo.AiPromptTemplateVO;
import com.novel.modules.ai.vo.RenderedPromptVO;

import java.util.List;

/**
 * 提示词模板的读取与管理（E1）。
 *
 * <p>★★ <b>这个接口有两类调用方，而它们的失败语义不同</b>（判断 2.2）：
 * <ul>
 *   <li><b>管理端</b>（列表 / 保存 / 预览）：管理员在打字，出错就该 400 并告诉他改哪儿；</li>
 *   <li><b>生成端</b>（{@link #requireForScene}）：作者按了按钮，
 *       出错是配置缺失，返 {@code 5005} 并指向管理端。</li>
 * </ul>
 *
 * @author MickeyChen
 */
public interface AiPromptService {

    /**
     * 某个场景当前<b>生效</b>的模板：{@code enabled=1} 里按 {@code sort ASC, id ASC} 取第一条。
     *
     * <p>★ 取第一条而不是「取唯一一条」：唯一键是 {@code (scene, name)}，
     * 一个场景<b>可以</b>有多条模板（比如「续写-默认」和「续写-慢节奏」）。
     * 排序规则写死在这里，是因为它必须和列表页显示的顺序一致 ——
     * 两处各排各的，症状是「管理端看着生效的是 A，实际用的是 B」，且不报错。
     *
     * @throws com.novel.common.exception.BusinessException {@code AI_PROMPT_NOT_FOUND(5005)}，
     *         当该场景没有启用的模板。★ <b>绝不用内置兜底提示词</b>，理由见那个错误码的注释
     */
    AiPromptTemplate requireForScene(AiScene scene);

    /**
     * 列表。{@code scene} 为空则返回全部。
     *
     * <p>★ 管理端列表<b>必须包含停用的模板</b>，与 {@code AdminCategoryController.list} 同一条论证：
     * 过滤掉停用的，管理员就再也看不到它、也就改不回来，停用会变成一个不可逆操作。
     */
    List<AiPromptTemplateVO> list(AiScene scene);

    /** 新增。返回新 id。★ 同场景重名 → {@code 5006}；占位符非法 → 400（见 {@code PromptRenderer.validateTemplate}） */
    Long create(AiPromptSaveDTO dto);

    /** 编辑。★ 不存在 → {@code 5005}（复用：它就是「这条模板找不到」）；同场景重名 → {@code 5006} */
    void update(Long id, AiPromptSaveDTO dto);

    /**
     * 预览：<b>把库里那份模板渲染出来</b>，不调模型、不扣配额、不写任何表。
     *
     * <p>★ 这是 S2 的验证载体，也是管理端唯一能在「作者按按钮之前」
     * 看见作者会遇到什么的入口。详细论证见 {@link AiPromptPreviewDTO} 的类注释。
     *
     * <p>★★ <b>它【不做】保存时的合法性校验</b>，这是刻意的：
     * 模板被绕过管理端改坏（含未知占位符）正是预览最该暴露的故障，
     * 而那种情况只能在渲染时才撞得到 —— 渲染会抛 {@code IllegalStateException} 走 500。
     */
    RenderedPromptVO preview(AiPromptPreviewDTO dto);
}
