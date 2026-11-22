package com.mall.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API 网关（L7）：前端只访问 9000，由这里按路径转发到各微服务。
 *
 * 本类是 WebFlux 应用（Netty），不是 Tomcat + Spring MVC；
 * 所以业务服务里的 HandlerInterceptor、@RestControllerAdvice 那一套在这里都不生效，
 * L7-2 的鉴权要用 Gateway 自己的 GlobalFilter 写。
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
