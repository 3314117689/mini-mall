package com.mall.order.config;

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
    public OpenAPI orderServiceOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("mini-mall 订单服务 API")
                .description("下单、订单分页/游标查询、库存补偿重放；直连端口 8083")
                .version("1.0.0"));
    }
}
