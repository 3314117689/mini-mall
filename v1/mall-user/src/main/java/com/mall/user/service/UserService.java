package com.mall.user.service;

import com.mall.user.dto.RegisterDTO;
import com.mall.user.entity.User;

/**
 * 用户业务接口：方法名用业务语言，Controller 只认识这个接口。
 */
public interface UserService {

    /**
     * 根据ID查询用户
     * @param id 用户ID
     * @return 用户信息，不存在时返回 null
     */
    User getById(Long id);

    /** 注册新用户（L10-4 起收 DTO），返回数据库生成的 id */
    Long save(RegisterDTO dto);

    /** 更新用户，用户不存在时抛业务异常 */
    void update(User user);

    /** 删除用户，用户不存在时抛业务异常 */
    void deleteById(Long id);
}
