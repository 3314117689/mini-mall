package com.mall.order.dto;

import lombok.Data;

/**
 * 用户服务的返回结构：订单服务只需要确认用户存在，不接收密码等敏感字段。
 */
@Data
public class UserDTO {

	private Long id;

	private String username;

	private String phone;
}
