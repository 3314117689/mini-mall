package com.mall.product.config;

import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.BlockExceptionHandler;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.mall.common.exception.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sentinel 拦截响应定制（L4）。
 *
 * 为什么要有这个类：请求触发流控/熔断规则时，框架默认返回英文文本
 * "Blocked by Sentinel (flow limiting)"（HTTP 429），格式和全工程的
 * Result 不一致。实现 BlockExceptionHandler 接口并注册为 Bean，
 * 框架就会在拦截发生时调用它——这里是唯一需要自己写代码的地方。
 */
@Configuration
public class SentinelBlockConfig {

    /**
     * 拦截处理器：被 Sentinel 拦截的请求统一返回 Result JSON。
     * HTTP 状态码用 429（Too Many Requests），语义上表示"请求太频繁"；
     * body 里的 code 用全工程统一的 4290，前端只认 Result 结构。
     */
    @Bean
    public BlockExceptionHandler blockExceptionHandler() {
        return (HttpServletRequest request, HttpServletResponse response,
                String resourceName, BlockException e) -> {
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            // 手工拼 Result 的 JSON 结构：错误码和文案仍以 ErrorCode 为唯一来源。
            // 不用 ObjectMapper 是因为 Boot 4 默认换用 Jackson 3，无旧版 Bean 可注入
            String body = "{\"code\":" + ErrorCode.FLOW_LIMITED.getCode()
                    + ",\"msg\":\"" + ErrorCode.FLOW_LIMITED.getMsg() + "\",\"data\":null}";
            // 被拦截不影响另一条路：全局异常处理器管业务异常，这里管规则拦截
            response.getWriter().write(body);
        };
    }
}
