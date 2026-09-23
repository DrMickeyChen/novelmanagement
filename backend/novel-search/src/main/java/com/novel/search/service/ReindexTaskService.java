package com.novel.search.service;

import com.novel.common.security.UserContext;
import com.novel.search.config.ReindexExecutorConfig;
import com.novel.search.vo.ReindexSubmitVO;
import com.novel.search.vo.ReindexTaskVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/**
 * <b>把「同步重建」拆成「提交 + 轮询」两半（M5 · S9）。</b>
 *
 * <h3>★★ 为什么必须异步 —— 端点自己的注释里早就写着这笔债</h3>
 *
 * <p>{@code AdminSearchController} 在 M4 就记下了：
 * <blockquote>
 *   库大到重建超过网关/容器的读超时（30~60 秒）时，管理员会看到超时，
 *   <b>而重建其实还在跑</b>。…所以记为 M5 的债：正解是「提交任务 + 查询进度」两步式。
 * </blockquote>
 * <p>★ 本类就是那笔债的偿还。<b>注意那个失败模式的形状</b>：
 * 超时不代表没做，也不代表做了 —— 管理员收到的是一个
 * <b>无法判断</b>的结果。<b>偿还它的方式不是「把超时调长」，而是让「还没好」变成一个合法答案。</b>
 *
 * <h3>★★★ 本类唯一的硬约束：任何一个任务，最后都必须能落到一个【终态】</h3>
 *
 * <p>异步任务最经典的坏结局是<b>永远停在「进行中」</b>：管理员无限等下去，
 * 而没有任何东西告诉他出事了。<b>本类的每一行都是在保证这件事不发生</b>，
 * 具体是三条出路，缺一不可：
 *
 * <table border="1">
 *   <caption>一条任务的所有归宿</caption>
 *   <tr><th>#</th><th>场面</th><th>谁负责收尾</th></tr>
 *   <tr>
 *     <td>①</td><td>正常跑完 / 中途抛异常</td>
 *     <td>{@link #runTask} 的 {@code try/catch} → {@code markSucceeded} / {@code markFailed}</td>
 *   </tr>
 *   <tr>
 *     <td>②</td><td>★ <b>提交时就被线程池拒绝</b>（队列满）</td>
 *     <td>{@link #submit} 的 {@code catch RejectedExecutionException}。
 *         ★★ 这一支最容易漏 —— 见下面的注释，它是「先写 RUNNING 再派活」这个次序的必然产物</td>
 *   </tr>
 *   <tr>
 *     <td>③</td><td>★ <b>进程被 kill -9</b>，谁都来不及收尾</td>
 *     <td><b>没人</b> —— 只能靠 {@code ReindexTaskStore} 的 TTL 让记录消失。
 *         ★ 而且 {@code startedAt} 会暴露在轮询结果里，好让「看了很久还是 RUNNING」
 *         这件事在现场可被判断成「这是个尸体」</td>
 *   </tr>
 * </table>
 *
 * <p>★ 三条里 ① 和 ② 是本类的代码，③ 是明确<b>放弃</b>的（并写清代价）——
 * 一个「我们处理不了但知道它会发生」的场面，写出来比装作不存在好。
 *
 * @author MickeyChen
 */
@Slf4j
@Service
public class ReindexTaskService {

    private final IndexRebuildService indexRebuildService;
    private final ReindexTaskStore taskStore;
    private final ThreadPoolTaskExecutor reindexExecutor;

    /**
     * ★ <b>显式写构造函数，而不是用 {@code @RequiredArgsConstructor}</b> ——
     * 因为这里需要 {@code @Qualifier}。
     *
     * <p>Lombok 把字段注解搬到构造参数上要靠 {@code lombok.copyableAnnotations} 配置，
     * 而本仓库<b>没有 {@code lombok.config}</b> —— 于是写在字段上的 {@code @Qualifier}
     * 不会被复制，注入会退化成「按类型找」，
     * 而 Boot 自动配的 {@code applicationTaskExecutor} 也是
     * {@code ThreadPoolTaskExecutor} 类型 → <b>两个候选 → 启动直接失败</b>。
     *
     * <p>★ 这个写法与 {@code AiController} 一致（那里也是显式构造函数 + 参数上的 {@code @Qualifier}），
     * 是仓库里已有的先例，不是为这一处发明的。
     */
    public ReindexTaskService(IndexRebuildService indexRebuildService,
                              ReindexTaskStore taskStore,
                              @Qualifier(ReindexExecutorConfig.BEAN_NAME) ThreadPoolTaskExecutor reindexExecutor) {
        this.indexRebuildService = indexRebuildService;
        this.taskStore = taskStore;
        this.reindexExecutor = reindexExecutor;
    }

    /**
     * 提交一次重建，<b>立刻</b>返回一张收据（{@code taskId}）。
     *
     * <p>★★ <b>次序是不能反的：先写 RUNNING，再派活。</b>
     * 反过来的话有一个很窄但真实的窗口：任务跑得足够快时，
     * 管理员拿到 {@code taskId} 立刻来轮询，而状态还没写下去 →
     * 他拿到「任务不存在」，可任务其实<b>已经成功了</b>。
     * 先落状态就没有这个窗口 —— 代价是「派活失败时要负责把状态改掉」（见 ②）。
     *
     * @return 见 {@link ReindexSubmitVO}；<b>它只是收据，不是结论</b>
     * @throws IllegalStateException 任务队列已满。<b>让它变成 500</b>：
     *                               管理员需要知道「这次没派出去」，而不是拿到一个
     *                               永远不会有结果的 taskId
     */
    public ReindexSubmitVO submit() {
        String taskId = UUID.randomUUID().toString().replace("-", "");

        /*
         * ★ 提交人从 UserContext 取，不从参数传 —— 由调用链自然带来，避免每个调用方都记得传。
         *   ★★ 取在这里（提交线程）是【必须】的：runTask 跑在池线程上，
         *   那时候 UserContext 已经被装饰器换成了池线程的那一份，
         *   虽然装饰器保证了它是同一个用户，但「在还有把握的地方取值」是更稳的写法。
         */
        Long submitterId = UserContext.getUserId();

        taskStore.markRunning(taskId, submitterId);

        try {
            reindexExecutor.execute(() -> runTask(taskId));
        } catch (RejectedExecutionException e) {
            /*
             * ★★ ② 号出路。这一支的存在完全是「先写 RUNNING」换来的 ——
             *   状态已经落下去了，而任务【永远不会被跑】。
             *
             *   不处理的话，这就是一个标准的「永远 RUNNING」：
             *   管理员拿到 taskId、一直轮询、永远是「进行中」，
             *   而队列里根本没有这个任务。★ 而且它【不报任何错】。
             *
             * ★ 标记成 FAILED 之后再抛：轮询方看到的是一个有原因的失败，
             *   本次请求拿到的是 500 —— 两条路都指向「这次没成，重试」。
             */
            taskStore.markFailed(taskId, e);
            /*
             * ★ 日志里带上「正在跑 + 排队中」的条数：只报「队列已满」的话，
             *   下一次看到它的人仍然不知道是「有一个卡住的」还是「一下子来了很多」。
             */
            log.error("重建任务没能提交到线程池（队列已满） | taskId={} 正在跑={} 排队={}",
                    taskId,
                    reindexExecutor.getActiveCount(),
                    reindexExecutor.getThreadPoolExecutor().getQueue().size());
            throw new IllegalStateException("重建任务队列已满，请稍后重试（上一次重建可能仍在进行）", e);
        }

        log.info("重建任务已提交 | taskId={} 提交人={}（★ 已提交 ≠ 已完成，结果要轮询）",
                taskId, submitterId);
        return new ReindexSubmitVO(taskId);
    }

    /**
     * 查一个任务的当前状态。
     *
     * @return 任务不存在（id 打错、或记录过了 TTL）时返回 {@code null}，
     *         由调用方翻译成 HTTP 404。<b>不在这里编一个「未知状态」的 VO</b>：
     *         「没有这个任务」和「这个任务状态不明」是两件事
     */
    public ReindexTaskVO status(String taskId) {
        return taskStore.find(taskId);
    }

    /**
     * 池线程上真正干活的那一段。<b>它绝不外抛</b> —— 异常必须在这里变成状态。
     *
     * <p>★★ 这是整条异步链路里最容易写错的一处：
     * 若让异常冒到线程池，它会被交给 {@code UncaughtExceptionHandler}，
     * 打一行日志，然后<b>什么都不留下</b> ——
     * {@code ReindexTaskStore} 里那条记录还是 RUNNING。
     * <b>「池子里抛异常了」和「任务还在跑」在轮询方眼里是一模一样的。</b>
     *
     * <p>★ 所以这里的 {@code catch} 不是「优雅处理」，是<b>唯一的收尾点</b>（① 号出路）。
     *
     * <p>★ <b>吞掉异常是对的</b>：池线程上没有别人能接住它。
     * 这与 {@code AdminSearchController} 在同步时代「不吞、让它变 500」的选择并不矛盾 ——
     * 那时异常有一个能收到它的调用方（浏览器），现在没有了，<b>所以改由状态字段来承担那个角色</b>。
     */
    private void runTask(String taskId) {
        try {
            IndexRebuildService.RebuildReport report = indexRebuildService.rebuildAll();
            /*
             * ★ skipped 原样传下去 —— 见 ReindexTaskVO 的类注释：
             *   「没抢到锁什么都没做」和「做了但结果是 0 条」必须长得不一样。
             *   ★ 这里最容易犯的错是「skipped 就不算成功，标成 FAILED」——
             *   那是错的：没抢到锁说明【另一个重建正在正常进行】，
             *   这不是故障，是设计中的串行化。
             */
            taskStore.markSucceeded(taskId, report.indexed(), report.elapsedMs(), report.skipped());
            log.info("重建任务结束 | taskId={} 灌入={} 条 耗时={}ms skipped={}",
                    taskId, report.indexed(), report.elapsedMs(), report.skipped());
        } catch (Throwable t) {
            /*
             * ★ 捕 Throwable 而不是 Exception：池线程上没人兜底，
             *   连 Error（OOM 等）也必须留下痕迹，否则同样是「永远 RUNNING」。
             */
            taskStore.markFailed(taskId, t);
            log.error("重建任务失败 | taskId={} ★ 状态已写成 FAILED，轮询方能看到原因 | {}: {}",
                    taskId, t.getClass().getSimpleName(), t.getMessage(), t);
        }
    }
}
