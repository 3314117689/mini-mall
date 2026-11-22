package com.mall.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.exception.ErrorCode;
import com.mall.common.result.Result;

import reactor.core.publisher.Mono;

/**
 * 限流响应统一化（L7-4）。
 *
 * RequestRateLimiter 超过阈值时只做两件事：把状态码设成 429、调用 setComplete() 结束响应，
 * 不带任何响应体。前端只认 body.code，所以这里包一层响应装饰器：
 * 发现"429 + 准备直接结束"时，改写状态为 200 并输出统一的 {"code":4290,...}。
 *
 * 顺序在 AuthGlobalFilter(-100) 之后、路由过滤器(1 起)之前，
 * 这样才能包住 RequestRateLimiter 产生的响应。
 */
@Component
public class RateLimitResponseFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitResponseFilter.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpResponseDecorator decorated = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> setComplete() {
                // 只有限流器拒绝时才是 429；业务服务不会主动返回 429
                if (HttpStatus.TOO_MANY_REQUESTS.equals(getStatusCode())) {
                    setStatusCode(HttpStatus.OK);
                    getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    try {
                        byte[] bytes = objectMapper.writeValueAsBytes(Result.fail(ErrorCode.FLOW_LIMITED));
                        DataBuffer buffer = bufferFactory().wrap(bytes);
                        return super.writeWith(Mono.just(buffer));
                    } catch (JsonProcessingException e) {
                        log.error("网关写限流响应失败", e);
                    }
                }
                return super.setComplete();
            }
        };
        return chain.filter(exchange.mutate().response(decorated).build());
    }

    @Override
    public int getOrder() {
        return -90;
    }
}
