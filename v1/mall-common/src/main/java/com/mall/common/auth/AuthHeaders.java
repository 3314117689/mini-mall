package com.mall.common.auth;

/**
 * 网关与业务服务之间的身份头约定（L7-3）。
 *
 * 单独放一个纯常量类，不实现 HandlerInterceptor、不碰 Servlet：
 * 网关是 WebFlux，如果直接引用 GatewayAuthInterceptor 里的常量，
 * 类加载时会因为找不到 jakarta.servlet 而失败。
 */
public final class AuthHeaders {

    public static final String USER_ID = "X-Mall-User-Id";

    public static final String USERNAME = "X-Mall-Username";

    public static final String ROLE = "X-Mall-Role";

    private AuthHeaders() {
    }
}
