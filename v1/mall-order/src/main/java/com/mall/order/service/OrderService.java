package com.mall.order.service;

import java.time.LocalDateTime;
import java.util.List;

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

	/** L8：offset 分页，page 从 1 开始，深翻页用它暴露问题 */
	List<Order> page(Long userId, int page, int size);

	/** L8：游标分页，lastCreateTime/lastId 传空表示第一页 */
	List<Order> cursor(Long userId, LocalDateTime lastCreateTime, Long lastId, int size);

	/** L9 补丁2：人工重放 MANUAL 状态的库存补偿任务，false 表示没有可重放任务 */
	boolean replayCompensate(String orderNo);
}
