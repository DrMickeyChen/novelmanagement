package com.novel;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.core.env.Environment;

/**
 * 认证服务 —— 启动类（第二期 M1）。
 *
 * <p>装的是第一期的 {@code com.novel.modules.system} 整个包：登录注册、用户、
 * 角色权限、作者申请、公告、ping。
 *
 * <p><b>★★ 这个类为什么和 {@link NovelApplication} 一样放在 {@code com.novel} 包：</b>
 * {@code @SpringBootApplication} 的默认扫描范围就是启动类所在的那个包，
 * 放在 {@code com.novel} 下，两个服务都天然扫到
 * {@code com.novel.common.**}（共用的 Result / 异常 / JwtUtil / 技术配置）。
 *
 * <p>这样做还顺手避开了一个坑：如果改成把 classpath 上所有包都扫一遍
 * （比如 {@code scanBasePackages = "com" }），就会扫到别的模块的东西。
 * 放在 {@code com.novel} 是最自然的范围 —— 而两个服务【各自 classpath 上
 * 只装着自己的 modules】，所以同一个扫描范围在两边扫出来的东西自然不同：
 * <pre>
 *   novel-auth     扫到  com.novel.modules.system
 *   novel-backend  扫到  com.novel.modules.{content,interaction,ai}
 * </pre>
 *
 * <p><b>★ {@code @MapperScan} 为什么可以照抄 {@code com.novel.modules.**.mapper}：</b>
 * 同一个道理 —— 这个通配在 novel-auth 里只会命中 {@code modules.system.mapper}，
 * 因为别的模块根本不在这个服务的 classpath 上。不用为一个服务专门写窄范围。
 *
 * <p><b>★★ M1 起这个服务【不自己验签 JWT】：</b>
 * 认证被网关接管了（大纲 §177「网关做认证，服务做鉴权」）。网关验完签、
 * 查完 Redis，把身份写进 {@code X-User-Id} 等请求头；本服务由
 * {@code com.novel.common.security.filter.HeaderAuthenticationFilter}
 * 读那些头，重建 {@code SecurityContext} 与 {@code UserContext}。
 * 所以 {@code AuthSecurityConfig} 里配的仍然全是「URL 闸」，一行业务鉴权代码都没改。
 *
 * <p><b>★ {@code @EnableFeignClients} 为什么需要它：</b>
 * 本服务有一个出站调用 —— 审完作者申请要回头让 novel-backend 写审核流水
 * （{@code com.novel.auth.client.BackendAuditClient}）。
 * 不加这个注解，{@code @FeignClient} 接口<b>不会被注册成 Bean</b>，
 * 而错误只会在启动时冒出来：{@code AuthorApplyAuditRecorderImpl} 找不到
 * 可注入的 {@code BackendAuditClient}。
 * <p>★ 它的默认扫描范围是<b>启动类所在包</b>（{@code com.novel}），
 * 所以 {@code com.novel.auth.client} 在范围内，不用写 {@code basePackages}。
 * <p>★ <b>网关绝对不要加这个注解</b> —— 它不做任何出站业务调用，
 * 加了只会把 Feign 拖进 WebFlux 的 classpath（见 M0 留下的那道防线）。
 *
 * @author MickeyChen
 */
@Slf4j
@SpringBootApplication
@EnableFeignClients
@MapperScan("com.novel.modules.**.mapper")
public class AuthApplication {

    public static void main(String[] args) {
        Environment env = SpringApplication.run(AuthApplication.class, args).getEnvironment();

        String port = env.getProperty("server.port", "8081");
        String profiles = String.join(",", env.getActiveProfiles());

        log.info("""

                        ----------------------------------------------------------
                          小说管理系统 · 认证服务启动成功
                        ----------------------------------------------------------
                          当前环境 : {}
                          接口地址 : http://localhost:{}/api
                          接口文档 : http://localhost:{}/doc.html
                          Ping 检测: http://localhost:{}/api/ping
                          数据库检测: http://localhost:{}/api/ping/db
                        ----------------------------------------------------------
                          ★ 生产环境这个端口【不对外】，唯一入口是网关 :8080
                          ★ 登录接口 :8080/api/auth/login 由网关转发到这里""",
                profiles.isEmpty() ? "default" : profiles,
                port, port, port, port);
    }
}
