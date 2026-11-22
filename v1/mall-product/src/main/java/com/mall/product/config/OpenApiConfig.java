package com.mall.product.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

/**
 * L10：接口文档基础信息。
 * SpringDoc 扫描 Controller 生成 OpenAPI JSON，Swagger UI 再把它渲染成可调试页面。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI productServiceOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("mini-mall 商品服务 API")
                .description("商品增删改查与库存扣减/回补；直连端口 8082")
                .version("1.0.0"));
    }
}
