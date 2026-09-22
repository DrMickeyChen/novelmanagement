package com.novel.modules.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * AI 流式生成专用的线程池。
 *
 * <p><b>★★★ 为什么必须另起一个池，不能直接在 Controller 里同步跑 ——
 * 这是这一块最容易做错、且做错了后果最严重的一处。</b>
 *
 * <p>把生成放在 Tomcat 的请求线程上（也就是在 Controller 方法里直接
 * {@code aiStreamSource.stream(...)}）会发生两件事：
 * <ol>
 *   <li><b>请求线程被占用十几秒。</b>流式生成期间它什么也做不了，
 *       只能陪着 {@code Thread.sleep}。Tomcat 默认 200 个工作线程，
 *       20 个作者同时点「生成大纲」就占掉 10%。</li>
 *   <li><b>更要命的是第一条的放大版：</b>Tomcat 的线程是<b>整个应用共用</b>的。
 *       作者们在生成 AI 内容时占满的线程，同时也在服务读者端的
 *       「查作品列表」「读章节正文」「健康检查」。占满的后果不是
 *       「AI 变慢」，而是<b>整个后端不响应，包括 {@code /api/ping}</b> ——
 *       监控会报「服务全挂」，运维重启，而根因只是几个人点了生成。</li>
 * </ol>
 * 换成独立线程池之后，AI 的压力被关在自己的池子里：
 * 池满时新请求被拒绝（返回 500），但<b>读者端和其它接口完全不受影响</b>。
 * 「把不同性质的负载隔离开」这件事，在单体里就要做对 ——
 * 第二期拆微服务时，它只是从「线程池隔离」升级成「进程隔离」，
 * 思路是同一条。
 *
 * <p><b>★ 池子为什么这么小（核心 2 / 最大 4 / 队列 20）：</b>
 * 一次生成要占用一个线程十几秒，而 E1 之后每个并发的生成都在<b>花钱</b>。
 * 把池子开大不会让生成更快（瓶颈在模型那边），只会让并发烧钱的速度变快、
 * 让内存里同时挂着的 SSE 连接变多。4 个并发 + 20 个排队，
 * 对一个求职项目来说绰绰有余，而且「排队」本身就是正确的行为 ——
 * 第 25 个作者看到的是「稍后重试」，不是服务器宕机。
 *
 * <p>★ 队列满了之后的策略是<b>默认的 AbortPolicy</b>（抛异常 → 500），
 * 刻意<b>不用</b> {@code CallerRunsPolicy}：那个策略会让任务退回调用者线程执行，
 * 也就是「池子满了就在 Tomcat 线程上跑」—— 正好把上面费劲隔离开的东西
 * 又还回去了，而且是在最忙的时候还回去。宁可直接拒绝。
 *
 * @author MickeyChen
 */
@Slf4j
@Configuration
public class AiAsyncConfig {

    /** 线程名的前缀。★ 日志里靠它一眼认出「这是 AI 生成的线程」，不然全是 pool-3-thread-1 */
    private static final String THREAD_PREFIX = "ai-stream-";

    @Bean("aiStreamExecutor")
    public ThreadPoolTaskExecutor aiStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix(THREAD_PREFIX);
        executor.setKeepAliveSeconds(60);

        /*
         * ★ 优雅关闭：应用停止时等正在生成的流收尾（最多 5 秒）。
         *
         * 不等的话，开发者按 Ctrl+C 重启时，正在生成的那几个流会被硬切断，
         * 前端看到的是「生成到一半突然停了」—— 而且在本地开发时
         * 这会被误以为是 bug。5 秒是个折中：足够推完剩下的几行，
         * 又不至于让重启卡住。
         */
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);

        executor.initialize();
        log.info("AI 生成线程池初始化完成 | 核心={} 最大={} 队列={} 线程名前缀={}",
                2, 4, 20, THREAD_PREFIX);
        return executor;
    }
}
