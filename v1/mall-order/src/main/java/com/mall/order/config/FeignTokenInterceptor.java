package com.mall.order.config;

import org.springframework.stereotype.Component;

import com.mall.common.auth.UserContext;

import feign.RequestInterceptor;
import feign.RequestTemplate;

/**
 * Feign 调用透传 token（L6-5）。
 *
 * 为什么需要：order 收到的是"用户的票"，user-service 也装了拦截器；
 * 如果 order 调 user-service 时不带票，会被当成未登录。
 * 请求头只在当前这次 HTTP 请求里，所以从 UserContext 取出来，原样加到 Feign 请求上。
 *
 * 前提：Feign 默认同步执行，和 Controller 在同一个线程，ThreadLocal 读得到。
 * 以后如果换成异步线程池调用，这里会读不到（L9 会再遇到同类问题）。
 */
@Component
public class FeignTokenInterceptor implements RequestInterceptor {

    private static final String AUTHORIZATION = "Authorization";

    @Override
    public void apply(RequestTemplate template) {
        String token = UserContext.getToken();
        if (token != null && !template.headers().containsKey(AUTHORIZATION)) {
            template.header(AUTHORIZATION, "Bearer " + token);
        }
    }
}
