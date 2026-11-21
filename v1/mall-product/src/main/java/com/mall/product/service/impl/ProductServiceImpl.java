package com.mall.product.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.mall.common.exception.BizException;
import com.mall.common.exception.ErrorCode;
import com.mall.product.entity.Product;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.service.ProductService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ProductServiceImpl implements ProductService {

	@Autowired
	private ProductMapper productMapper;

	// @Cacheable（L5）：方法执行前框架先拿 "product::id" 当 key 查 Redis——
	// 命中就直接把缓存值返回，方法体（包括下面的 Mapper 查库）一行都不执行；
	// 没命中才真正进方法体查库，返回值会被框架顺手写进 Redis。
	// cacheNames="product" 是缓存分区名，key="#id" 用方法参数当缓存 key。
	@Cacheable(cacheNames = "product", key = "#id")
	@Override
	public Product getById(Long id) {
		log.info("查询商品, id={}", id);
		Product product = productMapper.selectById(id);
		// 查不到才抛业务异常；查到了就直接返回
		if (product == null) {
			log.warn("商品不存在, id={}", id);
			throw new BizException(ErrorCode.PRODUCT_NOT_FOUND);
		}
		return product;
	}

	@Override
	public Long save(Product product) {
		log.info("新增商品, name={}, price={}, stock={}",
				product.getName(), product.getPrice(), product.getStock());
		productMapper.insert(product);
		// insert 方法执行完后，自增主键已经被回填到 product.id，直接返回它
		return product.getId();
	}

	@Override
	public void update(Product product) {
		log.info("更新商品, id={}", product.getId());
		// update 返回受影响的行数：0 行说明这个 id 在表里不存在
		if (productMapper.update(product) == 0) {
			log.warn("更新失败, 商品不存在, id={}", product.getId());
			throw new BizException(ErrorCode.PRODUCT_NOT_FOUND);
		}
	}

	@Override
	public void deleteById(Long id) {
		log.info("删除商品, id={}", id);
		// delete 同理，0 行说明商品不存在
		if (productMapper.deleteById(id) == 0) {
			log.warn("删除失败, 商品不存在, id={}", id);
			throw new BizException(ErrorCode.PRODUCT_NOT_FOUND);
		}
	}
}
