package com.mall.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.mall.common.auth.JwtVerifier;

/**
 * 网关鉴权装配（L7-2）。
 *
 * 网关只负责验签：拿用户服务的 JWKS 公钥端点，不存私钥、不签发 token。
 * 直接复用 mall-common 里 L6 写好的 JwtVerifier，避免两份验签逻辑。
 */
@Configuration
public class GatewayAuthConfig {

    @Bean
    public JwtVerifier jwtVerifier(@Value("${mall.jwt.jwks-uri}") String jwksUri) {
        return new JwtVerifier(jwksUri);
    }
}
