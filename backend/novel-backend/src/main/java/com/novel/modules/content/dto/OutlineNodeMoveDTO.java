package com.novel.modules.content.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 拖拽移动 / 排序大纲节点的入参。
 *
 * <p><b>★★ 形状是 {@code {targetParentId, beforeNodeId}}，这个选择要讲清楚</b> ——
 * 它是 P4 里少数几个「接口形状直接决定实现难度」的地方。
 *
 * <table border="1">
 *   <caption>被否决的两种形状</caption>
 *   <tr><th>方案</th><th>为什么不行</th></tr>
 *   <tr>
 *     <td>传「排好序的完整 id 数组」</td>
 *     <td>前端每次拖拽都要算出完整的新顺序，而 {@code el-tree} 的 drop 事件
 *         给的是「拖到了哪个节点上、在它的前 / 后 / 里面」，
 *         <b>不是新数组</b> —— 还得反推一遍。</td>
 *   </tr>
 *   <tr>
 *     <td>传 {@code position: 2}（索引）</td>
 *     <td><b>★ 致命伤：没有任何 position 值能表达「追加到末尾」。</b>
 *         末尾索引取决于当前有几个兄弟，而算出来的那一瞬间就可能过期
 *         （别人刚插了一个）。而「拖到某个节点里 = 追加为最后一个子节点」
 *         恰恰是最高频的操作。
 *         <br>次一级的问题：{@code position} 是<b>相对量</b>，
 *         两个人同时拖拽时同一个「2」对应两份不同的兄弟列表。</td>
 *   </tr>
 * </table>
 *
 * <p><b>★ 采纳 {@code beforeNodeId} 的理由：{@code null} 唯一且稳定地表示「追加到末尾」。</b>
 * 而且三种拖拽位置到入参的映射是<b>零算术</b>的，没有 off-by-one 的空间：
 * <pre>
 *   dropType = 'inner'  → { targetParentId: 目标id,  beforeNodeId: null }
 *   dropType = 'after'  → { targetParentId: 父id,    beforeNodeId: 下一个兄弟id 或 null }
 *   dropType = 'before' → { targetParentId: 父id,    beforeNodeId: 该节点id }
 * </pre>
 *
 * <p><b>★ 前端注意</b>：{@code el-tree} 根层级的 {@code data.parent.data}
 * 是一个<b>包裹用的虚拟根</b>，它的 id 是 {@code undefined}。
 * 必须转成 {@code null} 再发 —— 直接发 {@code undefined} 会被
 * JSON 序列化丢掉（或者变成字符串 "undefined"），
 * 后端就会把「移到根下」理解成别的东西。
 *
 * @author MickeyChen
 */
@Data
@Schema(description = "拖拽移动大纲节点入参")
public class OutlineNodeMoveDTO {

    /**
     * 目标父节点 id。<b>为空 = 移到根层级</b>（成为根节点的子节点）。
     *
     * <p><b>★★ 这个值来自请求体，必须校验归属</b>（{@code requireNodeInNovel}）。
     * 不校验的后果见 {@code NovelAccessGuard#requireNodeInNovel} ——
     * 节点会<b>在树上完全消失且没有任何错误</b>。
     *
     * <p>★ 另外它还要过「防环」那一关：不能是当前节点自己或它的子孙。
     */
    @Schema(description = "目标父节点 id。不传 = 移到根层级")
    private Long targetParentId;

    /**
     * 插到哪个兄弟<b>前面</b>。<b>为空 = 追加到末尾</b>。
     *
     * <p><b>★ 这个字段是「追加到末尾」和「插在中间」的唯一区分方式</b>
     * （见类注释里对 {@code position} 方案的否决）。
     *
     * <p>★ 它必须和 {@code targetParentId} 是<b>同一批兄弟</b>，
     * 否则就是前后端约定被打破了 —— 这种情况抛 500 而不是业务错误码，
     * 因为它不是用户能做出来的操作（见 {@code OutlineServiceImpl#move}）。
     */
    @Schema(description = "插到这个兄弟节点前面。不传 = 追加到末尾")
    private Long beforeNodeId;
}
