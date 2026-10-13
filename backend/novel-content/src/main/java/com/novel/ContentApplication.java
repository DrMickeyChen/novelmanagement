package com.novel;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.core.env.Environment;

/**
 * Content service: novels, chapters, categories, outlines, publication and human audit.
 * AI handlers and audit consumers are owned by the independent novel-ai service (8085).
 */
@Slf4j
@SpringBootApplication
@EnableFeignClients
@org.mybatis.spring.annotation.MapperScans({
        @MapperScan("com.novel.modules.**.mapper"),
        @MapperScan(basePackages = "com.novel.modules.content.importing",
                annotationClass = org.apache.ibatis.annotations.Mapper.class)
})
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
