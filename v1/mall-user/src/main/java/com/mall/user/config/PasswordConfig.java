package com.mall.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码编码器（L6-2）。
 *
 * 为什么只引 spring-security-crypto 而不引 starter-security：
 * 我们只用 BCrypt 这个工具类，鉴权由自己的拦截器完成；引全家桶会自动装上一整套
 * Security 过滤器链，反而把简单链路搅乱。
 *
 * 这里注册成 Bean，Service 里 @Autowired 注入接口 PasswordEncoder，
 * 以后想换算法（如 Argon2）只改这一处。
 */
@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        // 强度 10 = 2^10 次迭代，是 BCrypt 默认值：太低调安全性，太高登录变慢
        return new BCryptPasswordEncoder();
    }
}
