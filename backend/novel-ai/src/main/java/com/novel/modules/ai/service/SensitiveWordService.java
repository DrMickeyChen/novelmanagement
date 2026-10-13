package com.novel.modules.ai.service;

import com.novel.modules.ai.dto.SensitiveWordSaveDTO;
import com.novel.modules.ai.vo.SensitiveWordVO;

import java.util.List;

/**
 * 违禁词库的管理端读写（E1/S7）。
 *
 * <p>★★★ <b>本接口的每一个写方法都【不】重建快照</b> —— 这是它最重要的一条约定，
 * 而它是刻意的，不是漏了：
 * <pre>
 *   写库（本接口）  →  「库里变了」
 *   reload（Scanner）→  「判定变了」
 * </pre>
 * 两者之间<b>必须</b>隔着一个显式动作（管理端点「立即重载」或 10 分钟的定时兜底）。
 * <p>★★ 自动 reload 会让 S7 的第三条阳性对照<b>变得无法构造</b>：
 * 「先断言未命中、再 reload 后命中」需要在「库已经改了、快照还没换」这个中间态上停住。
 * 每次保存都顺手换快照，那个中间态就<b>不存在</b>了 ——
 * 于是「快照真的被替换过」这件事也就<b>永远无法证明</b>（改了当然生效，因为每次都换）。
 * <p>★ 顺带一个更实际的收益：一次批量导入 200 个词，不会触发 200 次全表重建。
 *
 * <p>★ <b>不提供删除。</b> 停用（{@code enabled=0}）与删除在审核效果上完全一样
 * （都不再命中），区别只在可追溯性；而「谁把这个词删了」是一个答不上来的问题。
 * ★ 改错字走<b>编辑</b>，不要「新增一条再停用旧的」——
 * 后者会让词库里堆一排停用的错字，而列表页默认显示全部（停用的也要能看见、能改回来，
 * 同 {@code AiPromptService#list} 的论证）。
 *
 * @author MickeyChen
 */
public interface SensitiveWordService {

    /**
     * 列表。{@code enabled} 为空则返回全部。
     *
     * <p>★ <b>必须包含停用的词</b>：过滤掉它们，停用就变成一个改不回来的操作
     * （同 {@code AiPromptService#list} 与 {@code AdminCategoryController.list}）。
     */
    List<SensitiveWordVO> list(Integer enabled);

    /** 新增。返回新 id。★ 词重复 → {@code 5008}（库里的 {@code uk_word} 是兜底，不是唯一防线） */
    Long create(SensitiveWordSaveDTO dto);

    /** 编辑。★ 不存在 → {@code 5009}；与别的词重复 → {@code 5008} */
    void update(Long id, SensitiveWordSaveDTO dto);

    /**
     * 只改启用状态。
     *
     * <p>★★ <b>为什么单独开一个端点，而不是让前端发一次完整的 PUT</b>：
     * 它是列表页上那个开关，而开关<b>只会带上「id + 目标值」</b>。
     * 让它发一整行的话，前端必须先把那一行读出来再原样回传 ——
     * 两次读之间如果另一个人改了这条词，这次「只是关一下开关」的请求
     * 会把<b>别人的修改一并覆盖回去</b>（丢失更新），而且<b>不报任何错</b>。
     * <p>★ 这个端点只写一列，所以它天然没有那个问题。
     */
    void updateEnabled(Long id, Integer enabled);
}
