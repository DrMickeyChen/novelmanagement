package com.novel;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
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
 *   novel-content  扫到  com.novel.modules.content + com.novel.modules.ai
 *   novel-interaction 扫到  com.novel.modules.interaction
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
 * <p><b>★★★ M3 起这个服务【没有任何出站边】，而这件事是结构性的 ——
 * 不再依赖任何人记得住。</b>
 * M1/M2 期间这里挂着 {@code @EnableFeignClients}，只为了一件事：审完作者申请
 * 回头让 novel-content 写审核流水（{@code com.novel.auth.client.ContentAuditClient}）。
 * M3 把那一步换成了 {@link com.novel.auth.mq.producer.AuthorApplyAuditProducer}
 * 的一条<b>事务消息</b>，于是：
 * <pre>
 *   {@code @EnableFeignClients}        删掉了
 *   {@code spring-cloud-starter-openfeign}    从 pom 里删掉了
 *   {@code spring-cloud-starter-loadbalancer} 也删掉了（它只为解析 {@code name="novel-content"} 而存在）
 *   {@code com.novel.auth.client} 这个包        整个消失了
 * </pre>
 * ★ <b>这才是「环 #1 断了」的机械证据</b>，比一句「我们改用 MQ 了」硬：
 * 一个没有 Feign、没有 LoadBalancer、没有 {@code @FeignClient} 的服务，
 * <b>在编译期就发不出跨服务调用</b>。同 {@code novel-gateway} 那条
 * 「{@code spring-boot-starter-web} 一进 classpath 就起不来」的红线
 * 是同一类做法：把纪律变成结构。
 * <p>★ 顺带说清一件事：本服务仍然<b>被</b>别人调用
 * （{@code content → auth} 读用户昵称，见 {@code InternalUserController}），
 * 所以 {@code /internal/**} 那一套（白名单 + 内部令牌闸门）<b>一个字都不能动</b>。
 * 「没有出站」和「没有入站」是两件事。
 *
 * <p>★ <b>网关绝对不要加 {@code @EnableFeignClients}</b> —— 它不做任何出站业务调用，
 * 加了只会把 Feign 拖进 WebFlux 的 classpath（见 M0 留下的那道防线）。
 *
 * @author MickeyChen
 */
@Slf4j
@SpringBootApplication
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
