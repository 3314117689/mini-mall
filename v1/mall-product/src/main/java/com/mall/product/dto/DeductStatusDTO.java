package com.mall.product.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * L9 修复：库存扣减状态查询结果。
 * status = -1 表示没有扣减记录；其余对应 StockDeductLog 的 0~4。
 */
@Data
@AllArgsConstructor
public class DeductStatusDTO {

    private String orderNo;

    private Integer status;

    private Integer remaining;

    private Integer errorCode;

    private String errorMsg;
}
