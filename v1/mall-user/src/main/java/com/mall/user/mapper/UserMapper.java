package com.mall.user.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.mall.user.entity.User;

/**
 * 用户表数据访问层：只写 SQL，不放业务逻辑。
 */
@Mapper
public interface UserMapper {

    @Select("select id, username, password, phone, role, create_time from user where id = #{id}")
    User selectById(Long id);

    /** 按用户名查（L6-2 注册查重 / L6-3 登录校验共用）；查不到返回 null */
    @Select("select id, username, password, phone, role, create_time from user where username = #{username}")
    User selectByUsername(String username);

    /**
     * 新增用户。useGeneratedKeys = true 表示插入成功后
     * 把数据库自增生成的主键回填到 user.id 字段上。
     * create_time 不写：由数据库默认值自动生成。
     */
    @Insert("insert into user(username, password, phone) values(#{username}, #{password}, #{phone})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(User user);

    @Update("update user set username = #{username}, password = #{password}, phone = #{phone} where id = #{id}")
    int update(User user);

    @Delete("delete from user where id = #{id}")
    int deleteById(Long id);
}
