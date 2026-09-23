package com.novel.gateway;

import com.novel.common.config.RedisConfig;
import com.novel.common.security.JwtUtil;
import com.novel.common.security.TokenStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * 网关的启动类。<b>M0 起它只有这一个类</b> —— 网关的转发行为全部由配置决定
 * （{@code application.yml} 里的 routes），路由部分没有一行 Java 代码。
 *
 * <h3>为什么不写 {@code @EnableDiscoveryClient}</h3>
 * Spring Cloud 从 Dalston 版起就不需要了：只要 classpath 上有服务发现的实现
 * （这里是 {@code spring-cloud-starter-alibaba-nacos-discovery}），
 * 自动配置会自己把客户端注册上去。写了不报错，但会让人以为是它在起作用 ——
 * 哪天自动配置失效了，看到这行注解反而会排错排到错的方向。
 *
 * <h3>这个服务在本项目里的位置</h3>
 * <pre>
 *   浏览器 :3000
 *      └─ dev-proxy（开发期）/ Nginx（生产）
 *           └─ :8080  ★ 本服务 —— 唯一入口，M1 起在这里验 JWT
 *                ├─ lb://novel-auth        → :8081（登录/用户/角色/公告/作者申请）
 *                ├─ lb://novel-content     → :8082（作品/章节/分类/审核/搜索/AI）
 *                └─ lb://novel-interaction → :8083（评论/书架/阅读历史）
 * </pre>
 *
 * <h3>★★ M1：{@link Import} 为什么是「逐个点名」而不是「扫描 novel-common」</h3>
 *
 * <p>本服务的 {@code @SpringBootApplication} 在 {@code com.novel.gateway}，
 * 默认扫描范围<b>就是这一个包</b> —— <b>刻意不去动它</b>。
 * 需要 common 里的东西时，用 {@code @Import} 把类一个一个点出来。
 *
 * <p><b>为什么不干脆把范围放宽到 {@code com.novel}：</b>
 * 网关是 <b>WebFlux</b>，classpath 上<b>没有 {@code jakarta.servlet-api}</b>。
 * 而 {@code novel-common} 里有一批 servlet-bound 的类：
 * <table border="1">
 *   <caption>扫进来会怎样</caption>
 *   <tr><th>类</th><th>为什么扫进去就炸</th></tr>
 *   <tr><td>{@code GlobalExceptionHandler}</td>
 *       <td>方法签名收 {@code jakarta.servlet.http.HttpServletRequest}</td></tr>
 *   <tr><td>{@code RestAuthenticationEntryPoint} / {@code RestAccessDeniedHandler}</td>
 *       <td>实现 spring-security-web 的接口（网关没有那个 jar）</td></tr>
 *   <tr><td>{@code HeaderAuthenticationFilter}</td>
 *       <td>{@code extends OncePerRequestFilter} → 要 {@code jakarta.servlet.Filter}</td></tr>
 *   <tr><td>★ M5 新增：{@code TraceIdFilter} / {@code TraceIdFilterConfig}</td>
 *       <td>同上（一个 {@code extends OncePerRequestFilter}，
 *           另一个引用了 {@code FilterRegistrationBean}）</td></tr>
 *   <tr><td>{@code UploadProperties}</td>
 *       <td>{@code @Component} + {@code @ConfigurationProperties}，
 *           缺 {@code novel.upload.*} 时可能直接抛异常</td></tr>
 * </table>
 *
 * <p><b>★★ 这张表是【会过期】的，所以它必须每加一个 common 类就更新一次。</b>
 * 表里少写一个的后果不是「文档不准」，而是：下一个人把扫描范围放宽到
 * {@code com.novel} 时，<b>这张本该拦住他的表看起来是「全的」</b> ——
 * 于是一道「不会失败的护栏」。M5 加 {@code TraceIdFilter} 时
 * 就是照着这个规矩补的。
 *
 * <p><b>★★ 这个坑的症状值得单独说：</b>报错会说「找不到某个类」或
 * 「某个 bean 创建失败」，<b>指向的是那个被误扫的类</b>，
 * 完全不指向「你把扫描范围开大了」这个真正的原因。
 * 最自然的反应是「那我把那个类也搬走/加上依赖」，于是越修越歪 ——
 * 一路把 servlet 依赖拖进 WebFlux 网关，直到撞上
 * {@code Spring MVC found on classpath} 才被迫回头。
 * <b>一行 {@code @Import} 就是用来避免这条歪路的。</b>
 *
 * <p><b>★ 白名单里为什么只有这三个：</b>
 * <ul>
 *   <li>{@link RedisConfig} —— ★★ <b>必须是「同一个类」而不是「同一份配置」。</b>
 *       它里面那套 {@code BasicPolymorphicTypeValidator} 白名单 +
 *       {@code activateDefaultTyping} 决定了 {@code LoginUser} 能不能从 Redis
 *       里原样读回来。<b>网关只要有一点点不同</b>（漏了 default typing、
 *       换了 serializer、白名单少一个前缀），读出来的就是 {@code LinkedHashMap}
 *       或直接抛 {@code InvalidTypeIdException}。
 *       <p>症状是<b>全站静默登出</b>（网关拿不到身份 → 下游当匿名 → 401 →
 *       前端清 localStorage 跳登录页），而排错的人会一路往「token 是不是过期了」
 *       「Redis 是不是没连上」上查，<b>永远查不到序列化</b>。
 *       <p>所以：{@code RedisConfig} 单源在 common，任何服务都不许再有第二份。</li>
 *   <li>{@link JwtUtil} —— 验签用。它的构造器带
 *       {@code @Value("${novel.jwt.secret}")} / {@code @Value("${novel.jwt.expire-days}")}，
 *       {@code @Import} 进来的类<b>照常走构造器注入</b>，所以那两个配置键
 *       本服务的 yml 里必须配全（少一个就启动失败，报占位符解析不了）。
 *       <p>★ 注意 {@code JwtUtil} 本身带 {@code @Component}，但<b>没用</b> ——
 *       本服务的扫描范围是 {@code com.novel.gateway}，扫不到
 *       {@code com.novel.common.security}。这里的 {@code @Import} 才是它
 *       被实例化的原因。</li>
 *   <li>{@link TokenStore} —— 每请求查一次 Redis，判断 token 还有没有效。
 *       登出、被踢下线、改角色立刻生效，靠的都是这一查。</li>
 * </ul>
 *
 * <p><b>★★ 本服务【绝不】加 {@code @EnableFeignClients}：</b>
 * 网关不做任何出站业务调用。加了只会把 Feign 拖进 WebFlux 的 classpath ——
 * 而 M0 好不容易才把「webmvc 传不进来」这件事结构性地做掉。
 *
 * <p><b>★ 一个必须知道的坑（verify 时要看）：</b>本模块的 classpath 上
 * 不能出现 {@code spring-webmvc}，否则启动直接失败。原因和验证命令
 * 写在 {@code novel-gateway/pom.xml} 里 gateway starter 那一段。
 * <p>★ M1 引了 {@code novel-common}，这件事<b>第一次被真正检验</b>：
 * M0 把 common 里所有 Spring starter 标成 {@code <optional>true</optional>}
 * 就是为了这一刻 —— 那一枪现在兑现了。
 *
 * @author MickeyChen
 */
@SpringBootApplication
@Import({RedisConfig.class, JwtUtil.class, TokenStore.class})
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
