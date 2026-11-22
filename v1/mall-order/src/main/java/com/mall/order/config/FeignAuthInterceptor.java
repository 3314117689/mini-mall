package com.mall.order.config;

import org.springframework.stereotype.Component;

import com.mall.common.auth.AuthHeaders;
import com.mall.common.auth.LoginUser;
import com.mall.common.auth.UserContext;

import feign.RequestInterceptor;
import feign.RequestTemplate;

/**
 * Feign 调用透传身份头（L7-3）。
 *
 * 服务间的 Feign 调用不经过网关，user-service 又只认网关身份头，
 * 所以 order 调 user 时必须把当前用户的三件套（id/username/role）带上。
 * 数据来源仍是 UserContext：进入 order 时 GatewayAuthInterceptor 已经把它们放进去了。
 *
 * 前提：Feign 默认同步执行，和 Controller 同一个线程，ThreadLocal 读得到；
 * 换成异步线程池就会丢（L9 处理）。
 */
@Component
public class FeignAuthInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        LoginUser user = UserContext.get();
        if (user == null) {
            return;
        }
        template.header(AuthHeaders.USER_ID, String.valueOf(user.getUserId()));
        template.header(AuthHeaders.USERNAME, user.getUsername());
        template.header(AuthHeaders.ROLE, user.getRole());
    }
}
