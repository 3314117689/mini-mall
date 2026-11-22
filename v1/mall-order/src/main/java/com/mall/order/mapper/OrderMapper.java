package com.mall.order.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.mall.order.entity.Order;

@Mapper
public interface OrderMapper {

	@Select("select id, user_id, product_id, num, amount, create_time from orders where id = #{id}")
	Order selectById(Long id);

	/**
	 * 新增订单。自增主键回填到 order.id。
	 * create_time 不写：由数据库默认值自动生成。
	 */
	@Insert("insert into orders(user_id, product_id, num, amount) values(#{userId}, #{productId}, #{num}, #{amount})")
	@Options(useGeneratedKeys = true, keyProperty = "id")
	int insert(Order order);

	@Update("update orders set user_id = #{userId}, product_id = #{productId}, num = #{num}, amount = #{amount} where id = #{id}")
	int update(Order order);

	@Delete("delete from orders where id = #{id}")
	int deleteById(Long id);

	/**
	 * L8：offset 分页，按创建时间倒序。
	 * create_time 是 datetime（秒级），可能重复，追加 id 排序保证顺序稳定。
	 */
	@Select("select id, user_id, product_id, num, amount, create_time from orders "
			+ "where user_id = #{userId} order by create_time desc, id desc limit #{offset}, #{size}")
	List<Order> selectPage(@Param("userId") Long userId, @Param("offset") int offset, @Param("size") int size);

	/**
	 * L8：游标分页，从 (lastCreateTime, lastId) 之后继续取。
	 * 用复合条件而不是行构造器，MySQL 5.7 下执行计划更直观。
	 */
	@Select("select id, user_id, product_id, num, amount, create_time from orders "
			+ "where user_id = #{userId} "
			+ "and (create_time < #{lastCreateTime} or (create_time = #{lastCreateTime} and id < #{lastId})) "
			+ "order by create_time desc, id desc limit #{size}")
	List<Order> selectCursor(@Param("userId") Long userId,
			@Param("lastCreateTime") LocalDateTime lastCreateTime,
			@Param("lastId") Long lastId,
			@Param("size") int size);
}
