package com.mall.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.mall.common.result.Result;
import com.mall.order.dto.ProductDTO;

/**
 * 商品服务客户端。
 * name 写注册中心里的服务名（不是 IP）；方法上的注解照抄 ProductController，
 * 框架据此拼 URL，并按返回值 Result<ProductDTO> 解析响应。
 */
@FeignClient(name = "product-service")
public interface ProductClient {

	@GetMapping("/product/{id}")
	Result<ProductDTO> getById(@PathVariable("id") Long id);
}
