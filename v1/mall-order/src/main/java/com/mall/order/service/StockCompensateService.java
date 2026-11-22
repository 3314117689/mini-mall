package com.mall.order.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import com.mall.common.exception.BizException;
import com.mall.common.exception.ErrorCode;
import com.mall.order.config.ThreadPoolConfig;
import com.mall.order.entity.StockCompensateTask;
import com.mall.order.mapper.StockCompensateTaskMapper;
import com.mall.order.remote.RemoteService;

import lombok.extern.slf4j.Slf4j;

/**
 * L9 补丁2：库存补偿任务。
 *
 * 改动前：补偿只在内存线程池里跑一次，product 不可用就只剩 error 日志，需要人工对账。
 * 改动后：先把任务落库，再异步执行一次；失败的由定时任务按退避策略重试，
 * 次数耗尽转 MANUAL，等人调用重放接口。
 *
 * 为什么先落库再执行：进程重启或线程池异常时，内存里的任务会丢，表里的不会。
 * 为什么可以重复执行：product 的 release 按 orderNo 幂等（2→4），重复回补不会多加库存。
 */
@Slf4j
@Service
public class StockCompensateService {

    @Autowired
    private StockCompensateTaskMapper taskMapper;

    @Autowired
    private RemoteService remoteService;

    @Autowired
    @Qualifier(ThreadPoolConfig.COMPENSATE_EXECUTOR)
    private ThreadPoolTaskExecutor compensateExecutor;

    @Value("${mall.order.compensate.max-retry:5}")
    private int maxRetry;

    @Value("${mall.order.compensate.backoff-base-ms:10000}")
    private long backoffBaseMs;

    @Value("${mall.order.compensate.batch-size:20}")
    private int batchSize;

    /**
     * 提交一次补偿：先落任务，再异步跑第一次。
     * 落库失败（比如订单库也不可用）时退化成"只跑一次"的尽力补偿，并打 error 日志。
     */
    public void submit(String orderNo, Long productId, Integer num) {
        StockCompensateTask task;
        try {
            taskMapper.insertIgnore(orderNo, productId, num, LocalDateTime.now());
            task = taskMapper.selectByOrderNo(orderNo);
        } catch (Exception e) {
            log.error("补偿任务落库失败，退化为一次性补偿, orderNo={}, productId={}, num={}",
                    orderNo, productId, num, e);
            submitDirect(orderNo, productId, num);
            return;
        }
        if (task == null || task.getStatus() != StockCompensateTask.PENDING) {
            // 已经有了且不在待处理状态：说明补偿过了或已转人工，不重复执行
            log.info("补偿任务已存在且无需执行, orderNo={}, status={}",
                    orderNo, task == null ? null : task.getStatus());
            return;
        }
        try {
            compensateExecutor.execute(() -> compensate(task));
        } catch (Exception e) {
            // 提交线程池失败不影响定时任务兜底：next_retry_time 已经是当前时间
            log.warn("补偿任务提交线程池失败，等待定时重试, orderNo={}", orderNo, e);
        }
    }

    /**
     * 定时扫描到期任务。fixedDelay 在上一轮跑完后再计时，不会重叠执行。
     * 单个任务失败只更新自己的重试信息，不中断整批。
     */
    @Scheduled(fixedDelayString = "${mall.order.compensate.scan-delay-ms:10000}")
    public void retryPending() {
        List<StockCompensateTask> tasks;
        try {
            tasks = taskMapper.selectDue(batchSize);
        } catch (Exception e) {
            log.error("扫描库存补偿任务失败", e);
            return;
        }
        for (StockCompensateTask task : tasks) {
            compensate(task);
        }
    }

    /**
     * 人工重放：只有 MANUAL 状态的能重放，重置后同步执行一次，让操作者马上看到结果。
     * 返回 false 表示没有可重放的任务（不存在或不是 MANUAL），不是错误。
     */
    public boolean replay(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        if (taskMapper.resetForReplay(orderNo) == 0) {
            log.info("没有可重放的补偿任务, orderNo={}", orderNo);
            return false;
        }
        StockCompensateTask task = taskMapper.selectByOrderNo(orderNo);
        if (task != null && task.getStatus() == StockCompensateTask.PENDING) {
            compensate(task);
        }
        return true;
    }

    /** 真正执行一次回补；成功置 SUCCESS，失败按退避更新重试信息 */
    private void compensate(StockCompensateTask task) {
        try {
            remoteService.releaseStock(task.getOrderNo(), task.getProductId(), task.getNum());
            taskMapper.markSuccess(task.getId());
            log.info("库存补偿成功, orderNo={}, retryCount={}", task.getOrderNo(), task.getRetryCount());
        } catch (Exception e) {
            handleFailure(task, e);
        }
    }

    private void handleFailure(StockCompensateTask task, Exception e) {
        int retried = (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1;
        String error = trim(e.getMessage());
        if (retried >= maxRetry) {
            taskMapper.markManual(task.getId(), error);
            log.error("库存补偿重试耗尽，转人工处理, orderNo={}, retryCount={}, error={}",
                    task.getOrderNo(), retried, error);
            return;
        }
        // 指数退避：10s、20s、40s、80s，避免下游一直不可用时高频重试
        long delayMs = backoffBaseMs * (1L << (retried - 1));
        LocalDateTime nextRetryTime = LocalDateTime.now().plusNanos(delayMs * 1_000_000L);
        taskMapper.markRetry(task.getId(), retried, nextRetryTime, error);
        log.warn("库存补偿失败，{}ms 后重试, orderNo={}, retryCount={}, error={}",
                delayMs, task.getOrderNo(), retried, error);
    }

    /** 任务落不了库时的最后兜底：只在当前进程内跑一次，失败只能人工对账 */
    private void submitDirect(String orderNo, Long productId, Integer num) {
        try {
            compensateExecutor.execute(() -> {
                try {
                    remoteService.releaseStock(orderNo, productId, num);
                    log.info("库存补偿成功（任务未落库）, orderNo={}", orderNo);
                } catch (Exception e) {
                    log.error("库存补偿失败且任务未落库，需要人工对账, orderNo={}, productId={}, num={}",
                            orderNo, productId, num, e);
                }
            });
        } catch (Exception e) {
            log.error("补偿线程池不可用且任务未落库，需要人工对账, orderNo={}, productId={}, num={}",
                    orderNo, productId, num, e);
        }
    }

    private String trim(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 480 ? message.substring(0, 480) : message;
    }
}
