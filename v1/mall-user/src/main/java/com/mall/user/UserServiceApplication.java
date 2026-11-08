package com.mall.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 用户服务启动类。
 * 注意：@SpringBootApplication 默认只扫描本包及子包（com.mall.user.*），
 * 而 GlobalExceptionHandler 在 mall-common 的 com.mall.common.exception 包下，
 * 所以这里用 scanBasePackages 把扫描范围扩大到 com.mall，让公共模块的 Bean 也能被发现。
 */
@SpringBootApplication(scanBasePackages = "com.mall")
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
