package com.mall.product.service;

/**
 * L9-4：库存扣减/回补。
 * 扣减先走 Redis Lua 原子预扣，再以数据库条件更新为最终依据。
 */
public interface StockService {

    /** 扣减库存，返回剩余库存；库存不足抛 1006 */
    int deduct(Long productId, Integer num);

    /** 回补库存（下单失败补偿用） */
    void release(Long productId, Integer num);
}
