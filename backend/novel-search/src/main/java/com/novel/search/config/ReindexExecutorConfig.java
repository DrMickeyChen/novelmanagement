package com.novel.search.config;

import com.novel.common.concurrent.ContextPropagatingTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * <b>跑异步重建任务的线程池（M5 · S9）。</b>形状照 {@code content} 的 {@code AuditAggExecutorConfig}（S7）。
 *
 * <h3>★★ 为什么必须有【自己的】线程池，不能借别人的</h3>
 *
 * <p>S7 已经为「管理端审核日志的并行取值」立过这条规矩，S9 是第二个使用者，
 * 而理由在这里更硬 —— 重建是<b>秒级到分钟级的长任务</b>：
 * <ul>
 *   <li>★★ <b>绝不能用 {@code ForkJoinPool.commonPool}</b>（大纲点名过）：
 *       它是 <b>JVM 全局</b>的，一个长任务会把它占住，
 *       然后<b>整个 JVM 里所有并行流一起卡住</b> ——
 *       症状是「某个不相干的接口变慢了」，排查方向完全跑偏。</li>
 *   <li>★ <b>也不借 {@code applicationTaskExecutor}</b>（Boot 自动配的那个）：
 *       它是 MVC 异步、{@code @Async} 等共用的。
 *       把一个长任务丢进去，等于给所有共用者加了一个不可预测的延迟。</li>
 * </ul>
 *
 * <p>★ 线程名 {@code novel-reindex-*} 是<b>给线程 dump 用的</b>：
 * 没有辨识度的话，一次 jstack 里那一堆 {@code pool-3-thread-1} 谁也认不出来。
 *
 * <h3>★★★ 队列满了【拒绝】，而不是交给调用者跑 —— 这里刻意和 S7 相反</h3>
 *
 * <p>S7 的 {@code AuditAggExecutorConfig} 用的是 {@code CallerRunsPolicy}，
 * 在那里是对的（任务很短，让请求线程自己跑完比丢弃好）。
 * <b>在这里是错的，而且错得很隐蔽：</b>
 *
 * <pre>
 *   CallerRunsPolicy + 队列满
 *     → Runnable 在【提交它的那个线程】上跑 —— 而那是 Tomcat 的请求线程
 *     → 管理员这一次 POST 会一直阻塞到重建跑完
 *     → ★ 于是「异步化」在最需要它的那一刻（系统繁忙、队列积压）
 *       【静默退化回同步】——而且没有任何报错，接口照常 200
 * </pre>
 *
 * <p>换句话说：用 {@code CallerRunsPolicy} 的话，
 * <b>本期 S9 做的全部事情都可以被一次队列积压抵消掉，且不留痕迹。</b>
 * 所以这里用 {@link ThreadPoolExecutor.AbortPolicy}（默认）：
 * 队列满就抛 {@code RejectedExecutionException}，
 * 由 {@code ReindexTaskService} 接住并把它变成一次<b>看得见的</b>失败。
 * <b>「拒绝一个请求」比「假装异步」诚实得多。</b>
 *
 * <h3>★ 线程数与队列为什么都是 1 / 2 这么小</h3>
 *
 * <p>重建<b>本来就不能并发</b> —— {@code IndexRebuildService} 用一把分布式锁把这件事钉死了，
 * 第二个并发执行只会拿到 {@code skipped=true} 空跑一轮。
 * 所以把池开大买不到任何吞吐，只是让「同时来 8 个请求」变成
 * 「8 个任务排队、7 个白跑」。<b>并发度不设在消费方，设在产出方。</b>
 *
 * <p>★★ 队列容量 2 是给「连点两下按钮」留的余量：第二次会排队，
 * 等第一次跑完、锁释放之后再真跑一轮（这正是 S8 修掉幽灵锁之后才成立的行为）。
 * 再多就没有意义了 —— 一次点 5 下的人需要的是提示，不是 5 次重建。
 *
 * @author MickeyChen
 */
@Configuration
public class ReindexExecutorConfig {

    /**
     * Bean 名。★ 注入处要显式 {@code @Qualifier}（{@code AiController} 那个先例）——
     * Boot 自己还有一个 {@code applicationTaskExecutor} 也是
     * {@code ThreadPoolTaskExecutor} 类型，<b>按类型注入会撞上两个候选而启动失败</b>。
     */
    public static final String BEAN_NAME = "reindexExecutor";

    @Bean(BEAN_NAME)
    public ThreadPoolTaskExecutor reindexExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(2);
        executor.setThreadNamePrefix("novel-reindex-");

        /*
         * ★★ 这个装饰器在 S9 里【不只是为了日志好看】。
         *
         *   重建任务要跨服务调 content（/internal/novels/index-page），
         *   而 Feign 的出站拦截器是靠 UserContext（ThreadLocal）来写身份头的。
         *   不装它的话，池线程上 UserContext 为 null →
         *   IdentityHeaders.write(headers, null) 【静默地什么都不写】→
         *   这次调用以【匿名】的身份发出去。
         *
         *   ★ 本服务那条内部端点恰好不校验身份（content 的 /internal/** 是
         *     permitAll，真正的闸是共享密钥），所以它【不会报错】——
         *     但 traceId 会断：出站不带 X-Trace-Id → content 自己生成一个新的
         *     → 一次重建的日志在链路中间断成两截。
         *
         *   ★★ 判据：装饰器把「提交时那一刻的上下文」搬进池线程，
         *     于是这次重建的全部日志（含对端那半截）都挂在【提交它的那个请求】的
         *     traceId 下 —— 管理员点了一下按钮，那条链路从头到尾是连着的。
         *     完整的取舍论证见 ContextPropagatingTaskDecorator 的类注释。
         */
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());

        /*
         * ★ 关停时等任务跑完，而不是当场打断。
         *   重建被打断的后果是留下一个【灌了一半的新物理索引】——
         *   它没被任何别名指向（切换还没发生），所以不影响检索，
         *   但会一直占着空间直到下一轮重建的 deleteStaleIndices 顺手清掉。
         *
         * ★★ 而等待时间【必须】有上限：重建是大批量灌数据，
         *   理论上可以跑很久，不能让它把 JVM 关停无限期拖住。
         *   30 秒是「够跑完小库、又不至于让人等不下去」的取值，
         *   超时就照常打断 —— 代价见上，是可接受的。
         */
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        return executor;
    }
}
