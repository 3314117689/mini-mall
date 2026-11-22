package com.mall.user.dto;

import lombok.Data;

/**
 * 登录请求参数（L6-3）：只收用户名和密码。
 * 不直接复用 User，避免客户端顺手传 id/role 之类的字段进来。
 */
@Data
public class LoginDTO {

    private String username;

    private String password;
}
