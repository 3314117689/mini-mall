package com.mall.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.mall.common.auth.GatewayAuthInterceptor;

/**
 * 用户服务的鉴权装配（L7-3）。
 *
 * 鉴权已经上移到网关：这里不再验签，只读网关注入的三个身份头。
 * 只拦 /user/**：登录、注册、JWKS 都在 /auth/** 下，必须免登录，
 * 否则"拿门票的地方也要门票"，谁也进不来。
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
                .addPathPatterns("/user/**");
    }
}
