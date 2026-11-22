package com.mall.user.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mall.common.auth.RequireRole;
import com.mall.common.result.Result;
import com.mall.user.config.OpenApiConfig;
import com.mall.user.dto.RegisterDTO;
import com.mall.user.entity.User;
import com.mall.user.service.UserService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * 用户接口：按《用户服务接口文档 v1.0》实现。
 * 注意 Controller 的 import 里没有 mapper 包——业务只经过 Service 层。
 */
@Tag(name = "用户服务", description = "用户增删改查，全部接口需要 Bearer token")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/user")
public class UserController {

    @Autowired
    private UserService userService;

    @Operation(summary = "按 id 查询用户")
    @GetMapping("/{id}")
    public Result<User> getById(@PathVariable Long id) {
        return Result.ok(userService.getById(id));
    }

    /** 新增：参数从请求体 JSON 来，用 @RequestBody 接收（和查询的 @PathVariable 不同） */
    @Operation(summary = "新增用户")
    @PostMapping
    public Result<Long> save(@Valid @RequestBody RegisterDTO dto) {
        return Result.ok(userService.save(dto));
    }

    @Operation(summary = "更新用户")
    @PutMapping
    public Result<Void> update(@RequestBody User user) {
        userService.update(user);
        return Result.ok();
    }

    /** 删除用户是管理操作：只有 ADMIN 角色能调（L6-6） */
    @Operation(summary = "删除用户", description = "仅 ADMIN 角色可调")
    @RequireRole("ADMIN")
    @DeleteMapping("/{id}")
    public Result<Void> deleteById(@PathVariable Long id) {
        userService.deleteById(id);
        return Result.ok();
    }
}
