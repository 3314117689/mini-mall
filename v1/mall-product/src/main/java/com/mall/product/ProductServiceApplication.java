package com.mall.product;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * 商品服务启动类。
 * 同样用 scanBasePackages 扫描 com.mall，让 mall-common 里的
 * 全局异常处理器等公共 Bean 也能被发现。
 */
// @EnableCaching：打开 Spring Cache 总开关（L5）。
// 不开它，Service 方法上的 @Cacheable 只是个普通注解，没人理会。
@EnableCaching
@SpringBootApplication(scanBasePackages = "com.mall")
public class ProductServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductServiceApplication.class, args);
    }
}
