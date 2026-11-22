package com.mall.order.config;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import lombok.extern.slf4j.Slf4j;

/**
 * 下单线程池（L9-1）：按任务来源分级，避免一个慢依赖打满所有线程。
 *
 * 两个池的分工：
 * 1. remoteExecutor：在线请求用，有界队列 + AbortPolicy。队列满就快速失败，
 *    由业务返回 4291，绝不无界排队——无界队列会把内存和响应时间一起拖垮。
 * 2. compensateExecutor：库存回补用，CallerRunsPolicy。补偿任务不能丢，
 *    队列满时退回调用线程自己跑，宁可拖慢也把它执行掉。
 */
@Slf4j
@Configuration
public class ThreadPoolConfig {

    /** 按名字注入时使用，避免两个 ThreadPoolTaskExecutor 类型冲突 */
    public static final String REMOTE_EXECUTOR = "remoteExecutor";

    public static final String COMPENSATE_EXECUTOR = "compensateExecutor";

    @Bean(name = REMOTE_EXECUTOR)
    public ThreadPoolTaskExecutor remoteExecutor(
            @Value("${mall.order.pool.remote.core:8}") int core,
            @Value("${mall.order.pool.remote.max:16}") int max,
            @Value("${mall.order.pool.remote.queue:64}") int queue) {
        return build("mall-remote-", core, max, queue, new LoggingAbortPolicy("remote"));
    }

    @Bean(name = COMPENSATE_EXECUTOR)
    public ThreadPoolTaskExecutor compensateExecutor(
            @Value("${mall.order.pool.compensate.core:1}") int core,
            @Value("${mall.order.pool.compensate.max:2}") int max,
            @Value("${mall.order.pool.compensate.queue:32}") int queue) {
        return build("mall-compensate-", core, max, queue, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    private ThreadPoolTaskExecutor build(String prefix, int core, int max, int queue,
            RejectedExecutionHandler handler) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(prefix);
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setKeepAliveSeconds(60);
        executor.setRejectedExecutionHandler(handler);
        // 停机时把已提交任务跑完，避免订单流程半途而废
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }

    /**
     * 拒绝策略：先打印池的真实指标，再抛异常。
     * 只打日志不抛，任务就静默丢了；只抛不打印，事后无法判断是队列小还是下游慢。
     */
    private static class LoggingAbortPolicy implements RejectedExecutionHandler {

        private final String poolName;

        private LoggingAbortPolicy(String poolName) {
            this.poolName = poolName;
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            log.warn("线程池拒绝任务, pool={}, active={}, poolSize={}, queue={}, completed={}",
                    poolName, executor.getActiveCount(), executor.getPoolSize(),
                    executor.getQueue().size(), executor.getCompletedTaskCount());
            throw new RejectedExecutionException(poolName + " 线程池队列已满");
        }
    }
}
