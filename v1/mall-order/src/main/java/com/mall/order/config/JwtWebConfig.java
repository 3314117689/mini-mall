package com.mall.order.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.mall.common.auth.CurrentUserArgumentResolver;
import com.mall.common.auth.JwtInterceptor;
import com.mall.common.auth.JwtVerifier;

/**
 * 订单服务的鉴权装配（L6-4）。
 *
 * mall-common 里只放"能复用的零件"，具体哪个服务、拦哪些路径，由各服务自己注册：
 * - /order/** 全部要过 JwtInterceptor；
 * - /auth/** 不在 order 服务里，所以不用排除；
 * - 顺手注册 @CurrentUser 参数解析器，Controller 可直接声明 Long userId。
 */
@Configuration
public class JwtWebConfig implements WebMvcConfigurer {

    @Value("${mall.jwt.jwks-uri}")
    private String jwksUri;

    @Bean
    public JwtVerifier jwtVerifier() {
        return new JwtVerifier(jwksUri);
    }

    @Bean
    public JwtInterceptor jwtInterceptor(JwtVerifier jwtVerifier) {
        return new JwtInterceptor(jwtVerifier);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor(jwtVerifier()))
                .addPathPatterns("/order/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver());
    }
}
