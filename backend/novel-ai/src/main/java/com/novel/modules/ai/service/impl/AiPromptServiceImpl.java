package com.novel.modules.ai.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.ResultCode;
import com.novel.modules.ai.dto.AiPromptPreviewDTO;
import com.novel.modules.ai.dto.AiPromptSaveDTO;
import com.novel.modules.ai.entity.AiPromptTemplate;
import com.novel.modules.ai.enums.AiPlaceholder;
import com.novel.modules.ai.enums.AiScene;
import com.novel.modules.ai.mapper.AiPromptTemplateMapper;
import com.novel.modules.ai.service.AiPromptService;
import com.novel.modules.ai.service.PromptRenderer;
import com.novel.modules.ai.vo.AiPromptTemplateVO;
import com.novel.modules.ai.vo.RenderedPromptVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 提示词模板的读取与管理（E1）。
 *
 * <h3>★★ 为什么【不缓存】模板</h3>
 *
 * <p>判据是「加载代价落不落在请求路径上」（判断 2.4）：
 * <ul>
 *   <li>模板的读取发生在<b>用户点按钮</b>那一刻，而它按 {@code (scene, enabled)} 查一张
 *       几十行的表 —— 单行级查询，缓存省下的那点时间远小于它引入的失效问题。</li>
 *   <li>本项目<b>刻意不接 Nacos Config</b>，多实例下缓存失效只能靠 TTL 轮询，
 *       症状是「管理员改了模板，某些请求还用旧的」—— <b>静默、随机、不可复现</b>。</li>
 * </ul>
 * ★ <b>不缓存 ⇒「改了立刻生效」是免费得到的</b>：管理端和 AI 调用在同一个 JVM 里，
 * 同一个连接池读的同一张表。
 * <p>（对照：判断 3.5 的敏感词库<b>必须</b>缓存 —— 它的构建代价落在
 * <b>消息消费路径</b>上，每条待审内容都要扫一遍全表词条。）
 *
 * @author MickeyChen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiPromptServiceImpl implements AiPromptService {

    private final AiPromptTemplateMapper templateMapper;

    @Override
    public AiPromptTemplate requireForScene(AiScene scene) {
        requireActiveTemplateScene(scene);
        /*
         * ★ 用 selectList + 取第一条，而不是 selectOne：
         *   MyBatis-Plus 的 selectOne 在命中多行时抛 TooManyResultsException ——
         *   而「一个场景有多条模板」是完全正常的配置（唯一键是 (scene, name)，
         *   不是 scene），那会让一次普通的生成变成 500。
         * ★ 排序必须是 sort ASC, id ASC，且必须和 list() 展示的顺序一致：
         *   两处各排各的，症状是「管理端看着生效的是 A，实际用的是 B」，且不报错。
         */
        List<AiPromptTemplate> rows = templateMapper.selectList(Wrappers.<AiPromptTemplate>lambdaQuery()
                .eq(AiPromptTemplate::getScene, scene)
                .eq(AiPromptTemplate::getEnabled, 1)
                .orderByAsc(AiPromptTemplate::getSort)
                .orderByAsc(AiPromptTemplate::getId));
        if (rows.isEmpty()) {
            /*
             * ★ 停用（enabled=0）和不存在【走同一个码】，这是刻意的：
             *   对作者来说两者是同一件事 —— 「这个功能现在用不了，得找管理员」。
             *   区分它们只会多一条作者看不懂的信息。
             * ★ 但服务端日志要能分开：管理端排查时「没人配」和「被停用了」修法不同。
             */
            log.warn("生成请求没有可用的提示词模板 | scene={} | 该场景下没有任何 enabled=1 的模板", scene.getCode());
            throw new BusinessException(ResultCode.AI_PROMPT_NOT_FOUND,
                    "「" + scene.getLabel() + "」功能尚未配置好提示词模板，请联系管理员。");
        }
        return rows.get(0);
    }

    @Override
    public List<AiPromptTemplateVO> list(AiScene scene) {
        if (scene != null) requireActiveTemplateScene(scene);
        List<AiPromptTemplate> rows = templateMapper.selectList(Wrappers.<AiPromptTemplate>lambdaQuery()
                .in(AiPromptTemplate::getScene, AiScene.AUDIT, AiScene.SUMMARY)
                .eq(scene != null, AiPromptTemplate::getScene, scene)
                .orderByAsc(AiPromptTemplate::getScene)
                .orderByAsc(AiPromptTemplate::getSort)
                .orderByAsc(AiPromptTemplate::getId));
        List<AiPromptTemplateVO> list = new ArrayList<>(rows.size());
        for (AiPromptTemplate row : rows) {
            list.add(toVO(row));
        }
        return list;
    }

    @Override
    public Long create(AiPromptSaveDTO dto) {
        requireActiveTemplateScene(dto.getScene());
        PromptRenderer.validateTemplate(dto.getScene(), dto.getSystemPrompt(), dto.getUserTemplate());
        assertNameAvailable(dto.getScene(), dto.getName(), null);

        AiPromptTemplate entity = new AiPromptTemplate();
        entity.setScene(dto.getScene());
        entity.setName(dto.getName());
        entity.setSystemPrompt(dto.getSystemPrompt());
        entity.setUserTemplate(dto.getUserTemplate());
        entity.setEnabled(dto.getEnabled());
        entity.setSort(dto.getSort());
        templateMapper.insert(entity);

        log.info("新增提示词模板 | id={} | scene={} | name={}", entity.getId(), dto.getScene().getCode(), dto.getName());
        return entity.getId();
    }

    @Override
    public void update(Long id, AiPromptSaveDTO dto) {
        requireActiveTemplateScene(dto.getScene());
        AiPromptTemplate exists = templateMapper.selectById(id);
        if (exists == null) {
            throw new BusinessException(ResultCode.AI_PROMPT_NOT_FOUND, "要修改的提示词模板不存在，请刷新后重试。");
        }
        requireActiveTemplateScene(exists.getScene());
        PromptRenderer.validateTemplate(dto.getScene(), dto.getSystemPrompt(), dto.getUserTemplate());
        // ★ excludeId 必须传：不排除自己的话，「原样保存」「只改个排序」都会被自己的唯一键挡住
        assertNameAvailable(dto.getScene(), dto.getName(), id);

        AiPromptTemplate entity = new AiPromptTemplate();
        entity.setId(id);
        entity.setScene(dto.getScene());
        entity.setName(dto.getName());
        entity.setSystemPrompt(dto.getSystemPrompt());
        entity.setUserTemplate(dto.getUserTemplate());
        entity.setEnabled(dto.getEnabled());
        entity.setSort(dto.getSort());
        templateMapper.updateById(entity);

        log.info("修改提示词模板 | id={} | scene={} | name={} | enabled={}",
                id, dto.getScene().getCode(), dto.getName(), dto.getEnabled());
    }

    @Override
    public RenderedPromptVO preview(AiPromptPreviewDTO dto) {
        AiScene scene = dto.getScene();
        requireActiveTemplateScene(scene);
        AiPromptTemplate template = dto.getId() == null
                ? requireForScene(scene)
                : requireByIdAndScene(dto.getId(), scene);

        Map<AiPlaceholder, String> values = toVarValues(dto.getSampleVars());
        /*
         * ★★ 这里【只做渲染】，不做 validTemplate —— 刻意如此。
         *   模板被绕过管理端改坏（含未知占位符）是它唯一的真实故障模式，
         *   而那种模板只能在这一步撞上；在这里把它拦成 400，
         *   等于把预览器改成「只能看见好模板的预览器」。
         */
        RenderedPromptVO vo = PromptRenderer.render(scene, template.getSystemPrompt(), template.getUserTemplate(), values);
        log.info("预览提示词模板 | scene={} | templateId={} | 用到={} | 移除={} | 长度={}",
                scene.getCode(), template.getId(), vo.getUsedVars(), vo.getRemovedVars(), vo.getTotalLength());
        return vo;
    }

    /* ==================== 内部实现 ==================== */

    /**
     * 按 id 取模板，并校验它属于该场景。
     *
     * <p>★ <b>不过滤 {@code enabled}</b>：停用的模板照样能预览 ——
     * 管理员停用它之前总得先看一眼它现在长什么样。
     * <p>★ 不属于该场景时<b>明确报错</b>，不静默改用「生效的那条」：
     * 静默回退会让管理员以为看的是刚点的那条、实际看的是另一条，
     * 然后据此判断「模板没问题」。
     */
    private static void requireActiveTemplateScene(AiScene scene) {
        if (scene != AiScene.AUDIT && scene != AiScene.SUMMARY) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "旧写作助手模板已退役；写作 Agent 使用独立的任务提示词");
        }
    }

    private AiPromptTemplate requireByIdAndScene(Long id, AiScene scene) {
        AiPromptTemplate template = templateMapper.selectById(id);
        if (template == null || template.getScene() != scene) {
            throw new BusinessException(ResultCode.AI_PROMPT_NOT_FOUND,
                    "要预览的模板不存在，或者不属于「" + scene.getLabel() + "」场景。");
        }
        return template;
    }

    /**
     * 把「占位符名 → 值」转成渲染器要的 {@code EnumMap}。
     *
     * <p>★★ <b>不认识的键要 400，不能静默忽略</b>：那不是「这个变量没值」，
     * 是「你在给一个系统里不存在的变量赋值」——忽略它，管理员会以为自己填的值生效了，
     * 而渲染结果里根本没有它，且<b>界面上看不出任何异常</b>。
     * <p>★ 键【不存在】和值为 {@code null}/空串/纯空白，四种写法一视同仁地当作「没有值」——
     * 那是 {@code PromptRenderer.hasValue} 的判据，这里不重复实现。
     */
    private Map<AiPlaceholder, String> toVarValues(Map<String, String> sampleVars) {
        Map<AiPlaceholder, String> values = new EnumMap<>(AiPlaceholder.class);
        if (sampleVars == null || sampleVars.isEmpty()) {
            return values;
        }
        List<String> unknown = new ArrayList<>();
        sampleVars.forEach((name, value) -> {
            AiPlaceholder placeholder = AiPlaceholder.fromToken(name);
            if (placeholder == null) {
                unknown.add(name);
            } else {
                values.put(placeholder, value);
            }
        });
        if (!unknown.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "样本数据里有系统不认识的变量名：" + unknown
                            + "。可用的变量名见本场景的 availableVars。");
        }
        return values;
    }

    private void assertNameAvailable(AiScene scene, String name, Long excludeId) {
        Long count = templateMapper.selectCount(Wrappers.<AiPromptTemplate>lambdaQuery()
                .eq(AiPromptTemplate::getScene, scene)
                .eq(AiPromptTemplate::getName, name)
                .ne(excludeId != null, AiPromptTemplate::getId, excludeId));
        if (count != null && count > 0) {
            /*
             * ★ 在写库之前查重（理由见 ResultCode.AI_PROMPT_NAME_EXISTS）。
             *   数据库那一条 uk_scene_name 仍然是兜底 —— 并发下仍可能撞上它，
             *   那时是 500，但那种情况需要一个管理员在同一秒里提交两次，不值得为它加事务。
             */
            throw new BusinessException(ResultCode.AI_PROMPT_NAME_EXISTS,
                    "「" + scene.getLabel() + "」下已经有叫「" + name + "」的模板了，换个名字。");
        }
    }

    private AiPromptTemplateVO toVO(AiPromptTemplate entity) {
        AiPromptTemplateVO vo = new AiPromptTemplateVO();
        vo.setId(entity.getId());
        vo.setScene(entity.getScene().getCode());
        vo.setSceneLabel(entity.getScene().getLabel());
        vo.setName(entity.getName());
        vo.setSystemPrompt(entity.getSystemPrompt());
        vo.setUserTemplate(entity.getUserTemplate());
        vo.setEnabled(entity.getEnabled());
        vo.setSort(entity.getSort());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        return vo;
    }
}
