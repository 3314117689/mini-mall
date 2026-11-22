package com.mall.common.auth;

import java.util.Arrays;

import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.mall.common.exception.BizException;
import com.mall.common.exception.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 统一验 token 的拦截器（L6-4）。
 *
 * 三个时刻：
 * 1. preHandle：请求进 Controller 之前，验 Authorization 头 → 验签 → 存 UserContext；
 * 2. Controller/Service 执行：随时从 UserContext 取当前用户；
 * 3. afterCompletion：请求结束，无论成功失败都清掉 ThreadLocal。
 */
public class JwtInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtVerifier jwtVerifier;

    public JwtInterceptor(JwtVerifier jwtVerifier) {
        this.jwtVerifier = jwtVerifier;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        LoginUser user = jwtVerifier.verify(token);
        // 角色校验（L6-6）：方法上标了 @RequireRole 就检查，没标就只验登录
        if (handler instanceof HandlerMethod handlerMethod) {
            RequireRole requireRole = handlerMethod.getMethodAnnotation(RequireRole.class);
            if (requireRole != null && !Arrays.asList(requireRole.value()).contains(user.getRole())) {
                throw new BizException(ErrorCode.FORBIDDEN);
            }
        }
        UserContext.set(user);
        UserContext.setToken(token);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
            Object handler, Exception ex) {
        // 必须清：Tomcat 线程会被复用，不清就会把身份带给下一个请求
        UserContext.clear();
    }
}
