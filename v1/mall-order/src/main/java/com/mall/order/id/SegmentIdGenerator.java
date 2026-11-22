package com.mall.order.id;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 号段发号器（L9-3）：Redis 管"段"，本地 AtomicLong 管"段内发号"。
 *
 * 为什么不每个订单都 INCR 一次 Redis：一次网络往返换一个号，QPS 高时 Redis 往返成为瓶颈。
 * 号段模式一次领 step 个号（INCRBY step），本地 CAS 发号，只有段用完才再访问 Redis。
 *
 * 代价：进程重启会丢掉当前段没发完的号（号段空洞）；Redis key 被删会从头开始，
 * 可能和历史订单号重复，所以生产必须持久化 Redis、不能随手删 key。
 */
@Slf4j
@Component
public class SegmentIdGenerator {

    /** 业务前缀，避免和别的项目共用 Redis 时撞 key */
    private static final String KEY = "mall:order:seq";

    private final StringRedisTemplate redisTemplate;

    private final long step;

    /** 当前已发到的号；只在 value < max 时用 CAS 前进 */
    private final AtomicLong current = new AtomicLong(0);

    /** 当前段的上界（含）；current >= max 表示段用完，要换段 */
    private final AtomicLong max = new AtomicLong(0);

    public SegmentIdGenerator(StringRedisTemplate redisTemplate,
            @Value("${mall.order.segment.step:1000}") long step) {
        this.redisTemplate = redisTemplate;
        this.step = step;
    }

    /** 发一个号：先本地 CAS，段用完再进同步块换段（双重检查，避免并发重复换段） */
    public long nextId() {
        while (true) {
            long value = current.get();
            if (value < max.get()) {
                if (current.compareAndSet(value, value + 1)) {
                    return value + 1;
                }
                // 被别的线程抢先改了 current，重读重试
                continue;
            }

            synchronized (this) {
                // 双重检查：可能已经有线程换好段了
                if (current.get() >= max.get()) {
                    Long newMax = redisTemplate.opsForValue().increment(KEY, step);
                    if (newMax == null) {
                        throw new IllegalStateException("Redis 号段分配失败, key=" + KEY);
                    }
                    max.set(newMax);
                    current.set(newMax - step);
                    log.info("订单号段切换, key={}, step={}, range={}~{}",
                            KEY, step, newMax - step + 1, newMax);
                }
            }
        }
    }

    /** 对外使用的订单号：固定前缀 + 12 位数字，便于日志检索和人工核对 */
    public String nextOrderNo() {
        return String.format("ORD%012d", nextId());
    }
}
