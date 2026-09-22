package com.novel.modules.content.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 整棵大纲树的出参。
 *
 * <p><b>★★ 这里包一层对象、用 {@code roots} <u>数组</u>而不是直接返回单个根节点，
 * 是两个各自独立的决定，都要说清楚。</b>
 *
 * <h3>一、为什么用数组，而不是单个 root</h3>
 * 「一本书只有一个根节点」这条规则<b>数据库拦不住</b>：
 * {@code parent_id IS NULL} 的行，MySQL 没有「条件唯一索引」这种东西
 * （唯一索引里 NULL 互不相等，{@code UNIQUE(novel_id, parent_id)}
 * 对多行 NULL 完全无效）。所以它只能由 Service 层保证
 * （见 {@code OutlineServiceImpl#create} 的 {@code 3008} 分支）。
 * <p>那万一漏进去两个根呢？
 * <ul>
 *   <li>返回<b>单个</b> root 对象 → 组装时拿到 List、赋值给单个字段 →
 *       多出来的那个要么被丢掉（数据静默消失），要么反序列化直接失败 →
 *       <b>整个创作中心打不开</b>。</li>
 *   <li>返回<b>数组</b> → 最坏的情况只是渲染出两棵树，
 *       作者看得见、能自己删掉一个。<b>故障被降级成「看起来有点怪」。</b></li>
 * </ul>
 * 这就是选数组的理由 —— 不是「数组更灵活」，而是<b>它把一种致命故障降级了</b>。
 *
 * <h3>二、为什么要包一层对象，而不是直接返回数组</h3>
 * 直接返回 {@code List<OutlineNodeVO>} 的话，将来想加
 * 「全书总节点数」「最后更新时间」这类字段就没有地方放，
 * 只能改成对象 —— 那是一次破坏性的接口变更。
 * 包一层的成本是前端多写一个 {@code .roots}，一次性的。
 *
 * <p><b>★ 「这本书还没有大纲」返回的是 {@code {roots: []}}，
 * 不是被 {@code non_null} 剥掉字段的空对象。</b>
 * {@link #roots} 有初值，所以它在 JSON 里永远存在，
 * 前端不用写 {@code res.roots ?? []} 这种防御代码。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "整棵大纲树")
public class OutlineTreeVO {

    /**
     * 根节点列表。<b>正常情况长度是 1</b>（一本书一个根），
     * 空数组表示还没建大纲，大于 1 表示数据有异常（见类注释）。
     */
    @Schema(description = "根节点列表。★ 正常长度为 1，空数组表示还没建大纲")
    private List<OutlineNodeVO> roots = new ArrayList<>();

    /** 节点总数（含所有层级）。★ 前端要在别处显示「共 N 个节点」时用得上，省一次递归 */
    @Schema(description = "节点总数（含所有层级）", example = "37")
    private Integer totalNodes = 0;
}
