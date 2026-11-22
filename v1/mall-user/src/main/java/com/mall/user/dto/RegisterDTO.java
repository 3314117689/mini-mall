package com.mall.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求参数（L10-4）。
 * 只收 username/password/phone，客户端传 id、role 一律忽略；
 * 校验交给 JSR303，进入 Service 之前就会被拦下，message 由全局异常处理器拼上字段名。
 */
@Data
public class RegisterDTO {

    @NotBlank(message = "不能为空")
    @Size(min = 3, max = 20, message = "长度需在 3~20 个字符之间")
    private String username;

    @NotBlank(message = "不能为空")
    @Size(min = 6, max = 32, message = "长度需在 6~32 个字符之间")
    private String password;

    /** 手机号可选，只限制上限，防止超长字符串直接落库 */
    @Size(max = 20, message = "长度不能超过 20 个字符")
    private String phone;
}
