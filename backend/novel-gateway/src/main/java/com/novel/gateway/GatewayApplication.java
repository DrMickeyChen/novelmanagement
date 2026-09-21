package com.novel.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 网关的启动类。<b>M0 阶段它只有这一个类</b> —— 网关的全部行为都由配置决定
 * （{@code application.yml} 里的 routes），没有任何 Java 代码参与路由。
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
 *           └─ :8080  ★ 本服务 —— 唯一入口
 *                └─ lb://novel-backend → :8090（第一期那个单体）
 * </pre>
 *
 * <p>第二期后面几步会陆续在这里加东西：M1 接管 JWT 校验（白名单放行、
 * 解析 token 后把 userId 放进请求头传给下游），M5 加限流和链路追踪。
 * 现在它是空的，<b>这是有意的</b> —— M0 的验收标准就是「路由通」，
 * 一个字节的业务逻辑都不该出现在这里。
 *
 * <p><b>★ 一个必须知道的坑（verify 时要看）：</b>本模块的 classpath 上
 * 不能出现 {@code spring-webmvc}，否则启动直接失败。原因和验证命令
 * 写在 {@code novel-gateway/pom.xml} 里 gateway starter 那一段。
 *
 * @author MickeyChen
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
