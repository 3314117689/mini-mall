package com.mall.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @EnableFeignClients：让框架扫描 @FeignClient 接口并生成代理对象。
 * 不写 basePackages 时，默认扫描本启动类所在包（com.mall.order）及其子包。
 * @EnableScheduling：L9 补丁2，打开定时任务，扫描库存补偿任务重试。
 */
@SpringBootApplication(scanBasePackages = "com.mall")
@EnableFeignClients
@EnableScheduling
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
