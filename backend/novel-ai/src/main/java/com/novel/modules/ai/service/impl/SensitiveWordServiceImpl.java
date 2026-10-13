package com.novel.modules.ai.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.ResultCode;
import com.novel.modules.ai.dto.SensitiveWordSaveDTO;
import com.novel.modules.ai.entity.SensitiveWord;
import com.novel.modules.ai.enums.AiRiskLevel;
import com.novel.modules.ai.mapper.SensitiveWordMapper;
import com.novel.modules.ai.service.SensitiveWordService;
import com.novel.modules.ai.vo.SensitiveWordVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 违禁词库的管理端读写（E1/S7）。
 *
 * <p>★ 与 {@code AiPromptServiceImpl} 同形：不缓存（缓存的是<b>自动机快照</b>，
 * 由 {@code SensitiveWordScanner} 持有），所有写入都<b>不</b>顺手 reload ——
 * 理由见 {@link SensitiveWordService} 的类注释。
 *
 * <h3>★★ 唯一一处不那么显然的实现：{@code word} 的 trim</h3>
 *
 * <p>「甲」和「 甲 」在人的眼里是同一个词，在 {@code uk_word} 眼里是两条 ——
 * 而它们的命中行为完全一样。★ 不 trim 的话，管理员会看到列表里两条长得一模一样的词，
 * 并且<b>无法解释为什么加了新词之后旧文本才开始命中</b>（其实早就命中过，是那条带空格的）。
 * <p>★★ 更糟的一种：{@code "占位敏感词 甲"}（<b>中间</b>带空格）。
 * trim 管不到它，而它<b>永远不可能被正文命中</b> —— 库里有一条「看起来在保护，实际是死的」词条。
 * ★ 这个不在服务端拦：判据是「它是不是用户真的可能想要的」，
 * 而「词中间有空格」在中文里没有正当用途、在英文词组里却完全正常（{@code "bad word"}）。
 * 拦它会把英文词库挡死，所以只在<b>保存成功后的提示</b>里由前端提示（见 docs/E1 的「不做」）。
 *
 * @author MickeyChen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensitiveWordServiceImpl implements SensitiveWordService {

    private final SensitiveWordMapper sensitiveWordMapper;

    @Override
    public List<SensitiveWordVO> list(Integer enabled) {
        List<SensitiveWord> rows = sensitiveWordMapper.selectList(Wrappers.<SensitiveWord>lambdaQuery()
                .eq(enabled != null, SensitiveWord::getEnabled, enabled)
                /*
                 * ★ 排序按 等级倒序 → id 升序：运营打开这一页最先要看的是高危的那几条。
                 * ★ 带 id 兜底是铁律：同秒写入的多条词靠 create_time 排不出稳定顺序，
                 *   而翻页顺序会跳（同 P5 的「按时间倒序一律带 id DESC」）。
                 */
                .orderByDesc(SensitiveWord::getLevel)
                .orderByAsc(SensitiveWord::getId));

        List<SensitiveWordVO> list = new ArrayList<>(rows.size());
        for (SensitiveWord row : rows) {
            list.add(toVO(row));
        }
        return list;
    }

    @Override
    public Long create(SensitiveWordSaveDTO dto) {
        String word = normalize(dto.getWord());
        assertWordAvailable(word, null);

        SensitiveWord entity = new SensitiveWord();
        entity.setWord(word);
        entity.setLevel(dto.getLevel());
        entity.setCategory(dto.getCategory());
        entity.setEnabled(dto.getEnabled());
        entity.setRemark(dto.getRemark());
        sensitiveWordMapper.insert(entity);

        /*
         * ★ 这条日志要说清「还需要 reload」—— 否则看到「新增成功」的人会以为
         *   下一次审核就会命中它，而事实是快照还是旧的。
         * ★★ 这不是一句可有可无的提醒：本类所有写入都不 reload（见接口注释），
         *   「改了不生效」是一个<b>设计出来的</b>中间态，它必须被说出来。
         */
        log.info("新增违禁词 | id={} | word={} | level={} | enabled={} | ★ 需 reload 后生效",
                entity.getId(), word, dto.getLevel(), dto.getEnabled());
        return entity.getId();
    }

    @Override
    public void update(Long id, SensitiveWordSaveDTO dto) {
        SensitiveWord exists = sensitiveWordMapper.selectById(id);
        if (exists == null) {
            throw new BusinessException(ResultCode.AI_WORD_NOT_FOUND, "要修改的违禁词不存在，请刷新后重试。");
        }

        String word = normalize(dto.getWord());
        // ★ excludeId 必须传：不排除自己的话，「原样保存」会被自己的唯一键挡住
        assertWordAvailable(word, id);

        SensitiveWord entity = new SensitiveWord();
        entity.setId(id);
        entity.setWord(word);
        entity.setLevel(dto.getLevel());
        entity.setCategory(dto.getCategory());
        entity.setEnabled(dto.getEnabled());
        entity.setRemark(dto.getRemark());
        sensitiveWordMapper.updateById(entity);

        log.info("修改违禁词 | id={} | word={} | level={} | enabled={} | ★ 需 reload 后生效",
                id, word, dto.getLevel(), dto.getEnabled());
    }

    @Override
    public void updateEnabled(Long id, Integer enabled) {
        SensitiveWord exists = sensitiveWordMapper.selectById(id);
        if (exists == null) {
            throw new BusinessException(ResultCode.AI_WORD_NOT_FOUND, "要操作的违禁词不存在，请刷新后重试。");
        }
        if (exists.getEnabled() != null && exists.getEnabled().equals(enabled)) {
            /*
             * ★ 值没变就直接返回，不写库、不报错（幂等）。
             *   列表页的开关双击一次就会走到这里 —— 报错会让管理员以为操作失败，
             *   而其实状态正是他想要的那个。
             */
            return;
        }

        /*
         * ★★ 用 lambdaUpdate().set(...) 而不是 updateById(实体)：
         *   后者会把实体里所有【非 null】字段一起写回去，
         *   而这里只想动 enabled 一列。★ 更重要的是这正是「只改开关」
         *   这个端点的全部价值 —— 它不该有能力覆盖别的列（见接口注释里的丢失更新）。
         */
        sensitiveWordMapper.update(null, Wrappers.<SensitiveWord>lambdaUpdate()
                .eq(SensitiveWord::getId, id)
                .set(SensitiveWord::getEnabled, enabled));

        log.info("违禁词启用状态变更 | id={} | word={} | {} → {} | ★ 需 reload 后生效",
                id, exists.getWord(), exists.getEnabled(), enabled);
    }

    /* ==================== 内部实现 ==================== */

    /**
     * 规范化：去掉两端空白。
     *
     * <p>★ {@code null} 进不来（DTO 上是 {@code @NotBlank}），但这里仍然兜一下 ——
     * 这个方法将来可能被别处调用，而 {@code null} 传到 {@code trim()} 是一次 500。
     */
    private static String normalize(String word) {
        return word == null ? null : word.trim();
    }

    /**
     * 查重。
     *
     * <p>★★ <b>在写库之前查，但数据库那条 {@code uk_word} 仍然是兜底</b>
     * （同 {@code AiPromptServiceImpl#assertNameAvailable}）：并发下仍可能撞上唯一键，
     * 那时是 500。★ 为一个需要两个管理员在同一秒提交同一条词才会出现的场面加事务，
     * 不值得 —— 而这个查重的价值在于把 99.99% 的情况变成一句能看懂的话。
     *
     * <p>★★ <b>比较的是 trim 之后的值</b>，所以 {@code "甲"} 与 {@code " 甲 "} 在这里
     * 已经是同一个词了。★ 而 MySQL 的 {@code utf8mb4_unicode_ci} 更宽
     * （大小写、全半角都不敏感），所以库里那条唯一键比这个查询<b>更严</b> ——
     * 方向是安全的：最坏情况是被唯一键挡成 500，而不是库里出现两条等价词。
     */
    private void assertWordAvailable(String word, Long excludeId) {
        Long count = sensitiveWordMapper.selectCount(Wrappers.<SensitiveWord>lambdaQuery()
                .eq(SensitiveWord::getWord, word)
                .ne(excludeId != null, SensitiveWord::getId, excludeId));
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.AI_WORD_EXISTS,
                    "「" + word + "」已经在词库里了。要改它的等级或分类，请直接编辑那一条。");
        }
    }

    /**
     * 实体 → VO。
     *
     * <p>★ {@code levelLabel} 由服务端从 {@link AiRiskLevel} 取，前端不要自己映射 ——
     * 同 {@code AiPromptTemplateVO.sceneLabel} 的理由：那份映射漏改的症状是
     * <b>表格里显示错的等级</b>，而接口一切正常。
     */
    private SensitiveWordVO toVO(SensitiveWord entity) {
        SensitiveWordVO vo = new SensitiveWordVO();
        vo.setId(entity.getId());
        vo.setWord(entity.getWord());
        vo.setLevel(entity.getLevel());
        /*
         * ★ 用宽松的 labelOf 而不是 fromCode：level 是库里的裸 TINYINT，
         *   一行脏数据不该让整个列表 500。★ 未知值只是标签留空，
         *   裸数字照样显示 —— 见 AiRiskLevel.labelOf 的注释。
         */
        vo.setLevelLabel(AiRiskLevel.labelOf(entity.getLevel()));
        vo.setCategory(entity.getCategory());
        vo.setEnabled(entity.getEnabled());
        vo.setRemark(entity.getRemark());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        return vo;
    }
}
