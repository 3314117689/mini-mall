package com.mall.order.service;

import com.mall.order.dto.OrderCreateDTO;
import com.mall.order.entity.Order;

public interface OrderService {
	Order getById(Long id);

	/** 新增订单，返回数据库生成的 id */
	Long save(Order order);

	/**
	 * 下单：先调用用户服务、商品服务校验并取价格，
	 * 由服务端计算金额后落库，返回订单 id。
	 */
	Long create(OrderCreateDTO dto);

	/** 更新订单，订单不存在时抛业务异常 */
	void update(Order order);

	/** 删除订单，订单不存在时抛业务异常 */
	void deleteById(Long id);
}
