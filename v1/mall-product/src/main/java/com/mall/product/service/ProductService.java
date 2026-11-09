package com.mall.product.service;

import com.mall.product.entity.Product;

public interface ProductService {

    Product getById(Long id);

    /** 新增商品，返回数据库生成的 id */
    Long save(Product product);

    /** 更新商品，商品不存在时抛业务异常 */
    void update(Product product);

    /** 删除商品，商品不存在时抛业务异常 */
    void deleteById(Long id);
}
