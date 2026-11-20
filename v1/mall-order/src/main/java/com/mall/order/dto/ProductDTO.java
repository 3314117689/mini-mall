package com.mall.order.dto;

import java.math.BigDecimal;

import lombok.Data;

/**
 * 商品服务的返回结构：订单服务只关心下单需要的字段，
 * 不直接复用商品服务的 Product 实体（服务之间不共享实体类）。
 */
@Data
public class ProductDTO {

	private Long id;

	private String name;

	private BigDecimal price;

	private Integer stock;
}
