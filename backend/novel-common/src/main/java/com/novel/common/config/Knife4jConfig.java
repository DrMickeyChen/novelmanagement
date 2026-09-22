package com.novel.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档配置。
 *
 * <p>启动后访问 <b>http://localhost:8080/doc.html</b>，
 * 能直接在页面上调接口、看参数说明，不用再开 Postman 手搓 JSON。
 *
 * <p>注意：Knife4j 只是给 springdoc 套了层好看的 UI，
 * 底层的注解全是标准的 OpenAPI 3（{@code io.swagger.v3.oas.annotations.*}），
 * 所以将来换回官方的 swagger-ui 也不用改任何注解。
 *
 * @author MickeyChen
 */
@Configuration
public class Knife4jConfig {

    /** 认证方案的名字，会被 SecurityRequirement 引用 */
    private static final String SECURITY_SCHEME_NAME = "Bearer";

    @Bean
    public OpenAPI novelOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("小说管理系统 API")
                        .version("1.0.0")
                        .description("""
                                小说管理系统后端接口文档。

                                **认证方式：** 登录接口拿到 token 后，点击右上角「Authorize」，
                                填入 token（不用手写 Bearer 前缀，页面会自动加）。

                                **统一响应体：** 所有接口都返回 `{ code, message, data }`，
                                `code = 200` 表示成功，其余见错误码定义。
                                """)
                        .contact(new Contact().name("MickeyChen"))
                        .license(new License().name("MIT")))

                // 声明一个 Bearer Token 的认证方式，让文档页面多出「Authorize」按钮。
                // ★ 现在还没引入 Spring Security，这里只是文档层面的声明，
                //   不影响任何接口的实际访问 —— P1 加上认证后就能直接用。
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME, new SecurityScheme()
                                .name(SECURITY_SCHEME_NAME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .in(SecurityScheme.In.HEADER)
                                .description("登录接口返回的 token")))

                // 全局默认需要认证。个别公开接口（登录、注册、作品列表）
                // 可以用 @SecurityRequirements 单独去掉
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME));
    }
}
