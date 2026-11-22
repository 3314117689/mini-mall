package com.mall.order.id;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.mall.order.mapper.SegmentMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 号段发号器（L9-3）：Redis 管"段"，本地 AtomicLong 管"段内发号"。
 *
 * 为什么不每个订单都 INCR 一次 Redis：一次网络往返换一个号，QPS 高时 Redis 往返成为瓶颈。
 * 号段模式一次领 step 个号（INCRBY step），本地 CAS 发号，只有段用完才再访问 Redis。
 *
 * L9 补丁3：Redis 不再是单点。
 * - 每次从 Redis 领段后，把新水位同步到 id_segment，数据库始终有一份最新水位；
 * - Redis 不可用时，用 id_segment 的 for update 行锁领段，照样能发号；
 * - 启动时把 Redis 水位对齐到数据库，Redis 丢过数据也不会重发历史订单号。
 */
@Slf4j
@Component
public class SegmentIdGenerator implements ApplicationRunner {

    /** 业务前缀，避免和别的项目共用 Redis 时撞 key */
    private static final String KEY = "mall:order:seq";

    /** id_segment 里的业务标识 */
    private static final String BIZ = "order_no";

    private final StringRedisTemplate redisTemplate;

    private final SegmentMapper segmentMapper;

    private final TransactionTemplate transactionTemplate;

    private final long step;

    /** 当前已发到的号；只在 value < max 时用 CAS 前进 */
    private final AtomicLong current = new AtomicLong(0);

    /** 当前段的上界（含）；current >= max 表示段用完，要换段 */
    private final AtomicLong max = new AtomicLong(0);

    public SegmentIdGenerator(StringRedisTemplate redisTemplate,
            SegmentMapper segmentMapper,
            TransactionTemplate transactionTemplate,
            @Value("${mall.order.segment.step:1000}") long step) {
        this.redisTemplate = redisTemplate;
        this.segmentMapper = segmentMapper;
        this.transactionTemplate = transactionTemplate;
        this.step = step;
    }

    /** 启动对齐：必须在接流量前完成，否则可能先发出一个旧号 */
    @Override
    public void run(ApplicationArguments args) {
        alignWatermark();
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
                    long newMax = allocateSegment();
                    max.set(newMax);
                    current.set(newMax - step);
                }
            }
        }
    }

    /** 对外使用的订单号：固定前缀 + 12 位数字，便于日志检索和人工核对 */
    public String nextOrderNo() {
        return String.format("ORD%012d", nextId());
    }

    /** 优先 Redis；领不到再走数据库兜底 */
    private long allocateSegment() {
        Long newMax = allocateFromRedis();
        if (newMax != null) {
            return newMax;
        }
        return allocateFromDb();
    }

    private Long allocateFromRedis() {
        try {
            Long newMax = redisTemplate.opsForValue().increment(KEY, step);
            if (newMax == null) {
                return null;
            }
            log.info("订单号段切换, key={}, step={}, range={}~{}",
                    KEY, step, newMax - step + 1, newMax);
            syncDbWatermark(newMax);
            return newMax;
        } catch (Exception e) {
            log.warn("Redis 号段不可用，改用数据库号段, key={}", KEY, e);
            return null;
        }
    }

    /**
     * 数据库兜底：for update 锁住水位行，读到 max_id 后加 step 再写回。
     * 多实例并发时靠行锁串行化，不会领到同一段。
     */
    private long allocateFromDb() {
        Long newMax = transactionTemplate.execute(tx -> {
            Long dbMax = segmentMapper.selectMaxIdForUpdate(BIZ);
            if (dbMax == null) {
                // 表里没有行：先补一行再锁，正常情况 SQL 脚本已经种好了
                segmentMapper.insertIgnore(BIZ, 0);
                dbMax = segmentMapper.selectMaxIdForUpdate(BIZ);
            }
            long next = (dbMax == null ? 0 : dbMax) + step;
            segmentMapper.updateMaxId(BIZ, next);
            return next;
        });
        if (newMax == null) {
            throw new IllegalStateException("数据库号段分配失败, biz=" + BIZ);
        }
        log.warn("使用数据库号段, biz={}, step={}, range={}~{}",
                BIZ, step, newMax - step + 1, newMax);
        return newMax;
    }

    /** 每次 Redis 领段后把水位推到数据库；失败只记日志，不阻断发号 */
    private void syncDbWatermark(long newMax) {
        try {
            segmentMapper.raiseMaxId(BIZ, newMax);
        } catch (Exception e) {
            log.error("同步号段水位到数据库失败, biz={}, maxId={}", BIZ, newMax, e);
        }
    }

    /**
     * 启动对齐：数据库水位是下限。
     * Redis 丢失后从 0 开始，不拉起就会重发历史订单号。
     */
    private void alignWatermark() {
        try {
            Long dbMax = segmentMapper.selectMaxId(BIZ);
            if (dbMax == null) {
                segmentMapper.insertIgnore(BIZ, 0);
                dbMax = 0L;
            }
            String redisValue = redisTemplate.opsForValue().get(KEY);
            long redisMax = redisValue == null ? 0 : Long.parseLong(redisValue);
            if (redisMax < dbMax) {
                redisTemplate.opsForValue().set(KEY, String.valueOf(dbMax));
                log.warn("Redis 号段水位低于数据库，已对齐, redis={}, db={}", redisMax, dbMax);
            } else if (redisMax > dbMax) {
                syncDbWatermark(redisMax);
                log.info("已把 Redis 号段水位同步到数据库, redis={}, db={}", redisMax, dbMax);
            }
        } catch (Exception e) {
            // Redis 或数据库暂时不可用都不能阻断启动：运行时各有兜底
            log.error("号段水位启动对齐失败，Redis 故障时将使用数据库兜底", e);
        }
    }
}
