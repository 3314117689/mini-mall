package com.mall.product.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Data;

/**
 * 商品实体：与 db_product.product 表一一对应。
 * 注意：金额一律用 BigDecimal，不用 double（浮点数有精度丢失，算钱会出错）。
 */
@Data
public class Product {
	private Long id;

	private String name;

	private BigDecimal price;

	private Integer stock;

	private LocalDateTime createTime;
}
