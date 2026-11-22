package com.mall.gateway.config;

import java.net.InetSocketAddress;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.mall.common.auth.AuthHeaders;

import reactor.core.publisher.Mono;

/**
 * 网关限流的分桶规则（L7-4）。
 *
 * KeyResolver 只决定"这笔账记在谁头上"，同一个 Key 共用一个令牌桶：
 * - 登录用户：按 userId 分桶，各自有独立额度；
 * - 未登录（如商品浏览）：按客户端 IP 兜底。
 *
 * 这个 Key 同时就是 Redis 计数键的一部分。
 */
@Configuration
public class RateLimitConfig {

    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> {
            // AuthGlobalFilter（order = -100）已经先执行，验签后的用户 id 在请求头里
            String userId = exchange.getRequest().getHeaders().getFirst(AuthHeaders.USER_ID);
            if (userId != null && !userId.isBlank()) {
                return Mono.just("user:" + userId);
            }
            InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
            String ip = remote == null ? "unknown" : remote.getAddress().getHostAddress();
            return Mono.just("ip:" + ip);
        };
    }
}
