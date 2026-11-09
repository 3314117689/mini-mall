package com.mall.product;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 商品服务启动类。
 * 同样用 scanBasePackages 扫描 com.mall，让 mall-common 里的
 * 全局异常处理器等公共 Bean 也能被发现。
 */
@SpringBootApplication(scanBasePackages = "com.mall")
public class ProductServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductServiceApplication.class, args);
    }
}
