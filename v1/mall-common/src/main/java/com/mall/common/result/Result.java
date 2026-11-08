package com.mall.common.result;

import lombok.Data;

/**
 * 统一返回体：所有接口的出参都用这个结构，
 * 前端/小程序只需要认一种格式，判断 code 是否为 0 即可。
 *
 * @param <T> 业务数据的类型
 */
@Data
public class Result<T> {

    /** 业务状态码：0 成功，非 0 失败（区别于 HTTP 状态码） */
    private int code;

    /** 提示信息 */
    private String msg;

    /** 业务数据 */
    private T data;

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.setCode(0);
        r.setMsg("ok");
        r.setData(data);
        return r;
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(int code, String msg) {
        Result<T> r = new Result<>();
        r.setCode(code);
        r.setMsg(msg);
        return r;
    }
}
