package com.mall.product.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mall.common.result.Result;
import com.mall.product.entity.Product;
import com.mall.product.service.ProductService;

@RestController
@RequestMapping("/product")
public class ProductController {

	@Autowired
	private ProductService productService;

	/**
	 * L9 测试开关：默认 0 不生效；验收时用启动参数
	 * --mall.product.slow-ms=2000 打开，模拟慢下游，测完重启即恢复。
	 */
	@Value("${mall.product.slow-ms:0}")
	private long slowMs;

	@GetMapping("/{id}")
	public Result<Product> getById(@PathVariable Long id) throws InterruptedException {
		if (slowMs > 0) {
			Thread.sleep(slowMs);
		}
		return Result.ok(productService.getById(id));
	}

	/** 新增：参数从请求体 JSON 来，用 @RequestBody 接收（和查询的 @PathVariable 不同） */
	@PostMapping
	public Result<Long> save(@RequestBody Product product) {
		return Result.ok(productService.save(product));
	}

	@PutMapping
	public Result<Void> update(@RequestBody Product product) {
		productService.update(product);
		return Result.ok();
	}

	@DeleteMapping("/{id}")
	public Result<Void> deleteById(@PathVariable Long id) {
		productService.deleteById(id);
		return Result.ok();
	}
}
