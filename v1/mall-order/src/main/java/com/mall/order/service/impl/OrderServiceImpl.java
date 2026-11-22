package com.mall.order.service.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import com.mall.common.auth.LoginUser;
import com.mall.common.auth.UserContext;
import com.mall.common.exception.BizException;
import com.mall.common.exception.ErrorCode;
import com.mall.order.config.ThreadPoolConfig;
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

	/** L9-1：在线请求线程池，有界队列 + AbortPolicy，队列满直接拒绝 */
	@Autowired
	@Qualifier(ThreadPoolConfig.REMOTE_EXECUTOR)
	private ThreadPoolTaskExecutor remoteExecutor;

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
	 *
	 * L9-1 改动：第 2、3 步整体提交到 remoteExecutor，慢下游不再一直占用 Tomcat 线程；
	 * 队列满时由拒绝策略抛异常，这里翻译成 4291。并行编排在 L9-2 再做。
	 */
	@Override
	public Long create(Long userId, OrderCreateDTO dto) {
		if (dto.getProductId() == null || dto.getNum() == null || dto.getNum() <= 0) {
			throw new BizException(ErrorCode.PARAM_ERROR);
		}
		log.info("下单, userId={}, productId={}, num={}", userId, dto.getProductId(), dto.getNum());

		// FeignAuthInterceptor 从 UserContext 取身份头；换线程后 ThreadLocal 为空，
		// 必须把当前请求的身份快照带进任务，任务结束再 clear（线程会被复用）。
		LoginUser loginUser = UserContext.get();
		Future<RemoteSnapshot> future;
		try {
			future = remoteExecutor.submit(() -> {
				log.info("远程调用任务开始, thread={}, userId={}",
						Thread.currentThread().getName(), userId);
				UserContext.set(loginUser);
				try {
					UserDTO user = remoteService.getUser(userId);
					ProductDTO product = remoteService.getProduct(dto.getProductId());
					return new RemoteSnapshot(user, product);
				} finally {
					UserContext.clear();
				}
			});
		} catch (RejectedExecutionException e) {
			log.warn("线程池拒绝下单任务, userId={}, productId={}", userId, dto.getProductId());
			throw new BizException(ErrorCode.POOL_BUSY);
		}

		RemoteSnapshot snapshot;
		try {
			snapshot = future.get();
		} catch (InterruptedException e) {
			// 恢复中断标记，交给上层线程池决定是否退出
			Thread.currentThread().interrupt();
			throw new BizException(ErrorCode.SYSTEM_ERROR);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			// 业务失败（用户/商品不存在、Sentinel 降级文案）原样放行
			if (cause instanceof BizException bizException) {
				throw bizException;
			}
			log.error("下单远程调用失败, userId={}, productId={}", userId, dto.getProductId(), cause);
			throw new BizException(ErrorCode.SYSTEM_ERROR);
		}

		Order order = new Order();
		order.setUserId(snapshot.user().getId());
		order.setProductId(snapshot.product().getId());
		order.setNum(dto.getNum());
		// 金额只在服务端计算，客户端伪造不了
		order.setAmount(snapshot.product().getPrice().multiply(BigDecimal.valueOf(dto.getNum())));
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

	/** 线程池任务里两次远程调用的返回值，用 record 一次带回主线程 */
	private record RemoteSnapshot(UserDTO user, ProductDTO product) {
	}
}
