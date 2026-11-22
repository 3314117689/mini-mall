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
import com.mall.order.config.OpenApiConfig;
import com.mall.order.dto.OrderCreateDTO;
import com.mall.order.entity.Order;
import com.mall.order.service.OrderService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Tag(name = "订单服务", description = "下单与订单查询，全部接口需要 Bearer token")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/order")
public class OrderController {

	/** 单页上限，方法参数注解与 Service 共用同一个值，避免两边改漏 */
	private static final int MAX_PAGE_SIZE = 100;

	@Autowired
	private OrderService orderService;

	@Operation(summary = "按 id 查询订单")
	@GetMapping("/{id}")
	public Result<Order> getById(@PathVariable Long id) {
		return Result.ok(orderService.getById(id));
	}

	/** 新增：参数从请求体 JSON 来，用 @RequestBody 接收（和查询的 @PathVariable 不同） */
	@Operation(summary = "新增订单（原始插入，不做下单编排）")
	@PostMapping
	public Result<Long> save(@RequestBody Order order) {
		return Result.ok(orderService.save(order));
	}

	/**
	 * 下单：只接收 productId/num，userId 由 @CurrentUser 从 token 解析（L6-5）。
	 * 这样客户端改不了"替谁下单"。
	 */
	@Operation(summary = "下单", description = "userId 从 token 解析，金额由服务端计算")
	@PostMapping("/create")
	public Result<Long> create(@Valid @RequestBody OrderCreateDTO dto, @CurrentUser Long userId) {
		return Result.ok(orderService.create(userId, dto));
	}

	@Operation(summary = "更新订单")
	@PutMapping
	public Result<Void> update(@RequestBody Order order) {
		orderService.update(order);
		return Result.ok();
	}

	@Operation(summary = "删除订单")
	@DeleteMapping("/{id}")
	public Result<Void> deleteById(@PathVariable Long id) {
		orderService.deleteById(id);
		return Result.ok();
	}

	/**
	 * L8：offset 分页，page 从 1 开始。
	 * GET /order/page?userId=1&page=1&size=10
	 */
	@Operation(summary = "订单分页（offset）", description = "page 从 1 开始，size 上限 100")
	@GetMapping("/page")
	public Result<List<Order>> page(
			@RequestParam @NotNull(message = "不能为空") @Min(value = 1, message = "不能小于 1") Long userId,
			@RequestParam(defaultValue = "1") @Min(value = 1, message = "不能小于 1") int page,
			@RequestParam(defaultValue = "10") @Min(value = 1, message = "不能小于 1")
			@Max(value = MAX_PAGE_SIZE, message = "不能大于 100") int size) {
		return Result.ok(orderService.page(userId, page, size));
	}

	/**
	 * L8：游标分页。第一次不传 lastCreateTime/lastId；
	 * 之后把上一页最后一条的 createTime、id 原样带回来。
	 * GET /order/cursor?userId=1&lastCreateTime=2026-11-30T03:28:30&lastId=100007&size=10
	 */
	@Operation(summary = "订单游标分页", description = "第一页不传 lastCreateTime/lastId")
	@GetMapping("/cursor")
	public Result<List<Order>> cursor(
			@RequestParam @NotNull(message = "不能为空") @Min(value = 1, message = "不能小于 1") Long userId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime lastCreateTime,
			@RequestParam(required = false) @Min(value = 1, message = "不能小于 1") Long lastId,
			@RequestParam(defaultValue = "10") @Min(value = 1, message = "不能小于 1")
			@Max(value = MAX_PAGE_SIZE, message = "不能大于 100") int size) {
		return Result.ok(orderService.cursor(userId, lastCreateTime, lastId, size));
	}

	/**
	 * L9 补丁2：人工重放库存补偿任务（MANUAL → PENDING 并立即执行一次）。
	 * POST /order/compensate/replay?orderNo=ORD...
	 */
	@Operation(summary = "人工重放库存补偿任务", description = "MANUAL → PENDING 并立即执行一次")
	@PostMapping("/compensate/replay")
	public Result<Boolean> replayCompensate(
			@RequestParam @NotBlank(message = "不能为空") String orderNo) {
		return Result.ok(orderService.replayCompensate(orderNo));
	}
}
