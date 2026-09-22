package com.novel.interaction.client;

import com.novel.common.security.IdentityHeaders;
import com.novel.common.security.UserContext;
import feign.RequestInterceptor;
import feign.Retryer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>novel-interaction 所有 Feign 出站请求的统一配置（M2）。</b>
 *
 * <p><b>★★ 这是仓库里第三份 {@code FeignIdentityConfig}，而它是【照抄】的。</b>
 * 前两份分别在 {@code novel-auth}（M1）和 {@code novel-content}（跟着 content
 * 从 novel-backend 搬过来的）—— 三份内容几乎逐字相同，只有 javadoc 里的
 * 服务名不一样。这是<b>有意接受的重复</b>，不是漏了合并：
 * <ul>
 *   <li>{@code novel-common} 对 Spring 的依赖全部是 {@code optional}、
 *       一个字节都不传给下游；要把它放进去就得让 common 依赖 Feign 的 API
 *       （{@code RequestInterceptor}），网关会顺带多一个用不上的 Feign。</li>
 *   <li>★ 更硬的一条：三份里<b>只有 auth 和 interaction 这两份带
 *       {@code Retryer.NEVER_RETRY}</b>（M2 时它们都有写接口），
 *       content 那份<b>没有</b>（它的方法都是读）。合并就要为
 *       「两份语义不同」选一个赢家 —— <b>那是行为变更</b>，
 *       与 M2「业务无回归」的目标直接冲突。
 *       ★ M3 之后 interaction 也变成只读了（见下面那段），
 *       差异<b>没有消失、只是换了理由</b>，所以合并的障碍一点没少。</li>
 * </ul>
 * <p><b>★ 记成 M5 治理项</b>（合并成单源，用
 * {@code @FeignClient(configuration = ...)} 区分读/写客户端），M2 不动。
 * <p>★ 真正需要「只有一份」的逻辑（怎么编码、头叫什么）已经在
 * {@link IdentityHeaders} 里了；剩下的这五行是<b>配置</b>，不是逻辑。
 *
 * <h3>★★ 为什么必须带上身份 —— ★ 以及 M3 之后这条理由【已经没有消费者】了</h3>
 *
 * <p>Feign 调用是一个<b>全新的 HTTP 请求</b>，{@code UserContext}
 * 不会自动跟过去。改造前（M2）本服务有<b>一个接口</b>需要它：
 * {@code POST /internal/comment-audit-logs}（记评论审核流水）——
 * 对端要从 {@code UserContext} 取操作人，而那个值只能由这里写进去。
 * 漏了它的症状不是报错，是<b>「流水里操作人是空的」</b>。
 *
 * <p><b>★★ M3 把那个接口整条搬去了 MQ</b>（{@code CommentAuditProducer} →
 * {@code AUDIT_RECORD} 消息 → content 的 {@code AuditRecordConsumer}），
 * 而本服务对 content 剩下的 5 个调用<b>全是读，对端一个都不读
 * {@code UserContext}</b>。所以严格来说：
 * <pre>
 *   IdentityHeaders.write(headers, UserContext.get()) 这一行
 *   在 interaction → content 这条边上【目前没有消费者】。
 * </pre>
 *
 * <p><b>★ 但仍然保留，理由不是「说不定哪天要用」这种话：</b>
 * <ul>
 *   <li><b>同一个拦截器里还要写内部令牌</b>（{@code writeInternalToken}）——
 *       那个是硬需求：content 的 {@code HeaderAuthenticationFilter} 对
 *       {@code /internal/**} 有闸门，不带令牌的直接 403。
 *       为了删上面那一行而拆拦截器，是把一个必需的东西改坏去清理一个无害的东西。</li>
 *   <li>★ <b>而「身份透传」这件事本身是有成本倒挂的</b>：多写两个头是几个字节，
 *       漏了却是「某天新加一个写接口，操作人静默变成空」——
 *       那种 bug 的排查路径会一路跑到数据库上去。
 *       <b>在「现在就没用」和「将来漏掉会静默出错」之间，留着的期望损失更低。</b></li>
 * </ul>
 * <p>★ 这条要如实记下来，因为它是 M3 留下的<b>一处「机制还在、消费者没了」</b>——
 * 和 {@code comments} 里那句「结论还对、前提已经变了」是同一类东西，
 * 区别是这次前提变了而结论<b>故意不改</b>。
 *
 * @author MickeyChen
 */
@Slf4j
@Configuration
public class FeignIdentityConfig {

    /** ★ 不给默认值 —— 没配就启动失败。理由同 {@code HeaderAuthenticationFilter} */
    @Value("${novel.internal.token}")
    private String internalToken;

    @Bean
    public RequestInterceptor identityRelayInterceptor() {
        return template -> {
            Map<String, String> headers = new LinkedHashMap<>();
            IdentityHeaders.write(headers, UserContext.get());
            IdentityHeaders.writeInternalToken(headers, internalToken);
            headers.forEach(template::header);

            log.debug("Feign 出站携带身份 | url={} | {}", template.url(),
                    IdentityHeaders.describe(headers::get));
        };
    }

    /**
     * <b>★★ 关掉 Feign 的重试 —— 这个 bean 是必需的，不是优化。</b>
     *
     * <p>Feign 默认带一个 {@code Retryer.Default}（最多 5 次尝试）。对<b>读</b>接口
     * 那是好事（网络抖一下自动恢复）；对本服务的<b>写</b>接口是灾难。
     * 改造前（M2）本服务对 content 有<b>三个写接口</b>，最坏的是
     * {@code POST /internal/comment-audit-logs} —— <b>重试一次就是两条
     * 一模一样的审核流水</b>，而流水表是「只追加、不可改、不可删」的设计，
     * 那两条<b>删不掉</b>，只能人工写 SQL 清理，而且会永久污染
     * 「谁在什么时候审了什么」的追责链。
     * <p>★ <b>更隐蔽的一种情况</b>：请求其实<b>已经到达并对端已经写入成功</b>，
     * 只是响应在回来的路上丢了（连接被重置、超时）。客户端认为是失败、
     * 重试一次 —— 对端就真的写了第二条。这种「成功但客户端不知道」
     * 的场景用重试是修不好的，只能靠<b>幂等键</b>。
     * {@code NEVER_RETRY} 把它压到「最多一次」，是 M2 能做的最小正确动作。
     *
     * <h3>★★ M3 之后这个 bean 对本服务【只剩代价】—— 而且我们仍然不动它</h3>
     *
     * <p>M3 把那三个写接口全部删了（换成两条消息），本服务对 content
     * <b>剩下的 5 个调用全是读</b>。于是这个 bean 的意义从
     * 「保护写接口」翻转成了「让 5 个读接口失去自动重试」——
     * <b>它现在是一个纯粹的负项。</b>
     *
     * <p><b>★ 但不删，理由是纪律而不是判断：</b>
     * <ul>
     *   <li>删掉它是<b>行为变更</b>（读接口会突然开始重试），
     *       而 M3 的自我约束是「只换接缝类型，不顺手改行为」——
     *       一个本期的验收目标里根本没提的行为变化，会变成
     *       「M3 之后偶尔出现重复的 GET」这类没人归因的怪事；</li>
     *   <li>它和「三份 {@code FeignIdentityConfig} 副本」是同一个治理项
     *       （M5：合并成单源，用 {@code @FeignClient(configuration = ...)}
     *       区分读/写客户端）。<b>那时候一并处理读接口的重试语义，
     *       比现在在这里拆一半更省事。</b></li>
     * </ul>
     * <p>★ 顺带把那条原本写在这里、现在<b>依然成立</b>的规矩留下：
     * 给读客户端单独开重试要用 {@code @FeignClient(configuration = ...)} 配，
     * 而不是把这个 bean 删掉 —— 因为下一个写接口出现时，
     * 你不会有第二次机会想起这里曾经有过一道护栏。
     */
    @Bean
    public Retryer feignRetryer() {
        return Retryer.NEVER_RETRY;
    }
}
