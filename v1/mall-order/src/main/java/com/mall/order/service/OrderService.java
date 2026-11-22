package com.mall.order.service;

import com.mall.order.dto.OrderCreateDTO;
import com.mall.order.entity.Order;

public interface OrderService {
	Order getById(Long id);

	/** 新增订单，返回数据库生成的 id */
	Long save(Order order);

	/**
	 * 下单：userId 来自登录 token，商品和价格来自商品服务，
	 * 由服务端计算金额后落库，返回订单 id。
	 */
	Long create(Long userId, OrderCreateDTO dto);

	/** 更新订单，订单不存在时抛业务异常 */
	void update(Order order);

	/** 删除订单，订单不存在时抛业务异常 */
	void deleteById(Long id);
}
