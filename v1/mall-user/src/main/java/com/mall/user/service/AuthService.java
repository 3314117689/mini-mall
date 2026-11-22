package com.mall.user.service;

import com.mall.user.dto.LoginDTO;
import com.mall.user.dto.LoginVO;

/**
 * 认证业务接口（L6-3）：登录校验 + 签发 token。
 */
public interface AuthService {

    /** 登录成功返回 token 信息，失败抛 BizException(1005) */
    LoginVO login(LoginDTO dto);
}
