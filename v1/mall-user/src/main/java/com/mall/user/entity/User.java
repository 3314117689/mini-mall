package com.mall.user.entity;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体，对应 db_user 库的 user 表。
 * 注意：password 只允许"写进来"（注册/修改时接收），不允许"读出去"（接口返回时不带）。
 */
@Data
public class User {

    private Long id;

    private String username;

    /** WRITE_ONLY：JSON 转对象时接收，对象转 JSON 时忽略（@JsonIgnore 是双向忽略，会把注册传的密码也丢掉） */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    private String phone;

    /** 数据库列名是 create_time，靠驼峰映射自动对应 */
    private LocalDateTime createTime;
}
