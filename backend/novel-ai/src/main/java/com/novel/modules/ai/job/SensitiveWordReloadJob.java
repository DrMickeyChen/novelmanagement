package com.novel.modules.ai.job;

import com.novel.modules.ai.service.SensitiveWordScanner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * <b>敏感词库快照的定时兜底重建（E1/S7）—— 每 10 分钟一次。</b>
 *
 * <p>★ 它本身<b>没有任何逻辑</b>，全部工作在 {@link SensitiveWordScanner#reload()}。
 * 这不是「薄封装」，是刻意的：<b>同一件事有三个触发源</b>（启动时的 {@code ApplicationRunner}、
 * 管理端的「立即重载」按钮、这里的定时），它们必须走<b>同一条实现</b>。
 * ★ 三个触发源各写一份的话，最先漂移的是「失败时怎么办」那一份 ——
 * 而它的症状是<b>某一条路径上快照被清空了，另外两条没有</b>，两边日志都正常。
 * （形状同 search 的 {@code IndexRebuildJob}，见那里的类注释。）
 *
 * <h3>★★ 定时兜底存在的理由：管理端那个按钮【不一定会被按】</h3>
 *
 * <p>「改完词点一下重载」是一个<b>依赖人记得</b>的动作。而词库这件事的失败方向很坏：
 * 管理员加了一个词、忘了点重载，于是<b>那个词一直不生效</b>，而界面上一切正常
 * —— 词在列表里、开关是开的、状态页显示「快照就绪」（只是词条数少 1）。
 * ★★ 定时兜底就是「不需要谁记得」的那条路：<b>最多陈旧 10 分钟。</b>
 * （同 M4 的「定时全量重建」相对增量消息的地位：正常状态下它一次都不该被需要。）
 *
 * <h3>★★ 10 分钟是怎么来的</h3>
 *
 * <p>它就是<b>「词库能有多旧」的上界</b>，两个方向都有代价：
 * <pre>
 *   调短 → 每次重建都要读一遍全表 + 建自动机（词库大时是实打实的开销），
 *          而这个开销换来的只是「提前几分钟生效」
 *   调长 → 管理员忘了点重载时，新词要更久才开始命中
 * </pre>
 * ★ 10 分钟与 interaction 的收藏数对账同频（{@code NovelStatReconcileJob}）——
 * 本项目刻意不把这些定时任务错开：此时只有三个任务，
 * <b>撞在同一秒也只是两台机器上各自一个线程</b>，没有需要回避的惊群。
 * ★ 这条判断<b>随规模变</b>：任务多到几十个时就必须错开了，届时改的是这一行。
 *
 * <h3>★ 失败处理：吞掉、记 ERROR、等下一轮</h3>
 *
 * <p>同 {@code NovelStatReconcileJob} / {@code IndexRebuildJob} 的形状：
 * 定时任务线程抛出去的异常到不了任何用户，只会变成框架打印的一行栈，
 * <b>所以必须自己接住</b>。★ 而这里还有第二层保险：
 * {@code reload()} <b>本身就永不抛异常</b>，且失败时<b>不销毁旧快照</b> ——
 * 所以「这一轮失败了」最坏的结果是「快照再旧 10 分钟」，不是「审核停摆」。
 *
 * <h3>★★★ 它靠的是一份【继承来的】能力</h3>
 *
 * <p>{@code @EnableScheduling} 不在 content —— 它在 {@code novel-common} 的
 * {@code SchedulingConfig} 里，本服务因为依赖 {@code novel-common} 而<b>白拿到了这个能力</b>。
 * ★★ <b>这件事必须写下来</b>，否则下一个人会因为「ai 模块里没有任何地方写过
 * {@code @EnableScheduling}」而得出「本类根本没被调度」的结论 ——
 * 而那个结论<b>不报任何错</b>，只是词库永远只有启动那一刻的样子。
 * ★ <b>另一个陷阱</b>：本项目里已经有过 {@code @Scheduled} 的线程前缀是
 * {@code novel-sched-}（{@code NovelViewAggregator}）—— 排查时别把本类的线程当成野线程。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SensitiveWordReloadJob {

    private final SensitiveWordScanner sensitiveWordScanner;

    /**
     * 每 10 分钟重建一次词库快照。
     *
     * <p>★ 用 {@code cron} 而不是 {@code fixedDelay}：这里表达的是<b>「每 10 分钟检查一次」</b>
     * 这个日历语义，{@code fixedDelay} 表达的是「上一轮结束后隔 10 分钟」——
     * 正常情况下一样，但前者的周期是确定可预期的，后者会随执行时间漂移。
     * <p>★ 它在快照<b>没有变化</b>时也会重建一遍（version 照样 +1）。
     * 这是刻意的：{@code reload()} 里没有「和上一份比对、一样就不换」的逻辑，
     * 因为那个比对要么按内容哈希（多存一份指纹），要么按 {@code update_time}
     * （漏掉「删了一行」这种变化）—— <b>两条路都会引入一个会静默失效的判据</b>，
     * 而它们省下的只是一次每 10 分钟一次的全表读。
     */
    @Scheduled(cron = "0 */10 * * * *")
    public void reloadSensitiveWords() {
        /*
         * ★ 返回值直接用不上（本任务只要「跑过」），所以这里【刻意不打日志】：
         *   成功与失败的细节 SensitiveWordScanner 已经记全了，
         *   在这里再打一行只会让人以为有两处在重建。
         */
        sensitiveWordScanner.reload();
    }
}
