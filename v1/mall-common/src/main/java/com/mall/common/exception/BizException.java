package com.mall.common.exception;

import lombok.Getter;

/**
 * 业务异常：Service 层发现业务规则不满足时抛出，
 * 由 GlobalExceptionHandler 统一捕获并转换成 Result 返回给前端。
 *
 * 继承 RuntimeException 而不是 Exception：
 * 非受检异常不需要方法签名上层层声明 throws，业务代码更干净。
 */
@Getter
public class BizException extends RuntimeException {

    /** 业务错误码（区别于 HTTP 状态码） */
    private final int code;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMsg());
        this.code = errorCode.getCode();
    }

    public BizException(int code, String msg) {
        super(msg);
        this.code = code;
    }
}
