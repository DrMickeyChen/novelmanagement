package com.novel.common.concurrent;

import com.novel.common.security.LoginUser;
import com.novel.common.security.UserContext;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * <b>把「当前请求的上下文」搬进线程池的线程里 —— MDC 和 {@link UserContext} 【两个都要】。</b>
 *
 * <p>M5 的 S7 把管理端审核日志的三次跨服务取值改成并行，于是它们从
 * 请求线程挪到了池线程。★ 而这两样东西都是 {@code ThreadLocal}，
 * <b>换线程就丢</b>：
 *
 * <table border="1">
 *   <caption>丢了会怎样 —— 两个的症状完全不同</caption>
 *   <tr><th>丢的东西</th><th>存哪</th><th>丢了的表现</th></tr>
 *   <tr>
 *     <td>traceId</td>
 *     <td>{@link MDC}（{@code TraceContext} 是它的门面）</td>
 *     <td>★ <b>看得见</b>：池线程打的那几行日志，id 那一格是空的；
 *         而且出站的 Feign 调用<b>不再带 {@code X-Trace-Id}</b>，
 *         对端会自己生成一个新的 —— <b>链路在这一次调用上断成两截</b>。</td>
 *   </tr>
 *   <tr>
 *     <td>登录态</td>
 *     <td>{@link UserContext}（裸 {@code ThreadLocal}）</td>
 *     <td>★★ <b>看不见，而且更危险</b>：出站拦截器里
 *         {@code IdentityHeaders.write(headers, null)} 会<b>静默地什么都不写</b>，
 *         于是对端把这次调用当成<b>匿名</b>处理。
 *         请求照常成功、返回照常正确 —— 直到某个内部端点开始按身份返回不同内容，
 *         它才会开始悄悄少给数据。见 {@code FeignIdentityConfig} ② 那段。</td>
 *   </tr>
 * </table>
 *
 * <h3>★★★ 为什么要「恢复」而不是「清掉」</h3>
 *
 * <p>直觉写法是 {@code finally { MDC.clear(); UserContext.clear(); }} ——
 * 池线程是复用的，不清理就会把上一个请求的身份<b>泄漏给下一个任务</b>。
 * ★ 但那是个<b>只在池线程上成立</b>的写法，而这里有一个反例：
 *
 * <pre>
 *   ThreadPoolTaskExecutor + CallerRunsPolicy
 *   队列满时，Runnable 会在【调用者线程】（也就是请求线程）上直接跑 ——
 *   此时 finally 里的 clear() 抹掉的是【请求线程自己的】上下文。
 *   请求还没结束，后面的代码就再也读不到自己的身份了。
 * </pre>
 *
 * <p>★ 所以这里做的是<b>保存 → 覆盖 → 还原</b>：
 * <pre>
 *   池线程   ：前一个值本来就是 null → 还原 == 清掉   ← 没有泄漏
 *   调用者线程：前一个值是本次请求自己的 → 还原 == 原样放回  ← 没有抹掉
 * </pre>
 * <p>★ 一个写法同时满足两种情况，代价只是多存两个引用。
 * <b>「清掉」是「还原」在池线程上的特例，而反过来不成立。</b>
 *
 * <p>★★ 注意 {@code null} 要还原成 <b>真正地把 ThreadLocal remove 掉</b>
 * （{@code UserContext.clear()} / {@code MDC.clear()}），
 * 而不是 {@code set(null)} —— 后者留着那个 {@code ThreadLocalMap} 条目，
 * 池线程的引用就一直挂着不被回收。
 *
 * @author MickeyChen
 */
public class ContextPropagatingTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        /*
         * ★ 快照在【提交线程】上取，不在任务里取 —— 任务跑起来时
         *   当前线程已经是池线程了，那时候再取就什么都取不到。
         */
        Map<String, String> submittedMdc = MDC.getCopyOfContextMap();
        LoginUser submittedUser = UserContext.get();

        return () -> {
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            LoginUser previousUser = UserContext.get();
            try {
                if (submittedMdc == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(submittedMdc);
                }
                /*
                 * ★ submittedUser 可能是 null（匿名请求），这时也要显式清 ——
                 *   否则池线程会带着【上一个任务】的身份跑完这一次。
                 */
                if (submittedUser == null) {
                    UserContext.clear();
                } else {
                    UserContext.set(submittedUser);
                }

                runnable.run();
            } finally {
                if (previousMdc == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previousMdc);
                }
                if (previousUser == null) {
                    UserContext.clear();
                } else {
                    UserContext.set(previousUser);
                }
            }
        };
    }
}
