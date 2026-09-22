package com.novel.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 定时任务配置。这是全仓<b>第一个</b> {@code @EnableScheduling}。
 *
 * <p>目前只有 {@code NovelViewCounter} 一个任务（阅读量落库）。
 *
 * <h3>★ 为什么单独建一个配置类，而不是把 {@code @EnableScheduling} 挂在启动类上</h3>
 * 挂在 {@code NovelApplication} 上只有一行，但「本应用会跑定时任务」这件事就
 * 藏在了启动类里，谁都不会去读第二遍。更实际的理由是：{@code @Scheduled}
 * 的影响面比它看起来大得多 —— 它会<b>在每一个实例上都跑一遍</b>（见下），
 * 这是个需要被看见的决定，放在自己的文件里才有人看见。
 *
 * <h3>★★ 多实例下 {@code @Scheduled} 会重复执行</h3>
 * 定时任务没有「谁是主节点」的概念：起两个 jar，两个都会在每分钟的第 0 秒
 * 执行同一段代码。第二期上 K8s 多副本之后这是必然会踩的。
 *
 * <p>应对方式按任务类型分两种：
 * <ul>
 *   <li><b>「消费型」任务</b>（本期的阅读量落库就是）—— 靠一次原子操作
 *       天然互斥，不需要锁。{@code NovelViewCounter} 用
 *       {@code SREM} 返回 1 来认领每个 novelId，抢不到就跳过。
 *       这类任务的写法要求是：<b>取数这一步本身必须是原子的</b>
 *       （{@code GETDEL}，而不是 {@code GET} 之后再 {@code DEL}）。</li>
 *   <li><b>「计算型」任务</b>（每天凌晨重算一次全站榜单之类）—— 没有天然的
 *       互斥点，必须自己加分布式锁，用 {@link com.novel.common.constant.RedisKeyConstant#LOCK_PREFIX}。
 *       第二期 M3 引入 Redisson 之后这件事会简单很多。</li>
 * </ul>
 *
 * <p>★ <b>不要把上面第一类改成第二类的形态而不加锁。</b>
 * 「读出来 → 处理 → 更新」在任何多实例部署下都会重复执行，
 * 而在单机开发环境里<b>完全测不出来</b>。
 *
 * <h3>★ 定时任务里拿不到当前用户</h3>
 * 任务线程不是 Tomcat 的工作线程，{@code UserContext} 里的 ThreadLocal
 * 是空的 —— 任务里调用的任何 service 方法，只要它读了
 * {@code UserContext.getUserId()} 或 {@code isAdmin()}，拿到的都是
 * {@code null} / {@code false}。所以定时任务要么直接用 mapper，
 * 要么调那种显式接收 userId 参数的方法，<b>不要调带权限判断的业务方法</b>。
 *
 * @author MickeyChen
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    /**
     * 定时任务线程池。
     *
     * <p>不配这个的话，Spring 默认只给<b>一个</b>线程：任务一多就会互相排队，
     * 而且某个任务卡住（比如落库时数据库连接池满了）会连带把后面所有任务
     * 一起堵死 —— 表现为「日志里什么也没有，任务就是不跑」，很难往
     * 「另一个不相干的任务堵住了它」这个方向想。
     *
     * <p>池子给 3 个是留余量，不是为了并发：本期的任务本身就该串行。
     * 真需要并发的任务应该在自己的实现里显式分片，而不是靠加线程数。
     *
     * <p>★ {@code setThreadNamePrefix} 是为了日志 —— 定时任务的日志里会带上
     * 这个名字，一眼能区分「这是定时任务在跑」还是「这是某个用户在请求」。
     * 排查时这个信息很值钱。
     *
     * <p>★ {@code setWaitForTasksToCompleteOnShutdown(true)}：停机时等在跑的
     * 任务做完再退，避免「落库做到一半进程被杀」。配合 {@code awaitTerminationSeconds}
     * 兜底 —— 卡死的任务不能拖着进程不让它退。
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("novel-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(20);
        return scheduler;
    }
}
