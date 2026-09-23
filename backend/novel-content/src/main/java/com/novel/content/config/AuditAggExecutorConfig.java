package com.novel.content.config;

import com.novel.common.concurrent.ContextPropagatingTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * <b>管理端审核日志「跨服务取值」用的线程池（M5 · S7）。</b>
 *
 * <p>{@code AuditLogServiceImpl} 渲染一页日志要打三次跨服务调用
 * （content→interaction 取评论素材、content→auth 取申请摘要、
 * content→auth 取操作人昵称），它们之间<b>没有任何依赖</b> ——
 * 串行发就是白等两个往返。M5 把它们改成并行。
 *
 * <h3>★★★ 为什么【绝不能用】{@code ForkJoinPool.commonPool}</h3>
 *
 * <p>{@code CompletableFuture.supplyAsync(fn)} 不传 Executor 时用的就是它，
 * 而那是<b>JVM 全局</b>的一个池：一个慢下游会把它占满，
 * 然后<b>整个 JVM 里所有用到 commonPool 的地方一起卡住</b> ——
 * 包括任何并行流。★ 症状是「某个<b>毫不相干</b>的接口变慢了」，
 * 排查方向会完全跑偏，而且<b>没有任何日志</b>指向这里。
 *
 * <p>★ 所以：<b>名字要是专用的（{@code novel-audit-agg-*}），池要是自己的。</b>
 * 线程名带前缀这件事不是好看 —— 线程 dump 里认不出来就等于没有这个池。
 *
 * <h3>★ 为什么队列是有界的 + {@code CallerRunsPolicy}</h3>
 *
 * <p>无界队列（{@code LinkedBlockingQueue} 不设容量）会让
 * {@code maxPoolSize} 永远用不上、任务无限堆积，直到 OOM。
 * ★ 这里有界 + <b>显式</b>拒绝策略：
 *
 * <pre>
 *   队列满 → CallerRunsPolicy → 任务在【请求线程】上直接跑完
 * </pre>
 *
 * <p>这是刻意的选择，不是随手挑的默认值：
 * 审核日志是<b>只读的管理端页面</b>，队列满时「慢一点但给得出结果」
 * 明显好过 {@code AbortPolicy} 的「当场 500」。
 * ★★ 而它能安全地这么做，前提是 {@link ContextPropagatingTaskDecorator}
 * 用的是<b>保存-还原</b>而不是「用完清掉」——
 * 否则任务在请求线程上跑完时会把请求自己的 MDC / {@code UserContext} 抹掉。
 * <b>这两个决定是一对，改一个必须回头看另一个。</b>
 *
 * <h3>★★ 它唯一的坏脾气，如实记下来</h3>
 *
 * <p>{@code CallerRunsPolicy} 有一段分支是 <b>{@code if (e.isShutdown()) return;}</b> ——
 * 池已经关了的时候它<b>什么都不做</b>，任务被<b>静默丢弃</b>，
 * 而 {@code CompletableFuture} 因此<b>永远不会完成</b> → 调用方的
 * {@code join()} <b>永久挂住</b>。★ 也就是「本该 500 的地方变成线程挂死」，
 * 而那比 500 难查得多。
 *
 * <p>★ 为什么这里接受它：
 * <ol>
 *   <li>本类的 {@code setWaitForTasksToCompleteOnShutdown(true)} 走的是
 *       {@code executor.shutdown()}（<b>排空队列</b>），不是 {@code shutdownNow()} ——
 *       所以<b>已经在队列里的任务照样会跑完</b>，被丢的只可能是
 *       「停机开始【之后】才提交进来」的那一个；</li>
 *   <li>而那时候 Tomcat 连接器已经不再收新请求，正常路径到不了这一步；</li>
 *   <li>★ 真要堵死它，得把拒绝策略换成 {@code AbortPolicy} 并改用
 *       {@code shutdownNow()} —— 代价是<b>队列一满就 500</b>。
 *       一个只读的日志页，<b>「慢」比「500」好，但「挂住」比两者都差</b>；
 *       这里的判断是前两条让「挂住」窄到可以接受。</li>
 * </ol>
 * <p>★ 换句话说：<b>这不是「没有这个坑」，是「这个坑被前面的两个设置挡住了大半」</b>——
 * 谁要是把 {@code setWaitForTasksToCompleteOnShutdown} 改成 {@code false}，
 * 就同时把这条也放了出来。两处是连着的。
 *
 * <h3>★★ 池大小是怎么定的</h3>
 *
 * <p>这里的任务<b>全是阻塞式的 HTTP 调用</b>（等下游返回），不是 CPU 活。
 * 所以 {@code corePoolSize} 是「一个页面最多并发几次调用」而不是「有几个核」。
 * ★ 一个请求最多提交 3 个任务，4 个核心线程足够单请求全并行；
 * {@code maxPoolSize = 8} 是留给并发访问的余量。
 * <b>不需要更大</b> —— 再大只是把压力原样转给下游。
 *
 * @author MickeyChen
 */
@Configuration
public class AuditAggExecutorConfig {

    /**
     * ★ bean 名字在这里定死一次，{@code AuditLogServiceImpl} 通过它注入 ——
     * 不靠类型注入是因为本服务里 {@code ThreadPoolTaskExecutor} 不止一个
     * （{@code AiAsyncConfig} 还有一个 {@code aiStreamExecutor}），
     * 类型注入会直接报「找到多个候选」。
     */
    public static final String BEAN_NAME = "auditAggExecutor";

    /** ★ 前缀进线程名，线程 dump / 日志里一眼认得出是谁。 */
    private static final String THREAD_PREFIX = "novel-audit-agg-";

    @Bean(BEAN_NAME)
    public ThreadPoolTaskExecutor auditAggExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix(THREAD_PREFIX);

        /*
         * ★ 显式写。不写就是默认的 AbortPolicy（抛 RejectedExecutionException），
         *   行为上「也算显式」，但读代码的人分不出这是想的还是忘的。
         *   理由见类注释 —— 只读的日志页宁可慢，不要 500。
         */
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        /*
         * ★★ 这一行是 S7 的承重件：池线程上没有请求上下文，
         *    traceId 和登录态都要靠它搬过去。见那个类的注释。
         */
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());

        /*
         * ★ 优雅停机：等在途的聚合跑完（最多 10 秒）再关。
         *   不等的话，停服瞬间正在渲染的日志页会拿到一堆
         *   莫名其妙的 RejectedExecutionException。
         */
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);

        return executor;
    }
}
