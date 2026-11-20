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

import com.mall.common.result.Result;
import com.mall.user.entity.User;
import com.mall.user.service.UserService;

/**
 * 用户接口：按《用户服务接口文档 v1.0》实现。
 * 注意 Controller 的 import 里没有 mapper 包——业务只经过 Service 层。
 */
@RestController
@RequestMapping("/user")
public class UserController {

    @Autowired
    private UserService userService;

    @GetMapping("/{id}")
    public Result<User> getById(@PathVariable Long id) {
        return Result.ok(userService.getById(id));
    }

    /** 新增：参数从请求体 JSON 来，用 @RequestBody 接收（和查询的 @PathVariable 不同） */
    @PostMapping
    public Result<Long> save(@RequestBody User user) {
        return Result.ok(userService.save(user));
    }

    @PutMapping
    public Result<Void> update(@RequestBody User user) {
        userService.update(user);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteById(@PathVariable Long id) {
        userService.deleteById(id);
        return Result.ok();
    }
}
