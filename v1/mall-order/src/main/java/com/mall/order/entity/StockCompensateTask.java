package com.mall.order.entity;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * L9 补丁2：库存补偿任务。
 *
 * 为什么要有这张表：原来的补偿只在内存线程池里跑一次，失败就只剩一行 error 日志。
 * 落成任务后，进程重启、下游长时间不可用都能继续重试，重试耗尽转人工。
 */
@Data
public class StockCompensateTask {

    /** 待处理：定时任务会扫描 next_retry_time 到期的行 */
    public static final int PENDING = 0;

    /** 补偿成功 */
    public static final int SUCCESS = 1;

    /** 重试次数耗尽，需要人工介入 */
    public static final int MANUAL = 2;

    private Long id;

    private String orderNo;

    private Long productId;

    private Integer num;

    private Integer status;

    private Integer retryCount;

    private LocalDateTime nextRetryTime;

    private String lastError;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
