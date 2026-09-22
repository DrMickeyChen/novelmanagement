package com.novel.content.mq.consumer;

import com.novel.common.constant.MqConstant;
import com.novel.common.constant.RedisKeyConstant;
import com.novel.common.mq.AuditRecordMessage;
import com.novel.common.mq.MqErrors;
import com.novel.modules.content.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 审核流水的消费者（M3）：把 {@code AUDIT_RECORD} 落成 content 的 {@code audit_log} 一行。
 *
 * <p><b>它是 M3 断掉服务级环 #1（{@code novel-auth ⇄ novel-content}）的那一半。</b>
 * 生产者那半是 auth 的 {@code AuthorApplyAuditProducer}（<b>事务消息</b>）与
 * interaction 的 {@code CommentAuditProducer}（普通消息 + {@code AfterCommit}）
 * ——<b>两条 Tag 相同、可靠性等级不同</b>，这个消费者对它俩一视同仁：
 * 到这一侧时，消息已经是「确定发生过的事」了。
 *
 * <h3>★★ 坑 1：消费者线程上【永远没有】{@code UserContext} —— 而且绝不能伪造一个</h3>
 *
 * <p>{@code AuditLogServiceImpl} 里那句 {@code UserContext.requireUserId()} 是
 * {@code OncePerRequestFilter} 填进去的，而 MQ 线程背后<b>根本没有请求</b>。
 * HTTP 时代身份能跨服务跳活下来，靠的是 {@code FeignIdentityConfig} 的出站拦截器
 * 重写 {@code X-User-*} 头、content 侧的过滤器再读回来 ——
 * <b>MQ 把这条第二通道整个拿掉了。</b>
 *
 * <p><b>处置是给 {@code AuditLogService} 加一个显式传 {@code operatorId} 的重载</b>
 * （那个接口的注释里记着这次「预判说对了机制、说错了场景」），
 * <b>不是</b>在消费者里 {@code UserContext.set(一个假的 LoginUser)}：
 * <ul>
 *   <li>那样 {@code requireUserId()} 会<b>开始在消费者线程上「正常工作」</b>，
 *       把「这里根本没有调用方」这件事藏起来 —— <b>比抛异常更坏</b>；</li>
 *   <li>而且 MQ 的消费线程池是<b>复用</b>的，{@code set()} 不配 {@code finally clear()}
 *       就是 {@code UserContext} 注释里记的那个「张三登录着、李四的请求以张三身份执行」。</li>
 * </ul>
 *
 * <h3>★★ 坑 2：幂等 —— 四条消息里【只有它】需要显式幂等</h3>
 *
 * <p>另外三条的重复消费都是无害的（阅读量是累加、进度是 upsert、统计值是绝对值覆盖），
 * <b>而流水是「一次事实」</b>：重复写一条等于记了一次没发生的事，
 * 正好撞上 {@code AuditLogService} 那句「一条不真实的流水比没有流水更糟」。
 * {@code audit_log} 又没有唯一键（它本来就不该有 —— 同一条评论可以被审核多次），
 * 所以只能靠载荷里的 {@code messageKey} 做 {@code SETNX}。
 *
 * <h3>★★★ 幂等键的【释放】——本类最容易写错、写错了照样跑通的地方</h3>
 *
 * <p>直觉写法是「SETNX 成功 → 写库」，失败时抛出去让 Broker 重试。
 * <b>那样写会让任何一次数据库抖动变成一条永久丢失的流水</b>：
 * <pre>
 *   SETNX 成功（key 已存在，TTL 24h） → 写库抛异常（MySQL 挂了）
 *   → Broker 10 秒后重投 → SETNX 失败 → 「这条处理过了」→ 正常返回（ACK）
 *   → ★ 流水一次都没写成，消息却被确认掉了
 * </pre>
 * 所以下面 <b>认领之后必须 try/catch，写失败时删掉自己的 key 再抛</b> ——
 * 把「认领」退回成「没认领」，重试才有意义。
 * ★ 这里 {@code DEL} 是安全的：key 里带着 {@code messageKey}（生产者生成的唯一串），
 * 这一次删的必然是自己刚才那一把，不会误删别人的。
 *
 * <h3>★ 失败处理：和另外两个消费者同一条规矩（判断 E）</h3>
 *
 * <p>永久失败（缺 {@code messageKey} / 缺 {@code operatorId}）→ 记日志、ACK 丢弃；
 * 瞬时失败（Redis / MySQL 挂）→ 抛出去进 {@code %RETRY%group-content-audit}。
 *
 * <p>★ <b>为什么「缺 operatorId」要丢弃而不是写一条 {@code operator_id} 为 null 的流水</b>：
 * 那张表的全部价值就是「谁做的」这件事有据可查。一条不知道是谁的流水
 * <b>看起来是一条正常记录</b>，查账时才会发现查不下去 —— 那时已经无从补起。
 * {@code AuditLogServiceImpl} 用 {@code requireUserId()} 而不是 {@code getUserId()}
 * 就是在守这条线，本类不能从后面把它绕过去。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstant.TOPIC,
        selectorExpression = MqConstant.TAG_AUDIT_RECORD,
        // ★★ 独占的消费组，见 MqConstant 的类注释：一个 (服务, Tag) 一个组。
        //   auth 的事务消息和 interaction 的普通消息【共用这一个组】是对的 ——
        //   它们 Tag 相同、消费模式相同、载荷是同一个 record，只是发送方式不同。
        //   （★ 反过来说：如果哪天给其中一条换了 consumeMode，就必须拆组。）
        consumerGroup = MqConstant.GROUP_CONTENT_AUDIT,
        consumeMode = ConsumeMode.CONCURRENTLY,
        // ★ 显式写死，理由同 ViewReportConsumer：注解默认 -1 在顺序消费下等于永不放弃。
        //   这里是并发（-1 折算成默认 16 次），但「重试次数看得见」比「靠推导」值钱。
        maxReconsumeTimes = 3
)
public class AuditRecordConsumer implements RocketMQListener<AuditRecordMessage> {

    /**
     * 幂等 key 的存活时间。
     *
     * <p>★ 24 小时，<b>比死信保留期（{@code broker.conf} 的 {@code fileReservedTime = 48}
     * 小时）短</b>。这不是随手取的：重投只发生在重试阶梯和消费位点回退里，
     * 那几个窗口都在分钟级；而超过 48 小时还出现的，只可能是有人手工重放一条两天前的死信 ——
     * 那时候「重新记一条流水」比「静默丢掉」更能接受。
     * 完整论证见 {@code RedisKeyConstant#MQ_IDEMPOTENT_PREFIX}。
     */
    private static final Duration IDEMPOTENT_TTL = Duration.ofHours(24);

    private final AuditLogService auditLogService;

    /**
     * ★ 必须是 {@code StringRedisTemplate}（纯字符串），不是
     * {@code RedisTemplate<String,Object>} —— 后者的 value 序列化器会把 {@code 1}
     * 写成一坨 JSON，于是「值等于 1」这种判断莫名其妙地不成立。
     * 完整论证见 {@code RedisConfig} 的类注释。
     */
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void onMessage(AuditRecordMessage message) {
        /*
         * ── 第一段：校验（永久失败，ACK 掉） ──
         */
        if (message == null || message.messageKey() == null || message.messageKey().isBlank()) {
            /*
             * ★ 缺幂等键就只能丢弃，不能「算了这次不做幂等」。
             *   那等于在消费者里开一个后门：一条没有 key 的消息可以随便重投几次，
             *   每次都会多写一条流水。而幂等键是【生产者】生成的
             *   （见 AuditRecordMessage#of），所以缺它意味着消息不是我们的生产者发的。
             */
            log.error("流水消息缺少幂等键，已丢弃（它不像是本项目生产者发出的） | message={}", message);
            return;
        }
        if (message.operatorId() == null) {
            log.error("流水消息缺少操作人，已丢弃（宁可不记，也不记一条不知道是谁的） | message={}", message);
            return;
        }

        /*
         * ── 第二段：认领幂等键 ──
         *
         * ★ 一条命令 SET key 1 NX EX 86400（setIfAbsent + Duration 就是它）。
         *   拆成「先 GET 判断、再 SET」在并发重投下会双双通过；
         *   拆成「SETNX + EXPIRE」一旦中间断了，这把 key 就【永远不会过期】，
         *   那条消息对应的业务从此再也处理不了，且没有日志。
         *   同 CommentServiceImpl 里评论 10 秒频控那段论证。
         */
        String idempotentKey = RedisKeyConstant.MQ_IDEMPOTENT_PREFIX + message.messageKey();
        Boolean claimed = stringRedisTemplate.opsForValue()
                .setIfAbsent(idempotentKey, "1", IDEMPOTENT_TTL);
        if (!Boolean.TRUE.equals(claimed)) {
            /*
             * ★ 这条不是 ERROR，是 DEBUG：重复消费是【设计内的正常事件】
             *   （重试、重平衡、位点回退都会产生），把它打成 ERROR
             *   会让真正的故障被淹没在噪音里。
             */
            log.debug("流水消息已处理过，跳过 | key={}", message.messageKey());
            return;
        }

        /*
         * ── 第三段：写库（瞬时失败要抛，但抛之前必须把认领退回去） ──
         */
        try {
            auditLogService.record(
                    message.targetType(),
                    message.targetId(),
                    message.action(),
                    message.remark(),
                    message.operatorId(),
                    /*
                     * ★ 用消息里的生产时刻，不是 now()。
                     *   audit_log.create_time 是管理端日志页上显示的时间，
                     *   它要回答「管理员什么时候审的」，而不是「这条消息什么时候轮到被处理」。
                     *   一有积压这两者就差得很远，而且没人能从页面上看出来。
                     */
                    message.atAsLocalDateTime());

            log.info("审核流水已落库 | key={} type={} targetId={} action={} operatorId={}",
                    message.messageKey(), message.targetType(), message.targetId(),
                    message.action(), message.operatorId());
        } catch (Exception e) {
            /*
             * ★★ 这一段是整个 M3 里最有必要写下来的一段注释。
             *
             *   幂等 key 已经写下（TTL 24 小时），而流水没写成。
             *   如果就这么把异常抛出去，Broker 会重投 ——
             *   重投时 SETNX 必然失败，于是这条消息被判成「已处理」而 ACK 掉：
             *   ★ 一次数据库抖动 = 一条永久丢失的流水，而且日志里只有一行
             *     「流水消息已处理过，跳过」（DEBUG 级，生产上根本看不到）。
             *
             *   所以抛之前先把认领退回去 —— 「删掉自己刚才写的那把 key」，
             *   让重试回到「从没来过」的状态。
             * ★ DEL 失败（Redis 也挂了）时仍然把原异常抛出去：那说明整个
             *   基础设施在抖，重试本来就会继续失败，丢的这一条由人工看死信兜底。
             *   （本项目目前没有任何东西在看 %DLQ%，这是 M5 的监控债 —— 记在这里。）
             */
            stringRedisTemplate.delete(idempotentKey);
            /*
             * ★ 只写短描述、不带整只 e —— 因为下面就把原异常【抛出去了】，
             *   框架会带完整堆栈再打一遍，这里再打一次就是同一件事说两遍。
             * ★ 用 MqErrors.describe 而不是 e.getMessage()：MyBatis 的
             *   DataAccessException 家族也是包装器，getMessage() 常常只有
             *   「nested exception is ...」这类壳。（理由详见 MqErrors 类注释）
             */
            log.warn("流水落库失败，已退回幂等键等待重投 | key={} error={}",
                    message.messageKey(), MqErrors.describe(e));
            throw e;
        }
    }
}
