package com.mall.user.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.mall.common.exception.BizException;
import com.mall.common.exception.ErrorCode;
import com.mall.user.auth.JwtIssuer;
import com.mall.user.dto.LoginDTO;
import com.mall.user.dto.LoginVO;
import com.mall.user.entity.User;
import com.mall.user.mapper.UserMapper;
import com.mall.user.service.AuthService;

import lombok.extern.slf4j.Slf4j;

/**
 * 登录实现（L6-3）。
 * 校验顺序：参数 → 查用户 → BCrypt 比对 → 签发 token。
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtIssuer jwtIssuer;

    @Override
    public LoginVO login(LoginDTO dto) {
        if (dto.getUsername() == null || dto.getUsername().isBlank()
                || dto.getPassword() == null || dto.getPassword().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }

        User user = userMapper.selectByUsername(dto.getUsername());
        // 用户不存在 和 密码错误 返回同一个 1005：不让攻击者试出"哪些用户名存在"
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            log.warn("登录失败, username={}", dto.getUsername());
            throw new BizException(ErrorCode.PASSWORD_ERROR);
        }

        String token = jwtIssuer.issue(user);
        log.info("登录成功, userId={}, role={}", user.getId(), user.getRole());

        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setTokenType("Bearer");
        vo.setExpiresIn(jwtIssuer.getTtlSeconds());
        return vo;
    }
}
