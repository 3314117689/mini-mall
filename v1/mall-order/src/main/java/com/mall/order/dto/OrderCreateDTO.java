package com.mall.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 下单请求参数：只接收客户端该传的字段。
 * 金额、订单状态、userId 都由服务端决定——userId 从 token 取（L6-5），客户端传了也不认。
 * L10-6：productId/num 的必填与下限由注解声明，Controller 的 @Valid 触发。
 */
@Data
public class OrderCreateDTO {

	/** 下单商品 id */
	@NotNull(message = "不能为空")
	@Min(value = 1, message = "不能小于 1")
	private Long productId;

	/** 购买数量 */
	@NotNull(message = "不能为空")
	@Min(value = 1, message = "不能小于 1")
	private Integer num;
}
