package com.mall.order.service.impl;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.mall.common.exception.BizException;
import com.mall.common.exception.ErrorCode;
import com.mall.order.dto.OrderCreateDTO;
import com.mall.order.dto.ProductDTO;
import com.mall.order.dto.UserDTO;
import com.mall.order.entity.Order;
import com.mall.order.mapper.OrderMapper;
import com.mall.order.remote.RemoteService;
import com.mall.order.service.OrderService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class OrderServiceImpl implements OrderService {

	@Autowired
	private OrderMapper orderMapper;

	/** 远程调用收口：每个调用是 Sentinel 资源，降级/拦截翻译都在里面（L4） */
	@Autowired
	private RemoteService remoteService;

	@Override
	public Order getById(Long id) {
		log.info("查询订单, id={}", id);
		Order order = orderMapper.selectById(id);
		if (order == null) {
			log.warn("订单不存在, id={}", id);
			throw new BizException(ErrorCode.ORDER_NOT_FOUND);
		}
		return order;
	}

	@Override
	public Long save(Order order) {
		log.info("新增订单, userId={}, productId={}, num={}",
				order.getUserId(), order.getProductId(), order.getNum());
		orderMapper.insert(order);
		// insert 执行完后自增主键已回填到 order.id，直接返回
		return order.getId();
	}

	/**
	 * 下单编排：
	 * 1. 调用户服务确认用户存在；
	 * 2. 调商品服务拿到价格；
	 * 3. 服务端用 单价 × 数量 算金额，客户端传的金额一律不信任；
	 * 4. 落库并返回订单 id。
	 */
	@Override
	public Long create(OrderCreateDTO dto) {
		if (dto.getUserId() == null || dto.getProductId() == null
				|| dto.getNum() == null || dto.getNum() <= 0) {
			throw new BizException(ErrorCode.PARAM_ERROR);
		}
		log.info("下单, userId={}, productId={}, num={}",
				dto.getUserId(), dto.getProductId(), dto.getNum());

		UserDTO user = remoteService.getUser(dto.getUserId());
		ProductDTO product = remoteService.getProduct(dto.getProductId());

		Order order = new Order();
		order.setUserId(user.getId());
		order.setProductId(product.getId());
		order.setNum(dto.getNum());
		// 金额只在服务端计算，客户端伪造不了
		order.setAmount(product.getPrice().multiply(BigDecimal.valueOf(dto.getNum())));
		orderMapper.insert(order);

		log.info("下单成功, orderId={}, amount={}", order.getId(), order.getAmount());
		return order.getId();
	}

	@Override
	public void update(Order order) {
		log.info("更新订单, id={}", order.getId());
		if (orderMapper.update(order) == 0) {
			log.warn("更新失败, 订单不存在, id={}", order.getId());
			throw new BizException(ErrorCode.ORDER_NOT_FOUND);
		}
	}

	@Override
	public void deleteById(Long id) {
		log.info("删除订单, id={}", id);
		if (orderMapper.deleteById(id) == 0) {
			log.warn("删除失败, 订单不存在, id={}", id);
			throw new BizException(ErrorCode.ORDER_NOT_FOUND);
		}
	}
}
