package com.mall.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 用户服务启动类。
 * 注意：@SpringBootApplication 默认只扫描本包及子包（com.mall.user.*），
 * 所以 mapper / service / controller 都必须放在这个包下面，否则注解不生效。
 */
@SpringBootApplication
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
