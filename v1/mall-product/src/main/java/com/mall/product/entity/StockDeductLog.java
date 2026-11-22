package com.mall.product.entity;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * L9 修复：库存扣减流水。
 * order_no 唯一，用它做幂等键：同一个订单号不管重试多少次，最多扣一次。
 */
@Data
public class StockDeductLog {

    public static final int INIT = 0;

    public static final int PROCESSING = 1;

    public static final int SUCCESS = 2;

    public static final int FAILED = 3;

    public static final int RELEASED = 4;

    private Long id;

    private String orderNo;

    private Long productId;

    private Integer num;

    private Integer status;

    private Integer remaining;

    private Integer errorCode;

    private String errorMsg;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
