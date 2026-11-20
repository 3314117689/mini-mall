package com.mall.order.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
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
}
