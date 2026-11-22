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

    /** 注册时用户名已被占用（L6-2） */
    USERNAME_EXISTS(1004, "用户名已存在"),

    /** 登录失败（L6-3）：用户不存在和密码错误共用一个码，不暴露用户名是否存在 */
    PASSWORD_ERROR(1005, "用户名或密码错误"),

    /** 没带 token（L6-4）：请求头没有 Authorization: Bearer ... */
    UNAUTHORIZED(4010, "未登录，请先登录"),

    /** token 无效（L6-4）：签名不对、已过期、格式错误、kid 找不到公钥 */
    TOKEN_INVALID(4011, "登录凭证无效或已过期"),

    /** 验签要用的 JWKS 拉不到（L6-4）：认证服务不可用 */
    AUTH_UNAVAILABLE(4012, "认证服务不可用，请稍后重试"),

    /** 已登录但角色不够（L6-6 RBAC） */
    FORBIDDEN(4030, "无权限访问"),

    /** 网关没有匹配到任何路由（L7-5）：路径写错或前缀不是 /api */
    NOT_FOUND(4040, "接口不存在"),

    /** 网关匹配到路由，但注册中心里没有可用实例（L7-5） */
    SERVICE_UNAVAILABLE(5030, "服务不可用，请稍后重试"),

    /** 用户服务不可用（Sentinel 降级：调用不通时由 fallback 抛出，L4） */
    USER_SERVICE_UNAVAILABLE(2002, "用户服务不可用，请稍后重试"),

    /** 商品服务不可用（Sentinel 降级：调用不通时由 fallback 抛出，L4） */
    PRODUCT_SERVICE_UNAVAILABLE(2003, "商品服务不可用，请稍后重试"),

    /** Sentinel 限流/熔断拦截（L4）：触发规则的请求由拦截处理器统一返回此文案 */
    FLOW_LIMITED(4290, "请求过于频繁，请稍后再试"),

    /** 下单线程池已满（L9-1）：有界队列 + AbortPolicy 的反压兜底 */
    POOL_BUSY(4291, "系统繁忙，请稍后重试"),

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
