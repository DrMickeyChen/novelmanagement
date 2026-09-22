package com.novel.auth.client;

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
 * <b>novel-auth 所有 Feign 出站请求的统一配置（M1）。</b>
 *
 * <p>和 novel-content / novel-interaction 的 {@code FeignIdentityConfig} 是<b>同一份东西</b>
 * （带密钥 + 带身份）。为什么没有抽到 novel-common 里去共用：
 * 那需要让共用的库依赖 Feign 的 API（{@code RequestInterceptor}），
 * 而 common 现在对 Spring 的依赖全部是 {@code optional}、一个字节都不传给下游
 * ——把 Feign 加进去会让网关也顺带多一个用不上的 Feign。
 * <p>真正需要「只有一份」的逻辑（怎么编码、头叫什么）已经在
 * {@link IdentityHeaders} 里了；剩下这五行是<b>配置</b>，不是逻辑，
 * 各服务自己声明自己的出站行为更符合「服务边界单向」那条规矩。
 *
 * <p><b>★★ 这个类【额外】做了一件 novel-content 那边不需要做的事：
 * 把重试关掉。</b>理由见下面 {@link #feignRetryer()}。
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
     * 那是好事（网络抖一下自动恢复）；对本服务的客户端<b>是灾难</b>：
     * 它唯一的调用是 {@code POST /internal/author-apply-audit-logs}，
     * 一次写操作。<b>重试一次就是两条一模一样的审核流水。</b>
     *
     * <p>而流水表是「只追加、不可改、不可删」的设计（见
     * {@code AuditLogServiceImpl} 的类注释）—— 那两条重复记录<b>删不掉</b>，
     * 只能人工写 SQL 清理，而且它会永久污染「谁在什么时候审了什么」的追责链。
     *
     * <p>★ <b>更隐蔽的一种情况</b>：请求其实<b>已经到达并对端已经写入成功</b>，
     * 只是响应在回来的路上丢了（连接被重置、超时）。客户端认为是失败、
     * 重试一次 —— 对端就真的写了第二条。这种「成功但客户端不知道」
     * 的场景用重试是修不好的，只能靠<b>幂等键</b>（M5 治理项）。
     * {@code NEVER_RETRY} 把它压到「最多一次」，是 M1 能做的最小正确动作。
     *
     * <p>★ 注意这个 bean 是全局的（对所有 Feign 客户端生效）。
     * 本服务只有一个客户端，所以等价于「只作用于它」。
     * 将来若新增<b>读</b>客户端并想要重试，应该用
     * {@code @FeignClient(configuration = ...)} 单独配，而不是把这个删掉。
     */
    @Bean
    public Retryer feignRetryer() {
        return Retryer.NEVER_RETRY;
    }
}
