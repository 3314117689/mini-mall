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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mall.common.result.Result;
import com.mall.product.config.OpenApiConfig;
import com.mall.product.dto.DeductStatusDTO;
import com.mall.product.entity.Product;
import com.mall.product.service.ProductService;
import com.mall.product.service.StockService;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Tag(name = "商品服务", description = "商品增删改查与库存扣减/回补；查询公开，写操作需要 Bearer token")
@RestController
@RequestMapping("/product")
public class ProductController {

	@Autowired
	private ProductService productService;

	@Autowired
	private StockService stockService;

	/**
	 * L9 测试开关：默认 0 不生效；验收时用启动参数
	 * --mall.product.slow-ms=2000 打开，模拟慢下游，测完重启即恢复。
	 */
	@Value("${mall.product.slow-ms:0}")
	private long slowMs;

	/**
	 * L9 修复测试开关：模拟"扣减已提交但响应很慢"，用来验证 order 超时后查状态。
	 * 默认 0，验收时用 --mall.product.deduct-slow-ms=4000 打开。
	 */
	@Value("${mall.product.deduct-slow-ms:0}")
	private long deductSlowMs;

	@Operation(summary = "按 id 查询商品", description = "公开接口，不需要 token")
	@GetMapping("/{id}")
	public Result<Product> getById(@PathVariable Long id) throws InterruptedException {
		if (slowMs > 0) {
			Thread.sleep(slowMs);
		}
		return Result.ok(productService.getById(id));
	}

	/** 新增：参数从请求体 JSON 来，用 @RequestBody 接收（和查询的 @PathVariable 不同） */
	@Operation(summary = "新增商品")
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	@PostMapping
	public Result<Long> save(@Valid @RequestBody Product product) {
		return Result.ok(productService.save(product));
	}

	@Operation(summary = "更新商品")
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	@PutMapping
	public Result<Void> update(@Valid @RequestBody Product product) {
		productService.update(product);
		return Result.ok();
	}

	@Operation(summary = "删除商品")
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	@DeleteMapping("/{id}")
	public Result<Void> deleteById(@PathVariable Long id) {
		productService.deleteById(id);
		return Result.ok();
	}

	/**
	 * L9-4：扣减库存，返回剩余库存。内部接口，供 order 服务 Feign 调用。
	 * L9 修复：带 orderNo 做幂等键，同一订单重试不会重复扣。
	 * L10-5：@Hidden 不生成到 Swagger 文档；参数约束仍生效，坏参数在进 Service 前返回 1000。
	 * POST /product/stock/deduct?orderNo=ORD...&productId=1&num=1
	 */
	@Hidden
	@Operation(summary = "扣减库存（内部接口）", description = "order 服务 Feign 调用，orderNo 是幂等键")
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	@PostMapping("/stock/deduct")
	public Result<Integer> deductStock(@RequestParam @NotBlank(message = "不能为空") String orderNo,
			@RequestParam @NotNull(message = "不能为空") @Min(value = 1, message = "不能小于 1") Long productId,
			@RequestParam @NotNull(message = "不能为空") @Min(value = 1, message = "不能小于 1") Integer num)
			throws InterruptedException {
		Integer remaining = stockService.deduct(orderNo, productId, num);
		if (deductSlowMs > 0) {
			Thread.sleep(deductSlowMs);
		}
		return Result.ok(remaining);
	}

	/** L9-4：回补库存，下单失败时由 order 服务调用；L9 修复后同样按 orderNo 幂等；L10-5 文档隐藏 */
	@Hidden
	@Operation(summary = "回补库存（内部接口）", description = "order 服务 Feign 调用，orderNo 是幂等键")
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	@PostMapping("/stock/release")
	public Result<Void> releaseStock(@RequestParam @NotBlank(message = "不能为空") String orderNo,
			@RequestParam @NotNull(message = "不能为空") @Min(value = 1, message = "不能小于 1") Long productId,
			@RequestParam @NotNull(message = "不能为空") @Min(value = 1, message = "不能小于 1") Integer num) {
		stockService.release(orderNo, productId, num);
		return Result.ok();
	}

	/** L9 修复：按订单号查询扣减状态，order 超时后用它确认到底扣没扣；L10-5 文档隐藏 */
	@Hidden
	@Operation(summary = "查询扣减状态（内部接口）", description = "order 服务 Feign 调用")
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	@GetMapping("/stock/deduct/status")
	public Result<DeductStatusDTO> deductStatus(
			@RequestParam @NotBlank(message = "不能为空") String orderNo) {
		return Result.ok(stockService.status(orderNo));
	}
}
