package com.novel;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;

/**
 * 小说管理系统 —— 启动类（第一期：单体）
 *
 * <p>第一期 {@code backend/} 是<b>单模块</b>，所有业务都在这一个进程里。
 * 第二期拆微服务时，这个类会被拆成 gateway / auth / content / interaction / search
 * 五个独立的启动类，业务代码基本不用改 —— 所以现在就要按「模块化」的包结构写，
 * 见 {@code modules/} 下的划分。
 *
 * @author MickeyChen
 */
@Slf4j
@SpringBootApplication
@MapperScan("com.novel.modules.**.mapper")
public class NovelApplication {

    public static void main(String[] args) {
        Environment env = SpringApplication.run(NovelApplication.class, args).getEnvironment();

        String port = env.getProperty("server.port", "8080");
        String profiles = String.join(",", env.getActiveProfiles());

        log.info("""

                        ----------------------------------------------------------
                          小说管理系统 · 后端启动成功
                        ----------------------------------------------------------
                          当前环境 : {}
                          接口地址 : http://localhost:{}/api
                          接口文档 : http://localhost:{}/doc.html
                          Ping 检测: http://localhost:{}/api/ping
                          数据库检测: http://localhost:{}/api/ping/db
                        ----------------------------------------------------------""",
                profiles.isEmpty() ? "default" : profiles,
                port, port, port, port);
    }
}
