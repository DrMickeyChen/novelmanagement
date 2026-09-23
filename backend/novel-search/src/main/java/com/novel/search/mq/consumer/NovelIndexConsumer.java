package com.novel.search.mq.consumer;

import com.novel.common.constant.MqConstant;
import com.novel.common.internal.InternalResults;
import com.novel.common.internal.NovelIndexDoc;
import com.novel.common.mq.NovelIndexMessage;
import com.novel.search.client.ContentClient;
import com.novel.search.document.NovelDocument;
import com.novel.search.service.IndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 索引重建的消费者（M4）：收到「这本书的索引可能过期了」，回 content 读真值，写进 ES。
 *
 * <p>★★★ <b>本类的核心是「消费方必须是 search」这件事</b> —— 见
 * {@code ContentClient} 的类注释：content → search 只有 MQ、没有 HTTP，
 * 所以这一对服务<b>不构成服务级环</b>。★ <b>把本类搬到 content 那边去
 * （改成 content 消费、再调 search 推文档）会立刻造出一个环</b>，
 * 而它在代码上看起来只是「把消费者挪个位置」。
 *
 * <h3>★★★ 幂等：这一条是「天然免费」的，前四条消息都没有这个待遇</h3>
 *
 * <p>{@code NovelIndexMessage} 的载荷里<b>只有一个 id</b>，没有内容字段。
 * 于是本类做的事永远是「<b>读当前真值 → 覆盖写同一个文档</b>」，由此得到：
 * <ul>
 *   <li><b>重复消费</b> —— 两次读同一份状态、写同一个 {@code _id} → 结果一样
 *       （<b>不需要 {@code SETNX}</b>，对照 {@code AuditRecordConsumer}）；</li>
 *   <li><b>乱序消费</b> —— 同上（<b>不需要时间戳单调写</b>，对照 {@code NovelStatConsumer}）；
 *       ★ 载荷里那个 {@code at} 是<b>纯观测</b>字段，本类一个字都不用它；</li>
 *   <li><b>顺序</b> —— 更不需要（<b>不是 ORDERLY</b>，对照 {@code ProgressReportConsumer}）。</li>
 * </ul>
 * <p>★ 所以本类的 {@code consumeMode} 是 {@code CONCURRENTLY}，而且
 * <b>不是「虽然并发但有别的机制兜着」，是「并发在这里真的没有代价」</b>。
 * 铁律 7 那张五种形态的表里，这是唯一一条「幂等免费」的。
 *
 * <h3>★★ 失败处理：永久失败绝不抛，只有基础设施异常才抛（判断 E，同 M3 四条消费者）</h3>
 *
 * <ul>
 *   <li><b>永久失败</b>（重试一万次结果一样）→ 记 WARN、<b>正常返回（= ACK）</b>：
 *       载荷缺 {@code novelId}。这只能是手工投毒或代码写错。</li>
 *   <li>★★ <b>「作品不存在」不是失败，是正常业务</b> —— 见下面的 delete 分支。
 *       它<b>必须 ACK</b>：作品已经删了，重试一万次它还是不存在。</li>
 *   <li><b>瞬时失败</b>（ES 连不上、content 连不上）→ <b>不捕获</b>，
 *       让它抛出去进 {@code %RETRY%group-search-index} 的延迟重试阶梯。</li>
 * </ul>
 * ★ 所以本类的 try/catch <b>只包住参数校验那一段</b>，和 {@code NovelStatConsumer} 同一个形状。
 * <b>包了整个方法就等于把「ES 挂了」也 ACK 掉</b> —— 索引会永久陈旧，
 * 而且日志里只有一行 WARN，看上去和「一切正常」没有区别。
 *
 * <h3>★ 兜底：本类失败或不跑，都不影响最终一致</h3>
 *
 * <p>S8 的定时全量重建会把索引整个重灌一遍（判断 6 里那条「有对账兜底的用 {@code AfterCommit}」）。
 * ★★ <b>但兜底不是「可以随便丢」的理由</b>：全量重建的周期是小时级，
 * 在那之前用户看到的是旧数据。所以瞬时失败仍然要重试，不能在这里吞掉。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstant.TOPIC,
        selectorExpression = MqConstant.TAG_NOVEL_INDEX,
        /*
         * ★★ 独占的消费组，见 MqConstant 的类注释：一个 (服务, Tag) 一个组。
         *   这是全项目唯一一个不属于 content / interaction / auth 的组。
         *   ★ 两个 Tag 共用一个组名的症状是「后注册的覆盖前一个、某个 Tag 收不到消息且不报错」
         *     —— 和网关那条 `4 < 5` 的路由不变式是同一种形状。
         */
        consumerGroup = MqConstant.GROUP_SEARCH_INDEX,
        /*
         * ★ 幂等天然 + 顺序无关，所以并发在这里真的没有代价（见类注释）。
         *   不需要 ORDERLY —— 那是 PROGRESS_REPORT 独有的，代价是队头阻塞 + 单线程消费。
         */
        consumeMode = ConsumeMode.CONCURRENTLY,
        /*
         * ★ 显式写死 3。注解默认值 -1 在【顺序消费】下等于 Integer.MAX_VALUE
         *   （M3 实测订正过：显式写了之后顺序消息照样重试 3 次进 DLQ，队列照样前进）。
         *   本类是并发消费，-1 会被折算成默认的 16 次 ——
         *   ★ 但「每条消息的重试次数都在注解上看得见」比「有的靠默认、有的靠推导」更值得。
         */
        maxReconsumeTimes = 3
)
public class NovelIndexConsumer implements RocketMQListener<NovelIndexMessage> {

    private final ContentClient contentClient;
    private final IndexService indexService;

    @Override
    public void onMessage(NovelIndexMessage message) {
        /*
         * ── 第一段：校验（永久失败在这里消化掉，绝不抛） ──
         * ★ 这一段和下面那段【必须分开】：两段的失败语义相反
         *   （这里要 ACK，那里要重试），所以它们必须能被分别看见。
         */
        if (message == null || message.novelId() == null) {
            log.warn("索引消息缺少 novelId，已丢弃 | message={}", message);
            return;
        }

        Long novelId = message.novelId();

        /*
         * ── 第二段：读真值 → 写索引（瞬时失败在这里抛出去，交给重试阶梯） ──
         *
         * ★★ 顺序是「先问 content、再决定 upsert 还是 delete」，不能反。
         *   反过来（先删再问）在「读失败」时会留下一个不该有的空洞。
         */
        Map<Long, NovelIndexDoc> docs =
                InternalResults.unwrap(contentClient.indexDocs(List.of(novelId)), "索引重建取数");

        if (docs == null || !docs.containsKey(novelId)) {
            /*
             * ★★★ 这一支【不是错误分支】，它有两种正常成因，处置相同：
             *
             *   ① 作品被【逻辑删除】了 —— 这是最主要的成因，也是本分支存在的理由。
             *      `@TableLogic` 是 MyBatis 往 WHERE 里注入 `deleted = 0`，
             *      ★ ES 完全不知道 `deleted` 这一列存在，那条文档会原样留在索引里。
             *      不删它的后果不是「内容泄露」（装配端查不到，第二道闸门挡着），
             *      而是 total 虚高 + 页内空洞。
             *
             *   ② 作品从来不存在（id 打错了 / 手工投的毒消息）。
             *
             * ★★ 所以这里【必须 ACK】：重试一万次，这本书还是不存在。
             *   把它当失败抛出去，就是「一条永不可能成功的消息占着重试阶梯」。
             *
             * ★ 日志用 INFO 不用 WARN：成分 ① 是正常业务流程（作者删作品），
             *   用 WARN 会让「删了几本书」变成几条告警。而成分 ② 是编程/投毒错误，
             *   它会表现为「同一条消息反复出现而没有任何一次 upsert」——
             *   ★ 这个信号在 INFO 里同样看得见，不需要靠级别去抬。
             */
            log.info("索引文档已删除（作品不存在或已逻辑删除） | novelId={} reason={}",
                    novelId, message.reason());
            indexService.delete(novelId);
            return;
        }

        /*
         * ★ NovelDocument.from 是【全项目唯一】一处 NovelIndexDoc → ES 文档的映射
         *   （LocalDateTime → ISO-8601 字符串的转换就在那里）。这里不要自己拼字段。
         */
        indexService.bulkUpsert(List.of(NovelDocument.from(docs.get(novelId))));

        /*
         * ★ DEBUG 级：这条链路每改一次作品就跑一次，INFO 会把正常日志刷满。
         *   ★ reason 在这里派上用场 —— 它是唯一能回答
         *   「明明没改书名，索引为什么一直在重建」的字段。
         */
        log.debug("索引已同步 | novelId={} reason={}", novelId, message.reason());
    }
}
