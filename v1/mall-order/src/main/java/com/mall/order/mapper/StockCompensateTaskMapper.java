package com.mall.order.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.mall.order.entity.StockCompensateTask;

/**
 * L9 补丁2：补偿任务表。
 * 所有状态流转都带 status 条件，重复执行只会命中一次。
 */
@Mapper
public interface StockCompensateTaskMapper {

    /**
     * 同一个 orderNo 只落一条任务；重复提交靠唯一键 uk_order_no 忽略。
     * next_retry_time 直接给当前时间，让定时任务马上能扫到。
     */
    @Insert("insert ignore into stock_compensate_task"
            + "(order_no, product_id, num, status, retry_count, next_retry_time) "
            + "values(#{orderNo}, #{productId}, #{num}, 0, 0, #{nextRetryTime})")
    int insertIgnore(@Param("orderNo") String orderNo,
            @Param("productId") Long productId,
            @Param("num") Integer num,
            @Param("nextRetryTime") LocalDateTime nextRetryTime);

    @Select("select id, order_no, product_id, num, status, retry_count, next_retry_time, "
            + "last_error, create_time, update_time from stock_compensate_task "
            + "where order_no = #{orderNo}")
    StockCompensateTask selectByOrderNo(String orderNo);

    /** 只取到期的待处理任务，按下次重试时间从早到晚 */
    @Select("select id, order_no, product_id, num, status, retry_count, next_retry_time, "
            + "last_error, create_time, update_time from stock_compensate_task "
            + "where status = 0 and next_retry_time <= now() "
            + "order by next_retry_time limit #{limit}")
    List<StockCompensateTask> selectDue(@Param("limit") int limit);

    @Update("update stock_compensate_task set status = 1, last_error = null, update_time = now() "
            + "where id = #{id} and status = 0")
    int markSuccess(Long id);

    /** 退避重试：记录次数、下次时间和失败原因 */
    @Update("update stock_compensate_task set retry_count = #{retryCount}, "
            + "next_retry_time = #{nextRetryTime}, last_error = #{lastError}, update_time = now() "
            + "where id = #{id} and status = 0")
    int markRetry(@Param("id") Long id,
            @Param("retryCount") Integer retryCount,
            @Param("nextRetryTime") LocalDateTime nextRetryTime,
            @Param("lastError") String lastError);

    /** 重试次数耗尽：转人工，定时任务不再自动扫它 */
    @Update("update stock_compensate_task set status = 2, last_error = #{lastError}, "
            + "update_time = now() where id = #{id} and status = 0")
    int markManual(@Param("id") Long id, @Param("lastError") String lastError);

    /** 人工重放：MANUAL → PENDING，次数清零，立刻到期 */
    @Update("update stock_compensate_task set status = 0, retry_count = 0, "
            + "next_retry_time = now(), last_error = null, update_time = now() "
            + "where order_no = #{orderNo} and status = 2")
    int resetForReplay(String orderNo);
}
