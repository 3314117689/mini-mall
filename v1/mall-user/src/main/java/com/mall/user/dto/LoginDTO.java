package com.mall.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 登录请求参数（L6-3）：只收用户名和密码。
 * 不直接复用 User，避免客户端顺手传 id/role 之类的字段进来。
 *
 * L10-4：只校验"非空"和长度上限；不校验密码最小长度，
 * 否则"密码太短"会变成 1000，与"密码错误"的 1005 混在一起反而暴露信息。
 */
@Data
public class LoginDTO {

    @NotBlank(message = "不能为空")
    @Size(max = 20, message = "长度不能超过 20 个字符")
    private String username;

    @NotBlank(message = "不能为空")
    @Size(max = 32, message = "长度不能超过 32 个字符")
    private String password;
}
