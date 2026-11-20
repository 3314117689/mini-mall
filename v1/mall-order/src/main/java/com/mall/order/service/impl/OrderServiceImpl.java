package com.mall.order.service.impl;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.mall.common.exception.BizException;
import com.mall.common.exception.ErrorCode;
import com.mall.common.result.Result;
import com.mall.order.client.ProductClient;
import com.mall.order.client.UserClient;
import com.mall.order.dto.OrderCreateDTO;
import com.mall.order.dto.ProductDTO;
import com.mall.order.dto.UserDTO;
import com.mall.order.entity.Order;
import com.mall.order.mapper.OrderMapper;
import com.mall.order.service.OrderService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class OrderServiceImpl implements OrderService {

	@Autowired
	private OrderMapper orderMapper;

	@Autowired
	private UserClient userClient;

	@Autowired
	private ProductClient productClient;

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

		UserDTO user = getUser(dto.getUserId());
		ProductDTO product = getProduct(dto.getProductId());

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

	/** 调用用户服务：HTTP 失败和业务失败分开处理 */
	private UserDTO getUser(Long userId) {
		Result<UserDTO> result;
		try {
			// 一行发起远程调用：服务名 -> 实例地址、拼 URL、反序列化都由框架完成
			result = userClient.getById(userId);
		} catch (Exception e) {
			// 连不上、超时、无可用实例、响应解析失败：下游不可用，给出可重试的提示
			log.error("调用用户服务失败, userId={}", userId, e);
			throw new BizException(ErrorCode.REMOTE_CALL_FAILED);
		}
		if (result == null || result.getCode() != 0 || result.getData() == null) {
			log.warn("用户不存在, userId={}, result={}", userId, result);
			throw new BizException(ErrorCode.USER_NOT_FOUND);
		}
		return result.getData();
	}

	/** 调用商品服务：拿商品 id、价格 */
	private ProductDTO getProduct(Long productId) {
		Result<ProductDTO> result;
		try {
			// 和 getUser 同一套：Feign 接口方法即远程调用
			result = productClient.getById(productId);
		} catch (Exception e) {
			log.error("调用商品服务失败, productId={}", productId, e);
			throw new BizException(ErrorCode.REMOTE_CALL_FAILED);
		}
		if (result == null || result.getCode() != 0 || result.getData() == null) {
			log.warn("商品不存在, productId={}, result={}", productId, result);
			throw new BizException(ErrorCode.PRODUCT_NOT_FOUND);
		}
		return result.getData();
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
