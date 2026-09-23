package com.novel.search.service;

import com.novel.common.constant.RedisKeyConstant;
import com.novel.search.enums.ReindexTaskStatus;
import com.novel.search.vo.ReindexTaskVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * <b>异步重建任务的【状态存放处】（M5 · S9）。</b>只干「读写那一个 Hash」这一件事。
 *
 * <h3>★★ 为什么用 Hash 而不是把一个 JSON 串塞进 String</h3>
 *
 * <p>状态是<b>分几次写</b>的：提交时写 {@code RUNNING}+{@code startedAt}，
 * 结束时补 {@code finishedAt}/{@code indexed}/…。
 * Hash 的 {@code HSET} 天然是<b>逐字段更新</b>，每次只碰自己那几个字段。
 *
 * <p>★ 用 JSON 串就得「读出来 → 反序列化 → 改 → 序列化 → 写回去」，
 * 那是一个<b>读改写</b>，中间任何一次并发都会丢更新 ——
 * 虽然本类目前只有一个写入方，但把一个「现在恰好安全」的写法留在这里，
 * 下一个来加字段的人不会知道它有多脆弱。
 *
 * <p>★★ 而 Hash 的字段各自独立，还带来一个排查上的好处：
 * {@code HGETALL} 一次拿全，而 {@code HGET} 能只看 {@code status} 这一个字段 ——
 * 「任务到底还在不在跑」是最高频的那个问题。
 *
 * <p>★ 这个形状不是新发明的：{@code NOVEL_STAT_AT} 也是 Hash，同一个理由
 * （那条消息的重放守卫同样需要逐字段读写）。
 *
 * <h3>★★ TTL：它是「进程被杀」这个场面的唯一出路</h3>
 *
 * <p>下面所有写路径都在<b>同一个 JVM 里</b>：提交时写 RUNNING，
 * {@code catch} 里写 FAILED。所以有个场面本类<b>处理不了</b>：
 * <b>进程在重建中途被 kill -9</b> —— 那一刻没人有机会改写状态，
 * 那条记录会永远停在 {@code RUNNING}。
 *
 * <p>★ 处置不是「再造一个心跳机制」，而是<b>给它一个 TTL</b>：
 * 记录自己会消失，轮询方拿到的是「任务不存在」——
 * <b>一个明确的答案，好过一个永远 RUNNING 的假象。</b>
 *
 * <p>★★ 所以 {@code startedAt} 必须暴露出去（{@link ReindexTaskVO#startedAt()}）：
 * 只看 {@code status=RUNNING} 分不出「刚提交」和「上个进程留下的尸体」，
 * <b>加上开始时刻才分得出</b> —— 于是「看了半天还是 RUNNING」这件事
 * 在现场是<b>可以被判断的</b>，而不是只能重启了事。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReindexTaskStore {

    /**
     * 任务记录活多久。
     *
     * <p>★ 取值不是「越长越好」：它要<b>明显大于</b>一次重建的耗时（秒级到分钟级），
     * 让正常轮询永远看得到结果；又要<b>足够短</b>，好在进程被杀之后
     * 不留下一个长期存在的假 RUNNING。
     *
     * <p>★★ 和它对照着看的是重建那把锁的 {@code LOCK_TTL = 30 分钟} ——
     * <b>24 小时远大于 30 分钟是刻意的</b>：这个 TTL 只负责「尸体清理」，
     * 不负责并发控制（那是锁的事）。把两件事的时长绑在一起会让人以为它们是一回事。
     */
    private static final Duration TASK_TTL = Duration.ofHours(24);

    /* ── Hash 的字段名。★ 抽成常量：写和读分开在两处，靠字符串字面量对齐迟早会错一个字母 ── */
    private static final String F_STATUS = "status";
    private static final String F_SUBMITTER = "submitterId";
    private static final String F_STARTED_AT = "startedAt";
    private static final String F_FINISHED_AT = "finishedAt";
    private static final String F_INDEXED = "indexed";
    private static final String F_ELAPSED_MS = "elapsedMs";
    private static final String F_SKIPPED = "skipped";
    private static final String F_ERROR = "error";

    /**
     * ★ 必须是 {@code StringRedisTemplate}，不是 {@code RedisTemplate<String,Object>}。
     * 后者的 value 序列化器会把字符串写成带 {@code @class} 的 JSON，
     * 于是 {@code HGET} 出来的是 {@code {"@class":"java.lang.String","value":"RUNNING"}} ——
     * <b>{@link ReindexTaskStatus#valueOf} 会直接抛异常</b>。
     * 完整论证见 {@code RedisConfig} 的类注释（{@code NovelStatWriteGuard} 里同一条）。
     */
    private final StringRedisTemplate stringRedisTemplate;

    /** 任务 id → Redis key。★ 抽成一个方法，因为写路径和读路径都要用它。 */
    public String keyOf(String taskId) {
        return RedisKeyConstant.REINDEX_TASK + taskId;
    }

    /**
     * 落一条「已提交、正在跑」的记录。
     *
     * <p>★ <b>必须在把任务交给线程池【之前】调用</b>，而且顺序不能反：
     * 反过来的话，任务跑得够快时，轮询方会在状态还没写下去时就去查 ——
     * 拿到「任务不存在」，而任务其实已经成功了。
     * <b>先写状态、再派活，是异步任务的基本次序。</b>
     */
    public void markRunning(String taskId, Long submitterId) {
        String key = keyOf(taskId);
        stringRedisTemplate.opsForHash().putAll(key, Map.of(
                F_STATUS, ReindexTaskStatus.RUNNING.name(),
                F_SUBMITTER, submitterId == null ? "" : String.valueOf(submitterId),
                F_STARTED_AT, String.valueOf(System.currentTimeMillis())));
        stringRedisTemplate.expire(key, TASK_TTL);
    }

    /**
     * 落一条「跑完了」的记录。{@code skipped} 原样带上 —— 见 {@code ReindexTaskVO} 的类注释。
     *
     * <p>★ 这里<b>重设一次 TTL</b>，而不是指望 {@link #markRunning} 那次：
     * 24 小时是从「提交」起算的，而一个跑了很久的任务，
     * 它的结果应当从「结束」起还能再看到 24 小时。
     * 一行的事，省掉一类「刚跑完就查不到了」的怪事。
     */
    public void markSucceeded(String taskId, long indexed, long elapsedMs, boolean skipped) {
        String key = keyOf(taskId);
        stringRedisTemplate.opsForHash().putAll(key, Map.of(
                F_STATUS, ReindexTaskStatus.SUCCEEDED.name(),
                F_FINISHED_AT, String.valueOf(System.currentTimeMillis()),
                F_INDEXED, String.valueOf(indexed),
                F_ELAPSED_MS, String.valueOf(elapsedMs),
                F_SKIPPED, String.valueOf(skipped)));
        stringRedisTemplate.expire(key, TASK_TTL);
    }

    /**
     * 落一条「失败了」的记录。★★ <b>这是本类最重要的一个方法。</b>
     *
     * <p>没有它，{@code rebuildAll()} 抛出的异常就只留在
     * {@code ReindexTaskService} 的 {@code catch} 里变成一行日志，
     * 而管理员那边的轮询会<b>永远看到 RUNNING</b>（R7 记的就是这一条）。
     * 「任务失败」和「任务还在跑」对轮询方是同一件事：
     * <b>都得继续等 —— 而其中一个永远等不到。</b>
     *
     * <p>★ {@code error} 只放<b>异常类型 + 消息</b>，不放整个堆栈：
     * 这个字符串会进 JSON 给前端展示，也会进 Redis。
     * 堆栈属于日志（那里有 traceId，能顺藤摸瓜），<b>放进状态里只会把有用的那句淹掉</b>。
     *
     * <p>★★ 它自己<b>吞掉 Redis 的异常</b>：调用它的时候，
     * 我们已经在处理一个「重建失败」了 —— 如果写状态也失败，
     * 抛出去只会把最初那个异常<b>替换掉</b>，让日志里留下一个更没用的错误。
     * 代价说清楚：这种情况下轮询方看到的仍是 RUNNING，要等 TTL 到期。
     */
    public void markFailed(String taskId, Throwable cause) {
        try {
            String key = keyOf(taskId);
            stringRedisTemplate.opsForHash().putAll(key, Map.of(
                    F_STATUS, ReindexTaskStatus.FAILED.name(),
                    F_FINISHED_AT, String.valueOf(System.currentTimeMillis()),
                    F_ERROR, cause.getClass().getSimpleName()
                            + ": " + (cause.getMessage() == null ? "(无消息)" : cause.getMessage()),
                    /*
                     * ★ skipped 在失败时写 false 而不是留空：留空的话，
                     * 前端拿到的 JSON 里没有这个字段，而 Vue 模板里
                     * `task.skipped` 是 undefined —— 走 else 分支，
                     * 和「真跑了」看起来一样。失败就是失败，不该像是「跑了但跳过」。
                     */
                    F_SKIPPED, String.valueOf(false)));
            stringRedisTemplate.expire(key, TASK_TTL);
        } catch (Exception e) {
            log.warn("写入重建任务的失败状态时又失败了 —— 轮询方会一直看到 RUNNING，直到 TTL({}) 到期"
                            + " | taskId={} 原因={}: {}",
                    TASK_TTL, taskId, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * 读一个任务的状态。
     *
     * @return 任务不存在（id 打错、或记录已过 TTL）时返回 {@code null}。
     *         ★ <b>调用方必须把 null 和 RUNNING 分开处理</b> —— 这正是本类的存在意义
     */
    public ReindexTaskVO find(String taskId) {
        Map<Object, Object> raw = stringRedisTemplate.opsForHash().entries(keyOf(taskId));
        if (raw == null || raw.isEmpty()) {
            return null;
        }

        ReindexTaskStatus status;
        try {
            status = ReindexTaskStatus.valueOf(str(raw, F_STATUS));
        } catch (Exception e) {
            /*
             * ★★ 读不出来【不当成 RUNNING】。理由：RUNNING 会让轮询方继续等，
             *   而这正是「永远等下去」那个失败模式本身。
             *   改成 FAILED + 一句能读懂的原因，人至少知道该去看日志。
             *   ★ 触发场景：枚举被改名/删值，而一条旧记录还在（见 ReindexTaskStatus 的类注释）。
             */
            log.warn("重建任务的状态字段读不出来（枚举改名了？） | taskId={} 原始值={}",
                    taskId, str(raw, F_STATUS));
            return new ReindexTaskVO(taskId, ReindexTaskStatus.FAILED,
                    null, num(raw, F_STARTED_AT), null, null, null, null,
                    "任务状态无法解析（raw=" + str(raw, F_STATUS) + "），请查看服务日志");
        }

        return new ReindexTaskVO(
                taskId,
                status,
                num(raw, F_SUBMITTER),
                num(raw, F_STARTED_AT),
                num(raw, F_FINISHED_AT),
                num(raw, F_INDEXED),
                num(raw, F_ELAPSED_MS),
                bool(raw, F_SKIPPED),
                str(raw, F_ERROR));
    }

    /* ── 下面三个小转换：Hash 里全是字符串，而 VO 里是数字/布尔/枚举 ──
     *  ★★ 它们的判据一律是「**解析不出来就返回 null，绝不返回 0**」——
     *    同 ReindexTaskVO 类注释那条：0 是一个有意义的坏消息，
     *    不能拿它兼职表示「不知道」。宁可让字段从 JSON 里消失。 */

    private static String str(Map<Object, Object> raw, String field) {
        Object v = raw.get(field);
        return v == null ? null : v.toString();
    }

    private static Long num(Map<Object, Object> raw, String field) {
        String v = str(raw, field);
        if (v == null || v.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean bool(Map<Object, Object> raw, String field) {
        String v = str(raw, field);
        if (v == null || v.isEmpty()) {
            return null;
        }
        return Boolean.valueOf(v);
    }
}
