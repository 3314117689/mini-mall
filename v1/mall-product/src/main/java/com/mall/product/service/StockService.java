package com.mall.product.service;

import com.mall.product.dto.DeductStatusDTO;

/**
 * L9-4：库存扣减/回补。
 * 扣减先走 Redis Lua 原子预扣，再以数据库条件更新为最终依据。
 * L9 修复：所有操作以 orderNo 为幂等键，同一订单最多扣一次、最多回补一次。
 */
public interface StockService {

    /** 扣减库存，返回剩余库存；库存不足抛 1006 */
    int deduct(String orderNo, Long productId, Integer num);

    /** 回补库存（下单失败补偿用），幂等 */
    void release(String orderNo, Long productId, Integer num);

    /** 查询某个订单号的扣减状态，没有记录返回 status=-1 */
    DeductStatusDTO status(String orderNo);
}
