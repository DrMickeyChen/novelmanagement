package com.novel.content.mq.consumer;

import com.novel.common.constant.MqConstant;
import com.novel.common.enums.NovelStatField;
import com.novel.common.mq.NovelStatMessage;
import com.novel.modules.content.service.NovelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 作品统计值同步的消费者（M3）：把 {@code collect_count} / {@code score} 写回 content 的 {@code novel} 表。
 *
 * <p><b>它替换掉的是 M2 留下的两笔债</b>（R8 / R9）—— 原来是 interaction 侧
 * 一次同步 Feign 写回：收藏数那次不在事务里（写失败即停在旧值、不自愈），
 * 评分那次在事务里（远程超时把 DB 事务挂住最长 5 秒）。
 * 债的完整论证在 {@code NovelStatMessage} 的类注释里，这里只讲「怎么消费」。
 *
 * <h3>★★ 为什么是 {@code CONCURRENTLY}，明明载荷推的是绝对值</h3>
 *
 * <p>「绝对值 → 必须顺序」是个很自然的推论，也是<b>本方案初稿写的东西，
 * 后来被推翻了</b>。
 * ★★ <b>但这里原来写的推翻理由也是错的，M3 实施时改正过一次：</b>
 * 原文说顺序消费「{@code maxReconsumeTimes} 默认 {@code Integer.MAX_VALUE} →
 * 永不放弃、永不进 {@code %DLQ%}、一条毒消息把整条 MessageQueue 永久卡死」。
 * <b>实测证明：只要显式配了 {@code maxReconsumeTimes}（四个消费者都写了 3），
 * 顺序消息重试 3 次后照常进 DLQ、队列照常前进。</b>
 * 「永久卡死」从来不是必然，只是<b>不写时的默认值</b>。
 * 实测记录（时间戳 + {@code reconsume} 计数 + DLQ 里的原始消息）见
 * {@code MqConstant} 的类注释。
 *
 * <p>★ 真正成立的代价是<b>队头阻塞 + 固定 1 秒重试 + 整条队列单线程</b>，
 * 而修正乱序<b>本来就不需要整条队列排队</b> —— 一个时间戳比较就够了。
 * 所以仍然改成<b>应用层单调写</b>（{@link NovelStatWriteGuard}）：
 * 载荷带生产时间戳，消费端只接受不旧于已应用时间戳的消息。
 * <b>乱序的旧消息到了只会被丢弃，不会排在最前面堵路。</b>
 * <b>结论没变，依据换成了「更便宜」而不是「不可恢复」。</b>
 *
 * <h3>★★ 幂等：这条链路【不需要】显式幂等，原因不是「我们忘了」</h3>
 *
 * <p>推的是<b>绝对值</b>：重复消费写的是同一个数，写两次和写一次结果一样。
 * 这白拿来的幂等，正是当初决定推绝对值而不是推 ±1 的收益之一
 * （见 {@code ShelfServiceImpl#refreshCollectCount} 里那段论证）——
 * 反过来说，<b>谁把生产者改成推增量，这里的幂等就没了，
 * 而且要额外加 SETNX</b>，那不是「优化」，是把一个不变量换成一套机制。
 *
 * <h3>★ 失败处理：永久失败绝不抛，只有基础设施异常才抛（判断 E）</h3>
 *
 * <p>和 {@code ViewReportConsumer} 同一条规矩，但这里的「永久失败」有真实内容 ——
 * 它<b>会碰数据库</b>，所以不能再写「本类永不抛」：
 * <ul>
 *   <li><b>永久失败</b>（重试一万次结果一样）→ 记 WARN、<b>正常返回（= ACK）</b>：
 *       载荷缺字段、{@code field} 不认识、收藏数的值不是整数 ——
 *       这些只可能来自手工投毒或代码写错，重试没有意义。</li>
 *   <li><b>瞬时失败</b>（Redis 连不上、MySQL 连不上）→ <b>不捕获</b>，
 *       让它抛出去进 {@code %RETRY%group-content-novelstat} 的延迟重试阶梯。</li>
 * </ul>
 * ★ 所以本类的 {@code onMessage} <b>有</b> try/catch，但<b>只包住参数校验那一段</b>，
 * 不包数据库那一段 —— 包了整个方法就等于把瞬时失败也吞了，
 * 那正是「一条坏消息变成一条丢数据的消息」的写法。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstant.TOPIC,
        selectorExpression = MqConstant.TAG_NOVEL_STAT,
        // ★★ 独占的消费组，见 MqConstant 的类注释：一个 (服务, Tag) 一个组。
        //   和 group-content-view / group-content-audit 是三个不同的名字 ——
        //   合并任意两个都会让其中一条 Tag 静默收不到消息。
        consumerGroup = MqConstant.GROUP_CONTENT_NOVELSTAT,
        // ★ 绝对值，但防乱序靠应用层单调写而不是顺序消费（理由见类注释）。
        consumeMode = ConsumeMode.CONCURRENTLY,
        // ★ 显式写死：注解默认值 -1 在顺序消费下等于永不放弃。这里虽是并发
        //   （-1 会被折算成默认的 16 次），但「每条消息的重试次数都在注解上看得见」
        //   比「有的靠默认、有的靠推导」更值得。同 ViewReportConsumer。
        maxReconsumeTimes = 3
)
public class NovelStatConsumer implements RocketMQListener<NovelStatMessage> {

    private final NovelService novelService;
    private final NovelStatWriteGuard writeGuard;

    @Override
    public void onMessage(NovelStatMessage message) {
        /*
         * ── 第一段：校验（永久失败在这里消化掉，绝不抛） ──
         *
         * ★ 这一段【必须】在写库之前就结束，而且不能和下面合并：
         *   下面那段的异常要抛出去重试，这一段的异常要 ACK 掉。
         *   两段的失败语义相反，所以它们必须能被分别看见。
         */
        if (message == null || message.novelId() == null
                || message.field() == null || message.value() == null) {
            log.warn("统计值消息缺少必要字段，已丢弃 | message={}", message);
            return;
        }

        /*
         * ★ 用枚举名（而不是 ordinal / code）当 Redis Hash 的 field：
         *   它就是消息体里的那个字符串，排查时 Redis 里看到的和消息里看到的是同一个词。
         *   ★ 别改成 ordinal —— 枚举加一个值就会让所有历史时间戳错位。
         */
        String field = message.field().name();

        /*
         * ★ 收藏数必须是整数 —— 校验放在【写库之前、守卫之前】，理由有两条：
         *   ① 它属于「永久失败」（这条消息永远不可能成功），必须和下面
         *      「数据库连不上」那种瞬时失败分开，绝不能被重试；
         *   ② 放在守卫【之前】，是因为一条被丢弃的毒消息不该在 Redis 里
         *      留下一个「已应用到的时间戳」—— 那会让随后一条**合法**的
         *      稍旧消息被判成过旧而丢掉。
         *
         * ★ 为什么不直接 value.longValueExact()：它在值带小数时抛
         *   ArithmeticException（RuntimeException），会一路冒到 onMessage 外面
         *   被当成瞬时失败重试 3 次再进死信，而日志里只有一句看不出根因的异常。
         *   ★ 而带小数的收藏数确实可能出现：NOVEL_STAT 这个 Tag 谁都能往
         *   novel-topic 上发（见 AuditRecordMessage 里那段「信任边界内的通道」），
         *   手工投一条 3.7 进来就会走到这里。
         */
        long collectCount = 0L;
        if (message.field() == NovelStatField.COLLECT_COUNT) {
            if (message.value().stripTrailingZeros().scale() > 0) {
                log.warn("收藏数不是整数，已丢弃 | novelId={} value={}",
                        message.novelId(), message.value());
                return;
            }
            collectCount = message.value().longValue();
        }

        /*
         * ★★ 单调写守卫：这里返回 false 就是「这条消息比已经应用过的旧」。
         *   直接 return = ACK = 丢弃。
         *
         * ★★ 这个 WARN 不能省、也不该降成 DEBUG：它是「顺序消息改成单调写」
         *   这条设计里【唯一能被观察到的证据】。压测时看到它，
         *   说明防乱序真的在拦东西（而不是「乱序根本没发生、守卫是摆设」）；
         *   线上看到它，说明这条 Tag 的乱序程度变高了，值得回头看看
         *   是不是消费端变慢、或者有实例在反复重平衡。
         */
        if (!writeGuard.shouldApply(message.novelId(), field, message.at())) {
            log.warn("统计值消息过旧，已丢弃 | novelId={} field={} at={}",
                    message.novelId(), field, message.at());
            return;
        }

        /*
         * ── 第二段：写库（瞬时失败在这里抛出去，交给重试阶梯） ──
         */
        switch (message.field()) {
            /*
             * ★ 收藏数走原有的 NovelService#refreshCollectCount，不新开一条写路径。
             *   那边挂着两条不能丢的论证：① 用 LambdaUpdateWrapper.set() 而不是
             *   实体 updateById（收藏数可能是 0，而「看起来像空所以跳过」是个静默 bug）；
             *   ② 不填 update_time（「有人收藏了」不是「作品更新了」）。
             *   在这里重写一遍就有两个会各自漂移的版本。
             */
            case COLLECT_COUNT -> novelService.refreshCollectCount(message.novelId(), collectCount);

            // ★ 评分原样传 BigDecimal：列是 DECIMAL(3,1)，不做 double 中转。
            case SCORE -> novelService.refreshScore(message.novelId(), message.value());
        }

        log.debug("统计值已落库 | novelId={} field={} value={} at={}",
                message.novelId(), field, message.value(), message.at());
    }
}
