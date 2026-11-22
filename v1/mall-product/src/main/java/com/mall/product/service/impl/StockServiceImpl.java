package com.mall.product.service.impl;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.mall.common.exception.BizException;
import com.mall.common.exception.ErrorCode;
import com.mall.product.entity.Product;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.service.StockService;

import lombok.extern.slf4j.Slf4j;

/**
 * 库存扣减（L9-4）：Redis 预扣挡并发，数据库条件更新兜底。
 *
 * 为什么不是单纯 DECR：GET 判断再 DECR 是两步，高并发下两个请求可能都读到 1，
 * 然后都扣成 -1。Lua 把"读取 + 判断 + 扣减"放进 Redis 单线程里一次执行，天然原子。
 *
 * 为什么还要数据库条件更新：Redis 可能丢数据、被清空、初始值不对；
 * `stock = stock - n where stock >= n` 由 MySQL 行锁保证，是最终不超卖的防线。
 */
@Slf4j
@Service
public class StockServiceImpl implements StockService {

    /** 库存 key 统一带项目前缀，和别的项目共用 Redis 时不冲突 */
    private static final String KEY_PREFIX = "mall:stock:";

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private DefaultRedisScript<Long> stockDeductScript;

    @Autowired
    private CacheManager cacheManager;

    @Override
    public int deduct(Long productId, Integer num) {
        checkArgs(productId, num);
        String key = KEY_PREFIX + productId;

        // 1) Redis 预扣：正常流量在这里就被挡住，不碰数据库
        Long luaResult = null;
        try {
            ensureStockLoaded(productId, key);
            luaResult = redisTemplate.execute(stockDeductScript, List.of(key), String.valueOf(num));
            if (luaResult != null && luaResult == -2L) {
                // key 在 hasKey 之后、执行之前过期/被删：补一次再试
                ensureStockLoaded(productId, key);
                luaResult = redisTemplate.execute(stockDeductScript, List.of(key), String.valueOf(num));
            }
        } catch (BizException e) {
            // 商品不存在，原样抛
            throw e;
        } catch (Exception e) {
            // Redis 不可用不阻断扣减：退回数据库条件更新，仍然不会超卖
            log.warn("Redis 库存不可用，退化为 DB 条件扣减, productId={}", productId, e);
            luaResult = null;
        }

        if (luaResult != null && luaResult == -1L) {
            throw new BizException(ErrorCode.PRODUCT_STOCK_NOT_ENOUGH);
        }

        // 2) 数据库条件扣减：真相源，Redis 说成功也要以它为准
        int rows = productMapper.deductStock(productId, num);
        if (rows == 0) {
            // Redis 扣了但数据库没扣成：把 Redis 加回去，避免少卖
            if (luaResult != null && luaResult >= 0) {
                compensateRedis(key, num);
            }
            if (productMapper.selectById(productId) == null) {
                throw new BizException(ErrorCode.PRODUCT_NOT_FOUND);
            }
            throw new BizException(ErrorCode.PRODUCT_STOCK_NOT_ENOUGH);
        }

        evictProductCache(productId);
        if (luaResult != null && luaResult >= 0) {
            return luaResult.intValue();
        }
        // Redis 不可用时没有剩余值，回查一次数据库
        Product updated = productMapper.selectById(productId);
        return updated == null ? 0 : updated.getStock();
    }

    @Override
    public void release(Long productId, Integer num) {
        checkArgs(productId, num);
        String key = KEY_PREFIX + productId;

        if (productMapper.releaseStock(productId, num) == 0) {
            throw new BizException(ErrorCode.PRODUCT_NOT_FOUND);
        }

        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
                redisTemplate.opsForValue().increment(key, num);
            } else {
                // key 不在就从数据库重建，不能从 0 加，否则 Redis 会少算
                Product product = productMapper.selectById(productId);
                if (product != null) {
                    redisTemplate.opsForValue().set(key, String.valueOf(product.getStock()));
                }
            }
        } catch (Exception e) {
            // 回补 Redis 失败只记日志；下一次缓存缺失会从数据库重新加载
            log.warn("回补 Redis 库存失败, productId={}, num={}", productId, num, e);
        }

        evictProductCache(productId);
        log.info("库存回补成功, productId={}, num={}", productId, num);
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

    private void checkArgs(Long productId, Integer num) {
        if (productId == null || num == null || num <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
    }
}
