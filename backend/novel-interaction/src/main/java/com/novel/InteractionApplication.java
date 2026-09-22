package com.novel;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.core.env.Environment;

/**
 * 小说管理系统 —— 互动服务启动类（第二期 M2 起）
 *
 * <p><b>这个类的名字换过两次，因为它所在的进程一直在缩水：</b>
 * <ul>
 *   <li>第一期：{@code NovelApplication}，{@code backend/} 是<b>单模块</b>，
 *       所有业务都在这一个进程里。</li>
 *   <li>M1：还叫 {@code NovelApplication}，但 {@code modules/system}
 *       （登录注册 / 用户 / 角色权限 / 作者申请 / 公告 / ping）已经搬进
 *       {@link AuthApplication} 所在的 novel-auth 进程。</li>
 *   <li>★ M2：改名为 {@code InteractionApplication}，{@code modules/content}
 *       与 {@code modules/ai} 也搬走了，这里<b>只剩 {@code modules/interaction}</b>。
 *       名字跟着事实走 —— 「NovelApplication 只剩评论和书架」是一句假话。</li>
 * </ul>
 *
 * <p><b>★ 为什么 {@code @MapperScan("com.novel.modules.**.mapper")} 不用改：</b>
 * 这个通配在本服务的 classpath 上只会命中 interaction 的 mapper，
 * 因为 system / content / ai 的 mapper 都已经不在这个服务的 classpath 上了。
 * ★★ 三个服务写同一个通配、扫出不同结果，是因为它们的 classpath 不同 ——
 * 不是因为配置有什么玄机。这也是 M2 之后【仍然】不需要给它们各写一份
 * {@code basePackages} 的原因。
 *
 * <p><b>★ {@code @EnableFeignClients} 为什么需要它：</b>
 * M2 之后本服务有一整套出站调用（拿作品简介、章节简介、作者昵称、
 * 章节状态、写审核日志），全部落在
 * {@code com.novel.modules.interaction.client} 下的 Feign 接口上。
 * 不加这个注解，{@code @FeignClient} 接口不会被注册成 Bean，
 * 启动或首次调用时才报「找不到可注入的类型」。
 * <p>★ 它的默认扫描范围是启动类所在包（{@code com.novel}），
 * 客户端所在的 {@code com.novel.modules.interaction.client} 在范围内，
 * 不用写 {@code basePackages}。
 *
 * @author MickeyChen
 */
@Slf4j
@SpringBootApplication
@EnableFeignClients
@MapperScan("com.novel.modules.**.mapper")
public class InteractionApplication {

    public static void main(String[] args) {
        Environment env = SpringApplication.run(InteractionApplication.class, args).getEnvironment();

        String port = env.getProperty("server.port", "8083");
        String profiles = String.join(",", env.getActiveProfiles());

        /*
         * ★★ M1：这个横幅里的 "Ping 检测 / 数据库检测" 两行【删掉了】。
         *   它们原来指向本服务的 /api/ping —— 而 ping 已经跟着
         *   modules/system 搬进 novel-auth（:8081）了。
         *   放着一个打印出来就 404 的地址，比不打印更糟：
         *   排查的人会以为「服务没起好」，而不是「这个接口不在这儿」。
         * ★ server.port 的默认值一路跟着服务走：8080（一期）→ 8090（M0）
         *   → 8083（M2）。8080 现在是网关的端口。默认值只在 application.yml
         *   被改坏时才生效，而那时打印一个错的端口是纯粹的误导 ——
         *   所以这里的三处文案（端口默认值 / 横幅 / 下面那几行）必须同步改。
         */
        log.info("""

                        ----------------------------------------------------------
                          小说管理系统 · 互动服务启动成功
                        ----------------------------------------------------------
                          当前环境 : {}
                          接口地址 : http://localhost:{}/api
                          接口文档 : http://localhost:{}/doc.html
                        ----------------------------------------------------------
                          ★ 生产环境这个端口【不对外】，唯一入口是网关 :8080
                          ★ 登录 / 用户 / 公告 / ping 都在 novel-auth   :8081
                          ★ 小说 / 章节 / 分类 / 审核 都在 novel-content:8082""",
                profiles.isEmpty() ? "default" : profiles,
                port, port);
    }
}
