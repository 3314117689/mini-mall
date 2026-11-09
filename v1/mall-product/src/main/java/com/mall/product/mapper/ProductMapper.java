package com.mall.product.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.mall.product.entity.Product;

/**
 * 商品表数据访问层：只写 SQL，不放业务逻辑。
 * 注意：SQL 里写的是数据库真实列名（name、create_time），
 * 驼峰映射配置会自动把 create_time 映射到实体的 createTime。
 */
@Mapper
public interface ProductMapper {

    @Select("select id, name, price, stock, create_time from product where id = #{id}")
    Product selectById(Long id);

    /**
     * 新增商品。useGeneratedKeys = true 表示插入成功后
     * 把数据库自增生成的主键回填到 product.id 字段上。
     */
    @Insert("insert into product(name, price, stock) values(#{name}, #{price}, #{stock})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Product product);

    @Update("update product set name = #{name}, price = #{price}, stock = #{stock} where id = #{id}")
    int update(Product product);

    @Delete("delete from product where id = #{id}")
    int deleteById(Long id);
}
