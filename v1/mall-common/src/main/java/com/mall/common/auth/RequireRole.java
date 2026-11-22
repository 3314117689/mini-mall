package com.mall.common.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 角色校验（L6-6）：标在 Controller 方法上，要求当前用户具备其中一个角色。
 * 例：@RequireRole("ADMIN") 只有管理员能调。
 *
 * 校验由 JwtInterceptor 统一做——注解只声明规则，不写逻辑。
 */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    /** 允许访问的角色列表，满足任意一个即可 */
    String[] value();
}
