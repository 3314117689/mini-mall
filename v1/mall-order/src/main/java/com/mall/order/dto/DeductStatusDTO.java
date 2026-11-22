package com.mall.order.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * L9 修复：库存扣减状态。
 * status = -1 表示没有记录；0 初始化 / 1 处理中 / 2 成功 / 3 失败 / 4 已释放。
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
