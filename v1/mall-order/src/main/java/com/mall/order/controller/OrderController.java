package com.mall.order.controller;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

	/**
	 * L8：offset 分页，page 从 1 开始。
	 * GET /order/page?userId=1&page=1&size=10
	 */
	@GetMapping("/page")
	public Result<List<Order>> page(@RequestParam Long userId,
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "10") int size) {
		return Result.ok(orderService.page(userId, page, size));
	}

	/**
	 * L8：游标分页。第一次不传 lastCreateTime/lastId；
	 * 之后把上一页最后一条的 createTime、id 原样带回来。
	 * GET /order/cursor?userId=1&lastCreateTime=2026-11-30T03:28:30&lastId=100007&size=10
	 */
	@GetMapping("/cursor")
	public Result<List<Order>> cursor(@RequestParam Long userId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime lastCreateTime,
			@RequestParam(required = false) Long lastId,
			@RequestParam(defaultValue = "10") int size) {
		return Result.ok(orderService.cursor(userId, lastCreateTime, lastId, size));
	}
}
