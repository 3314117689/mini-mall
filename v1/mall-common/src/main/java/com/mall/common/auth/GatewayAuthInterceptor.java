package com.mall.common.auth;

import java.util.Arrays;

import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.mall.common.exception.BizException;
import com.mall.common.exception.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 信任网关的鉴权拦截器（L7-3）。
 *
 * 网关（AuthGlobalFilter）已经验过 token，然后把身份写进三个头；
 * 业务服务不再验签，只读这三个头重建 LoginUser。
 *
 * 角色校验仍然留在这里：@RequireRole 标在 Controller 方法上，网关看不到方法注解，
 * 所以"你是谁"由网关负责，"你能不能调这个接口"由服务自己负责。
 */
public class GatewayAuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String userId = request.getHeader(AuthHeaders.USER_ID);
        String username = request.getHeader(AuthHeaders.USERNAME);
        String role = request.getHeader(AuthHeaders.ROLE);
        // 三个头缺一个，说明请求不是网关放过来的（或网关没注入），按未登录处理
        if (userId == null || username == null || role == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }

        LoginUser user = new LoginUser(Long.valueOf(userId), username, role);
        // 角色校验：标了 @RequireRole 就检查，没标就只要求已登录
        if (handler instanceof HandlerMethod handlerMethod) {
            RequireRole requireRole = handlerMethod.getMethodAnnotation(RequireRole.class);
            if (requireRole != null && !Arrays.asList(requireRole.value()).contains(user.getRole())) {
                throw new BizException(ErrorCode.FORBIDDEN);
            }
        }

        UserContext.set(user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
            Object handler, Exception ex) {
        // Tomcat 线程会复用，必须清掉本次请求的身份
        UserContext.clear();
    }
}
