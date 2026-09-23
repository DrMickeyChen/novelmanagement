package com.novel.search.mq.job;

import com.novel.search.service.IndexRebuildService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * <b>索引的定时全量重建（M4 / S8）—— 每 6 小时一次，把整个索引重灌一遍。</b>
 *
 * <p>★ 它本身<b>没有任何逻辑</b>，全部工作（锁、删索引、翻页灌数据）都在
 * {@link IndexRebuildService#rebuildAll()}。这不是「薄封装」，是刻意的：
 * <b>同样那一件事有三个触发源</b>（这里的定时、{@code IndexInitializer} 的启动自愈、
 * {@code AdminSearchController} 的管理端点），它们必须走<b>同一条实现、同一把锁</b>。
 * ★ 三个触发源各写一份的话，最先漂移的一定是「加锁」那一份 ——
 * 而它的症状是「索引时有时无」，两个实例的日志都正常。
 *
 * <h3>★★ 它为什么存在：这是「最终一致」里那个「最终」的兑现</h3>
 *
 * <p>完整论证在 {@link IndexRebuildService} 的类注释里。一句话：
 * S7 那条 {@code NOVEL_INDEX} 消息在发送失败 / 重试耗尽进死信 / 写入口忘加落点
 * 这三种情况下<b>永久丢失，且没有任何东西会再试一次</b>。
 * 本任务就是那个「再试一次」—— <b>它不去分辨成因，整个重灌，三种情况一起修掉。</b>
 *
 * <h3>★★ 6 小时是怎么来的</h3>
 *
 * <p>它就是<b>「索引能有多旧」的上界</b>，而取值的两个方向都有代价：
 * <pre>
 *   调短 → 每次重建都 DELETE 索引，窗口内全站走降级路径（ES 的排序能力关掉一段时间）
 *   调长 → 消息真丢了的时候，用户可能长时间搜不到一本刚改过名的书
 * </pre>
 * ★ <b>6 小时这个数能从「上面那三种都是低频事件」推出来</b>，不是拍的：
 * 正常情况下 S7 的增量消息是秒级的，<b>本类一次都不该被需要</b>。
 * 它正确的运行状态是「每 6 小时跑一次、每次都发现没什么要修」。
 *
 * <h3>★★★ 这段是 M4 唯一使用 {@code @Scheduled} 的地方，而它靠的是一份【继承来的】能力</h3>
 *
 * <p>{@code @EnableScheduling} 不在本服务 —— 它在 {@code novel-common} 的
 * {@code SchedulingConfig} 里，本服务因为依赖 {@code novel-common} 而<b>白拿了这个能力</b>。
 * ★★ <b>这件事必须写下来</b>，否则下一个人会因为「search 里没有任何地方写过
 * {@code @EnableScheduling}」而得出「本类根本没被调度」的结论 ——
 * 而那个结论<b>不报任何错</b>，只是索引永远不重建。
 * （S2 的探针已经实测过：search 里没有 {@code @Scheduled} 方法时不会报错，
 * 而加了方法之后确实被调度 —— 见本任务的实测记录。）
 *
 * <h3>★ 失败处理：吞掉、记 ERROR、等下一轮</h3>
 *
 * <p>和 {@code NovelStatReconcileJob} 同一个形状：定时任务线程抛出去的异常
 * 到不了任何用户，只会变成框架打印的一行栈。<b>所以这里必须自己接住。</b>
 * ★ 处置是<b>记 ERROR 然后什么都不做</b> —— 下一轮 6 小时后再来。
 * ★★ 有一条例外值得说清：<b>不重试、不缩短间隔</b>。
 * 失败最常见的原因是 ES / content 不可达，那是分钟级就能恢复的事 ——
 * 而等待它恢复是<b>降级路径</b>的职责（用户此刻搜索仍然可用，只是走 LIKE），
 * 不是本任务的职责。本任务只负责「最终」那一段。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexRebuildJob {

    private final IndexRebuildService indexRebuildService;

    /**
     * 每 6 小时（整点）重灌一次索引。锁、轮次、页大小全部在 {@link IndexRebuildService}。
     *
     * <p>★ 用 {@code cron} 而不是 {@code fixedDelay}，理由同
     * {@code NovelStatReconcileJob}：这里要表达的是<b>「每 6 小时检查一次」</b>
     * 这个日历语义，{@code fixedDelay} 表达的是「上一轮结束后隔 6 小时」——
     * 正常情况下一样，但前者的周期是确定可预期的，后者会随执行时间漂移。
     *
     * <p>★★ 时钟点选整点（{@code 0 0}）而不是错开：本项目的定时任务只有两个
     * （另一个是 interaction 的收藏数对账，每 10 分钟），
     * 撞在同一秒也只是两台机器上各自一个线程 —— 没有需要回避的惊群。
     * ★ <b>这条判断是随规模变的</b>：任务多到几十个时就必须错开了，
     * 届时改的是这一行，而不是「加一个随机延迟」那种让 cron 变得不可读的写法。
     */
    @Scheduled(cron = "0 0 */6 * * *")
    public void rebuildIndex() {
        try {
            IndexRebuildService.RebuildReport report = indexRebuildService.rebuildAll();

            /*
             * ★ skipped 走 INFO 而不是 WARN：多实例部署时「另一个实例正在重建」
             *   是完全正常的排班结果，不是故障。★ 但文案要写全，
             *   否则看到这行的人会以为是「重建成功、0 条」。
             */
            if (report.skipped()) {
                log.info("定时索引重建：本轮跳过（没拿到锁，另一个实例在重建）");
                return;
            }

            /*
             * ★ 正常的一轮就是这一行：灌入条数与上一轮一致、耗时秒级。
             *   它【每一次都要打】—— 因为「这条日志一直在、条数一直没变」
             *   本身就是「增量消息那条链路是健康的」这个结论的证据。
             *   反过来，条数突然变了就说明中间有东西漂了，值得回头看。
             */
            log.info("定时索引重建完成 | 灌入={} 条 耗时={}ms", report.indexed(), report.elapsedMs());

        } catch (Exception e) {
            /*
             * ★★ ERROR 级 + 把「会怎样」一起写出来：这条日志唯一的价值是
             *    让「索引已经陈旧了」这件事在日志里有一个明确的时间点。
             *    不说清楚的话，读日志的人只知道「重建失败了」，
             *    不知道「此刻全站搜索的排序退回阅读量了」。
             */
            log.error("定时索引重建失败，索引保持上一轮的内容（可能已陈旧）"
                            + " | 搜索仍可用（走降级路径或旧索引），下一轮 6 小时后再试。原因={}: {}",
                    e.getClass().getSimpleName(), e.getMessage(), e);
        }
    }
}
