package com.novel.modules.content.client;

import com.novel.common.security.IdentityHeaders;
import com.novel.common.security.UserContext;
import feign.RequestInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>novel-backend 所有 Feign 出站请求的统一配置（M1）。</b>
 *
 * <p>它做两件事，缺任何一件都会静默出错：
 *
 * <h3>① 带上服务间共享密钥</h3>
 * 没有它，对端的 {@code HeaderAuthenticationFilter} 会把这次调用当成
 * 「有人直连了内网端口」——{@code /internal/**} 直接 403。
 * <p>★ 这一处和网关写的是<b>同一个头、同一个值</b>（{@code novel.internal.token}），
 * 也就是决策 4 里「两处都加共享密钥头」的第二处。
 *
 * <h3>② 把当前登录态一并带过去（★★ 这一条是真正容易漏的）</h3>
 *
 * <p>Feign 调用是一个<b>全新的 HTTP 请求</b>：它<b>不会</b>自动继承
 * 当前线程的 {@code UserContext}。而对端有一批接口要靠那个身份才能正确工作
 * （最典型的是 {@code /internal/author-apply-audit-logs} —— 流水的
 * {@code operator_id} 是从 {@code UserContext} 取的，见
 * {@code AuditLogServiceImpl#record}）。
 *
 * <p><b>★ 漏了它会怎样：</b>操作人变成 null 或被 {@code requireUserId()}
 * 拒绝，而症状取决于对端怎么处理 —— 但一定是「功能性缺失」而不是「崩溃」，
 * 所以不会有任何测试失败。<b>这类问题只能靠「一开始就写对」来避免。</b>
 *
 * <p>★ 好处是这一段的逻辑不用重写：写头和读头都在
 * {@link IdentityHeaders} 里（网关写的是同一组头，用的是同一个方法）。
 * 这里只是把「当前线程的登录态」喂给它。
 *
 * <p><b>★ 为什么这个 {@code @Bean} 是全局的（而不是挂在某一个客户端上）：</b>
 * Spring Cloud OpenFeign 会把容器里所有 {@code RequestInterceptor} bean
 * 应用到<b>所有</b>客户端。本服务目前只有一个客户端，将来多几个也应该
 * 一律带上密钥和身份 —— 没有一个「不该带」的场景。
 * 反过来，如果哪天真出现了「必须匿名调某个服务」的需求，
 * 那时应该用 {@code @FeignClient(configuration = ...)} 单独配一个，
 * 而<b>不是</b>把这个全局的删掉。
 *
 * @author MickeyChen
 */
@Slf4j
@Configuration
public class FeignIdentityConfig {

    /**
     * ★ 不给默认值 —— 没配就启动失败。理由同 {@code HeaderAuthenticationFilter}
     * 里那个字段：给了默认值，「忘了配」会退化成「用弱密钥跑在生产上」。
     */
    @Value("${novel.internal.token}")
    private String internalToken;

    @Bean
    public RequestInterceptor identityRelayInterceptor() {
        return template -> {
            Map<String, String> headers = new LinkedHashMap<>();

            /*
             * ★ 匿名调用时 UserContext.get() 是 null，write 会直接返回 ——
             *   于是只带密钥、不带身份。这正是我们要的：
             *   首页那种匿名场景下的内部读接口，本来就不需要知道调用者是谁。
             */
            IdentityHeaders.write(headers, UserContext.get());
            IdentityHeaders.writeInternalToken(headers, internalToken);

            headers.forEach(template::header);

            log.debug("Feign 出站携带身份 | url={} | {}", template.url(),
                    IdentityHeaders.describe(headers::get));
        };
    }
}
