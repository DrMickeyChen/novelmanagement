package com.novel.search.vo;

/**
 * <b>提交一次异步重建后立刻返回的东西（M5 · S9）。</b>
 *
 * <h3>★★★ 它【只有一个字段】—— 而这个「少」是刻意的，是 S9 的核心取舍</h3>
 *
 * <p>S9 之前，{@code POST /api/admin/search/reindex} 返回的是
 * {@code RebuildReport{skipped, indexed, elapsedMs}}，也就是<b>结果</b>。
 * 现在它只返回一个 {@code taskId}，也就是<b>收据</b>。
 *
 * <pre>
 *   S9 之前：  提交 ──────────────► 结果         （一个请求里既提交又拿到结果）
 *   S9 之后：  提交 ──► taskId
 *                       └─► 拿着 taskId 去轮询 ──► 结果
 * </pre>
 *
 * <p>★★ <b>于是「提交成功」不再等于「重建成功」</b> ——
 * 这是异步化<b>卖掉</b>的东西，必须写在这里，否则下一个人会把
 * {@code code=200} 读成「索引已经好了」。
 * 完整的对照写在 {@code AdminSearchController} 的类注释里（买到了什么 / 卖掉了什么）。
 *
 * <p>★ 也正因如此，本类<b>刻意不叫 {@code ReindexResultVO}</b>：
 * 名字里带 Result 会让人以为拿到它就拿到了结果。
 * <b>它是一张收据，不是一个结论。</b>
 *
 * <p>★ 前端的正确用法只有一种：拿到 {@code taskId} 后开始轮询
 * {@code GET /api/admin/search/reindex/{taskId}}，直到 {@code status}
 * 不是 {@code RUNNING} 为止。
 *
 * @param taskId 任务 id。★ 它同时是 Redis 里那把 key 的一部分
 *               （{@code novel:reindex:task:{taskId}}），也是唯一的查询凭据
 * @author MickeyChen
 */
public record ReindexSubmitVO(String taskId) {
}
