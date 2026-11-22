package com.mall.order.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mall.common.auth.CurrentUser;
import com.mall.common.result.Result;
import com.mall.order.dto.OrderCreateDTO;
import com.mall.order.entity.Order;
import com.mall.order.service.OrderService;

@RestController
@RequestMapping("/order")
public class OrderController {

	@Autowired
	private OrderService orderService;

	@GetMapping("/{id}")
	public Result<Order> getById(@PathVariable Long id) {
		return Result.ok(orderService.getById(id));
	}

	/** 新增：参数从请求体 JSON 来，用 @RequestBody 接收（和查询的 @PathVariable 不同） */
	@PostMapping
	public Result<Long> save(@RequestBody Order order) {
		return Result.ok(orderService.save(order));
	}

	/**
	 * 下单：只接收 productId/num，userId 由 @CurrentUser 从 token 解析（L6-5）。
	 * 这样客户端改不了"替谁下单"。
	 */
	@PostMapping("/create")
	public Result<Long> create(@RequestBody OrderCreateDTO dto, @CurrentUser Long userId) {
		return Result.ok(orderService.create(userId, dto));
	}

	@PutMapping
	public Result<Void> update(@RequestBody Order order) {
		orderService.update(order);
		return Result.ok();
	}

	@DeleteMapping("/{id}")
	public Result<Void> deleteById(@PathVariable Long id) {
		orderService.deleteById(id);
		return Result.ok();
	}
}
