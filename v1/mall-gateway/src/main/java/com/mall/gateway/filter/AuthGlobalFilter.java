package com.mall.gateway.filter;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.auth.JwtVerifier;
import com.mall.common.auth.LoginUser;
import com.mall.common.exception.BizException;
import com.mall.common.exception.ErrorCode;
import com.mall.common.result.Result;

import reactor.core.publisher.Mono;

/**
 * 网关统一鉴权（L7-2）。
 *
 * 职责只有三件事：
 * 1. 白名单放行：登录/注册/JWKS，以及商品查询（GET/HEAD）；
 * 2. 其余请求验 Authorization: Bearer token，失败直接返回 Result JSON；
 * 3. 验签成功后，删掉客户端可能伪造的 X-Mall-User-* 头，写入可信身份再放行。
 *
 * 注意：网关是 WebFlux，不能用 MVC 的 HandlerInterceptor / @RestControllerAdvice，
 * 所以这里用 GlobalFilter + 手动写响应体。
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthGlobalFilter.class);

    /** 网关传给下游的身份头：下游只认这三个头，不再自己验 token（L7-3 接入） */
    public static final String HEADER_USER_ID = "X-Mall-User-Id";
    public static final String HEADER_USERNAME = "X-Mall-Username";
    public static final String HEADER_ROLE = "X-Mall-Role";

    private static final String BEARER_PREFIX = "Bearer ";

    /** 完全公开的路径：登录、注册、公钥端点 */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/jwks");

    private final JwtVerifier jwtVerifier;

    /**
     * 这里自建 Jackson 2 的 ObjectMapper，而不是从容器注入：
     * Boot 4 自动配置的是 Jackson 3（tools.jackson），类型与 jjwt 依赖的 Jackson 2 不同，
     * 按 com.fasterxml 类型注入会找不到 Bean。错误响应结构很简单，自建一个线程安全的实例即可。
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuthGlobalFilter(JwtVerifier jwtVerifier) {
        this.jwtVerifier = jwtVerifier;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        // 公开接口直接放行；但仍要剥掉客户端伪造的身份头，避免下游误信
        if (isPublic(path, request.getMethod())) {
            return chain.filter(withoutIdentityHeaders(exchange));
        }

        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            log.warn("网关鉴权失败, path={}, 原因=没有 Bearer token", path);
            return writeError(exchange, ErrorCode.UNAUTHORIZED);
        }

        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        LoginUser user;
        try {
            user = jwtVerifier.verify(token);
        } catch (BizException e) {
            // 4011（token 无效）和 4012（认证服务拉不到 JWKS）都从这里出去
            log.warn("网关鉴权失败, path={}, code={}", path, e.getCode());
            return writeError(exchange, e.getCode(), e.getMessage());
        }

        ServerHttpRequest mutated = request.mutate()
                .headers(headers -> {
                    // 先删客户端带来的同名头，再写验签后的真实身份
                    headers.remove(HEADER_USER_ID);
                    headers.remove(HEADER_USERNAME);
                    headers.remove(HEADER_ROLE);
                    headers.set(HEADER_USER_ID, String.valueOf(user.getUserId()));
                    headers.set(HEADER_USERNAME, user.getUsername());
                    headers.set(HEADER_ROLE, user.getRole());
                })
                .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    /**
     * 公开路径判断。
     * OPTIONS 预检直接放行；商品只公开读，写操作仍然要登录（L7 约定）。
     */
    private boolean isPublic(String path, HttpMethod method) {
        if (HttpMethod.OPTIONS.equals(method)) {
            return true;
        }
        if (PUBLIC_PATHS.contains(path)) {
            return true;
        }
        return path.startsWith("/api/product")
                && (HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method));
    }

    /** 放行前统一删除身份头：客户端自己带的 X-Mall-* 一律不可信 */
    private ServerWebExchange withoutIdentityHeaders(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(HEADER_USER_ID);
                    headers.remove(HEADER_USERNAME);
                    headers.remove(HEADER_ROLE);
                })
                .build();
        return exchange.mutate().request(request).build();
    }

    private Mono<Void> writeError(ServerWebExchange exchange, ErrorCode errorCode) {
        return writeError(exchange, errorCode.getCode(), errorCode.getMsg());
    }

    /** 沿用全工程约定：HTTP 200，前端只看 body 里的 code */
    private Mono<Void> writeError(ServerWebExchange exchange, int code, String msg) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.OK);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(Result.fail(code, msg));
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        } catch (JsonProcessingException e) {
            log.error("网关写错误响应失败", e);
            return response.setComplete();
        }
    }

    @Override
    public int getOrder() {
        // 必须早于路由自带的过滤器（StripPrefix 等）：此时路径还是 /api/**，
        // 身份头也要在转发前写完
        return -100;
    }
}
