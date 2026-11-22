package com.mall.common.auth;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 当前登录用户（L6-4）：从 token 的 claims 还原出来的最小身份信息。
 * 不放 password，避免在整条链路里传来传去。
 */
@Data
@AllArgsConstructor
public class LoginUser {

    private Long userId;

    private String username;

    private String role;
}
