package com.mall.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

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

	/** L9-5：扣库存，参数走 query，对应 ProductController 的 @RequestParam */
	@PostMapping("/product/stock/deduct")
	Result<Integer> deductStock(@RequestParam("productId") Long productId, @RequestParam("num") Integer num);

	/** L9-5：回补库存，订单落库失败时补偿 */
	@PostMapping("/product/stock/release")
	Result<Void> releaseStock(@RequestParam("productId") Long productId, @RequestParam("num") Integer num);
}
