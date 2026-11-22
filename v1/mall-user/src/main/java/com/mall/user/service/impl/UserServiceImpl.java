package com.mall.user.service.impl;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.mall.common.exception.BizException;
import com.mall.common.exception.ErrorCode;
import com.mall.user.dto.RegisterDTO;
import com.mall.user.entity.User;
import com.mall.user.mapper.UserMapper;
import com.mall.user.service.UserService;

/**
 * 用户业务实现类。 业务规则不满足时直接抛 BizException，由 mall-common 的全局异常处理器统一转换成 Result。
 */
@Slf4j
@Service
public class UserServiceImpl implements UserService {

	@Autowired
	private UserMapper userMapper;

	/** 密码编码器（L6-2）：注册时把明文换成 BCrypt 哈希，库里永远不存明文 */
	@Autowired
	private PasswordEncoder passwordEncoder;

	@Override
	public User getById(Long id) {
		log.info("查询用户, id={}", id);
		User user = userMapper.selectById(id);
		if (user == null) {
			log.warn("用户不存在, id={}", id);
			throw new BizException(ErrorCode.USER_NOT_FOUND);
		}
		return user;
	}

	@Override
	public Long save(RegisterDTO dto) {
		log.info("新增用户, username={}", dto.getUsername());
		// 必填与长度已由 RegisterDTO 上的注解校验，这里只保留业务规则：用户名唯一
		if (userMapper.selectByUsername(dto.getUsername()) != null) {
			log.warn("注册失败, 用户名已存在: {}", dto.getUsername());
			throw new BizException(ErrorCode.USERNAME_EXISTS);
		}
		User user = new User();
		user.setUsername(dto.getUsername());
		user.setPhone(dto.getPhone());
		// 明文 → BCrypt 哈希（同一个密码每次生成的哈希都不同，因为带随机盐）
		user.setPassword(passwordEncoder.encode(dto.getPassword()));
		userMapper.insert(user);
		// insert 执行完后自增主键已回填到 user.id，直接返回
		return user.getId();
	}

	@Override
	public void update(User user) {
		log.info("更新用户, id={}", user.getId());
		// 更新里带了新密码也要先哈希，否则会把明文写进库（L6-2）
		if (user.getPassword() != null && !user.getPassword().isBlank()) {
			user.setPassword(passwordEncoder.encode(user.getPassword()));
		}
		// 返回受影响行数，0 行说明这个 id 不存在
		if (userMapper.update(user) == 0) {
			log.warn("更新失败, 用户不存在, id={}", user.getId());
			throw new BizException(ErrorCode.USER_NOT_FOUND);
		}
	}

	@Override
	public void deleteById(Long id) {
		log.info("删除用户, id={}", id);
		if (userMapper.deleteById(id) == 0) {
			log.warn("删除失败, 用户不存在, id={}", id);
			throw new BizException(ErrorCode.USER_NOT_FOUND);
		}
	}
}
