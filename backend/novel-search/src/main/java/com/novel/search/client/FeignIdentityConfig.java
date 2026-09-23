package com.novel.search.client;

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
 * <b>novel-search 所有 Feign 出站请求的统一配置（M4）。</b>
 *
 * <p>它做两件事，缺任何一件都会静默出错：
 *
 * <h3>① 带上服务间共享密钥</h3>
 * 没有它，对端（content）的 {@code HeaderAuthenticationFilter} 会把这次调用当成
 * 「有人直连了内网端口」——{@code /internal/**} 直接 403。
 * <p>★ 这一处和网关写的是<b>同一个头、同一个值</b>（{@code novel.internal.token}）。
 *
 * <h3>② 把当前登录态一并带过去</h3>
 *
 * <p>Feign 调用是一个<b>全新的 HTTP 请求</b>：它<b>不会</b>自动继承
 * 当前线程的 {@code UserContext}。
 *
 * <p><b>★★ 对 search 来说，这条【比在 content 时更重要】，要说清楚为什么：</b>
 * <pre>
 *   content 调 auth 取作者昵称  → 对端不需要知道「谁在查」，② 缺失只是少带信息
 *   search  调 content 装配数据 → ★ content 的装配端会按【调用者身份】
 *                                  决定返回什么（作者看自己的草稿、
 *                                  管理员看全部），以及审计「谁调的」
 * </pre>
 * <p>★ 本服务的 {@code /api/search} 是<b>匿名放行</b>的，所以大多数调用里
 * {@code UserContext} 本来就是 null —— 这正是我们要的（只带密钥、不带身份）。
 * 但<b>登录用户</b>搜索时，身份必须传过去，否则 content 无法区分
 * 「匿名读者」和「登录读者」，将来做「搜索我自己的草稿」时会静默失效。
 * ★ 那是一个「不报错、只是搜不到」的故障，所以现在就要带对。
 *
 * <p>★ 好处是这一段的逻辑不用重写：写头和读头都在
 * {@link IdentityHeaders} 里（网关写的是同一组头，用的是同一个方法）。
 *
 * <h3>★★ 这是第 4 份 —— 一份 M5 的债，M4 只记录不处理</h3>
 *
 * <p>全项目现在有四份几乎一样的 {@code FeignIdentityConfig}
 * （auth 没有，content / interaction / search 各一份）。
 * <b>重复的是这个类的注册代码，不是过滤器</b> ——
 * {@code HeaderAuthenticationFilter} 本身全项目只有一份。
 *
 * <p>★ 为什么不现在合并进 {@code novel-common}：它能合并（逻辑逐字相同），
 * 但那会让 {@code novel-common} 依赖 {@code spring-cloud-starter-openfeign}，
 * 而这个依赖现在是 optional/test 范围内的东西。★★ 更要紧的是
 * <b>M4 的原则是「只动必要的公共代码」</b> —— 这一期已经为了 search
 * 给 5 个 common 类加了条件注解，再顺手做一次跨服务重构会让
 * 「S2 探针失败时到底是谁的锅」变得说不清。
 * <p>★ 正确顺序是：先让四个服务都跑起来、验证条件注解对老服务是 no-op，
 * <b>再</b>做合并。合并本身记在 M5。
 *
 * <p><b>★ 为什么这个 {@code @Bean} 是全局的（而不是挂在某一个客户端上）：</b>
 * Spring Cloud OpenFeign 会把容器里所有 {@code RequestInterceptor} bean
 * 应用到<b>所有</b>客户端。本服务只有一个客户端，将来多几个也应该
 * 一律带上密钥和身份。
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
             *   匿名搜索本来就不需要知道调用者是谁。
             */
            IdentityHeaders.write(headers, UserContext.get());
            IdentityHeaders.writeInternalToken(headers, internalToken);

            headers.forEach(template::header);

            log.debug("Feign 出站携带身份 | url={} | {}", template.url(),
                    IdentityHeaders.describe(headers::get));
        };
    }
}
