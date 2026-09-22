package com.novel.interaction.mq.producer;

import com.novel.common.constant.MqConstant;
import com.novel.common.mq.MqErrors;
import com.novel.common.mq.ProgressReportMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

/**
 * 阅读进度上报的生产者（M3）—— <b>全项目唯一一条顺序消息的发送端</b>。
 *
 * <p>它替换掉的是一次本地 {@code upsert}：改造前
 * {@code HistoryServiceImpl.report} 校验完就直接写 {@code read_history}，
 * 读者「离开章节」这个动作上挂着两次跨服务读 + 一次本地写。M3 之后写成一次投递。
 *
 * <h3>★★ 为什么是 {@code sendOrderly} 而不是普通发送</h3>
 *
 * <p>载荷推的是<b>绝对值</b>（读到第几章、章内百分之几），不是增量。
 * 乱序的后果不是「少算一点」，而是<b>进度回退</b> ——
 * 先收到第 5 章、再收到第 3 章，读者下次打开这本书就回到了第 3 章，
 * 而他的历史列表顺序也跟着错。所以这条必须有序。
 *
 * <p>★ 顺序由<b>两个条件共同</b>保证，缺一个都静默失效：
 * <ol>
 *   <li>这里传 {@code hashKey = userId} —— RocketMQ 按
 *       {@code |hashCode| % 队列数} 选队列，<b>同一个人的消息必定进同一条队列</b>；</li>
 *   <li>消费端声明 {@code consumeMode = ORDERLY} —— 见 {@code ProgressReportConsumer}。</li>
 * </ol>
 * ★ 而「有序」的粒度是<b>单条队列内部</b>，不是整个 Topic：
 * {@code docs/docker/broker.conf} 里 {@code defaultTopicQueueNums = 4}，
 * 所以是「每个用户内部有序」，用户之间互不影响。
 *
 * <h3>★★ 为什么是「同步发送」，而阅读量那条是异步的</h3>
 *
 * <p>这是一个刻意的区别，不是随手写的：
 * <ul>
 *   <li>{@code syncSendOrderly} 会<b>阻塞到 Broker 返回</b>，请求路径上多一次往返 ——
 *       这是它的代价。</li>
 *   <li>换来的是一句能写下来的话：<b>「这条进度落进队列了」在方法返回时是已知的</b>。
 *       异步发送拿不到这个结论，失败也只体现在回调里。</li>
 *   <li>★ 顺序消息本来就<b>不适合异步发</b>：异步发送会攒批，同一批内的顺序虽然成立，
 *       但「谁先进批」取决于线程调度 —— 而这个接口恰恰是并发的
 *       （阅读器滚动时每秒一次，用户开两个标签页就是两条线程）。
 *       同步发送让「请求线程 → 消息入队」这条链路上没有任何缓冲区。</li>
 * </ul>
 *
 * <h3>★★ 发送失败为什么【吞掉】而不是抛出去</h3>
 *
 * <p>和 {@code ViewReportProducer} 结论相同、但<b>理由完全不同</b>，别互相套用：
 * <ul>
 *   <li>阅读量是<b>近似值</b>，少几次无所谓 —— 那是「不重要」。</li>
 *   <li>进度是<b>用户的书签</b>，丢了是真丢 —— 但它<b>自愈</b>：
 *       前端的滚动上报是 1 秒防抖的<b>持续</b>行为（见 {@code reader-web/src/api/reader.js}），
 *       读者只要继续往下滚，下一条消息就把最新位置补上了。
 *       会真正丢掉的只有「读者滚到底就立刻关掉页面」那一次，
 *       而那种情况下他本来也没有更晚的进度可记。</li>
 * </ul>
 * <p>★ 反过来说，<b>把这里改成抛异常是有害的</b>：Broker 一挂，
 * 每个正在看书的读者每秒都会收到一个 500。前端虽然带 {@code silent: true}
 * 不会弹提示，但服务端会被自己的错误日志淹掉，而<b>读者该丢的进度一条也没少丢</b>。
 * 换句话说，抛出去换不来任何东西，只是把一次静默降级变成一次日志雪崩。
 *
 * <p>★ 也正因为它吞掉异常，这个方法<b>不改变 {@code report} 的返回契约</b> ——
 * 上面那两次校验（{@code 2001} / {@code 3001}）仍然照常抛，
 * 那才是这个接口有信息量的部分。顺序是：<b>先校验、后发送</b>，
 * 所以「校验失败」永远不会被这里吞掉。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProgressReportProducer {

    private final RocketMQTemplate rocketMQTemplate;

    /**
     * 投递一条进度上报。
     *
     * @param userId    ★ <b>必须由调用方从 {@code UserContext} 取</b>，
     *                  绝不接受请求体里的值 —— {@code ProgressReportMessage.of}
     *                  的注释把这条写死了，别绕过它自己 new 一个。
     * @param novelId   作品 id
     * @param chapterId 章节 id
     * @param progress  已夹到 {@code [0, 100]} 的百分比
     */
    public void report(Long userId, Long novelId, Long chapterId, Integer progress) {
        /*
         * ★★ hashKey 传 userId 的字符串形式，不要传 novelId。
         *   传 novelId 的话，「同一本书的所有读者」会被挤到同一条队列上 ——
         *   顺序确实还在（同书内有序），但毫无意义：
         *   我们从来没要求过「张三的进度要在李四之后」。
         *   而代价是真实的：一本热门书的队头阻塞会挡住这本书全部读者的进度。
         *   顺序消息的 hashKey 选的应该是【需要保序的那个维度的最小封闭集】，
         *   在这里就是「一个人」。
         */
        try {
            rocketMQTemplate.syncSendOrderly(
                    MqConstant.destinationOf(MqConstant.TAG_PROGRESS_REPORT),
                    ProgressReportMessage.of(userId, novelId, chapterId, progress),
                    String.valueOf(userId));

            /*
             * ★ debug 而不是 info：这是阅读器每滚一屏就调一次的接口，
             *   info 级别会刷屏（和改造前那条「进度已记录」同一个道理）。
             *   ★ 注意这条日志的位置变了：它现在说的是「已投递」，
             *   不再是「已记录」—— 落库发生在另一个进程的消费者里。
             *   排查「进度没记住」时，这条日志只能证明消息发出去了，
             *   还要去消费者的日志里找下一跳。
             */
            log.debug("进度已投递 | userId={} novelId={} chapterId={} progress={}",
                    userId, novelId, chapterId, progress);
        } catch (Exception e) {
            /*
             * ★ 兜的是 sendOrderly 的【同步】异常：Broker 连不上、
             *   producer 未启动、队列满。理由见类注释最后一段。
             * ★ 不重试、不入库补偿：进度是持续上报的，下一条就是补偿。
             */
            /*
             * ★ 用 MqErrors.describe 而不是 e.getMessage()：RocketMQ 抛的是包装异常，
             *   getMessage() 只会给出「unknown reason」这种壳，真因在 cause 里。
             *   ★ 也不把整只 e 传进来（= 不打堆栈）—— 同 ViewReportProducer：
             *     这是读者滚动就会触发的路径，一次抖动几百条三十行的堆栈，
             *     只会把日志淹掉，换不来任何东西（见类注释最后一段）。
             */
            log.warn("进度投递失败（已忽略，读者下一次滚动会重新上报） | userId={} novelId={} chapterId={} error={}",
                    userId, novelId, chapterId, MqErrors.describe(e));
        }
    }
}
