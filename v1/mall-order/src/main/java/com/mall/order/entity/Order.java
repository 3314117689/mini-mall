package com.mall.order.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Data;

/**
 * 订单实体：与 db_order.orders 表一一对应。
 * 注意：
 * 1. ID 类字段用 Long，只有金额用 BigDecimal；
 * 2. Java 字段必须驼峰，数据库下划线靠 map-underscore-to-camel-case 自动映射。
 */
@Data
public class Order {
	private Long id;

	private Long userId;

	private Long productId;

	private Integer num;

	private BigDecimal amount;

	private LocalDateTime createTime;
}
