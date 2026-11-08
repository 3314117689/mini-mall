package com.mall.common.exception;

import lombok.extern.slf4j.Slf4j;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.mall.common.result.Result;

/**
 * 全局异常处理器：拦截所有 Controller 抛出的异常，统一转换成 Result 结构。
 *
 * 以前的做法：Service 返回 null → Controller 包成 Result.ok(null) → 前端拿到 code=0 但没数据，
 * 既不知道是"没有"还是"出错"，也没法给出友好提示。
 *
 * 现在的做法：Service 直接抛 BizException → 这里统一捕获 → 返回带错误码的 Result。
 * 好处：Service 里不用写 try-catch，Controller 保持一行式，前端永远拿到同一种结构。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：预期内的失败，warn 级别即可，前端拿错误码展示提示 */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException e) {
        log.warn("业务异常: code={}, msg={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /** 兜底：未预期的异常，error 级别带堆栈，方便排查，但对前端隐藏细节 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.fail(ErrorCode.SYSTEM_ERROR.getCode(), ErrorCode.SYSTEM_ERROR.getMsg());
    }
}
