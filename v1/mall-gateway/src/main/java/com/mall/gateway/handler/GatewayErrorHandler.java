package com.mall.gateway.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.exception.ErrorCode;
import com.mall.common.result.Result;

import reactor.core.publisher.Mono;

/**
 * 网关统一错误响应（L7-5）。
 *
 * 网关自己产生的 404（没有路由）和 503（没有实例）默认是 Spring 的错误 JSON，
 * 和业务服务的 Result 格式不一致。这里接管 WebFlux 的异常出口，统一成
 * {"code":...,"msg":"...","data":null}。
 *
 * 为什么用 ErrorWebExceptionHandler 而不是 GlobalFilter：
 * 404 发生在"还没匹配到路由"的阶段，GlobalFilter 根本不会执行，必须在异常出口处理。
 */
@Component
@Order(-2)
public class GatewayErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        // 响应已经提交就没法再改，交回默认处理
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }

        ErrorCode errorCode = resolve(ex);
        log.warn("网关异常, path={}, code={}, ex={}",
                exchange.getRequest().getPath().value(), errorCode.getCode(), ex.toString());

        ServerHttpResponse response = exchange.getResponse();
        // 沿用全工程约定：HTTP 200，业务码放 body.code
        response.setStatusCode(HttpStatus.OK);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(Result.fail(errorCode));
            return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
    }

    private ErrorCode resolve(Throwable ex) {
        if (ex instanceof ResponseStatusException statusException) {
            int status = statusException.getStatusCode().value();
            if (status == HttpStatus.NOT_FOUND.value()) {
                return ErrorCode.NOT_FOUND;
            }
            if (status == HttpStatus.SERVICE_UNAVAILABLE.value()) {
                return ErrorCode.SERVICE_UNAVAILABLE;
            }
        }
        return ErrorCode.SYSTEM_ERROR;
    }
}
