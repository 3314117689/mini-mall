package com.mall.product.service.impl;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.mall.common.exception.BizException;
import com.mall.common.exception.ErrorCode;
import com.mall.product.dto.DeductStatusDTO;
import com.mall.product.entity.Product;
import com.mall.product.entity.StockDeductLog;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.mapper.StockDeductLogMapper;
import com.mall.product.service.StockService;

import lombok.extern.slf4j.Slf4j;

/**
 * 库存扣减（L9-4）：Redis 预扣挡并发，数据库条件更新兜底。
 *
 * L9 修复：orderNo 做幂等键，stock_deduct_log 记状态。
 * - 同一 orderNo 最多扣一次；
 * - 超时后调用方可以查状态，不用猜；
 * - 回补也是幂等的，重复补偿不会多加库存。
 */
@Slf4j
@Service
public class StockServiceImpl implements StockService {

    /** 库存 key 统一带项目前缀，和别的项目共用 Redis 时不冲突 */
    private static final String KEY_PREFIX = "mall:stock:";

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private StockDeductLogMapper deductLogMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private DefaultRedisScript<Long> stockDeductScript;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Override
    public int deduct(String orderNo, Long productId, Integer num) {
        checkArgs(orderNo, productId, num);

        StockDeductLog deductLog = prepareLog(orderNo, productId, num);
        if (deductLog == null) {
            throw new BizException(ErrorCode.SYSTEM_ERROR);
        }
        if (deductLog.getStatus() == StockDeductLog.SUCCESS) {
            log.info("扣减幂等命中, orderNo={}, remaining={}", orderNo, deductLog.getRemaining());
            return deductLog.getRemaining() == null ? 0 : deductLog.getRemaining();
        }
        if (deductLog.getStatus() == StockDeductLog.RELEASED) {
            throw new BizException(ErrorCode.PRODUCT_STOCK_NOT_ENOUGH);
        }
        if (deductLog.getStatus() == StockDeductLog.FAILED) {
            throw failedException(deductLog);
        }

        // 认领执行权：INIT 或超过 60 秒的 PROCESSING 才能被认领
        if (deductLogMapper.claimProcessing(orderNo) == 0) {
            return resolveProcessing(orderNo);
        }

        String key = KEY_PREFIX + productId;

        // 1) Redis 预扣：正常流量在这里就被挡住，不碰数据库
        Long luaResult = null;
        try {
            ensureStockLoaded(productId, key);
            luaResult = redisTemplate.execute(stockDeductScript, List.of(key), String.valueOf(num));
            if (luaResult != null && luaResult == -2L) {
                ensureStockLoaded(productId, key);
                luaResult = redisTemplate.execute(stockDeductScript, List.of(key), String.valueOf(num));
            }
        } catch (BizException e) {
            deductLogMapper.markFailed(orderNo, e.getCode(), e.getMessage());
            throw e;
        } catch (Exception e) {
            // Redis 不可用不阻断扣减：退回数据库条件更新，仍然不会超卖
            log.warn("Redis 库存不可用，退化为 DB 条件扣减, orderNo={}, productId={}", orderNo, productId, e);
            luaResult = null;
        }

        if (luaResult != null && luaResult == -1L) {
            deductLogMapper.markFailed(orderNo, ErrorCode.PRODUCT_STOCK_NOT_ENOUGH.getCode(),
                    ErrorCode.PRODUCT_STOCK_NOT_ENOUGH.getMsg());
            throw new BizException(ErrorCode.PRODUCT_STOCK_NOT_ENOUGH);
        }

        // 2) 数据库条件扣减 + 流水成功状态放在同一个事务里
        Integer remaining = applyDbDeduct(orderNo, productId, num);
        if (remaining == null) {
            // DB 没扣成：Redis 如果扣了要加回去
            if (luaResult != null && luaResult >= 0) {
                compensateRedis(key, num);
            }
            StockDeductLog latest = deductLogMapper.selectByOrderNo(orderNo);
            if (latest != null && latest.getStatus() == StockDeductLog.SUCCESS) {
                return latest.getRemaining() == null ? 0 : latest.getRemaining();
            }
            Product product = productMapper.selectById(productId);
            int code = product == null ? ErrorCode.PRODUCT_NOT_FOUND.getCode()
                    : ErrorCode.PRODUCT_STOCK_NOT_ENOUGH.getCode();
            String msg = product == null ? ErrorCode.PRODUCT_NOT_FOUND.getMsg()
                    : ErrorCode.PRODUCT_STOCK_NOT_ENOUGH.getMsg();
            deductLogMapper.markFailed(orderNo, code, msg);
            throw new BizException(code, msg);
        }

        evictProductCache(productId);
        return remaining;
    }

    @Override
    public void release(String orderNo, Long productId, Integer num) {
        checkArgs(orderNo, productId, num);

        StockDeductLog deductLog = deductLogMapper.selectByOrderNo(orderNo);
        if (deductLog == null) {
            log.info("回补跳过：没有扣减记录, orderNo={}", orderNo);
            return;
        }
        if (deductLog.getStatus() == StockDeductLog.RELEASED) {
            log.info("回补幂等命中：已经回补过, orderNo={}", orderNo);
            return;
        }
        if (deductLog.getStatus() != StockDeductLog.SUCCESS) {
            if (deductLog.getStatus() == StockDeductLog.PROCESSING) {
                throw new BizException(ErrorCode.DEDUCT_PROCESSING);
            }
            log.info("回补跳过：扣减未成功, orderNo={}, status={}", orderNo, deductLog.getStatus());
            return;
        }

        // 状态 2→4 与库存回补在同一个事务里，避免"状态改了库存没加"
        Boolean released = transactionTemplate.execute(tx -> {
            if (deductLogMapper.claimRelease(orderNo) == 0) {
                return false;
            }
            if (productMapper.releaseStock(productId, num) == 0) {
                throw new BizException(ErrorCode.PRODUCT_NOT_FOUND);
            }
            return true;
        });

        if (!Boolean.TRUE.equals(released)) {
            StockDeductLog latest = deductLogMapper.selectByOrderNo(orderNo);
            if (latest != null && latest.getStatus() == StockDeductLog.RELEASED) {
                return;
            }
            throw new BizException(ErrorCode.DEDUCT_PROCESSING);
        }

        String key = KEY_PREFIX + productId;
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
                redisTemplate.opsForValue().increment(key, num);
            } else {
                Product product = productMapper.selectById(productId);
                if (product != null) {
                    redisTemplate.opsForValue().set(key, String.valueOf(product.getStock()));
                }
            }
        } catch (Exception e) {
            // Redis 回补失败只记日志；下一次缓存缺失会从数据库重新加载
            log.warn("回补 Redis 库存失败, orderNo={}, productId={}", orderNo, productId, e);
        }

        evictProductCache(productId);
        log.info("库存回补成功, orderNo={}, productId={}, num={}", orderNo, productId, num);
    }

    @Override
    public DeductStatusDTO status(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        StockDeductLog deductLog = deductLogMapper.selectByOrderNo(orderNo);
        if (deductLog == null) {
            return new DeductStatusDTO(orderNo, -1, null, null, null);
        }
        return new DeductStatusDTO(orderNo, deductLog.getStatus(), deductLog.getRemaining(),
                deductLog.getErrorCode(), deductLog.getErrorMsg());
    }

    /** 首次请求插 INIT；唯一键冲突说明并发请求已经插过，直接重查 */
    private StockDeductLog prepareLog(String orderNo, Long productId, Integer num) {
        StockDeductLog existing = deductLogMapper.selectByOrderNo(orderNo);
        if (existing != null) {
            return existing;
        }
        try {
            deductLogMapper.insertInit(orderNo, productId, num);
        } catch (DuplicateKeyException e) {
            log.debug("扣减流水并发插入, orderNo={}", orderNo);
        }
        return deductLogMapper.selectByOrderNo(orderNo);
    }

    /** 没抢到执行权：可能别人成功了、正在处理、或已经失败 */
    private int resolveProcessing(String orderNo) {
        StockDeductLog latest = deductLogMapper.selectByOrderNo(orderNo);
        if (latest == null) {
            throw new BizException(ErrorCode.SYSTEM_ERROR);
        }
        if (latest.getStatus() == StockDeductLog.SUCCESS) {
            return latest.getRemaining() == null ? 0 : latest.getRemaining();
        }
        if (latest.getStatus() == StockDeductLog.FAILED) {
            throw failedException(latest);
        }
        if (latest.getStatus() == StockDeductLog.RELEASED) {
            throw new BizException(ErrorCode.PRODUCT_STOCK_NOT_ENOUGH);
        }
        throw new BizException(ErrorCode.DEDUCT_PROCESSING);
    }

    private BizException failedException(StockDeductLog deductLog) {
        int code = deductLog.getErrorCode() == null ? ErrorCode.SYSTEM_ERROR.getCode() : deductLog.getErrorCode();
        String msg = deductLog.getErrorMsg() == null ? ErrorCode.SYSTEM_ERROR.getMsg() : deductLog.getErrorMsg();
        return new BizException(code, msg);
    }

    /**
     * 数据库条件扣减和流水成功状态必须一起提交：
     * 否则库存扣了、流水还是 PROCESSING，重试可能再扣一次。
     */
    private Integer applyDbDeduct(String orderNo, Long productId, int num) {
        return transactionTemplate.execute(tx -> {
            if (productMapper.deductStock(productId, num) == 0) {
                return null;
            }
            Product product = productMapper.selectById(productId);
            int remaining = product == null ? 0 : product.getStock();
            if (deductLogMapper.markSuccess(orderNo, remaining) == 0) {
                // 状态被别的线程改了，回滚本次库存更新
                tx.setRollbackOnly();
                return null;
            }
            return remaining;
        });
    }

    /** key 不存在时用数据库库存初始化；setIfAbsent 避免覆盖并发扣减后的值 */
    private void ensureStockLoaded(Long productId, String key) {
        if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            return;
        }
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BizException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(product.getStock()));
    }

    private void compensateRedis(String key, Integer num) {
        try {
            redisTemplate.opsForValue().increment(key, num);
        } catch (Exception e) {
            log.error("Redis 库存回补失败, key={}, num={}", key, num, e);
        }
    }

    /** 库存变了商品缓存必须失效；缓存故障不影响扣减结果 */
    private void evictProductCache(Long productId) {
        try {
            Cache cache = cacheManager.getCache("product");
            if (cache != null) {
                cache.evict(productId);
            }
        } catch (Exception e) {
            log.warn("清除商品缓存失败, productId={}", productId, e);
        }
    }

    private void checkArgs(String orderNo, Long productId, Integer num) {
        if (orderNo == null || orderNo.isBlank() || productId == null || num == null || num <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
    }
}
