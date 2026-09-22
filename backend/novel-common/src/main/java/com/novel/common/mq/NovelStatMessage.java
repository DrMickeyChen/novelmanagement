package com.novel.common.mq;

import com.novel.common.enums.NovelStatField;

import java.math.BigDecimal;

/**
 * 「{@code novel} 表上某一列现在是这个值」—— 作品统计值同步的消息载荷（M3）。
 *
 * <p>Tag：{@code NOVEL_STAT}；方向：<b>novel-interaction → novel-content</b>
 * （{@code collect_count} / {@code score} 都是 content 的表 {@code novel} 上的列）。
 *
 * <h3>它替换掉了什么（M2 留下的两笔债）</h3>
 *
 * <p>M2 之后这两处是<b>同步 Feign 写回</b>，各有一笔债：
 * <ul>
 *   <li><b>收藏数</b>（{@code ShelfServiceImpl.add/remove}）：写回<b>不在事务里</b> ——
 *       Feign 写失败时书架行已经提交、用户的收藏数却没变，<b>而且不会自愈</b>，
 *       停在一个错误的旧值上直到下次有人收藏这本书。</li>
 *   <li><b>评分</b>（{@code CommentServiceImpl.delete} / {@code audit}）：
 *       写回<b>在事务里</b> —— 远程超时（Feign read-timeout 5 秒）会把数据库事务
 *       一起挂住，两个 {@code @Transactional} 方法里各挂着一次远程调用。</li>
 * </ul>
 *
 * <p>换成消息之后两笔一起消掉：不在请求路径上、有重试、失败进死信，
 * 而内容最终会被对账任务兜住。
 *
 * <h3>★★ 载荷推「绝对值」而不是「加减量」—— 这是刻意的，别改</h3>
 *
 * <p>推绝对值有个白拿的好处：<b>幂等是天然的</b>。重复消费写的是同一个值，
 * 重试、重放、对账重推，全都不需要额外机制。
 * （对照 {@code VIEW_REPORT}：阅读量必须推增量，因为多个 content 实例各加各的；
 * 而这里的值是<b>从一个 instance 的表里 COUNT 出来的真值</b>，推总数才对。）
 *
 * <p>★ 代价是<b>乱序会让值回退</b>：先发出「收藏数 = 5」再发出「收藏数 = 3」
 * （用户连着点了取消），乱序到达、后一个先被消费 —— 最终停在 5，是错的。
 * 处置见 {@link #at}。
 *
 * <h3>★★ 为什么 {@code at} 是「单调写的钥匙」，而这里没有用顺序消息</h3>
 *
 * <p>顺序消息（ORDERLY）看起来是这个问题最直觉的解法。
 * ★★ <b>注意这里原来写的理由是错的。</b>原文说 ORDERLY「失败时永不放弃、
 * 永不进死信、整条队列被一条毒消息永久卡死」—— M3 实测推翻了它：
 * 只要 {@code maxReconsumeTimes} 是<b>显式</b>配的（四个消费者都写了 {@code 3}），
 * 顺序消息重试 3 次之后<b>照常进 {@code %DLQ%}</b>，队列<b>照常前进</b>。
 * 「永不放弃」只是<b>不写时的默认值</b>，实测记录见 {@code MqConstant} 的类注释。
 *
 * <p>★ 所以不用 ORDERLY 的真正理由是<b>便宜</b>，不是<b>不可恢复</b>：
 * <ul>
 *   <li>这条消息的消费端在 content，而 content 可能因为 DB 抖动而失败 ——
 *       按 ORDERLY 的语义，那几秒里<b>整条队列一条都过不去</b>（队头阻塞，
 *       实测半径 1/4，因为队列数是 4）。</li>
 *   <li>顺序消费的失败重试<b>固定在 1 秒</b>、且整条队列只交给<b>一个</b>线程，
 *       没有并发消费那种 10s/30s/60s 的延迟阶梯和横向铺开。</li>
 *   <li>而修正乱序<b>本来就不需要整条队列排队</b> —— 一个时间戳比较就够了。</li>
 * </ul>
 * <b>能用一条应用层不变量换掉的东西，就不该让整条队列去承担这些代价。</b>
 *
 * <p>做法就是：<b>消费端维护「这个 (novelId, field) 已经应用到的时间戳」，
 * 只有 {@code incoming.at >= 已应用.at} 才写库。</b>
 * 乱序的旧消息到了只会被丢弃，不会排在最前面堵路 ——
 * <b>把一条只能靠中间件保证的性质，换成了一条自己能保证的不变量。</b>
 *
 * @param novelId 作品 id
 * @param field   同步的是哪一列（{@code COLLECT_COUNT} / {@code SCORE}）
 * @param value   该列的<b>绝对值</b>。★ 见下面「为什么是 BigDecimal」
 * @param at      生产时刻（epoch 毫秒），★ <b>单调写的比较依据</b>
 */
public record NovelStatMessage(
        Long novelId,
        NovelStatField field,
        BigDecimal value,
        long at
) {

    /**
     * 构造一条统计值消息，生产时刻取当前。
     *
     * <h3>★★ {@code value} 为什么是 {@code BigDecimal}，不是 {@code Object}、也不是 {@code Number}</h3>
     *
     * <p>两个字段的真实类型不一样：{@code collect_count} 是 {@code INT}，
     * {@code score} 是 {@code DECIMAL(3,1)}。看着该用一个「什么都能装」的类型，
     * 但 {@code Object} 在 Jackson 上是个陷阱：
     * <b>JSON 数字反序列化成 {@code Object} 时，Jackson 按字面量大小猜类型</b> ——
     * {@code 3} 变 {@code Integer}、{@code 3.0} 变 {@code Double}、超过 int 变 {@code Long}。
     * 于是消费端拿到的东西<b>取决于那个数字长什么样</b>，
     * 想用还得先 {@code instanceof} 一串 —— 而漏掉一个分支就是 {@code ClassCastException}，
     * 在线上表现为「某些书的统计同步不了」，且只在那些数字恰好是某种字面量时复现。
     *
     * <p>{@code BigDecimal} 是 Jackson 里<b>唯一能无损还原任意 JSON 数字</b>的类型，
     * 而且它正好就是 {@code DECIMAL} 列在 Java 侧的对应物 ——
     * 一个类型同时满足两边，不用做转换。整数走它也不失真
     * （{@code new BigDecimal(26)} 精确等于 26，不是 26.000000000000004），
     * 消费端取 {@code intValueExact()} / {@code doubleValue()} 都是确定的。
     *
     * <p>★ 反过来说：<b>如果这里写 {@code double}，收藏数迟早会出现 25.999999999999996</b> ——
     * 那是个只在数字大到一定程度才出现的经典浮点误差，
     * 而「收藏数显示成 25.999…」这种事没人能一眼看出根因。
     */
    public static NovelStatMessage of(Long novelId, NovelStatField field, BigDecimal value) {
        return new NovelStatMessage(novelId, field, value, System.currentTimeMillis());
    }
}
