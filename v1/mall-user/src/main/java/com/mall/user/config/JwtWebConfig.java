package com.mall.user.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.mall.common.auth.JwtInterceptor;
import com.mall.common.auth.JwtVerifier;

/**
 * 用户服务的鉴权装配（L6-5）。
 *
 * 只拦 /user/**：登录、注册、JWKS 都在 /auth/** 下，必须免登录，
 * 否则"拿门票的地方也要门票"，谁也进不来。
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
                .addPathPatterns("/user/**");
    }
}
