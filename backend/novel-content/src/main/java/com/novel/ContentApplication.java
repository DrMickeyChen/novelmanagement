package com.novel;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.core.env.Environment;

/**
 * 小说管理系统 —— 内容服务启动类（第二期 M2 起）
 *
 * <p><b>本服务装着：</b>{@code modules/content}（小说 / 章节 / 分类 / 大纲 /
 * 审核流水 / 数据大盘）+ {@code modules/ai}（AI 写作助手）。
 * 它从第一期的单体里摘出来，M2 之前住在 novel-backend 进程里。
 *
 * <p><b>★ 为什么 AI 跟着 content 走：</b>大纲 §三约束 4 / §11.1 服务职责表 /
 * §四目录结构三处原文都指向 content，业务上也说得通 ——
 * AI 写作助手生成的是<b>章节正文</b>，与内容同域。
 *
 * <p><b>★ 为什么 {@code @MapperScan("com.novel.modules.**.mapper")} 不用改：</b>
 * 这个通配在本服务的 classpath 上只会命中 content 的 mapper，
 * 因为 system / interaction 的 mapper 都已经不在这个服务的 classpath 上了。
 * ★★ 四个服务写同一个通配、扫出不同结果，是因为它们的 classpath 不同 ——
 * 不是因为配置有什么玄机。这也是 M2 之后【仍然】不需要给它们各写一份
 * {@code basePackages} 的原因。
 *
 * <p><b>★ {@code @EnableFeignClients} 为什么需要它：</b>
 * 本服务有<b>两个</b>出站调用，都是铁律 1 说的「业务语义的接口住在消费方」：
 * <ul>
 *   <li>拿作者昵称 → novel-auth（{@code AuthUserClient}，M1 就有）</li>
 *   <li>★★ 拿评论正文 → novel-interaction（{@code CommentTextLookup} 的实现）。
 *       这就是铁律 1 原文里点名的那个反向接缝 ——
 *       接口留在消费方（本服务），实现变成「发一次 HTTP 请求」。</li>
 * </ul>
 * 不加这个注解，{@code @FeignClient} 接口不会被注册成 Bean，
 * 启动或首次调用时才报「找不到可注入的类型」。
 * <p>★ 它的默认扫描范围是启动类所在包（{@code com.novel}），
 * 客户端所在的 {@code com.novel.modules.content.client} 在范围内。
 *
 * <p><b>★★ 为什么这个类上【没有】{@code @EnableScheduling}，而阅读量落库却照跑：</b>
 * {@code NovelViewCounter} 用 Redis 的 {@code NOVEL_VIEWS} + {@code NOVEL_VIEW_DIRTY}
 * 两个 key 攒阅读量，靠一个 {@code @Scheduled} 任务把脏集里的增量回写数据库。
 * 按直觉，这个注解应该挂在启动类上 —— <b>但它不在这儿，而在
 * {@code com.novel.common.config.SchedulingConfig} 上</b>，
 * 那是个会被<b>每个服务</b>扫到的 {@code @Configuration}。
 * <ul>
 *   <li>★ 所以本服务【不要】再写一遍 {@code @EnableScheduling}。
 *       写了不会报错（这个注解是幂等的），但会让人以为「是这行在起作用」，
 *       而真正起作用的那一行在 common 里 —— 将来有人去动它时，会以为
 *       只影响某个服务。</li>
 *   <li>★★ 更要紧的是那条<b>连带事实</b>：四个服务<b>全都</b>在跑
 *       {@code @EnableScheduling}，也全都建了一个 {@code ThreadPoolTaskScheduler}。
 *       M2 之后只有 content 里真的有 {@code @Scheduled} 方法，
 *       所以「多实例下定时任务会重复执行」这条风险（见 SchedulingConfig 的长注释）
 *       目前只落在本服务头上 —— 起两个 content 实例，阅读量就会被回写两次。
 *       ★ 单机开发环境<b>完全测不出来</b>。</li>
 * </ul>
 *
 * @author MickeyChen
 */
@Slf4j
@SpringBootApplication
@EnableFeignClients
@MapperScan("com.novel.modules.**.mapper")
public class ContentApplication {

    public static void main(String[] args) {
        Environment env = SpringApplication.run(ContentApplication.class, args).getEnvironment();

        String port = env.getProperty("server.port", "8082");
        String profiles = String.join(",", env.getActiveProfiles());

        /*
         * ★★ 这个横幅是【从 novel-backend 的 NovelApplication 抄来的】，
         *   但三处文案全都改过，别以为只是复制粘贴：
         *     ① 服务名：内容服务（它以前是那句横幅里的「内容服务」，
         *        但那时它同时也装着互动和 AI —— 现在是一个名副其实的内容服务了）
         *     ② 端口：8083 → 8082
         *     ③ 最后两行：兄弟服务的位置（M2 之后是【四个】进程）
         *
         * ★ 为什么横幅里的地址必须是对的：默认值只在 application.yml 被改坏时
         *   才生效，而那时打印一个错的端口是纯粹的误导 ——
         *   排查的人会去连那个端口，然后得出「服务没起来」的结论。
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
                          ★ 登录 / 用户 / 公告 / ping 都在 novel-auth       :8081
                          ★ 互动服务（评论 / 书架 / 历史）在 novel-interaction :8083""",
                profiles.isEmpty() ? "default" : profiles,
                port, port);
    }
}
