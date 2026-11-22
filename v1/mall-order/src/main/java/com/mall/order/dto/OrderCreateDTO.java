package com.mall.order.dto;

import lombok.Data;

/**
 * 下单请求参数：只接收客户端该传的字段。
 * 金额、订单状态、userId 都由服务端决定——userId 从 token 取（L6-5），客户端传了也不认。
 */
@Data
public class OrderCreateDTO {

	/** 下单商品 id */
	private Long productId;

	/** 购买数量 */
	private Integer num;
}
