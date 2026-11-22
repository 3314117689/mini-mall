package com.mall.order.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.mall.common.auth.CurrentUserArgumentResolver;
import com.mall.common.auth.GatewayAuthInterceptor;

/**
 * 订单服务的鉴权装配（L7-3）。
 *
 * mall-common 里只放"能复用的零件"，具体哪个服务、拦哪些路径，由各服务自己注册：
 * - /order/** 全部要过 GatewayAuthInterceptor（只读网关身份头，不再验 token）；
 * - 顺手注册 @CurrentUser 参数解析器，Controller 可直接声明 Long userId。
 */
@Configuration
public class JwtWebConfig implements WebMvcConfigurer {

    @Bean
    public GatewayAuthInterceptor gatewayAuthInterceptor() {
        return new GatewayAuthInterceptor();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(gatewayAuthInterceptor())
                .addPathPatterns("/order/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver());
    }
}
