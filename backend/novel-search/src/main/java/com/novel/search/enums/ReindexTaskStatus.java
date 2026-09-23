package com.novel.search.enums;

/**
 * <b>一次异步重建任务的生命周期状态（M5 · S9）。</b>
 *
 * <h3>★★★ 为什么不复用一个 {@code boolean success} 就够了 —— 这个枚举存在的全部理由</h3>
 *
 * <p>因为「<b>任务还在跑</b>」和「<b>任务永远不会完成了</b>」在没有第三个状态时
 * <b>长得一模一样</b>：两者都是「没有结果」。
 *
 * <pre>
 *   boolean          轮询方看到                   它会怎么做
 *   ─────────────────────────────────────────────────────────────
 *   还没有结果        没有结果                      继续等 ← 如果任务早就崩了，它等到天荒地老
 * </pre>
 *
 * <p>★ 铁律 8 的同一个形状，只是换了个位置：<b>一个把两种不同处境映射成同一种表现的表示法，
 * 就是一条不会失败的护栏。</b> S9 之前这个坑在同步端点里不存在
 * （同步端点要么返回、要么 500，没有「中间态」），
 * <b>是异步化【新引入】的</b> —— 所以它是 S9 必须一起交付的东西，不是可选的打磨。
 *
 * <p>★★ 三态各自的「下一步动作」必须是不同的，否则分了也白分：
 * <table border="1">
 *   <caption>状态 → 人该做什么</caption>
 *   <tr><th>状态</th><th>含义</th><th>该做什么</th></tr>
 *   <tr>
 *     <td>{@link #RUNNING}</td>
 *     <td>还在跑</td>
 *     <td>接着轮询。★ 但要<b>连带看 {@code startedAt}</b> ——
 *         一个 began 很久还没结束的 RUNNING，实际上是「进程被杀了」，
 *         只是没人来得及改写状态（见 {@code ReindexTaskStore} 的 TTL 说明）。</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #SUCCEEDED}</td>
 *     <td>跑完了</td>
 *     <td>看 {@code indexed} / {@code skipped}。<b>★ 这两个字段决定了「真的灌了」还是「什么都没做」</b>
 *         —— 见 {@code ReindexTaskVO} 里 {@code skipped} 那段。</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #FAILED}</td>
 *     <td>失败了</td>
 *     <td>看 {@code error}（有原因）和 {@code finishedAt}（什么时候失败的）。<b>不要重试到天亮</b>，
 *         先确认 content / ES 是不是好的。</td>
 *   </tr>
 * </table>
 *
 * <p>★ <b>刻意不设 {@code CANCELLED} / {@code TIMEOUT}</b>：
 * 本任务没有取消入口（重建是幂等且短的，取消它买不到什么），
 * 也没有超时判定（真要有，判据是 {@code startedAt} 而不是再造一个状态）。
 * <b>枚举里每多一个值，都要有人去处理它；没人处理的值比没有这个值更糟。</b>
 *
 * <p>★★ 落到 Redis 里用的是 {@link #name()}（字符串）。这带来一条 M3 就学过的纪律：
 * <b>给枚举改名或删值，会让【在途】的记录读不出来。</b>
 * 区别在于后果量级完全不同 —— MQ 那边是消息全进 DLQ，
 * 这边只是一条状态记录在 TTL 内解析失败（{@code ReindexTaskStore} 把它当作
 * 「读不出来」并给出明确的提示，不是静默当成 RUNNING）。
 *
 * @author MickeyChen
 */
public enum ReindexTaskStatus {

    /** 已提交，正在跑。 */
    RUNNING,

    /** 跑完了。<b>★ 注意它不代表「灌了数据」</b> —— 要连着 {@code skipped} 一起看。 */
    SUCCEEDED,

    /** 抛异常了。{@code error} 里有原因。 */
    FAILED
}
