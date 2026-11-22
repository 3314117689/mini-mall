package com.mall.order.remote;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.mall.common.exception.BizException;
import com.mall.common.exception.ErrorCode;
import com.mall.common.result.Result;
import com.mall.order.client.ProductClient;
import com.mall.order.client.UserClient;
import com.mall.order.dto.DeductStatusDTO;
import com.mall.order.dto.ProductDTO;
import com.mall.order.dto.UserDTO;

import lombok.extern.slf4j.Slf4j;

/**
 * 远程调用收口（L4）：所有跨服务调用集中在这一个类里，每个调用是一个 Sentinel 资源。
 *
 * 为什么独立成类：@SentinelResource 只对"从外面调进来"的方法生效（框架的检查
 * 发生在方法调用的统一入口，同类内部 this.xxx() 直接调用绕过入口）。原来
 * getUser/getProduct 是 OrderServiceImpl 的私有方法、由 create() 内部调用，
 * 注解不会生效；搬到这里通过注入调用，检查才走得到。
 */
@Slf4j
@Component
public class RemoteService {

    @Autowired
    private UserClient userClient;

    @Autowired
    private ProductClient productClient;

    /**
     * 调用户服务。异常处理约定：
     * - 业务失败（用户不存在 1001）：BizException 原样上抛，交给 fallback 放行，不能被降级文案掩盖；
     * - 调用不通（连接失败/超时）：非 BizException 上抛，由 fallback 翻译成 2002 降级文案；
     * - 被规则拦截（BlockException，比如给 getUser 配了 QPS 规则）：由 blockHandler 处理。
     */
    @SentinelResource(value = "getUser", fallback = "getUserFallback", blockHandler = "getUserBlocked")
    public UserDTO getUser(Long userId) {
        Result<UserDTO> result = userClient.getById(userId);
        if (result == null || result.getCode() != 0 || result.getData() == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        return result.getData();
    }

    /**
     * getUser 的降级方法：本方法抛出非 BlockException 的异常时进这里。
     * 多出来的 Throwable 参数由框架传入"到底出了什么事"，按类型分诊：
     * BizException = 业务失败，原样放行；其余 = 服务真的不可用，翻译成 2002。
     */
    public UserDTO getUserFallback(Long userId, Throwable t) {
        if (t instanceof BizException) {
            throw (BizException) t;
        }
        log.error("用户服务降级, userId={}, 原因: {}", userId, t.getMessage());
        throw new BizException(ErrorCode.USER_SERVICE_UNAVAILABLE);
    }

    /** getUser 的拦截处理方法：规则触发（BlockException）时进这里，统一 4290 */
    public UserDTO getUserBlocked(Long userId, BlockException e) {
        log.warn("用户服务调用被 Sentinel 拦截, userId={}", userId);
        throw new BizException(ErrorCode.FLOW_LIMITED);
    }

    /** 调商品服务，约定同 getUser：业务失败 1002 上抛，调用不通交给 fallback，规则拦截交给 blockHandler */
    @SentinelResource(value = "getProduct", fallback = "getProductFallback", blockHandler = "getProductBlocked")
    public ProductDTO getProduct(Long productId) {
        Result<ProductDTO> result = productClient.getById(productId);
        if (result == null || result.getCode() != 0 || result.getData() == null) {
            throw new BizException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        return result.getData();
    }

    /** getProduct 的降级方法：业务失败原样放行，服务不可用翻译成 2003 */
    public ProductDTO getProductFallback(Long productId, Throwable t) {
        if (t instanceof BizException) {
            throw (BizException) t;
        }
        log.error("商品服务降级, productId={}, 原因: {}", productId, t.getMessage());
        throw new BizException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
    }

    /** getProduct 的拦截处理方法：规则触发时统一 4290 */
    public ProductDTO getProductBlocked(Long productId, BlockException e) {
        log.warn("商品服务调用被 Sentinel 拦截, productId={}", productId);
        throw new BizException(ErrorCode.FLOW_LIMITED);
    }

    /**
     * L9-5：扣库存。商品服务返回 1006（库存不足）时要原样保留业务码，
     * 不能像"调用不通"那样统一翻成 2003，否则前端分不清是没库存还是服务挂了。
     * L9 修复：orderNo 做幂等键，超时后可以用同一个 orderNo 重试或查状态。
     */
    @SentinelResource(value = "deductStock", fallback = "deductStockFallback", blockHandler = "deductStockBlocked")
    public Integer deductStock(String orderNo, Long productId, Integer num) {
        Result<Integer> result = productClient.deductStock(orderNo, productId, num);
        if (result == null) {
            throw new BizException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
        }
        if (result.getCode() != 0) {
            throw new BizException(result.getCode(), result.getMsg());
        }
        return result.getData();
    }

    public Integer deductStockFallback(String orderNo, Long productId, Integer num, Throwable t) {
        if (t instanceof BizException) {
            throw (BizException) t;
        }
        log.error("扣减库存降级, orderNo={}, productId={}, num={}, 原因: {}",
                orderNo, productId, num, t.getMessage());
        throw new BizException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
    }

    public Integer deductStockBlocked(String orderNo, Long productId, Integer num, BlockException e) {
        log.warn("扣减库存被 Sentinel 拦截, orderNo={}, productId={}, num={}", orderNo, productId, num);
        throw new BizException(ErrorCode.FLOW_LIMITED);
    }

    /** L9-5：回补库存，语义同扣减；L9 修复后按 orderNo 幂等 */
    @SentinelResource(value = "releaseStock", fallback = "releaseStockFallback", blockHandler = "releaseStockBlocked")
    public void releaseStock(String orderNo, Long productId, Integer num) {
        Result<Void> result = productClient.releaseStock(orderNo, productId, num);
        if (result == null) {
            throw new BizException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
        }
        if (result.getCode() != 0) {
            throw new BizException(result.getCode(), result.getMsg());
        }
    }

    public void releaseStockFallback(String orderNo, Long productId, Integer num, Throwable t) {
        if (t instanceof BizException) {
            throw (BizException) t;
        }
        log.error("回补库存降级, orderNo={}, productId={}, num={}, 原因: {}",
                orderNo, productId, num, t.getMessage());
        throw new BizException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
    }

    public void releaseStockBlocked(String orderNo, Long productId, Integer num, BlockException e) {
        log.warn("回补库存被 Sentinel 拦截, orderNo={}, productId={}, num={}", orderNo, productId, num);
        throw new BizException(ErrorCode.FLOW_LIMITED);
    }

    /** L9 修复：超时后查扣减状态，语义同扣减（调用不通 → 2003） */
    @SentinelResource(value = "deductStatus", fallback = "deductStatusFallback", blockHandler = "deductStatusBlocked")
    public DeductStatusDTO deductStatus(String orderNo) {
        Result<DeductStatusDTO> result = productClient.deductStatus(orderNo);
        if (result == null) {
            throw new BizException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
        }
        if (result.getCode() != 0) {
            throw new BizException(result.getCode(), result.getMsg());
        }
        return result.getData();
    }

    public DeductStatusDTO deductStatusFallback(String orderNo, Throwable t) {
        if (t instanceof BizException) {
            throw (BizException) t;
        }
        log.error("查询扣减状态降级, orderNo={}, 原因: {}", orderNo, t.getMessage());
        throw new BizException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
    }

    public DeductStatusDTO deductStatusBlocked(String orderNo, BlockException e) {
        log.warn("查询扣减状态被 Sentinel 拦截, orderNo={}", orderNo);
        throw new BizException(ErrorCode.FLOW_LIMITED);
    }
}
