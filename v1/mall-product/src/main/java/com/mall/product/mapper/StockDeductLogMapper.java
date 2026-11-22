package com.mall.product.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.mall.product.entity.StockDeductLog;

/**
 * L9 修复：库存扣减流水。
 * 所有状态流转都带 where 条件，避免并发重复扣减/重复回补。
 */
@Mapper
public interface StockDeductLogMapper {

    @Insert("insert into stock_deduct_log(order_no, product_id, num, status) "
            + "values(#{orderNo}, #{productId}, #{num}, 0)")
    int insertInit(@Param("orderNo") String orderNo,
            @Param("productId") Long productId,
            @Param("num") Integer num);

    @Select("select id, order_no, product_id, num, status, remaining, error_code, error_msg, "
            + "create_time, update_time from stock_deduct_log where order_no = #{orderNo}")
    StockDeductLog selectByOrderNo(String orderNo);

    /**
     * 认领任务：INIT 可以认领；PROCESSING 超过 60 秒视为上次进程异常退出，允许接管。
     * 返回 1 表示抢到执行权，0 表示别的线程正在处理或状态已经终态。
     */
    @Update("update stock_deduct_log set status = 1, update_time = now() "
            + "where order_no = #{orderNo} and (status = 0 "
            + "or (status = 1 and update_time < date_sub(now(), interval 60 second)))")
    int claimProcessing(String orderNo);

    @Update("update stock_deduct_log set status = 2, remaining = #{remaining}, "
            + "error_code = null, error_msg = null, update_time = now() "
            + "where order_no = #{orderNo} and status = 1")
    int markSuccess(@Param("orderNo") String orderNo, @Param("remaining") Integer remaining);

    @Update("update stock_deduct_log set status = 3, error_code = #{errorCode}, "
            + "error_msg = #{errorMsg}, update_time = now() "
            + "where order_no = #{orderNo} and status = 1")
    int markFailed(@Param("orderNo") String orderNo,
            @Param("errorCode") Integer errorCode,
            @Param("errorMsg") String errorMsg);

    /** 只有扣减成功（2）的记录才能回补，成功后置为已释放（4） */
    @Update("update stock_deduct_log set status = 4, update_time = now() "
            + "where order_no = #{orderNo} and status = 2")
    int claimRelease(String orderNo);
}
