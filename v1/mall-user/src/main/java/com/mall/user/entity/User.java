package com.mall.user.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体，对应 db_user 库的 user 表。
 * 注意：password 不对外暴露，接口层用 @JsonIgnore 排除。
 */
@Data
public class User {

    private Long id;

    private String username;

    @JsonIgnore
    private String password;

    private String phone;

    /** 数据库列名是 create_time，靠驼峰映射自动对应 */
    private LocalDateTime createTime;
}
