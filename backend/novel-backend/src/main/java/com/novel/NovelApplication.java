package com.novel;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.core.env.Environment;

/**
 * 小说管理系统 —— 单体启动类（第一期：单体；第二期 M1 起：<b>内容服务</b>）
 *
 * <p>第一期 {@code backend/} 是<b>单模块</b>，所有业务都在这一个进程里。
 * <p>★★ <b>M1 之后它不再是那个「全部业务」的单体了</b>：{@code modules/system}
 * （登录注册 / 用户 / 角色权限 / 作者申请 / 公告 / ping）已经搬进
 * {@link AuthApplication} 所在的 novel-auth 进程。留在这里的是
 * {@code modules/content}、{@code modules/interaction}、{@code modules/ai}
 * —— 按大纲的拆分计划，后续 M2/M3 还会把 content、interaction 陆续摘走，
 * 那时这个类才会真正变成「只剩下一点点」。
 *
 * <p><b>★ 为什么 {@code @MapperScan("com.novel.modules.**.mapper")} 不用改：</b>
 * 这个通配在本服务的 classpath 上只会命中 content / interaction 的 mapper，
 * 因为 {@code modules.system.mapper} 已经不在这个服务的 classpath 上了。
 * 两个服务写同一个通配、扫出不同结果，是因为它们的 classpath 不同 ——
 * 不是因为配置有什么玄机。
 *
 * <p><b>★ {@code @EnableFeignClients} 为什么需要它：</b>
 * 本服务有一个出站调用 —— 内容侧要拿作者昵称（{@code ContentUserLookup}
 * → {@code AuthUserClient}）。不加这个注解，{@code @FeignClient} 接口
 * 不会被注册成 Bean，启动或首次调用时才报「找不到可注入的类型」。
 * <p>★ 它的默认扫描范围是启动类所在包（{@code com.novel}），
 * 客户端所在的 {@code com.novel.modules.content.client} 在范围内，不用写
 * {@code basePackages}。
 *
 * @author MickeyChen
 */
@Slf4j
@SpringBootApplication
@EnableFeignClients
@MapperScan("com.novel.modules.**.mapper")
public class NovelApplication {

    public static void main(String[] args) {
        Environment env = SpringApplication.run(NovelApplication.class, args).getEnvironment();

        String port = env.getProperty("server.port", "8090");
        String profiles = String.join(",", env.getActiveProfiles());

        /*
         * ★★ M1：这个横幅里的 "Ping 检测 / 数据库检测" 两行【删掉了】。
         *   它们原来指向本服务的 /api/ping —— 而 ping 已经跟着
         *   modules/system 搬进 novel-auth（:8081）了。
         *   放着一个打印出来就 404 的地址，比不打印更糟：
         *   排查的人会以为「服务没起好」，而不是「这个接口不在这儿」。
         * ★ 同理，这里的 server.port 默认值也从 8080 改成了 8090 ——
         *   8080 现在是网关的端口（M0 起）。默认值只在 application.yml
         *   被改坏时才生效，而那时打印一个「本服务在 8080」是纯粹的误导。
         */
        log.info("""

                        ----------------------------------------------------------
                          小说管理系统 · 内容服务启动成功
                        ----------------------------------------------------------
                          当前环境 : {}
                          接口地址 : http://localhost:{}/api
                          接口文档 : http://localhost:{}/doc.html
                        ----------------------------------------------------------
                          ★ 生产环境这个端口【不对外】，唯一入口是网关 :8080
                          ★ 登录 / 用户 / 公告 / ping 都在 novel-auth :8081，不在这里""",
                profiles.isEmpty() ? "default" : profiles,
                port, port);
    }
}
