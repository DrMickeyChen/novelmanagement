package com.novel.auth.mq.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.common.constant.MqConstant;
import com.novel.common.enums.AuditAction;
import com.novel.common.enums.AuditStatus;
import com.novel.common.enums.AuditTargetType;
import com.novel.common.mq.AuditRecordMessage;
import com.novel.common.mq.MqErrors;
import com.novel.modules.system.entity.AuthorApply;
import com.novel.modules.system.mapper.AuthorApplyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.apache.rocketmq.common.message.MessageAccessor;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQUtil;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;

/**
 * 写一条「作者申请审核」流水的生产者（M3）—— <b>全网唯一一条用【事务消息】发的消息</b>。
 *
 * <p>Tag：{@code AUDIT_RECORD}；方向：<b>novel-auth → novel-content</b>
 * （{@code audit_log} 是 content 的表）。它替换掉的是
 * {@code ContentAuditClient.recordAuthorApplyAudit} 那次 Feign 写，
 * <b>而这一替换就是 M3 断掉服务级环 #1（{@code novel-auth ⇄ novel-content}）的全部动作</b>：
 * 本类落地之后，{@code novel-auth} 里<b>一条出站边都不剩</b>。
 *
 * <p><b>★★ 复核「一条出站边都不剩」要用这两条，别用 {@code grep novel-content}：</b>
 * <pre>
 *   # ① 出站边的宿主包不存在 —— 没有这个包，就写不出 @FeignClient
 *   ls backend/novel-auth/src/main/java/com/novel/auth/client/     → 目录不存在
 *
 *   # ② 依赖树里没有 feign / loadbalancer（★ 树是 Maven 算出来的，注释混不进来）
 *   mvn -f backend/pom.xml -pl novel-auth -am dependency:tree \
 *     | sed -n '/com.novel:novel-auth:jar/,$p' | grep -iE "feign|loadbalancer"   → 0
 * </pre>
 * ★ 阳性对照（证明这条管道【会】失败，不是只会打印 0）：把上面第二条的
 * {@code -pl novel-auth} 换成 {@code -pl novel-interaction} —— 同一段立刻 8 命中
 * （那边还留着 openfeign + loadbalancer）。没有这个对照，
 * 「0 命中」和「命令写错了」看起来是一模一样的。
 * ★ 本行原来写的是 {@code grep -rn "novel-content" backend/novel-auth/src/ → 0}，
 * <b>M3 实测那是 33 命中</b> —— 而且一条代码都不是，全是注释：
 * 「三份 application-dev.yml 的这三个值必须一致」「改造前这里有 ContentAuditClient」
 * 这类话<b>必然会提到那个名字</b>，删干净是不可能的。
 * ★ 于是那条「机械证据」成了一条<b>永远失败、且失败得毫无信息量</b>的命令：
 * 下一个人跑它、看到 33 行、心想「哦大概是注释」，护栏就废了。
 * <b>一条会被噪音淹没的判据和一条不存在的判据一样没用</b> ——
 * 判据要钉在「有没有这条边」上（注解 / 依赖 / 包），不是钉在一个词上。
 *
 * <h3>★★ 为什么这条必须用事务消息，而 interaction 那条（评论审核）不用</h3>
 *
 * <p>两条消息 Tag 相同、{@code consumerGroup} 相同、载荷是同一个 record、
 * 幂等机制也相同 —— <b>只有【发送方式】不同</b>。这不是疏漏，是代价对比的结果，
 * 论证在 {@code CommentAuditProducer} 的类注释里（那边是「不划算」的一侧）。
 * 这里是「划算」的那一侧，一句话：<b>auth 整个服务只发这一条消息，
 * 它的 {@code rocketmq.producer.group} 本来就是事务组</b>
 * （{@code novel-tx-audit}，见 {@code MqConstant.PRODUCER_GROUP_AUTH_TX}），
 * 监听器是白拿的；interaction 那边已经有两条普通消息占着 producer group，
 * 再养一个事务组 + 一个模板 bean 才是成本。
 *
 * <h3>★★★ 判断 C′：裁决只能在【本地事务提交之后】发出去 —— 而库的 API 把两件事压在了一次调用里</h3>
 *
 * <p><b>直觉写法是「发完半消息、本地事务也做完了，那就返回 COMMIT」—— 那是错的，
 * 而且错得很隐蔽：</b>
 * <pre>
 *   模板的 sendMessageInTransaction 一次调用里干了三件事：
 *     ① 发半消息 → ② 执行 executeLocalTransaction → ③ 按它返回的状态决定半消息可不可见
 *
 *   ★ 而 ② 是在【外层 Spring 事务还开着、还没提交】的时候跑的
 *   → 在这里返回 COMMIT，半消息【可能先于 DB 提交】对消费者可见
 *   → auth 随后回滚 → content 里留下一条「审批通过了」的流水，而审批根本没发生
 * </pre>
 *
 * <p>★ 注意这比 M2 那个「孤儿流水」<b>更坏</b>：孤儿流水至少内容是真的
 * （那次审核确实发生过，只是被回滚了）；而这一种记的是<b>一件没发生的事</b>。
 * 正好撞上 {@code AuditLogService} 那句「<b>一条不真实的流水比没有流水更糟</b>」——
 * 用事务消息的本意是修掉那个缺陷，写成这样反而把它升级了。
 *
 * <p><b>所以本类采用的写法是：</b>
 * <ol>
 *   <li>半消息先落在 Broker 上、<b>状态未知</b>（此刻它对消费者还不可见）——
 *       ★ <b>这一步本类自己发</b>，不再经 {@code sendMessageInTransaction}，理由见下；</li>
 *   <li>真正的 COMMIT 由 {@code TransactionSynchronization.afterCommit()} 驱动 ——
 *       「DB 真的提交了」那一刻才让半消息可见；回滚则走
 *       {@code afterCompletion(STATUS_ROLLED_BACK)} 发 ROLLBACK；</li>
 *   <li>{@link #checkLocalTransaction} <b>必须真查库</b> —— 生产端宕机后 Broker 来问
 *       「那笔本地事务到底成没成」，这是唯一的裁决依据。</li>
 * </ol>
 * ★★ <b>这三条缺一条，事务消息就是白加的</b>：少了 ②，坏的那个方向就漏了；
 * 少了 ③（比如写成「一律返回 COMMIT」），进程一崩半消息就永远悬着，审核流水静默不出现。
 *
 * <p>★★★ <b>而「先 ①、过一会儿再 ②」这件事，{@code sendMessageInTransaction} 根本表达不了</b>
 * —— 上面那个顺序就是它的形状：发半消息与给出裁决被压在<b>同一次调用</b>里，
 * 裁决只可能发生在「刚发完」那一刻。在它上面硬要实现，就只剩两条路：
 * 在 {@code executeLocalTransaction} 里提前返回（就是上面那个洞），
 * 或者放弃 ②、只留 ③（全交给 Broker 的回查，代价是每条流水都要等一个回查周期）。
 * <b>本类走第三条路：自己发半消息，把裁决权留在自己手里。</b>
 * 为什么非得自己发 —— 一句话版：库返回的 {@code TransactionSendResult}
 * <b>把 {@code offsetMsgId} 丢了</b>，于是它自己的 {@code endTransaction}
 * 只在「刚发完」那一刻可用（库内部那次调用之所以成功，只是因为当时它手里还握着
 * 原始的 {@code SendResult}）。完整论证见下面 API 细节第 1 条。
 * <p>★ 代价要如实记一笔：{@link #executeLocalTransaction} 从此<b>永远不会被调用</b>，
 * 它留着只是因为 {@code RocketMQLocalTransactionListener} 要求实现这个接口 ——
 * 它的语义（返回 {@code UNKNOWN}）仍然是「本类的裁决一律不在这里做」，与整条设计一致。
 *
 * <h3>★★ 失败语义：两个方向刻意不对称</h3>
 *
 * <table border="1">
 *   <caption>本类的失败处置</caption>
 *   <tr><th>哪一步失败</th><th>怎么办</th><th>为什么</th></tr>
 *   <tr>
 *     <td>半消息<b>没发出去</b>（抛异常）</td>
 *     <td><b>让它抛</b> → {@code audit()} 回滚 → 管理员看到 500</td>
 *     <td>和 M2 的 Feign 时代<b>语义完全一致</b>（那次调用也在事务里）。
 *         「审核通过但没留下流水」是不可接受的 —— 流水是那条不可逆操作
 *         （授予 AUTHOR 角色）唯一的追责依据。<b>这里必须失败得响亮。</b></td>
 *   </tr>
 *   <tr>
 *     <td>{@code endTransaction} <b>调用失败</b>（Broker 抖动）</td>
 *     <td><b>吞掉 + WARN</b></td>
 *     <td>★★ 和上面正好相反，而且理由不是「宽容一点」：
 *         这时半消息已经躺在 Broker 上了，只是我们没能告诉它裁决结果 ——
 *         <b>Broker 过一会儿自己会来问 {@link #checkLocalTransaction}，
 *         那时它会真查库并给出同样的答案。这条链路是【自愈】的。</b>
 *         <p>★ 对照 {@code AfterCommit} 那条边：那边「提交成功、发送失败」是
 *         <b>不可自愈</b>的（没有回查，只能靠对账），这正是两条边处置不同的根据。
 *         <p>★★ <b>但正常运行时它【不该响】。</b>如果每次审核都看到这条 WARN，
 *         说明裁决句柄（{@code offsetMsgId}）在传递途中丢了 —— 查 API 细节第 1 条。
 *         它不会报错，只会让每条流水都晚一个回查周期（本项目实测约 14 秒）。</td>
 *   </tr>
 * </table>
 * <p>★ <b>吞异常还有个更硬的理由</b>：{@code endTransaction} 是从
 * {@code afterCommit} 里调的，而 Spring 的规矩是
 * 「{@code afterCommit} 里抛出去，调用方收到失败，<b>但事务已经提交了</b>」
 * —— 让异常逃出去，管理员会拿到一个 500 而审核其实成功了。
 * 详见 {@code AfterCommit} 类注释里那段 Spring 源码引文。
 *
 * <h3>★★★ 本类踩过的四个 API 细节（对着 2.3.6 / 5.3.1 的 jar 反编译核过，别照网上的写）</h3>
 *
 * <ol>
 *   <li><b>★★★ {@code TransactionSendResult} 把 {@code offsetMsgId} 丢了 ——
 *       所以 {@code sendMessageInTransaction} 的返回值【永远】不能用来做延迟裁决。</b>
 *       <p>这是本类唯一一处「照文档写就一定错」的地方，也是它最终自己发半消息的全部原因。
 *       链条是这样的（每一步都对着字节码核过）：
 *       <ol type="a">
 *         <li>{@code DefaultMQProducerImpl.sendMessageInTransaction(...)} 内部先
 *             {@code this.send(msg)} 拿到一个<b>原生 {@code SendResult}</code>，
 *             再调 {@code executeLocalTransaction}，然后
 *             <b>立刻</b>拿那个原生结果去调 {@code endTransaction(...)}；</li>
 *         <li>它最后交出来的 {@code TransactionSendResult} 只拷贝了
 *             {@code sendStatus / messageQueue / msgId / queueOffset / transactionId /
 *             localTransactionState} 六个字段 —— <b>{@code offsetMsgId} 不在其中</b>；</li>
 *         <li>而 {@code endTransaction} 拼 {@code EndTransactionRequestHeader} 时，
 *             {@code commitLogOffset} 是<b>先取 {@code sendResult.getOffsetMsgId()}、
 *             取不到才退回 {@code getMsgId()}</b>，再交给
 *             {@code MessageDecoder.decodeMessageId(...)} 去解的；</li>
 *         <li>于是拿 {@code TransactionSendResult} 去调，解的就是<b>客户端自己生成的
 *             UNIQ_KEY</b>（形如 {@code C0A801BD7D6428A418FC70DE9F1D0000}）——
 *             {@code decodeMessageId} 把它的第 8~16 个字符当成端口号，
 *             当场抛 {@code IllegalArgumentException}。
 *             <b>★ 实测报错长这样（本类第一版跑出来就是这个，一字不改）：</b>
 *             <pre>
 *  WARN  半消息裁决发送失败，等待 Broker 回查自愈 | applyId=27 state=COMMIT_MESSAGE
 *        error=port out of range:2103716004
 *             </pre>
 *             （{@code 7D6428A4} 按十六进制换十进制正好是 {@code 2103716004}。）</li>
 *       </ol>
 *       <p>★ <b>关键推论：这不是「我们写错了」，是这条路根本不存在。</b>
 *       库自己那次立刻裁决之所以成功，只是因为<b>它当时手里还握着原生 {@code SendResult}</b>；
 *       一旦它把 {@code TransactionSendResult} 交出来，那个句柄就再也拿不回来了。
 *       ★ 而且<b>不能</b>自己编一个「能解码」的 id 去糊弄：半消息在 Broker 上是靠
 *       {@code commitLogOffset} 定位的，编错了就是<b>裁决到别人的半消息上</b> ——
 *       本类最不能犯的就是这个错。</p>
 *       <p><b>→ 修法：库内部那三行自己写。</b>{@code TRAN_MSG} / {@code PGROUP} / {@code send}
 *       全部是公开 API（见 {@code publishAuthorApplyAudit} 里那几行），照抄一遍，
 *       原生 {@code SendResult} 就留在我们手里了，可以一路传到 {@code afterCommit} 再用。
 *       ★ 这条路不危险，因为 {@code endTransaction} 从 {@code msg} 只取
 *       {@code getTopic()}（见下面第 2 条）—— 我们要传的那个 {@code msg}
 *       和发送时那份本来就是<b>同一个对象</b>。</p>
 *       <p>★ <b>症状对照（以后排查用）</b>：审核照常成功、流水照常出现，
 *       但日志里每次审核都有一条 {@code 半消息裁决发送失败…port out of range}，
 *       且流水要等一个回查周期（{@code transactionCheckInterval}）才出现 ——
 *       那就是裁决句柄丢了。<b>回查是自愈的，所以它不报错，只是慢、且 WARN 一直在</b>，
 *       特别容易当成「MQ 就是这样」而放着不管。</p></li>
 *
 *   <li><b>{@code endTransaction} 的签名是 4 个参数、且第一个是【原生 Message】
 *       而不是 {@code MessageExt}：</b>
 *       <pre>
 *  DefaultMQProducerImpl.endTransaction(
 *          org.apache.rocketmq.common.message.Message msg,
 *          SendResult sendResult,
 *          LocalTransactionState state,
 *          Throwable localException)
 *       </pre>
 *       ★ 反编译看到的实现里，它只从 {@code sendResult} 取
 *       msgId / offsetMsgId / transactionId / messageQueue / queueOffset（拼
 *       {@code EndTransactionRequestHeader}），从 {@code msg} 只取
 *       {@code getTopic()}。<b>所以 {@code msg} 只需要「非 null 且 topic 正确」。</b>
 *       <p>★ 所以下面要传给 {@code endTransaction} 的那个 {@code msg}，就是
 *       <b>发送时用的那一份</b>（{@link RocketMQUtil#convertToRocketMessage} 转出来的）——
 *       全程同一个对象，不存在「副本和原件对不对得上」的问题。
 *       <p>★ 顺带说明两个被反编译看到的字段：生产者在<b>发送成功之后</b>还会往 msg 上加一个
 *       {@code __transactionId__} user property、并 {@code setTransactionId(uniqKey)}
 *       （都是发完才加的，Broker 从来没收到过它们）。本类不做这两步 ——
 *       因为 {@code endTransaction} 的 transactionId 取自 {@code sendResult}，
 *       这两个字段它一个都不读。</li>
 *
 *   <li><b>★★★ {@code convertToRocketMessage} 有两个重载，只有其中一个能处理 POJO ——
 *       而本类自己调的就是【不能】的那个。</b>
 *       <pre>
 *  A) convertToRocketMessage(MessageConverter, charset, destination, Message&lt;?&gt;)
 *  B) convertToRocketMessage(ObjectMapper,     charset, destination, Message)
 *       </pre>
 *       A 的实现是：载荷是 String / byte[] 就直接转字节，<b>否则调
 *       {@code messageConverter.fromMessage(msg, 载荷.getClass())} 并把它
 *       {@code (String)} 强转</b>。而默认的 {@code RocketMQMessageConverter} 是
 *       {@code [ByteArray, String, Jackson]} 三合一 —— 一个 POJO 载荷会落到
 *       Jackson 上，返回的是<b>那个 POJO 对象本身</b>，强转 String 当场
 *       {@code ClassCastException}，再被包成
 *       {@code RuntimeException("convert to RocketMQ message failed.")}。
 *       <b>★ 症状极具误导性，报错长这样：</b>
 *       <pre>
 *  java.lang.RuntimeException: convert to RocketMQ message failed.
 *  Caused by: java.lang.ClassCastException:
 *      class com.novel.common.mq.AuditRecordMessage cannot be cast to class java.lang.String
 *       </pre>
 *       —— 看着像「载荷类写错了」，其实是<b>载荷太 POJO 了</b>。
 *       <p><b>★★ 那为什么别人用 {@code syncSend(topic, pojo)} 就没事？</b>
 *       因为那条路会先过 {@code AbstractMessageSendingTemplate#doConvert}，
 *       由 converter 的 {@code toMessage()} 把 POJO <b>提前压成 byte[]</b>，
 *       到 A 里就走「载荷是 byte[]」那个分支了。
 *       <b>而本类（以及库的 {@code sendMessageInTransaction}）收的是【构造好的 Message】，
 *       不过 doConvert，所以那条保护不存在</b> —— 这是本类与 {@code syncSend}
 *       系列唯一的、也是致命的差别。
 *       <p><b>★ 修法（见下面 {@code converter.toMessage(...)} 那几行）：
 *       自己先过一次 converter，让 Spring Message 的载荷变成 byte[]</b>，再交给 A。
 *       B 虽然直接拿 ObjectMapper 也能写，但那样序列化就交给了<b>另一个 mapper</b>
 *       （{@link #OBJECT_MAPPER} 那份是给回查用的），两条路各写各的迟早对不上；
 *       走 converter 则与 {@code syncSend} 完全同源。
 *       ★ 消费者侧不受影响：它拿到的一直是 body 字节，由同一个 converter 读成 DTO。</li>
 *
 *   <li><b>两个枚举拼写不同，别混：</b>
 *       面向上层的 {@code RocketMQLocalTransactionState} 是
 *       {@code COMMIT / ROLLBACK / UNKNOWN}（有 N），
 *       而传给原生 API 的 {@code LocalTransactionState} 是
 *       {@code COMMIT_MESSAGE / ROLLBACK_MESSAGE / UNKNOW}（<b>没有 N</b>）。
 *       本类两个都用到，所以两个都 import 了 —— 看着像多写了一个，其实不是。</li>
 * </ol>
 *
 * <p>★ {@code @RocketMQTransactionListener} <b>是按模板 bean 名绑定的</b>
 * （{@code rocketMQTemplateBeanName()} 默认 {@code "rocketMQTemplate"}），
 * 不再有 {@code txProducerGroup} 这个属性 —— 照着老版本教程写那个属性是编译不过的。
 * 本服务只有一个模板 bean，所以这里不写任何属性。
 *
 * <p>★★ <b>本类不再调模板的事务 API 之后，这个注解仍然【必须】留着</b> —— 它现在只剩一件事，
 * 但这件事缺了整个方案就不成立：<b>把 {@link #checkLocalTransaction} 注册到生产者上</b>，
 * Broker 回查时才能问到这里（starter 的 {@code RocketMQTransactionConfiguration}
 * 在装配时做的就是这件事）。★ 顺带也是它决定了模板背后的生产者<b>必须是</b>
 * {@code TransactionMQProducer} —— 否则 starter 装配时会直接抛 {@code IllegalStateException}，
 * 而本类那处强制转型恰好可以依赖这个前提（也顺便验证了它）。
 *
 * @author MickeyChen
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQTransactionListener
public class AuthorApplyAuditProducer implements RocketMQLocalTransactionListener {

    /**
     * 回查时用来解回查消息的载荷。
     *
     * <p><b>★ 刻意是一个【裸的】 {@code ObjectMapper}，不是注入 {@code JacksonConfig} 那个 bean。</b>
     * 理由是对称性：写这条载荷的是 starter 自己的消息转换器，它内部用的就是
     * {@code new ObjectMapper()}（所以 {@code JacksonConfig} 的日期格式、命名策略
     * 对 MQ 载荷<b>一概不生效</b>）。用什么写的就用什么读，才不会出现
     * 「生产端正常、回查端解析不了」这种只在进程崩溃后才暴露的错位。
     *
     * <p>★ <b>顺带排除一个看着一定会踩、实测没有的坑</b>：{@link AuditAction}
     * 这类枚举带 {@code @JsonValue}（序列化成数字 code 而不是枚举名），
     * 很容易以为「反序列化会退回按 ordinal 匹配」—— <b>实测不会</b>：
     * Jackson 的反序列化同样认 {@code @JsonValue}
     * （拿非法值试一下，报错信息里列出的合法值是 {@code [1, 2, 3, 4, 5, 6]}，
     * 即 code 而不是 ordinal）。所以这里不需要任何自定义 creator。
     * ★ 而且这个坑就算真的存在，它的表现是<b>响亮</b>的
     * （{@code InvalidFormatException} → 消费失败 → 死信），不是静默错值 ——
     * 这一点也值得记一笔，免得后人反复担心它。
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RocketMQTemplate rocketMQTemplate;
    private final AuthorApplyMapper applyMapper;

    /**
     * 投递一条作者申请审核流水（<b>事务消息</b>）。
     *
     * <p>★ <b>必须在调用方的事务里调</b>（{@code AuthorApplyServiceImpl#audit} 是
     * {@code @Transactional} 的）：COMMIT 的时机由那个事务的结局决定。
     * 万一外层没有事务，本方法仍能工作（见
     * {@link #registerTransactionCallback} 的无事务分支），但那条路径下
     * 「审核」和「流水」之间就没有原子性可言了 —— 那时该做的是把
     * {@code @Transactional} 加回去，不是改这里。
     *
     * @param operatorId ★ 由调用方从 {@code UserContext} 取（{@code audit()} 第一行就是
     *                   {@code requireUserId()}）。<b>不接受请求体里的值</b> ——
     *                   MQ 这条链路上没有第二条不可伪造的通道了，
     *                   所以「谁都能往 novel-topic 发消息」就是这条不变量的全部边界。
     *                   完整的论证在 {@code AuditRecordMessage} 的类注释里，改这里之前先读它。
     * @param applyId    作者申请 id —— 同时是 {@code audit_log.target_id}
     * @param action     ★ 只可能是 {@code APPROVE} / {@code REJECT}（判据写在
     *                   {@code audit()} 里：提交申请不记流水，因为那不算一次状态流转）
     * @param remark     审核备注（驳回理由），可为 {@code null}
     */
    public void publishAuthorApplyAudit(Long operatorId, Long applyId,
                                        AuditAction action, String remark) {
        /*
         * ★ 不用在这里做「申请存不存在 / 状态对不对」的校验：调用方刚 selectById 查过，
         *   而且那次读写和本次发送在同一个事务里。多一次校验不会更安全，
         *   只会多一次【在事务里】的往返 —— 同 CommentAuditProducer 的判断。
         */
        AuditRecordMessage message = AuditRecordMessage.of(
                operatorId, AuditTargetType.AUTHOR_APPLY, applyId, action, remark);

        String destination = MqConstant.destinationOf(MqConstant.TAG_AUDIT_RECORD);

        /*
         * ★★★ 载荷【必须先过一次 converter】变成 byte[]，不能把 POJO 直接塞进 Message。
         *
         *   理由（完整版在类注释「三个 API 细节」第 2 条）：sendMessageInTransaction
         *   收的是构造好的 Message、不过 doConvert，于是模板内部会拿一个 POJO 载荷
         *   去走 RocketMQUtil.convertToRocketMessage 的 MessageConverter 重载 ——
         *   那个重载对非 String/byte[] 的载荷会 (String) 强转 Jackson 的返回值，
         *   当场 ClassCastException（报错文案极具误导性，见类注释）。
         *
         *   ★ 这里走 converter.toMessage(...) 而不是 new ObjectMapper().writeValueAsString：
         *     这样载荷的序列化方式与 syncSend 系列【完全同源】（同一个
         *     RocketMQMessageConverter、同一个 ObjectMapper），不会出现
         *     「同步发的和事务发的 JSON 长得不一样」这种只有回查时才暴露的错位。
         */
        Message<?> springMessage = rocketMQTemplate.getMessageConverter()
                .toMessage(message, new MessageHeaders(Collections.emptyMap()));

        /*
         * ★★ 再手工转出一份原生 Message。它身兼两职，而且两份职责都必须在【同一个对象】上：
         *     ① 发送对象 —— 下面 producer.send(...) 发出去的就是它；
         *     ② 裁决句柄 —— 事务提交后 endTransaction(...) 要把它一起传回去。
         *   ★ 为什么不能「发完之后把返回值留下来当句柄」：见类注释 API 细节第 1 条
         *     （模板返回的 TransactionSendResult 丢了 offsetMsgId，那个句柄用完即废）。
         *   ★ 入参就是库自己用的那份（同一个 converter、同一个 charset、
         *     同一个 destination、同一个 springMessage）。
         */
        org.apache.rocketmq.common.message.Message rocketMessage =
                RocketMQUtil.convertToRocketMessage(rocketMQTemplate.getMessageConverter(),
                        rocketMQTemplate.getCharset(), destination, springMessage);

        /*
         * ★★ 强制转型是安全的，而且这里【必须】是 TransactionMQProducer ——
         *   本类挂着 @RocketMQTransactionListener，starter 在装配时就是往
         *   TransactionMQProducer 上 setTransactionListener 的（它自己也是这么转的）。
         *   ★ 转不动说明回查那条通道没了（Broker 来问时没人能答）——
         *     那就该在这里响亮地失败，不要发出一个没人能裁决的半消息。
         */
        TransactionMQProducer producer = (TransactionMQProducer) rocketMQTemplate.getProducer();

        /*
         * ★★★ 下面这两行 + 一次 send，就是「一条事务消息」的全部 —— 与库内部
         *   DefaultMQProducerImpl.sendMessageInTransaction 的前两行【逐字相同】
         *   （对着 5.3.1 的字节码核过，偏移 54~83）。
         *
         *   ① TRAN_MSG=true
         *      → sendKernelImpl 读到它才把 sysFlag 的第 4 位置起来，Broker 收到之后
         *        才把它当【半消息】存（对消费者不可见）。
         *      ★★ 漏了这一行，它会变成一条【普通消息】：立刻可见、审核回滚也收不回来，
         *         而发送端一切正常、没有任何报错。这是本方法最危险的一行。
         *   ② PGROUP=生产者组
         *      → Broker 回查时靠它找回「该问哪个生产者实例」。
         *      ★ 这也正是 MqConstant.PRODUCER_GROUP_AUTH_TX 必须独立于普通生产者组的原因。
         */
        MessageAccessor.putProperty(rocketMessage,
                MessageConst.PROPERTY_TRANSACTION_PREPARED, "true");
        MessageAccessor.putProperty(rocketMessage,
                MessageConst.PROPERTY_PRODUCER_GROUP, producer.getProducerGroup());

        SendResult sendResult;
        try {
            /*
             * ★ 就是一次普通同步发送 —— 没有 TransactionSendResult、没有本地事务回调。
             *   与库唯一的区别是：我们【不】当场裁决，而是把这份原生 SendResult 收好，
             *   等外层事务提交之后（registerTransactionCallback）再拿它去 endTransaction。
             *   ★ 它必须原封不动活到那一刻：里面的 offsetMsgId 是 Broker 定位这条半消息的
             *     唯一凭据 —— 这正是库那条路走不通的原因（类注释 API 细节第 1 条）。
             */
            sendResult = producer.send(rocketMessage);
        } catch (Exception e) {
            /*
             * ★ 抛出去（见类注释的失败语义表）：audit() 会回滚，管理员拿到 500 并重试。
             *   半消息如果其实已经落到了 Broker 上，回查时 checkLocalTransaction
             *   会查到「申请还是 PENDING」→ ROLLBACK，不会留下孤儿流水。
             *
             * ★ MQProducer#send 抛的是四个【受检】异常，所以必须在这里包一层：
             *   直接往外抛会污染 audit() 的签名，而它上面的 @Transactional
             *   默认【只认 RuntimeException 才回滚】—— 受检异常穿过去是「报错了但改动照样提交」，
             *   那才是真的灾难。
             */
            throw new IllegalStateException(
                    "半消息发送失败，本次审核回滚以免丢失流水 | applyId=" + applyId, e);
        }

        /*
         * ★★ 半消息在 Broker 那边其实【没存住】—— 发送状态不是 SEND_OK 时，
         *   这条消息永久不可见，也不可能被回查捞回来（Broker 手里根本没有它）。
         *   而我们的 DB 改动马上就要提交了 —— 那就是「审核成功、流水永久丢失」，
         *   没有任何机制能补救。
         *
         *   所以这里主动失败：把异常抛进 audit()，让整件事一起回滚。
         *   管理员看到报错、再点一次，比一条静默消失的流水好得多。
         *
         * ★ 开发环境的 broker 是 ASYNC_FLUSH + ASYNC_MASTER，实际永远是 SEND_OK，
         *   这一段走不到；它保的是生产按 SYNC_FLUSH 部署时的那三个超时状态。
         * ★ 判据从 localTransactionState 换成了 sendStatus：那个字段只在
         *   「发送后立刻裁决」的写法里才有意义，而本类不再走那条路 ——
         *   此刻真正有意义的只有一件事实：Broker 到底收下了没有。
         */
        if (sendResult.getSendStatus() != SendStatus.SEND_OK) {
            throw new IllegalStateException(
                    "半消息未能可靠写入 Broker（sendStatus=" + sendResult.getSendStatus()
                            + "），本次审核回滚以免丢失流水 | applyId=" + applyId);
        }

        registerTransactionCallback(rocketMessage, sendResult, applyId);
    }

    /**
     * 把「这次本地事务的结局」翻译成对半消息的裁决 —— <b>{@link #checkLocalTransaction}
     * 之外的另一半，也是本类最容易被写反的地方。</b>
     *
     * <p>COMMIT 挂在 {@code afterCommit} 上、ROLLBACK 挂在
     * {@code afterCompletion(STATUS_ROLLED_BACK)} 上，<b>两个回调缺一个都会静默出错</b>：
     * 少了 COMMIT，审核成功而流水永远不出现（要等回查，也就是十几秒起步）；
     * 少了 ROLLBACK，回滚之后半消息会一直悬到回查（还好回查会判对，只是慢）。
     *
     * <p>★ 参数里的 {@code sendResult} 是<b>原生 {@code SendResult}</b>（不是
     * {@code TransactionSendResult}）—— 它是 Broker 定位这条半消息的唯一凭据
     * （{@code offsetMsgId}），必须一路带到 {@link #endTransactionQuietly}。
     * 换成模板交出来的那个类型，这里每次都会失败（类注释 API 细节第 1 条）。
     */
    private void registerTransactionCallback(org.apache.rocketmq.common.message.Message rocketMessage,
                                             SendResult sendResult, Long applyId) {
        /*
         * ★ 无事务分支：audit() 上的 @Transactional 被去掉时才会走到这里。
         *   这不是「兜底」，是【正常分支】—— 同 AfterCommit 那段论证。
         *   此时前面那些 DB 改动早已自动提交，所以直接 COMMIT 是对的。
         *   ★ 反过来写（有事务就直接 COMMIT）就会制造类注释开头讲的那个洞。
         */
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn("审核流水在【无事务】环境下投递，已直接 COMMIT —— "
                    + "AuthorApplyServiceImpl#audit 的 @Transactional 是不是被去掉了？ applyId={}", applyId);
            endTransactionQuietly(rocketMessage, sendResult,
                    LocalTransactionState.COMMIT_MESSAGE, applyId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                endTransactionQuietly(rocketMessage, sendResult,
                        LocalTransactionState.COMMIT_MESSAGE, applyId);
            }

            @Override
            public void afterCompletion(int status) {
                /*
                 * ★ 只处理 ROLLED_BACK。提交成功时 afterCommit 已经发过 COMMIT 了，
                 *   这里再发一次是多余的（而且 afterCompletion 在 afterCommit 之后必然触发，
                 *   两个分支不加区分就会 COMMIT 完立刻 ROLLBACK，把刚放出来的消息又收回去）。
                 */
                if (status == STATUS_ROLLED_BACK) {
                    log.warn("审核回滚，半消息一并回滚（撤销一条不会发生的流水） | applyId={}", applyId);
                    endTransactionQuietly(rocketMessage, sendResult,
                            LocalTransactionState.ROLLBACK_MESSAGE, applyId);
                }
            }
        });
    }

    /**
     * 通知 Broker 这条半消息的最终裁决。<b>永不抛异常</b> —— 见类注释的失败语义表。
     */
    private void endTransactionQuietly(org.apache.rocketmq.common.message.Message rocketMessage,
                                       SendResult sendResult,
                                       LocalTransactionState state, Long applyId) {
        try {
            /*
             * ★ 又是这个强制转型，但这次要的不是 getProducerGroup()，而是
             *   DefaultMQProducerImpl.endTransaction(...) —— 它没有暴露在 MQProducer 上，
             *   只能从生产者的实现类里拿。转型的安全性同 publishAuthorApplyAudit 里的说明。
             */
            TransactionMQProducer producer = (TransactionMQProducer) rocketMQTemplate.getProducer();
            producer.getDefaultMQProducerImpl()
                    .endTransaction(rocketMessage, sendResult, state, null);

            log.debug("半消息裁决已发出 | applyId={} state={} msgId={}",
                    applyId, state, sendResult.getMsgId());
        } catch (Exception e) {
            /*
             * ★★ 吞掉是对的，不是偷懒：半消息还在 Broker 上，它过一会儿会自己回来问
             *   checkLocalTransaction，那时会真查库并给出同样的答案。
             *   这条链路是自愈的 —— 和 AfterCommit 那条边的「提交了但没发出去」
             *   正相反，那个洞没有东西能补。
             *
             *   ★ 但【每次审核都响】不是正常现象：那说明裁决句柄丢了
             *     （offsetMsgId → 退回 msgId → decodeMessageId 解不了），
             *     症状与修法见类注释 API 细节第 1 条。
             */
            /*
             * ★ describe + 整只 e：这条链路的失败【低概率但高信息量】
             *   （它意味着裁决句柄丢了，要靠回查自愈），所以堆栈值得留下。
             * ★ 用 MqErrors.describe 而不是 e.getMessage()：RocketMQ 的
             *   「port out of range」/「unknown reason」都是包装异常的外壳，
             *   真因在 cause 里 —— 见 MqErrors 类注释（这条是真金白银踩出来的）。
             */
            log.warn("半消息裁决发送失败，等待 Broker 回查自愈 | applyId={} state={} error={}",
                    applyId, state, MqErrors.describe(e), e);
        }
    }

    /**
     * <b>本地事务的「执行」—— 本类唯一一个不做任何事的回调，而这是刻意的。</b>
     *
     * <p>★★ <b>本类现在【从不调用】它</b>（{@code sendMessageInTransaction} 才会调，
     * 而我们自己发半消息）。它留着只是因为 {@code RocketMQLocalTransactionListener}
     * 要求实现这个方法 —— 所以<b>别指望在这里打日志验证什么，它永远不会被触发</b>。
     * 语义仍然是「裁决一律不在这里做」，与整条设计一致。
     *
     * <p>它只返回 {@code UNKNOWN}，裁决全部留给事务提交后的回调和 Broker 的回查。
     * 为什么<b>不能</b>在这里返回 COMMIT（万一以后有人改回模板那条路），见类注释的判断 C′
     * （一句话：这个方法跑在外层事务提交<b>之前</b>）。
     *
     * <p>★ 返回 {@code UNKNOWN} 的代价是「每次发送都至少多一次网络往返吗」——
     * <b>不是</b>。正常情况下 {@code afterCommit} 里那次 {@code endTransaction}
     * 立刻就把半消息放出来了；只有在<b>进程没能发出那次调用</b>时，
     * 才轮到 Broker 按 {@code transactionCheckInterval}（默认 60 秒）来问。
     */
    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        return RocketMQLocalTransactionState.UNKNOWN;
    }

    /**
     * <b>Broker 回查：这是事务消息唯一的裁决依据，所以它【必须真查库】。</b>
     *
     * <p>什么时候会被问到：<b>生产端没能发出上面那次 {@code endTransaction}</b> ——
     * 进程崩了、被 kill 了、网络断了。Broker 手里只有一条状态未知的半消息，
     * 只能来问生产者「那笔本地事务到底成没成」。
     *
     * <p>★★ <b>写成「一律返回 COMMIT」会怎样</b>：回滚掉的审核也会被放出去 →
     * content 里凭空多出一条「审批通过」的流水，而申请还是待审核状态。
     * 这正是本类存在的意义被反转过来的样子。<b>不查库的回查 = 半消息永远悬着 /
     * 或者一条不会发生的流水</b>，两种都是静默的。
     *
     * <h3>判据：{@code author_apply} 的终态 + 操作人</h3>
     *
     * <p>本地事务（{@code audit()}）对这张表做的事是：
     * {@code status} 改成 {@code APPROVE/REJECT} 对应的终态、{@code audit_user_id}
     * 写成操作人。于是「成没成」可以直接读出来：
     * <pre>
     *   行没了 / 还是 PENDING      → 本地事务没提交（或没跑到）→ ROLLBACK
     *   终态 == 本消息说的事 且 audit_user_id == operatorId → COMMIT
     *   终态是别的值 / 操作人是别人 → 是别人的另一笔事务 → ROLLBACK
     * </pre>
     *
     * <p>★ <b>为什么「终态 + 操作人」就够，不用再比时间戳</b>：
     * {@code author_apply} 的终态是<b>冻结</b>的（{@code audit()} 明确禁止
     * 终态之间互改，要改判只能让用户重新提交一份），所以「谁把它变成终态的」
     * 是一个一旦定下就不再变的事实。反过来，比时间戳要拿
     * {@code audit_time}（{@code LocalDateTime.now()}）去对载荷里的
     * {@code at}（{@code System.currentTimeMillis()}），两个时钟之间那点抖动
     * 只会带来误判 —— 判据要能用，不能看起来严谨。
     *
     * <p>★ <b>只有 DB 异常才返回 {@code UNKNOWN}</b>（「我现在答不了，你待会儿再问」）：
     * 这时候给 COMMIT 或 ROLLBACK 都是在没有依据的情况下拍板。
     */
    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        AuditRecordMessage record;
        try {
            /*
             * ★ 回查消息的载荷是【原始 body 的 byte[]】，不是反序列化好的对象：
             *   starter 的 RocketMQUtil.convertToSpringMessage 就是这么装的
             *   （头里另带 TOPIC/TAGS/MESSAGE_ID 等）。所以这里自己解一次。
             */
            Object payload = msg.getPayload();
            if (!(payload instanceof byte[] body)) {
                log.error("回查载荷不是 byte[]，无法裁决，按回滚处理 | payloadType={}",
                        payload == null ? "null" : payload.getClass().getName());
                return RocketMQLocalTransactionState.ROLLBACK;
            }
            record = OBJECT_MAPPER.readValue(body, AuditRecordMessage.class);
        } catch (Exception e) {
            /*
             * ★ 解析不了 = 我们无法为这条消息背书。让它悬着没有意义 ——
             *   悬着的结局同样是超过 transactionCheckMax 后被丢弃，
             *   但期间每一次回查都会再打一行日志。
             * ★ 打了 ERROR 是对的：载荷形状对不上，多半是有人改了
             *   AuditRecordMessage 又恰好有半消息在飞（版本漂移），
             *   那是一件需要人看一眼的事。
             */
            log.error("回查载荷解析失败，按回滚处理 | error={}", MqErrors.describe(e), e);
            return RocketMQLocalTransactionState.ROLLBACK;
        }

        if (record == null || record.targetId() == null || record.operatorId() == null
                || record.targetType() != AuditTargetType.AUTHOR_APPLY) {
            /*
             * ★ 这条分支今天走不到（本事务组只有本类一个生产者），写它是为了把
             *   「回查的判据必须来自载荷、而且必须是本生产者能认领的那种载荷」
             *   这件事留成代码而不是留成默契：下一个人若把另一种流水挂到同一个
             *   事务组上，他会看到这行 ERROR，而不是一堆错误的 ROLLBACK。
             */
            log.error("回查消息不是可裁决的作者申请流水，按回滚处理 | record={}", record);
            return RocketMQLocalTransactionState.ROLLBACK;
        }

        AuditStatus expected = expectedStatusOf(record.action());
        if (expected == null) {
            log.error("回查消息的动作不是 APPROVE/REJECT，无法裁决，按回滚处理 | action={}",
                    record.action());
            return RocketMQLocalTransactionState.ROLLBACK;
        }

        AuthorApply apply;
        try {
            // ★ @TableLogic 生效：逻辑删除掉的申请查出来是 null，正好走下面的「没提交」分支
            apply = applyMapper.selectById(record.targetId());
        } catch (Exception e) {
            log.warn("回查时查库失败，答复 UNKNOWN 让 Broker 稍后再问 | applyId={} error={}",
                    record.targetId(), MqErrors.describe(e), e);
            return RocketMQLocalTransactionState.UNKNOWN;
        }

        if (apply == null) {
            log.warn("回查：申请不存在（或已删除），本地事务显然没提交 | applyId={}", record.targetId());
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        if (apply.getStatus() != expected) {
            log.warn("回查：申请状态与消息不符，本地事务没提交 | applyId={} 消息说={} 库里={}",
                    record.targetId(), expected, apply.getStatus());
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        if (!record.operatorId().equals(apply.getAuditUserId())) {
            log.warn("回查：终态是【别人】改的，本事务没提交 | applyId={} 消息operatorId={} 库里auditUserId={}",
                    record.targetId(), record.operatorId(), apply.getAuditUserId());
            return RocketMQLocalTransactionState.ROLLBACK;
        }

        log.info("回查裁决 COMMIT | applyId={} action={} operatorId={}",
                record.targetId(), record.action(), record.operatorId());
        return RocketMQLocalTransactionState.COMMIT;
    }

    /**
     * 动作 → 它应该造成的终态。{@code null} 表示这个动作不归本生产者管。
     *
     * <p>★ 只有两个分支。{@code AuditAction} 里另外四个（提交 / 下架 / 重新上架 / 退回草稿）
     * 不会出现在这条链路上 —— 前一个归作品与章节，后三个归作品，作者申请只有通过和驳回。
     */
    private static AuditStatus expectedStatusOf(AuditAction action) {
        if (action == AuditAction.APPROVE) {
            return AuditStatus.APPROVED;
        }
        if (action == AuditAction.REJECT) {
            return AuditStatus.REJECTED;
        }
        return null;
    }
}
