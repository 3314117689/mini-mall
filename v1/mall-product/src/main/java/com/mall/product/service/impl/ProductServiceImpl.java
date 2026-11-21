package com.mall.product.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
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

	@Autowired
	private ProductCacheService productCacheService;

	// L5-4：缓存逻辑搬到 ProductCacheService（注解必须从外部调用才生效）。
	// 这里只负责"查不到就报错"——null 已经被缓存住了，不会每次穿透到 MySQL。
	@Override
	public Product getById(Long id) {
		log.info("查询商品, id={}", id);
		Product product = productCacheService.queryById(id);
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

	// L5-5：改完数据必须让缓存失效，否则后面查询一直读到旧值。
	// @CacheEvict 默认在方法成功执行完之后执行（抛异常则不删，库也没改，正好一致）。
	// key 用 #product.id，两个方法必须和 @Cacheable 用同一个 key 拼法（product::id）才对得上。
	@CacheEvict(cacheNames = "product", key = "#product.id")
	@Override
	public void update(Product product) {
		log.info("更新商品, id={}", product.getId());
		// update 返回受影响的行数：0 行说明这个 id 在表里不存在
		if (productMapper.update(product) == 0) {
			log.warn("更新失败, 商品不存在, id={}", product.getId());
			throw new BizException(ErrorCode.PRODUCT_NOT_FOUND);
		}
	}

	@CacheEvict(cacheNames = "product", key = "#id")
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
