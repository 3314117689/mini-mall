package com.mall.user.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import com.mall.user.entity.User;

/**
 * 用户表数据访问层：只写 SQL，不放业务逻辑。
 */
@Mapper
public interface UserMapper {

    @Select("select id, username, password, phone, create_time from user where id = #{id}")
    User selectById(Long id);
}
