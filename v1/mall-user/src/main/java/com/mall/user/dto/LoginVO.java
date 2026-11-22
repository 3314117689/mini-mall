package com.mall.user.dto;

import lombok.Data;

/**
 * 登录响应（L6-3）：前端拿 token 放到后续请求的 Authorization 头里。
 * tokenType 固定 Bearer；expiresIn 是秒数，前端可据此提前提示续期/重登。
 */
@Data
public class LoginVO {

    private String token;

    private String tokenType;

    private long expiresIn;
}
