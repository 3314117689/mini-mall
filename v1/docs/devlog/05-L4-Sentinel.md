# 05 L4 Sentinel 熔断限流

> 按铁律 11：总览表只做导航，过程在"逐步行进"里。

## 一、这一层解决什么

L3 里下游挂了，上游只能"友好失败"；但流量大时上游会跟着一起死（雪崩）。L4 让上游在**调用前**先问一道闸机：放不放行、要不要走备胎。

## 二、涉及文件与归属

| 名字 | 归属 | 作用 |
|---|---|---|
| `OrderServiceImpl` | 本项目，`mall-order/.../service/impl/OrderServiceImpl.java` | 下单编排（第 59-80 行） |
| `RemoteService` | 本项目，`mall-order/.../remote/RemoteService.java` | 远程调用收口，`@SentinelResource` 挂在这里 |
| `UserClient` / `ProductClient` | 本项目，`mall-order/.../client/` | Feign 接口，按服务名调对方 |
| `SentinelBlockConfig` | 本项目，`mall-product/.../config/SentinelBlockConfig.java` | 全局 `BlockExceptionHandler`，兜底写 4290 |
| `@SentinelResource` | 框架：Sentinel | 把方法登记成资源，才能配规则 |
| `fallback` / `blockHandler` | 框架：Sentinel | 两种备胎：接异常 / 接规则拦截 |

## 三、`POST /order/create` 的调用链

**命令入场**：`POST http://localhost:8083/order/create`，body `{"userId":1,"productId":1,"num":2}`

| 顺序 | 谁 | 在哪 | 干了什么 |
|---|---|---|---|
| 1 | Tomcat 线程 | — | 按路径找到 `OrderController` 的下单方法 |
| 2 | 框架 | — | JSON body → `OrderCreateDTO` |
| 3 | `OrderController.create` | — | 调 `orderService.create(dto)` |
| 4 | `OrderServiceImpl.create` | 第 60-63 行 | 四个字段校验 |
| 5 | `OrderServiceImpl.create` | 第 67 行 | 调 `remoteService.getUser(userId)` |
| 6 | `RemoteService.getUser` | 第 43-50 行 | Sentinel 资源：先过闸机，再调 `userClient` |
| 7 | `UserClient.getById` | 第 18-19 行 | Feign 发 `GET /user/{id}` |
| 8 | `OrderServiceImpl.create` | 第 68 行 | 调 `remoteService.getProduct(productId)` |
| 9 | `RemoteService.getProduct` | 第 72-79 行 | 同上 |
| 10 | `OrderServiceImpl.create` | 第 75 行 | 服务端算金额 `price × num` |
| 11 | `OrderServiceImpl.create` | 第 76 行 | `orderMapper.insert(order)` 写库 |
| 12 | `OrderServiceImpl.create` | 第 79 行 | 返回订单 id |

**数据编号**

| 编号 | 数据 | 初始值 | 谁产生 |
|---|---|---|---|
| `[D1]` | 下单请求 | `OrderCreateDTO{userId=1, productId=1, num=2}` | 框架从 JSON 转换 |
| `[D2]` | 用户对象 | `UserDTO{id=1,...}` | user-service 经 Feign 返回 |
| `[D3]` | 商品对象 | `ProductDTO{id=1, price=9.90}` | product-service 经 Feign 返回 |
| `[D4]` | 订单金额 | `BigDecimal 19.80` | `OrderServiceImpl` 服务端算 |
| `[D5]` | 订单 id | `Long 5` | MySQL 自增回填 |
| `[D6]` | 响应 | `{"code":0,"data":5}` | 框架序列化 |

**四个结局（总览）**

| 结局 | 条件 | 走哪个方法 | 浏览器 |
|---|---|---|---|
| ① 正常 | 用户/商品都在，未超阈值 | 资源方法本体 | `code=0` |
| ② 商品服务挂了 | Feign 连接失败 | `getProductFallback(1, t)` | `code=2003` |
| ③ 用户不存在 | 对方返回 `code=1001`，你主动抛 `BizException` | `getUserFallback(1, t)` → 原样重抛 | `code=1001` |
| ④ 触发限流 | QPS 超阈值 | `getUserBlocked(1, e)` | `code=4290` |

---

## 四、结局①逐步行进：正常

### 第 1 步｜请求到达

- 【框架动作】Tomcat 派线程；按路径找到 Controller 方法；JSON body 转成 `OrderCreateDTO`。
- 【数据】`[D1] = OrderCreateDTO{userId=1, productId=1, num=2}` · 来源：请求体 · 去向：`orderService.create(dto)`。

### 第 2 步｜字段校验

```java
// 文件：mall-order/src/main/java/com/mall/order/service/impl/OrderServiceImpl.java
// 行号：60-63
if (dto.getUserId() == null || dto.getProductId() == null
        || dto.getNum() == null || dto.getNum() <= 0) {
    throw new BizException(ErrorCode.PARAM_ERROR);
}
```

- 【你写的】四个条件任一成立就抛 1000。
- 【数据】`[D1]` 三个字段都不是 null 且 `num=2 > 0` → 条件 false → 继续。

### 第 3 步｜打"下单"日志

```java
// 行号：64-65
log.info("下单, userId={}, productId={}, num={}",
        dto.getUserId(), dto.getProductId(), dto.getNum());
```

- 【你写的】从 `[D1]` 取三个字段打印。
- 【数据】`[D1]` 不变。

### 第 4 步｜调用户服务（进 Sentinel 资源）

```java
// 文件：mall-order/.../remote/RemoteService.java
// 行号：43-50
@SentinelResource(value = "getUser", fallback = "getUserFallback", blockHandler = "getUserBlocked")
public UserDTO getUser(Long userId) {
    Result<UserDTO> result = userClient.getById(userId);
    if (result == null || result.getCode() != 0 || result.getData() == null) {
        throw new BizException(ErrorCode.USER_NOT_FOUND);
    }
    return result.getData();
}
```

- 【注解】`@SentinelResource` 把 `getUser` 登记成资源名 `"getUser"`；`fallback`、`blockHandler` 指定两种备胎。
- 【框架动作】调用先过 Sentinel 闸机：没配规则或未超阈值 → 放行 → 执行方法体第 45 行。
- 【数据】`[D1]` 里的 `userId=1` 作为参数传进 `getUser(1L)`。

### 第 5 步｜Feign 发请求

```java
// 文件：mall-order/src/main/java/com/mall/order/client/UserClient.java
// 行号：15-20
@FeignClient(name = "user-service")
public interface UserClient {
    @GetMapping("/user/{id}")
    Result<UserDTO> getById(@PathVariable("id") Long id);
}
```

- 【你写的】`name="user-service"` 是注册中心里的服务名；`@GetMapping` 照抄对方 Controller。
- 【框架动作】Feign 找 LoadBalancer 要实例，按注解拼出 `http://IP:8081/user/1` 并发请求；收到 JSON 后按 `Result<UserDTO>` 反序列化。
- 【数据】`1L` → URL 里的 `1` → 对方返回 `{"code":0,"data":{"id":1,...}}` → `[D2]` 的载体 `Result<UserDTO>`。

### 第 6 步｜判 code，取出用户对象

- 【你写的】第 46 行三个条件（结果 null / code≠0 / data null）任一成立就抛 1001；都正常则第 49 行返回 `result.getData()`。
- 【数据】`Result<UserDTO>{code=0,...}` → `[D2] = UserDTO{id=1,...}` · 去向：`OrderServiceImpl` 第 67 行的变量 `user`。

### 第 7 步｜调商品服务

- 【你写的】`OrderServiceImpl` 第 68 行调 `remoteService.getProduct(1L)`；`RemoteService.getProduct` 第 72-79 行逻辑与第 4~6 步同构。
- 【数据】`1L` → Feign URL → `Result<ProductDTO>` → `[D3] = ProductDTO{id=1, price=9.90}` · 去向：第 68 行的变量 `product`。

### 第 8 步｜组装订单、服务端算钱

```java
// 行号：70-75
Order order = new Order();
order.setUserId(user.getId());
order.setProductId(product.getId());
order.setNum(dto.getNum());
order.setAmount(product.getPrice().multiply(BigDecimal.valueOf(dto.getNum())));
```

- 【你写的】用户 id、商品 id 取对方返回的对象；数量取请求；金额 = `price × num`，**客户端传的金额不参与**。
- 【数据】`[D2]`、`[D3]`、`[D1].num` → 组装出 `Order{userId=1, productId=1, num=2, amount=?}`；`9.90 × 2` → `[D4] = BigDecimal 19.80`。

### 第 9 步｜写库

```java
// 行号：76
orderMapper.insert(order);
```

- 【框架动作】MyBatis 执行 `insert into orders(...) values(...)`，把自增主键回填到 `order.id`。
- 【数据】`Order{...amount=19.80, id=null}` → `Order{..., id=5}`；`[D5] = 5L`。

### 第 10 步｜返回

```java
// 行号：78-79
log.info("下单成功, orderId={}, amount={}", order.getId(), order.getAmount());
return order.getId();
```

- 【你写的】打印成功日志，返回订单 id。
- 【数据】`[D5] = 5L` → 作为 `create` 返回值 → Controller 包装成 `[D6] = {"code":0,"data":5}`。

---

## 五、结局②逐步行进：商品服务挂了

**入场**：停掉 product-service，其余同结局①。

第 1~6 步同结局①（用户校验通过，`[D2]` 已拿到）；第 7 步开始分岔。

### 第 7 步｜Feign 调用失败

- 【框架动作】`ProductClient` 按服务名找不到可用实例，或连接被拒，抛 `RetryableException` / `ConnectException`（非 `BizException`）。
- 【数据】`[D3]` 没有产生；异常对象往上抛到 `RemoteService.getProduct`。

### 第 8 步｜Sentinel 捕获异常，转进 fallback

```java
// 文件：mall-order/.../remote/RemoteService.java
// 行号：82-88
public ProductDTO getProductFallback(Long productId, Throwable t) {
    if (t instanceof BizException) {
        throw (BizException) t;
    }
    log.error("商品服务降级, productId={}, 原因: {}", productId, t.getMessage());
    throw new BizException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
}
```

- 【注解】`fallback` 指定的方法：资源方法体抛出**非 BlockException** 时进这里。
- 【框架动作】把"原方法参数 + 到底出了什么异常"一起传给 fallback（所以第二个参数是 `Throwable t`）。
- 【数据】`t = ConnectException(...)`；`t instanceof BizException` 为 false → 走第 87 行；`[D3]` 仍然没有。

### 第 9 步｜翻译成降级文案

- 【你写的】抛 `BizException(PRODUCT_SERVICE_UNAVAILABLE)`（`code=2003`）。
- 【数据】异常对象 `BizException{code=2003, message="商品服务不可用，请稍后重试"}`。

### 第 10 步｜异常飞行并被接住

- 【框架动作】异常从 `remoteService.getProduct` 飞回 `OrderServiceImpl.create`，再飞回 Controller，最后被 `GlobalExceptionHandler` 接住。
- 【数据】`[D5]` 不产生（没写库）；`[D6] = {"code":2003,"msg":"商品服务不可用，请稍后重试"}`。

---

## 六、结局③逐步行进：用户不存在

**入场**：`userId=999`，user-service 正常响应，但返回 `{"code":1001,"data":null}`。

第 1~4 步同结局①（闸机放行，进入 `getUser` 方法体）。

### 第 5 步｜Feign 正常返回业务失败

- 【框架动作】HTTP 状态 200，body 是 `Result{code=1001,data=null}`；Feign 正常反序列化，**不抛异常**。
- 【数据】`result = Result{code=1001, data=null}`。

### 第 6 步｜你判 code 后主动抛异常

```java
// 行号：46-48
if (result == null || result.getCode() != 0 || result.getData() == null) {
    throw new BizException(ErrorCode.USER_NOT_FOUND);
}
```

- 【你写的】`code=1001 ≠ 0` 条件成立 → 抛 `BizException(USER_NOT_FOUND)`。
- 【数据】`Result{code=1001,...}` → `BizException{code=1001, message="用户不存在"}`。

### 第 7 步｜Sentinel 转进 fallback

- 【框架动作】方法体抛出非 `BlockException` → 进 `getUserFallback(999, t)`。
- 【数据】`t = BizException{code=1001}`。

### 第 8 步｜fallback 分诊：业务失败原样放行

```java
// 行号：57-63
public UserDTO getUserFallback(Long userId, Throwable t) {
    if (t instanceof BizException) {
        throw (BizException) t;
    }
    ...
}
```

- 【你写的】`t instanceof BizException` 为 true → 原样重抛，**不翻译成降级文案**。
- 【数据】同一个 `BizException{code=1001}` 继续上抛。

### 第 9 步｜异常出路

- 【框架动作】`GlobalExceptionHandler` 接住 → `Result.fail(1001,"用户不存在")`。
- 【数据】`[D6] = {"code":1001,"msg":"用户不存在"}`。

> 与结局②的区别：**业务失败必须原样透传**，否则"用户不存在"会被降级成"服务不可用"，前端和面试官都会被误导。

---

## 七、结局④逐步行进：触发限流规则

**入场**：给资源名 `getUser` 配了 QPS=1 的流控规则，1 秒内发第 2 单。

第 1~3 步同结局①（走到第 67 行准备调用）。

### 第 4 步｜闸机拦下，方法体不执行

- 【框架动作】Sentinel 检查资源 `getUser` 的 QPS 超阈值 → 抛 `BlockException`，**`getUser` 第 45-49 行一行都不执行**，`userClient` 也不会被调用。
- 【数据】`[D2]` 没有产生；`BlockException` 对象由框架造出。

### 第 5 步｜转进 `blockHandler`

```java
// 行号：66-69
public UserDTO getUserBlocked(Long userId, BlockException e) {
    log.warn("用户服务调用被 Sentinel 拦截, userId={}", userId);
    throw new BizException(ErrorCode.FLOW_LIMITED);
}
```

- 【注解】`blockHandler` 只接 `BlockException`；签名末尾固定是 `BlockException e`。
- 【框架动作】把原方法参数和被拦的 `BlockException` 一起传进来。
- 【数据】`userId=1`、`e = BlockException`。

### 第 6 步｜统一成 4290

- 【你写的】抛 `BizException(FLOW_LIMITED)`（`code=4290`）。
- 【数据】`BizException{code=4290, message="请求过于频繁，请稍后再试"}`。

### 第 7 步｜异常出路

- 【框架动作】`GlobalExceptionHandler` 接住。
- 【数据】`[D6] = {"code":4290,"msg":"请求过于频繁，请稍后再试"}`。

> 如果没写 `blockHandler`，`BlockException` 会直接上抛，被兜底处理器变成 `code=5000`——这是 L4-3 之前踩过的坑。

---

## 八、两道检查点（容易混）

| 检查点 | 写在哪 | 接什么 | 结果 |
|---|---|---|---|
| `blockHandler` | `RemoteService` 方法上，写进 `@SentinelResource` | `BlockException` | 4290 |
| `BlockExceptionHandler` Bean | `SentinelBlockConfig` | 所有没被 `blockHandler` 接住的 `BlockException` | 全局兜底 429 响应体 |

> 记忆：资源方法上有 `blockHandler` 就先走它；没写才落到全局 Bean。

## 九、坑

1. 只配 `fallback` 不配 `blockHandler`，规则拦截会变成 5000。
2. 两个备胎签名不同：`fallback` 末尾 `Throwable t`，`blockHandler` 末尾 `BlockException e`。
3. 资源方法必须"从外面调进来"；同类 `this.` 调用绕过代理，注解失效（所以收口到 `RemoteService`）。
4. 控制台配 URL 资源时写模板 `/product/{id}`，不是 `/product/1`。
5. 规则只在客户端内存：重启丢失；控制台删除不会清客户端内存；推送后要回读确认。

## 十、验收

- QPS=10，单进程一秒 15 连发 → `pass=10, block=5`。
- 停 product → 2003；`userId=999` → 1001；QPS=1 的第 2 单 → 4290。
