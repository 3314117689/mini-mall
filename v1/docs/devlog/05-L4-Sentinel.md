# 05 L4 Sentinel 熔断限流

## 一、这一层解决什么

L3 里下游挂了，上游只能"友好失败"；但流量大时上游会跟着一起死（雪崩）。L4 让上游在**调用前**先问一道闸机：放不放行、要不要走备胎。

## 二、相关文件与归属

| 名字 | 归属 | 作用 |
|---|---|---|
| `RemoteService` | 本项目，`mall-order/.../remote/RemoteService.java` | 把远程调用收口，注解挂在这里 |
| `SentinelBlockConfig` | 本项目，`mall-product/.../config/SentinelBlockConfig.java` | 注册全局 `BlockExceptionHandler`，把拦截结果写成 4290 JSON |
| `@SentinelResource` | 框架：Sentinel | 把方法登记成"资源"，才能配规则 |
| `fallback` / `blockHandler` | 框架：Sentinel | 两种备用方法：接异常 / 接规则拦截 |
| Sentinel 控制台 | 外部进程 | 推送规则到客户端内存 |

## 三、`POST /order/create` 的四条结局

**命令入场**：`POST http://localhost:8083/order/create`，body `{"userId":1,"productId":1,"num":2}`

### 调用链（正常路径）

| 顺序 | 谁 | 干了什么 |
|---|---|---|
| 1 | `OrderController` | 接 `OrderCreateDTO` |
| 2 | `OrderServiceImpl.create` | 字段校验 → 调 `remoteService.getUser(1)` |
| 3 | `RemoteService.getUser` | `@SentinelResource` 标记的资源方法 |
| 4 | `UserClient.getById` | Feign 请求 user-service |
| 5 | `RemoteService.getUser` | 判 `code`、判 `data` |
| 6 | `OrderServiceImpl.create` | 同样调 `remoteService.getProduct(1)` |
| 7 | `OrderServiceImpl.create` | 服务端算金额 → 写库 → 返回订单 id |

### 数据编号

| 编号 | 数据 | 形状 | 谁产生 | 去向 |
|---|---|---|---|---|
| `[D1]` | 下单请求 | `OrderCreateDTO{userId=1,productId=1,num=2}` | 框架从 JSON 转换 | `OrderServiceImpl.create` 参数 |
| `[D2]` | 用户对象 | `UserDTO{id=1,...}` | user-service 经 Feign 返回 | 只用于确认用户存在 |
| `[D3]` | 商品对象 | `ProductDTO{id=1,price=9.90}` | product-service 经 Feign 返回 | 取 `price` 算钱 |
| `[D4]` | 金额 | `BigDecimal 19.80` | `OrderServiceImpl` 服务端算 | 写 `orders.amount` |
| `[D5]` | 订单 id | `Long 5` | MySQL 自增回填 | 包进 `Result` |
| `[D6]` | 响应 | `{"code":0,"data":5}` | 框架序列化 | 浏览器 |

### 结局表

| 结局 | 条件 | 走哪个方法 | 浏览器收到 |
|---|---|---|---|
| ① 正常 | 用户/商品都在，未超阈值 | 资源方法本体 | `{"code":0,"data":5}` |
| ② 商品服务挂了 | Feign 连接被拒，抛非 `BizException` | `getProductFallback(1, t)` | `{"code":2003,"msg":"商品服务不可用，请稍后重试"}` |
| ③ 用户不存在 | 对方 HTTP 正常，但 `code=1001`，你主动抛 `BizException` | 仍进 `getUserFallback(1, t)`，但 `t` 是 `BizException` → **原样重抛** | `{"code":1001,"msg":"用户不存在"}` |
| ④ 触发限流规则 | QPS 超过阈值 | `getUserBlocked(1, e)`（`BlockException`） | `{"code":4290,"msg":"请求过于频繁，请稍后再试"}` |

**结局②③ 的关键区别**：两者都进 fallback，但 fallback 里要"分诊"——`BizException` 原样放行（业务失败不能被降级文案掩盖），其它异常才翻译成"服务不可用"。

**结局④ 走的是另一条路**：`blockHandler` 只接 `BlockException`（规则拦截）；不写它，`BlockException` 会直接上抛变成 5000。

## 四、两道检查点（容易混）

| 检查点 | 写在哪 | 接什么 | 结果 |
|---|---|---|---|
| `blockHandler` | `RemoteService` 方法上，写在 `@SentinelResource` 里 | `BlockException` | 方法自己定义的降级逻辑（4290） |
| `BlockExceptionHandler` Bean | `SentinelBlockConfig` | 所有没被 `blockHandler` 接住的 `BlockException` | 全局兜底，写 429 响应体 |

> 记忆：**资源方法上有 `blockHandler` 就先走它；没写才落到全局 Bean。**

## 五、坑

1. `@SentinelResource` 不配 `blockHandler`，`BlockException` 直接上抛变 5000。
2. `fallback` 和 `blockHandler` 要成对写，且签名规则不同：`fallback` 末尾加 `Throwable t`，`blockHandler` 末尾加 `BlockException e`。
3. 资源名必须"从外面调进来"才生效（同类内部 `this.` 调用绕过代理）——所以远程调用要收口到 `RemoteService`。
4. 控制台配规则时，URL 资源名是**模板** `/product/{id}`，不是 `/product/1`。
5. 规则只在客户端内存里：重启丢失；控制台删除规则不会清客户端内存；推送后要回读客户端确认。

## 六、验收

- QPS=10 时单进程一秒内 15 连发 → `pass=10, block=5`。
- 停 product → 2003；userId=999 → 1001；QPS=1 第 2 单 → 4290。
