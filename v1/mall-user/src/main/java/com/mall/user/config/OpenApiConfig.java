package com.mall.user.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

/**
 * L10：接口文档基础信息。
 * SpringDoc 扫描 Controller 生成 OpenAPI JSON，Swagger UI 再把它渲染成可调试页面。
 */
@Configuration
public class OpenApiConfig {

    /** 文档里的安全方案名，Controller 的 @SecurityRequirement 要和它一致 */
    public static final String BEARER_AUTH = "bearerAuth";

    @Bean
    public OpenAPI userServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("mini-mall 用户服务 API")
                        .description("用户增删改查 + 登录注册；直连端口 8081，文档请求统一走网关 9000")
                        .version("1.0.0"))
                // Swagger UI 的 "Try it out" 会拼成 http://localhost:9000/api/...
                // 走网关才会验 Bearer 并注入 X-Mall-* 头；直连 8081 只认身份头，不认 Bearer
                .servers(List.of(new Server()
                        .url("http://localhost:9000/api")
                        .description("经网关：统一验 Bearer token")))
                .components(new Components().addSecuritySchemes(BEARER_AUTH,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("先调 /auth/login 拿 token，再点 Authorize 填入")));
    }
}
