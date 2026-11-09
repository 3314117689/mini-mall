package com.mall.product.controller;

import org.springframework.beans.factory.annotation.Autowired;
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

	@GetMapping("/{id}")
	public Result<Product> getById(@PathVariable Long id) {
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
