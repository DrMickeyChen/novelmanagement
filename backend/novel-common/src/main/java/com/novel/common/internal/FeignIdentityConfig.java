package com.novel.common.internal;

import com.novel.common.security.IdentityHeaders;
import com.novel.common.security.UserContext;
import com.novel.common.trace.TraceContext;
import feign.RequestInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>所有 Feign 出站请求的统一配置 —— ★ M5 起这里是【唯一一份】。</b>
 *
 * <p>它做三件事，缺任何一件都会静默出错：
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
 * <h3>③ 把本次请求的 traceId 透传过去（M5 新增）</h3>
 *
 * <p>对端的 {@code TraceIdFilter} 会<b>采纳</b>它，于是「本服务 → 对端」
 * 这一次调用在两边日志里是同一个 id。
 * ★ 少写这一个头，链路就在这条边上断掉，而且<b>没有任何症状</b> ——
 * 请求照常成功，只是两段日志再也串不起来。
 *
 * <p>★ 只在非空时才写。MQ 消费线程发起的 Feign 调用没有 MDC
 * （M5 §「跨 MQ 不做」：traceId 的语义是「一次请求」，而消费是「一次投递」，
 * 生命周期不同 —— 所以 M5 不往消息里塞 traceId）。
 * ★★ 关键是<b>不要</b>在这里 fallback 生成一个新 id ——
 * 那等于凭空造出一个「请求 id」，让人以为它属于某次请求，比不带更糟
 * （{@link TraceContext#current()} 的注释里说的就是这个）。
 *
 * <h3>★★★ M5：为什么这段代码住在 novel-common</h3>
 *
 * <p>它原本是<b>三份几乎逐字相同的副本</b>（content / interaction / search
 * 各一份）。M4 记成了债，M5 兑现 —— 因为真正的「逻辑」其实早就单源了
 * （编码和头名都在 {@link IdentityHeaders} 里），重复的只是这十几行配置。
 * 合并的收益不是少写几行，是<b>少一个「改了一份忘了另两份」的地方</b>。
 *
 * <p><b>★★ 两条当时的顾虑，M5 逐一核实后都不成立：</b>
 *
 * <table border="1">
 *   <caption>M4 记下的合并障碍 vs 核实结果</caption>
 *   <tr><th>M4 的顾虑</th><th>核实结果</th></tr>
 *   <tr>
 *     <td>「只有 auth 和 interaction 两份带 {@code Retryer.NEVER_RETRY}，
 *         content 那份没有 —— 合并要为『语义不同』选一个赢家，那是行为变更」</td>
 *     <td>★★ <b>不成立，而且两份说法都已过时：</b>auth 那份 M3 随整条边删了；
 *         剩下 interaction 那个 {@code @Bean} <b>是重复声明框架默认值</b>
 *         （见下面那段），<b>删掉它行为一字不变</b>。</td>
 *   </tr>
 *   <tr>
 *     <td>「{@code novel-common} 要依赖 Feign 的 API，网关会顺带多一个用不上的 Feign」</td>
 *     <td>★ <b>靠 {@code <optional>true</optional>} 解决，而这一招仓库里早就在用</b>
 *         （{@code novel-common/pom.xml} 里那组 optional starter 有完整论证）。
 *         而 {@code feign-core} 这个依赖 M5 <b>本来就已经加了</b>
 *         —— {@link FallbackSupport} 要 {@code instanceof RetryableException}。</td>
 *   </tr>
 * </table>
 *
 * <p><b>★★ 为什么这个类必须挂 {@link ConditionalOnClass}（不是装饰）：</b>
 * <pre>
 *   novel-gateway  启动类在 com.novel.gateway  → 【扫不到】com.novel.common
 *   novel-auth     启动类在 com.novel          → ★【扫得到】com.novel.common
 * </pre>
 * <p>auth 已经<b>没有 Feign 了</b>（M3 把出站边清零，那是好不容易挣来的），
 * 它的 classpath 上没有 {@code RequestInterceptor}。少了这个条件注解，
 * auth 启动时会尝试处理这个类 —— 而 Spring 的条件注解是<b>用 ASM 读元数据</b>
 * 判断的，<b>不会加载类本身</b>，所以这里能安全跳过。
 * ★ 这与 M4 给另外 5 个 common 类加条件注解是同一件事，不是新套路。
 *
 * <h3>★★★ 关于重试：{@code NEVER_RETRY} 是【框架默认】，所以这里【不声明 Retryer】</h3>
 *
 * <p>interaction 曾经有一个 {@code @Bean Retryer feignRetryer() { return NEVER_RETRY; }}，
 * 它的注释把它写成了「一道必需的护栏」。★ <b>那个说法是错的</b>：
 * {@code spring-cloud-openfeign} 的 {@code FeignClientsConfiguration} 自己就注册了
 * <pre>
 *   &#64;Bean &#64;ConditionalOnMissingBean
 *   public Retryer feignRetryer() { return Retryer.NEVER_RETRY; }
 * </pre>
 * <p>也就是说 <b>Spring Cloud OpenFeign 的默认【本来就是】不重试</b>
 * （自 {@code spring-cloud-starter-openfeign 2.2.6} 起）。
 * {@code Retryer.Default}（最多 5 次尝试）是<b>裸 Feign</b> 的默认，
 * 那个数字被引进了两份注释里（interaction 那份 + {@code docs/M2-摘出content.md}），
 * M5 一并订正。★ <b>那个 {@code @Bean} 是同名 bean 覆盖，结果相同 ——
 * 它一个字节的行为都没改。</b> <b>它不是护栏，是一行让人以为有护栏的注释。</b>
 *
 * <p><b>★★ 所以 M5 把它删了 —— 但「读客户端不重试」这个结论【没有变】</b>，
 * 只是理由换了：不再是「怕写接口重复写」，而是 <b>重试与熔断相互放大</b>。
 * <ul>
 *   <li>熔断<b>打开之前</b>：重试把「1 次慢调用」放大成 N 次 ——
 *       加速熔断打开（好），但同一时刻下游本来已经吃力，
 *       重试是在往一个过载的服务上再加负载（坏）。</li>
 *   <li>熔断<b>打开之后</b>：重试全被快速失败挡住，<b>一次真正的请求都不会发生</b>
 *       —— 收益为零。</li>
 * </ul>
 * <p>→ 抖动恢复交给<b>熔断的半开探测</b>，不交给重试。
 * ★ 顺带一条 M5 白捡的性质：重试复用同一组请求头 → <b>同一个 traceId</b>，
 * 所以「重试了几次」在日志里是同一个 traceId 的重复行，一眼可见。
 *
 * <p><b>★★ 而「将来真要给某个客户端开重试」这条路仍然留着，只是换了写法：</b>
 * 用 {@code @FeignClient(configuration = ...)} 给那<b>一个</b>客户端单独配，
 * <b>而不是</b>在这里加一个全局的 {@code Retryer} bean ——
 * 后者会把「重试」这个决定强加给所有服务的所有客户端，
 * 而下一个写接口出现时，你不会有第二次机会想起这里曾经有过一道护栏。
 *
 * @author MickeyChen
 */
@Slf4j
@Configuration
@ConditionalOnClass(RequestInterceptor.class)
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

            /*
             * ★ M5：把本次请求的 traceId 透传过去 —— 见类注释 ③。
             *   ★ 空串也不写：对端 TraceIdFilter 的白名单会把空串挡掉、当没带处理，
             *     写了只是多一个无意义的头。
             */
            String traceId = TraceContext.current();
            if (traceId != null) {
                headers.put(TraceContext.HEADER, traceId);
            }

            headers.forEach(template::header);

            log.debug("Feign 出站携带身份 | url={} | {}", template.url(),
                    IdentityHeaders.describe(headers::get));
        };
    }
}
