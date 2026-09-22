package com.novel.common.mq;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 「这件事等事务提交了再做」—— M3 新增，目前只用来发消息。
 *
 * <p>放在 {@code com.novel.common.mq} 下是因为它<b>唯一的用途</b>就是
 * 「DB 改了，才让消息可见」，而<b>不是</b>因为它依赖 MQ ——
 * ★ 这个类<b>一行 RocketMQ 的代码都没有</b>，收的是一个普通 {@link Runnable}。
 * 序列化和发送都在调用方（各服务的 producer）里。
 *
 * <h3>★★ 为什么必须有这个东西：先发消息后回滚，消费者会看见一个没发生的事实</h3>
 *
 * <p>想象一个 {@code @Transactional} 方法：改了自己的表，然后发一条消息告诉别人。
 * 如果发送就写在方法体里，那么它的执行时刻是<b>事务还开着、还没提交</b>的时候 ——
 * 于是可能出现这个顺序：
 * <pre>
 *   改 DB（未提交） → 发消息 → 消费者收到并处理 → 本方法回滚
 * </pre>
 * 消费者<b>已经根据一个不存在的事实行动了</b>，而且它永远不知道自己错了。
 *
 * <p>★ 反过来「先提交、后发送」也不行 —— 那是同一个洞的另一半：
 * <pre>
 *   改 DB → 提交 → <b>进程在这里挂了</b> → 消息永远发不出去
 * </pre>
 * 数据变了、通知没发，同样不会自愈。
 *
 * <p><b>★★ 这两个洞【没有】纯应用的解法 —— 这正是 RocketMQ 事务消息存在的理由。</b>
 * 所以本类的定位要说清楚，别把它当成万能药：
 * <ul>
 *   <li><b>收藏数（{@code ShelfServiceImpl}）用它</b> ——
 *       那条消息推的是<b>绝对值</b>，而且有<b>对账任务</b>兜底
 *       （{@code NovelStatReconcileJob}）。真丢了，「提交了但没发出去」这个窗口
 *       会在下一个对账周期里被纠回来。所以这里可以接受
 *       「提交成功、发送失败」这个残余窗口。</li>
 *   <li><b>审核流水【不能】用它</b> —— {@code audit_log} 的价值就是
 *       「一条流水对应一次真实发生过的操作」，丢一条或者多一条都是错的，
 *       而且它<b>没有对账</b>（流水的真值在业务的因果里，不在任何一张表的 COUNT 里）。
 *       所以那条边用的是<b>事务消息</b>（半消息 + 回查），不是本类。</li>
 * </ul>
 * <p><b>一句话：能被对账兜住的用本类，兜不住的用事务消息。</b>
 *
 * <h3>★★ 传进来的 {@code action} 必须自己吞掉异常</h3>
 *
 * <p>这是本类唯一的、也是必须遵守的约定。理由在 Spring 的源码注释里写得很直白
 * （{@code AbstractPlatformTransactionManager#processCommit}）：
 * <blockquote>
 * Trigger afterCommit callbacks, with an exception thrown there
 * <b>propagated to callers but the transaction still considered as committed.</b>
 * </blockquote>
 * 也就是说：<b>在 {@code afterCommit} 里抛异常，调用方会收到一个失败，
 * 而事务其实已经提交了。</b> —— 「报错了」和「没做成」说的是两件相反的事，
 * 和 M2 那笔 R9 债的成因一模一样，只是方向相反。
 *
 * <p>★ 所以本项目所有 producer 的发送方法<b>自己 try/catch 并记 WARN</b>
 * （见 {@code ProgressReportProducer#report}），本类因此<b>不需要</b>再包一层 ——
 * 两层都包会让人以为「肯定有一处在把关」，反而两边都不敢改。
 * <p>★ 本类也<b>不打印 action 的异常</b>：它没东西可打，说明不了是哪个业务失败。
 * 日志由 producer 打，那里知道 novelId 和 count。
 *
 * <h3>★ 没有事务时直接执行</h3>
 *
 * <p>{@link TransactionSynchronizationManager#isSynchronizationActive()} 为
 * {@code false} 时（调用方没有事务，或者事务已经结束）<b>立刻执行</b>。
 * ★ 这不是「兜底分支」，是<b>正常分支</b>：{@code ShelfServiceImpl.add/remove}
 * 在 M3 之前根本没有 {@code @Transactional}，M3 才给它们加上 ——
 * 而将来如果有人去掉那个注解，<b>消息不能因此就不发了</b>。
 *
 * @author MickeyChen
 */
public final class AfterCommit {

    /** 工具类，禁止实例化 */
    private AfterCommit() {
    }

    /**
     * 在当前事务提交之后执行 {@code action}；没有事务则立刻执行。
     *
     * @param action ★ 必须自己处理异常 —— 见类注释那段 Spring 源码引文。
     *               <b>别在这里抛出去。</b>
     */
    public static void run(Runnable action) {
        /*
         * ★★ 这个 if/else 写反了的话，两个分支里必有一个【永远不发消息】，
         *   而且不报错：
         *     写反成「有事务就直接执行」→ 消息先于提交，回滚后消费者看见假事实；
         *     写反成「没事务就注册同步」→ 注册进一个不存在的同步器里，
         *     消息石沉大海（registerSynchronization 在没有活动事务时会抛，
         *     所以这一支其实会炸 —— 但炸在「功能第一次上线」的时候）。
         *   判断依据只有一个：isSynchronizationActive()，别用别的东西代替。
         */
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
