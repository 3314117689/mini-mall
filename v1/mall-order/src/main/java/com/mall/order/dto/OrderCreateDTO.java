package com.mall.order.dto;

import lombok.Data;

/**
 * 下单请求参数：只接收客户端该传的字段。
 * 金额、订单状态等由服务端决定，不能让客户端传。
 */
@Data
public class OrderCreateDTO {

	/** 下单用户 id */
	private Long userId;

	/** 下单商品 id */
	private Long productId;

	/** 购买数量 */
	private Integer num;
}
