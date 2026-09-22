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
 *       {@code Retryer.NEVER_RETRY}</b>（它们都有写接口），
 *       content 那份<b>没有</b>（它的四个方法都是读）。合并就要为
 *       「两份语义不同」选一个赢家 —— <b>那是行为变更</b>，
 *       与 M2「业务无回归」的目标直接冲突。</li>
 * </ul>
 * <p><b>★ 记成 M5 治理项</b>（合并成单源，用
 * {@code @FeignClient(configuration = ...)} 区分读/写客户端），M2 不动。
 * <p>★ 真正需要「只有一份」的逻辑（怎么编码、头叫什么）已经在
 * {@link IdentityHeaders} 里了；剩下的这五行是<b>配置</b>，不是逻辑。
 *
 * <h3>★★ 为什么必须带上身份</h3>
 *
 * <p>Feign 调用是一个<b>全新的 HTTP 请求</b>，{@code UserContext}
 * 不会自动跟过去。而本服务有<b>一个接口</b>需要它：
 * {@code POST /internal/comment-audit-logs}（记评论审核流水）——
 * 对端要从 {@code UserContext} 取操作人，而那个值只能由这里写进去。
 * <p>★ <b>漏了它的症状不是报错，是「流水里操作人是空的」</b>
 * —— 评论审核照常成功，只有审计记录缺了最关键的那一列。
 * 对端为此专门写了一条 error 日志（见 {@code InternalAuditLogController}），
 * 就是为了让这件事不要静默。
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
     * 那是好事（网络抖一下自动恢复）；对本服务的<b>写</b>接口是灾难：
     * <ul>
     *   <li>{@code POST /internal/comment-audit-logs} —— <b>重试一次就是两条
     *       一模一样的审核流水</b>，而流水表是「只追加、不可改、不可删」的设计，
     *       那两条<b>删不掉</b>，只能人工写 SQL 清理，而且会永久污染
     *       「谁在什么时候审了什么」的追责链。</li>
     *   <li>{@code PUT /novels/{id}/collect-count} / {@code .../score} ——
     *       这两个写的是<b>绝对值</b>，重试幂等，看起来没事。
     *       ★ 但那是运气好，不是可以依赖的性质：哪天有人把对端改成增量写法，
     *       这个 {@code NEVER_RETRY} 就是唯一的护栏。</li>
     * </ul>
     *
     * <p>★ <b>更隐蔽的一种情况</b>：请求其实<b>已经到达并对端已经写入成功</b>，
     * 只是响应在回来的路上丢了（连接被重置、超时）。客户端认为是失败、
     * 重试一次 —— 对端就真的写了第二条。这种「成功但客户端不知道」
     * 的场景用重试是修不好的，只能靠<b>幂等键</b>（M5 治理项）。
     * {@code NEVER_RETRY} 把它压到「最多一次」，是 M2 能做的最小正确动作。
     *
     * <p>★ 注意这个 bean 是全局的（对本服务所有 Feign 客户端生效），
     * 包括那两个只读的（{@code novelBriefs} / {@code chapterBriefs}）——
     * <b>它们因此失去了自动重试</b>。这是刻意选的：读接口丢一次重试
     * 只是「这次请求失败，用户重试一下」，而写接口重复一次是<b>脏数据</b>。
     * <p>将来若真要给读客户端单独开重试，应该用
     * {@code @FeignClient(configuration = ...)} 单独配，而不是把这个删掉。
     */
    @Bean
    public Retryer feignRetryer() {
        return Retryer.NEVER_RETRY;
    }
}
