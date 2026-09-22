package com.novel.common.mq;

/**
 * 「这本书被读了 {@code delta} 次」—— 阅读量上报的消息载荷（M3）。
 *
 * <p>Tag：{@code VIEW_REPORT}；<b>生产方和消费方都是 novel-content</b>
 * （{@code novel.view_count} 是 content 的表）。
 *
 * <h3>★★ 为什么载荷里是「增量」而不是「总数」—— 这条决定了整条链路的样子</h3>
 *
 * <p>推增量的话，<b>消息乱序是无害的</b>：累加与顺序无关。
 * 于是这条可以放心用 {@code CONCURRENTLY} 消费、可以在消费端随便聚合、
 * 可以让多个 content 实例各加各的（{@code view_count = view_count + ?} 可加），
 * <b>连分布式锁都不需要</b>。推总数就三条全部反过来。
 *
 * <p>★ 这条规则是从大纲那句「阅读量上报走 MQ 削峰」往下推出来的，
 * 不是我们额外发明的：削峰的前提就是「这件事可以攒一攒、批量做」，
 * 而攒的前提是「怎么合并都不会错」，只有增量满足。
 *
 * <h3>★ 用 {@code record} 而不是 {@code class} + setter</h3>
 *
 * <p>消息载荷是<b>值</b>，不是可变对象：生产者构造完就发走，消费者拿到就落库。
 * record 天然不可变，也天然没有「消费者改了载荷、下一个消费者看到改过的值」这种
 * 跨消费者的串味。<b>代价是 Jackson 反序列化要靠构造器参数名</b> ——
 * 这需要编译时开 {@code -parameters}，父 pom 的 maven-compiler-plugin
 * 里已经有了（MyBatis 的 {@code #{name}} 绑定也依赖它，所以它不会被人误删）。
 *
 * @param novelId 作品 id。★ 生产者已经校验过它存在且已上架 —— 见下面的说明
 * @param delta   增量，正常恒为 {@code 1}。
 *                ★ 之所以不写死成「收到一条就 +1」，是为了让「合并多条」这件事
 *                在类型上就说得通：消费端做批量聚合时，累加的是 {@code delta} 而不是消息条数
 * @param at      生产时刻（epoch 毫秒）。
 *                ★ 这一条<b>不是</b>用来防乱序的（增量乱序无害），是用来
 *                <b>排查</b>的：消息在 Broker 里待了多久、有没有堆在消费端的内存里，
 *                拿它和消费时刻一减就知道。<b>没有这个字段，积压就只是个感觉。</b>
 */
public record ViewReportMessage(
        Long novelId,
        int delta,
        long at
) {

    /**
     * 构造一条「读了 1 次」的上报。
     *
     * <p>★ 生产时刻在这里盖，<b>不在消费端盖</b>：
     * 消费端盖出来的时间是「这条消息被处理的时间」，
     * 一有积压就和事实差出好几秒 —— 而这个字段的用途正是<b>衡量积压</b>，
     * 盖在消费端会让它恒等于 0，等于没盖。
     * （阅读进度那条更要紧：它盖的时间戳会直接进 {@code read_history.update_time}，
     * 而那个列是阅读历史的排序依据。同一个道理。）
     */
    public static ViewReportMessage of(Long novelId) {
        return new ViewReportMessage(novelId, 1, System.currentTimeMillis());
    }
}
