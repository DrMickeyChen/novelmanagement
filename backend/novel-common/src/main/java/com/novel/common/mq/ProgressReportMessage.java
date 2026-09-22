package com.novel.common.mq;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 「读者读到哪了」—— 阅读进度上报的消息载荷（M3）。
 *
 * <p>Tag：{@code PROGRESS_REPORT}；<b>生产方和消费方都是 novel-interaction</b>
 * （{@code read_history} 是 interaction 的表）。
 *
 * <h3>★★ 它是全项目唯一一条顺序消息 —— 两个字段共同保证这一点</h3>
 *
 * <ul>
 *   <li><b>{@code userId}</b> —— 发送时作为 {@code hashKey} 传给
 *       {@code syncSendOrderly}。RocketMQ 用它算队列号（{@code |hashCode| % 队列数}），
 *       <b>同一个人的所有进度消息必定落在同一条队列上</b>，
 *       而顺序消费只在单条队列内部保证有序 —— 两者合起来才是「同一用户的进度不会乱序」。</li>
 *   <li><b>{@code at}</b> —— 生产时刻，随载荷走，进 {@code read_history.update_time}。</li>
 * </ul>
 *
 * <p><b>★★ 少了任何一个都会静默失效：</b>
 * 不发 {@code hashKey} 就发 {@code syncSendOrderly}，消息会被打散到 4 条队列上 ——
 * 「有序」变成「每条队列内部有序」，<b>跨队列的顺序一点都没有</b>，而且不报错
 * （{@code SelectMessageQueueByHash} 只在发送端用 hashKey 选队列，
 * 用默认的轮询策略就是轮流发）。反过来，发送端对了但消费端声明成
 * {@code CONCURRENTLY}，同一条队列的消息会被线程池打散并发处理 —— 同样没顺序。
 *
 * <h3>★ 为什么 {@code at} 必须在生产者盖，不能到消费者里取 {@code LocalDateTime.now()}</h3>
 *
 * <p>这个字段不是审计用的，它是 {@code read_history.update_time} 的值本身，
 * 而 {@code read_history} 的列表按 {@code idx_user_time} 排序 ——
 * <b>它就是「阅读历史」的排序依据</b>。
 *
 * <p>在消费端盖的话，一有积压，列表顺序就变成「消息处理顺序」而不是「读者阅读顺序」，
 * 用户看到自己的阅读记录<strong>前后颠倒了</strong>，而且没有任何东西会报错。
 * <b>这是本期最典型的一类 bug：结论看着都对，只是时间基准取错了地方。</b>
 *
 * @param userId    读者 id。★★ <b>它由生产方从 {@code UserContext} 取，
 *                  绝不接受请求体里传来的值</b> —— 见下面那段
 * @param novelId   作品 id
 * @param chapterId 章节 id
 * @param progress  读到该章的第几章 / 或百分比，语义与 {@code read_history.progress} 一致
 * @param at        生产时刻（epoch 毫秒）。<b>不是</b>消费时刻，理由见上
 */
public record ProgressReportMessage(
        Long userId,
        Long novelId,
        Long chapterId,
        Integer progress,
        long at
) {

    /**
     * 生产者用的工厂方法：{@code userId} 必须由调用方从登录态取出来再传进来。
     *
     * <p><b>★★ 这个参数是故意的 —— 它逼调用方显式地写出 userId 是哪来的。</b>
     * 请求体里也有一个 {@code userId}（{@code HistoryReportDTO} 上就有一个），
     * 但那个值是<b>客户端可伪造的</b>：今天 {@code HistoryServiceImpl.report}
     * 第一行的 {@code UserContext.requireUserId()} 是「本方法唯一的安全边界」，
     * 它的注释专门警告过不要换成 {@code dto.getUserId()}。
     *
     * <p>★ MQ 之后这条边界跟着移动了：userId 从「请求头里的登录态」变成
     * 「消息载荷里的一个字段」。<b>这可以接受，但前提是生产者是唯一的写入者</b> ——
     * 谁能往 {@code novel-topic} 发消息，谁就能伪造任意用户的阅读进度。
     * 这个前提和原来那条（「谁能调通 {@code /api/reader/history} 谁就是登录用户」）
     * 是同一个等级的信任边界，所以不变量没有变弱，只是执行机制从 HTTP 鉴权
     * 换成了「MQ 的生产端在可信进程里」。
     * <p><b>别把这段当成「反正一样」，要当成「换了一道锁，锁还在」。</b>
     */
    public static ProgressReportMessage of(Long userId, Long novelId,
                                           Long chapterId, Integer progress) {
        return new ProgressReportMessage(userId, novelId, chapterId, progress,
                System.currentTimeMillis());
    }

    /**
     * 把 {@code at} 还原成 {@code read_history.update_time} 要的 {@code LocalDateTime}。
     *
     * <p>★ 传的是 epoch 毫秒而不是直接传一个格式化好的时间串：
     * 时间串会把「哪个时区、什么格式」变成一份隐含契约，两个服务对不上就是几小时的偏差，
     * 而且是<b>静默的</b>（时间还是个合法时间，只是不对）。
     * epoch 毫秒是个绝对量，没有解释空间。
     *
     * <p>★ 这个方法<b>不会被 Jackson 序列化进载荷</b>：record 的 JSON 字段
     * 只来自它的组件访问器（{@code userId()} / {@code novelId()} …），
     * 一个普通方法不会被当成属性。<b>别把它改名叫 {@code getAt()}。</b>
     */
    public LocalDateTime atAsLocalDateTime() {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault());
    }
}
