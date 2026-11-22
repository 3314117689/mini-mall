package com.mall.common.auth;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.mall.common.exception.BizException;
import com.mall.common.exception.ErrorCode;

/**
 * 把 @CurrentUser Long userId 变成"从 UserContext 取值"（L6-4）。
 *
 * 这样 Controller 不需要自己解析 token，也不需要从请求头里抠数据。
 */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && (Long.class.equals(parameter.getParameterType())
                        || long.class.equals(parameter.getParameterType()));
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        LoginUser user = UserContext.get();
        // 正常情况拦截器已经 set 过了；拿不到说明路由没走拦截器，属于配置错误
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return user.getUserId();
    }
}
