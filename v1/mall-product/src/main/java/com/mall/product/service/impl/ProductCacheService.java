package com.mall.product.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.mall.product.entity.Product;
import com.mall.product.mapper.ProductMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 商品查询的"回源"服务（L5-4）。
 *
 * 为什么要单独放一个类：@Cacheable 只有从"别的类"调用时才生效，
 * 同类内部 this.queryById() 会绕过代理，注解变成摆设（和 L4 收口类同一个道理）。
 * 所以把带缓存注解的方法独立出来，由 ProductServiceImpl 注入调用。
 */
@Slf4j
@Service
public class ProductCacheService {

	@Autowired
	private ProductMapper productMapper;

	// @Cacheable：先查 Redis，命中直接返回；没命中才进方法体查库，
	// 返回值（包括 null）都会被框架写进 Redis。
	// 查不到时返回 null 而不是抛异常——异常不会被缓存，抛异常就等于每次都打库（缓存穿透）。
	@Cacheable(cacheNames = "product", key = "#id")
	public Product queryById(Long id) {
		log.info("回源 MySQL 查询商品, id={}", id);
		return productMapper.selectById(id);
	}
}
