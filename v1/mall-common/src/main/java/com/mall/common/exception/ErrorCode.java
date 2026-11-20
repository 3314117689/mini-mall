package com.mall.common.exception;

/**
 * 业务错误码枚举：全工程统一的 code 分段约定。
 * 0        成功
 * 1xxx     通用/参数类错误
 * 1001起   各服务业务错误（用户、商品、订单各自登记）
 * 5000     系统未知异常
 *
 * 用枚举而不是散落的魔法数字：错误码有名字、有说明，改一处全工程生效。
 */
public enum ErrorCode {

    /** 参数错误：必填项缺失或取值非法 */
    PARAM_ERROR(1000, "参数错误"),

    /** 用户不存在 */
    USER_NOT_FOUND(1001, "用户不存在"),
    
    /** 商品不存在 */
    PRODUCT_NOT_FOUND(1002, "商品不存在"),
    
    /** 订单不存在 */
    ORDER_NOT_FOUND(1003, "订单不存在"),

    /** 用户服务不可用（Sentinel 降级：调用不通时由 fallback 抛出，L4） */
    USER_SERVICE_UNAVAILABLE(2002, "用户服务不可用，请稍后重试"),

    /** 商品服务不可用（Sentinel 降级：调用不通时由 fallback 抛出，L4） */
    PRODUCT_SERVICE_UNAVAILABLE(2003, "商品服务不可用，请稍后重试"),

    /** Sentinel 限流/熔断拦截（L4）：触发规则的请求由拦截处理器统一返回此文案 */
    FLOW_LIMITED(4290, "请求过于频繁，请稍后再试"),

    /** 未捕获的系统异常兜底 */
    SYSTEM_ERROR(5000, "系统繁忙，请稍后再试");

    private final int code;
    private final String msg;

    ErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }
}
