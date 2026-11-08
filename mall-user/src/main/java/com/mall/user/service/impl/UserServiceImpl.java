package com.mall.user.service.impl;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.mall.user.entity.User;
import com.mall.user.mapper.UserMapper;
import com.mall.user.service.UserService;

/**
 * 用户业务实现类。
 * 现在只是简单转发 Mapper，后续任务会在这里加业务规则（如用户不存在时抛异常）。
 */
@Slf4j
@Service
public class UserServiceImpl implements UserService {

    @Autowired
    private UserMapper userMapper;

    @Override
    public User getById(Long id) {
        log.info("查询用户, id={}", id);
        User user = userMapper.selectById(id);
        if (user == null) {
            log.warn("用户不存在, id={}", id);
        }
        return user;
    }
}
