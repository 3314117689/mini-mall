package com.mall.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * @EnableFeignClients：让框架扫描 @FeignClient 接口并生成代理对象。
 * 不写 basePackages 时，默认扫描本启动类所在包（com.mall.order）及其子包。
 */
@SpringBootApplication(scanBasePackages = "com.mall")
@EnableFeignClients
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
