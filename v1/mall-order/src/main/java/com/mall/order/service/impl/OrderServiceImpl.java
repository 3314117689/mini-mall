package com.mall.order.service.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

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

	/** L8：单页上限，防止一次拉太多把接口和内存打满 */
	private static final int MAX_PAGE_SIZE = 100;

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
	 * 1. 校验商品 id 和数量；
	 * 2. 调用户服务确认用户存在（userId 来自 token，已可信）；
	 * 3. 调商品服务拿到价格；
	 * 4. 服务端用 单价 × 数量 算金额，客户端传的金额一律不信任；
	 * 5. 落库并返回订单 id。
	 */
	@Override
	public Long create(Long userId, OrderCreateDTO dto) {
		if (dto.getProductId() == null || dto.getNum() == null || dto.getNum() <= 0) {
			throw new BizException(ErrorCode.PARAM_ERROR);
		}
		log.info("下单, userId={}, productId={}, num={}", userId, dto.getProductId(), dto.getNum());

		UserDTO user = remoteService.getUser(userId);
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

	@Override
	public List<Order> page(Long userId, int page, int size) {
		checkPageArgs(userId, page, size);
		// offset 分页的代价就在这里：page 越深，数据库要跳过的行越多
		int offset = (page - 1) * size;
		log.info("分页查询订单, userId={}, page={}, size={}, offset={}", userId, page, size, offset);
		return orderMapper.selectPage(userId, offset, size);
	}

	@Override
	public List<Order> cursor(Long userId, LocalDateTime lastCreateTime, Long lastId, int size) {
		if (lastCreateTime == null || lastId == null) {
			// 第一页没有游标，等价于 offset=0
			return page(userId, 1, size);
		}
		checkPageArgs(userId, 1, size);
		log.info("游标查询订单, userId={}, lastCreateTime={}, lastId={}, size={}",
				userId, lastCreateTime, lastId, size);
		return orderMapper.selectCursor(userId, lastCreateTime, lastId, size);
	}

	/** 分页参数统一校验，page 从 1 开始 */
	private void checkPageArgs(Long userId, int page, int size) {
		if (userId == null || page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
			throw new BizException(ErrorCode.PARAM_ERROR);
		}
	}
}
