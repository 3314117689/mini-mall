package com.mall.product.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 商品实体：与 db_product.product 表一一对应。
 * 注意：金额一律用 BigDecimal，不用 double（浮点数有精度丢失，算钱会出错）。
 * L10-5：name/price/stock 是新增与更新的必填项，约束直接写在实体上，Controller 用 @Valid 触发。
 * id 不加 @NotNull——新增时由数据库自增，更新时缺 id 会因"受影响 0 行"落到 1003 商品不存在。
 */
@Data
public class Product {
	private Long id;

	@NotBlank(message = "不能为空")
	@Size(max = 100, message = "长度不能超过 100 个字符")
	private String name;

	@NotNull(message = "不能为空")
	@DecimalMin(value = "0.00", inclusive = false, message = "必须大于 0")
	private BigDecimal price;

	@NotNull(message = "不能为空")
	@Min(value = 0, message = "不能小于 0")
	private Integer stock;

	private LocalDateTime createTime;
}
