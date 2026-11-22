package com.mall.common.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 Controller 参数"从当前登录用户取"（L6-4）。
 * 例如：public Result<Long> create(@RequestBody OrderCreateDTO dto, @CurrentUser Long userId)
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
