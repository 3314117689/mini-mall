package com.mall.common.auth;

/**
 * 当前用户上下文（L6-4）：一次请求内，任何一层都能拿到登录用户，不用层层传参。
 *
 * 为什么用 ThreadLocal：Tomcat 一个请求一个线程，请求进来时拦截器 set，
 * 同线程里 Controller/Service 直接 get。请求结束必须 clear，否则线程被复用
 * 后下一个请求会读到上一个用户的身份（串号）。
 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    /** 原始 token（L6-5）：Feign 调下游时要把同一张票转交过去，所以也放上下文 */
    private static final ThreadLocal<String> TOKEN_HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    public static void setToken(String token) {
        TOKEN_HOLDER.set(token);
    }

    public static String getToken() {
        return TOKEN_HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
        TOKEN_HOLDER.remove();
    }
}
