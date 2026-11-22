package com.mall.order.service.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import com.mall.common.auth.LoginUser;
import com.mall.common.auth.UserContext;
import com.mall.common.exception.BizException;
import com.mall.common.exception.ErrorCode;
import com.mall.order.config.ThreadPoolConfig;
import com.mall.order.dto.DeductStatusDTO;
import com.mall.order.dto.OrderCreateDTO;
import com.mall.order.dto.ProductDTO;
import com.mall.order.dto.UserDTO;
import com.mall.order.entity.Order;
import com.mall.order.id.SegmentIdGenerator;
import com.mall.order.mapper.OrderMapper;
import com.mall.order.remote.RemoteService;
import com.mall.order.service.OrderService;
import com.mall.order.service.StockCompensateService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class OrderServiceImpl implements OrderService {

	/** L8：单页上限，防止一次拉太多把接口和内存打满 */
	private static final int MAX_PAGE_SIZE = 100;

	/** L9-2：两次并行远程调用的总等待上限；Feign 读超时是 3 秒，这里 2 秒先兜底 */
	private static final long REMOTE_TIMEOUT_MS = 2000;

	@Autowired
	private OrderMapper orderMapper;

	/** 远程调用收口：每个调用是 Sentinel 资源，降级/拦截翻译都在里面（L4） */
	@Autowired
	private RemoteService remoteService;

	/** L9-1：在线请求线程池，有界队列 + AbortPolicy，队列满直接拒绝 */
	@Autowired
	@Qualifier(ThreadPoolConfig.REMOTE_EXECUTOR)
	private ThreadPoolTaskExecutor remoteExecutor;

	/** L9-3：号段发号器，订单号不再用数据库自增 id 对外暴露 */
	@Autowired
	private SegmentIdGenerator segmentIdGenerator;

	/** L9 补丁2：补偿任务落库 + 定时重试，取代原来只在内存里跑一次的补偿 */
	@Autowired
	private StockCompensateService compensateService;

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
	 * L9-1 改动：第 2、3 步交给 remoteExecutor，队列满时翻译成 4291。
	 * L9-2 改动：第 2、3 步拆成两个 CompletableFuture 并行，thenCombine 等齐，
	 * orTimeout 2 秒超时返回 2004（它只让等待方提前结束，不取消底层任务）。
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
		CompletableFuture<RemoteSnapshot> future;
		try {
			CompletableFuture<UserDTO> userFuture = CompletableFuture.supplyAsync(() -> {
				log.info("并行调用用户服务开始, thread={}", Thread.currentThread().getName());
				return withUserContext(loginUser, () -> remoteService.getUser(userId));
			}, remoteExecutor);
			CompletableFuture<ProductDTO> productFuture = CompletableFuture.supplyAsync(() -> {
				log.info("并行调用商品服务开始, thread={}", Thread.currentThread().getName());
				return withUserContext(loginUser, () -> remoteService.getProduct(dto.getProductId()));
			}, remoteExecutor);
			// 两个调用互不依赖：谁先回都行，等齐后再组装；超时只影响等待方
			future = userFuture.thenCombine(productFuture, RemoteSnapshot::new)
					.orTimeout(REMOTE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
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
			Throwable cause = unwrap(e.getCause());
			// 业务失败（用户/商品不存在、Sentinel 降级文案）原样放行
			if (cause instanceof BizException bizException) {
				throw bizException;
			}
			if (cause instanceof TimeoutException) {
				log.warn("下单远程调用超时, userId={}, productId={}, timeoutMs={}",
						userId, dto.getProductId(), REMOTE_TIMEOUT_MS);
				throw new BizException(ErrorCode.REMOTE_TIMEOUT);
			}
			log.error("下单远程调用失败, userId={}, productId={}", userId, dto.getProductId(), cause);
			throw new BizException(ErrorCode.SYSTEM_ERROR);
		}

		// L9 修复：先取号，orderNo 同时作为库存扣减的幂等键
		String orderNo = segmentIdGenerator.nextOrderNo();

		Integer remaining;
		try {
			remaining = remoteService.deductStock(orderNo, snapshot.product().getId(), dto.getNum());
			log.info("库存扣减成功, orderNo={}, productId={}, num={}, remaining={}",
					orderNo, snapshot.product().getId(), dto.getNum(), remaining);
		} catch (BizException e) {
			remaining = resolveDeductAfterFailure(orderNo, snapshot.product().getId(), dto.getNum(), e);
		}

		Order order = new Order();
		try {
			order.setOrderNo(orderNo);
			order.setUserId(snapshot.user().getId());
			order.setProductId(snapshot.product().getId());
			order.setNum(dto.getNum());
			// 金额只在服务端计算，客户端伪造不了
			order.setAmount(snapshot.product().getPrice().multiply(BigDecimal.valueOf(dto.getNum())));
			orderMapper.insert(order);
		} catch (RuntimeException e) {
			// 库存已经扣了但订单没落成：必须回补，落补偿任务，不能丢
			log.error("订单落库失败，开始回补库存, orderNo={}, productId={}, num={}",
					orderNo, snapshot.product().getId(), dto.getNum(), e);
			compensateService.submit(orderNo, snapshot.product().getId(), dto.getNum());
			throw new BizException(ErrorCode.SYSTEM_ERROR);
		}

		log.info("下单成功, orderId={}, orderNo={}, amount={}",
				order.getId(), order.getOrderNo(), order.getAmount());
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

	/**
	 * 扣减超时/不通时，按 orderNo 查一次状态：
	 * - 成功：product 实际已扣，继续落单；
	 * - 明确不存在：安全失败，按原错误返回；
	 * - 失败：按 product 记录的失败码返回；
	 * - 处理中/状态也查不到：落一条补偿任务兜底，再返回 2040。
	 */
	private Integer resolveDeductAfterFailure(String orderNo, Long productId, Integer num, BizException e) {
		int code = e.getCode();
		if (code != ErrorCode.PRODUCT_SERVICE_UNAVAILABLE.getCode()
				&& code != ErrorCode.REMOTE_TIMEOUT.getCode()) {
			throw e;
		}

		DeductStatusDTO status;
		try {
			status = remoteService.deductStatus(orderNo);
		} catch (BizException statusError) {
			log.warn("扣减失败且状态查询失败, orderNo={}, 扣减码={}", orderNo, code, statusError);
			// 到底扣没扣不知道：落补偿任务，product 恢复后 release 一次（没扣就是空操作）
			compensateService.submit(orderNo, productId, num);
			throw new BizException(ErrorCode.DEDUCT_PROCESSING.getCode(),
					ErrorCode.DEDUCT_PROCESSING.getMsg() + "，订单号：" + orderNo);
		}

		if (status == null || status.getStatus() == null || status.getStatus() == -1) {
			throw e;
		}
		if (status.getStatus() == 2) {
			log.warn("扣减超时但实际成功, orderNo={}, remaining={}", orderNo, status.getRemaining());
			return status.getRemaining() == null ? 0 : status.getRemaining();
		}
		if (status.getStatus() == 3) {
			int errorCode = status.getErrorCode() == null
					? ErrorCode.SYSTEM_ERROR.getCode() : status.getErrorCode();
			String errorMsg = status.getErrorMsg() == null
					? ErrorCode.SYSTEM_ERROR.getMsg() : status.getErrorMsg();
			throw new BizException(errorCode, errorMsg);
		}
		// 还在处理中：订单这次不落，但库存可能最终会扣成功，交给补偿任务收口
		compensateService.submit(orderNo, productId, num);
		throw new BizException(ErrorCode.DEDUCT_PROCESSING.getCode(),
				ErrorCode.DEDUCT_PROCESSING.getMsg() + "，订单号：" + orderNo);
	}

	@Override
	public boolean replayCompensate(String orderNo) {
		return compensateService.replay(orderNo);
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

	/** 把当前请求的身份带进异步线程；执行完必须清理，否则线程复用会串号 */
	private <T> T withUserContext(LoginUser loginUser, Supplier<T> action) {
		UserContext.set(loginUser);
		try {
			return action.get();
		} finally {
			UserContext.clear();
		}
	}

	/** CompletableFuture 会把异常包成 CompletionException，逐层剥到真正的业务异常 */
	private Throwable unwrap(Throwable throwable) {
		Throwable current = throwable;
		while ((current instanceof CompletionException || current instanceof ExecutionException)
				&& current.getCause() != null) {
			current = current.getCause();
		}
		return current;
	}
}
