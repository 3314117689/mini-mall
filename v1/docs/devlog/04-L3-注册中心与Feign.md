# 04 L3 Nacos 注册中心 + OpenFeign

> 按铁律 11：总览表只做导航，过程在"逐步行进"里。
> 注意：L3 阶段远程调用直接写在 `OrderServiceImpl` 里；L4 才收口到 `RemoteService`。本章按**当前代码**写，涉及位置时标注"L4 后"。

## 一、这一层解决什么

L2 里地址写死（`localhost:8082`），服务换机器就废。L3 让服务**自己注册**到 Nacos，调用方**用服务名**找它。

## 二、启动期：注册链路（无业务代码、无业务数据）

### 第 1 步｜加依赖

```xml
<!-- 文件：mall-order/pom.xml（三个业务模块同理） -->
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-openfeign</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-loadbalancer</artifactId>
</dependency>
```

- 【你写的】声明要用注册中心、Feign、负载均衡。
- 【数据】无。

### 第 2 步｜配置注册中心地址

```yaml
# 文件：mall-order/src/main/resources/application.yml
spring:
  application:
    name: order-service
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
```

- 【你写的】`name` 是服务名（调用方就靠这个名字找你）；`server-addr` 是 Nacos 地址。
- 【数据】`服务名 = order-service`、`Nacos 地址 = 127.0.0.1:8848`。

### 第 3 步｜启动类只加 Feign 开关

```java
// 文件：mall-order/src/main/java/com/mall/order/OrderServiceApplication.java
@SpringBootApplication
@EnableFeignClients
public class OrderServiceApplication { ... }
```

- 【注解】`@EnableFeignClients` 让框架扫描 `@FeignClient` 接口并生成代理。
- 【框架动作】注册中心**不需要** `@EnableDiscoveryClient`，有依赖和配置就自动注册。

### 第 4 步｜框架把实例写进 Nacos

- 【框架动作】启动完成后，框架把 `服务名 + 本机 IP + 端口` 作为实例注册到 Nacos，并定时发心跳。
- 【数据】`order-service` / `192.168.x.x` / `8083` 三个值 → Nacos 的实例列表。你的代码里没有一行。

---

## 三、请求期：`POST /order/create` 的调用链

**命令入场**：`POST http://localhost:8083/order/create`，body `{"userId":1,"productId":1,"num":2}`

| 顺序 | 谁 | 在哪 | 干了什么 |
|---|---|---|---|
| 1 | Tomcat 线程 | — | 找到 `OrderController` 的下单方法 |
| 2 | 框架 | — | JSON body → `OrderCreateDTO` |
| 3 | `OrderServiceImpl.create` | 第 60-65 行 | 校验 + 打日志 |
| 4 | `OrderServiceImpl.create` | 第 67 行 | 调 `remoteService.getUser(1L)`（L4 前是直接调 `userClient`） |
| 5 | `RemoteService.getUser` | 第 43-50 行 | 调 `userClient.getById(1L)` |
| 6 | `UserClient.getById` | 第 15-20 行 | Feign 代理：服务名 + 注解 → HTTP |
| 7 | LoadBalancer + Nacos | — | 取 `user-service` 实例列表，选一个 |
| 8 | 对方 `UserController` | — | 处理请求，返回 `Result<UserDTO>` |
| 9 | Feign | — | 按 `Result<UserDTO>` 反序列化 |
| 10 | `OrderServiceImpl.create` | 第 68 行 | 同构调商品 |
| 11 | `OrderServiceImpl.create` | 第 70-79 行 | 算钱、写库、返回 id |

**数据编号**

| 编号 | 数据 | 初始值 | 谁产生 |
|---|---|---|---|
| `[D1]` | 下单请求 | `OrderCreateDTO{userId=1, productId=1, num=2}` | 框架从 JSON 转换 |
| `[D2]` | 用户服务 URL | `http://192.168.x.x:8081/user/1` | Feign 拼出 |
| `[D3]` | 用户返回 JSON | `{"code":0,"data":{"id":1,...}}` | user-service |
| `[D4]` | 用户结果对象 | `Result<UserDTO>` | Feign 反序列化 |
| `[D5]` | 用户对象 | `UserDTO{id=1,...}` | `[D4].getData()` |
| `[D6]` | 商品 URL | `http://192.168.x.x:8082/product/1` | Feign 拼出 |
| `[D7]` | 商品对象 | `ProductDTO{id=1, price=9.90}` | 经 Feign 返回 |
| `[D8]` | 订单 id | `Long 5` | MySQL 自增回填 |
| `[D9]` | 响应 | `{"code":0,"data":5}` | 框架序列化 |

**两个结局（L3 口径）**

| 结局 | 条件 | 谁处理 | 浏览器 |
|---|---|---|---|
| ① 正常 | Nacos 有实例、对方健康 | 一路走完 | `code=0` |
| ② 对方不可用 | 无实例或连接失败 | L3：捕获异常 → 2001；L4 后：`getUserFallback` / `getProductFallback` → 2002 / 2003 | `code=2001/2002/2003` |

---

## 四、结局①逐步行进：正常

### 第 1 步｜请求到达

- 【框架动作】Tomcat 派线程；按路径匹配到 `OrderController` 的下单方法；body 转 `OrderCreateDTO`。
- 【数据】`[D1] = OrderCreateDTO{userId=1, productId=1, num=2}`。

### 第 2 步｜字段校验

```java
// 文件：mall-order/.../service/impl/OrderServiceImpl.java
// 行号：60-63
if (dto.getUserId() == null || dto.getProductId() == null
        || dto.getNum() == null || dto.getNum() <= 0) {
    throw new BizException(ErrorCode.PARAM_ERROR);
}
```

- 【你写的】任一字段非法就抛 1000。
- 【数据】`[D1]` 合法 → 继续。

### 第 3 步｜准备调用户服务

- 【你写的】第 67 行 `UserDTO user = remoteService.getUser(dto.getUserId());`。
- 【数据】从 `[D1]` 取出 `userId=1` → 变成 `1L` 作为参数。

### 第 4 步｜进入 Feign 接口

```java
// 文件：mall-order/src/main/java/com/mall/order/client/UserClient.java
// 行号：15-20
@FeignClient(name = "user-service")
public interface UserClient {
    @GetMapping("/user/{id}")
    Result<UserDTO> getById(@PathVariable("id") Long id);
}
```

- 【你写的】`name` 写服务名，不写 IP；方法注解照抄对方 Controller。
- 【注解】`@FeignClient` 让框架给这个接口造代理（你 `new` 不出来，只能注入）。
- 【框架动作】调用被代理接住，开始"方法 → HTTP 请求"的翻译。
- 【数据】`1L` · 去向：`@PathVariable("id")` 占位。

### 第 5 步｜向 Nacos 要实例

- 【框架动作】LoadBalancer 按服务名 `user-service` 向 Nacos 查实例列表，按策略选一个（单实例就是它）。
- 【数据】`服务名 user-service` → 实例 `192.168.x.x:8081`（一个 IP + 端口）。

### 第 6 步｜拼出 URL

- 【框架动作】把 `@GetMapping("/user/{id}")` 的 `{id}` 换成 `1`，拼成完整 URL。
- 【数据】`1L` + 实例地址 + 路径模板 → `[D2] = "http://192.168.x.x:8081/user/1"` · 去向：HTTP 请求。

### 第 7 步｜对方接住并返回

- 【框架动作】对方 `UserController` 按路径匹配，执行查库，返回 `Result<UserDTO>`；框架序列化成 JSON。
- 【数据】`[D3] = {"code":0,"data":{"id":1,"name":"张三",...}}`。

### 第 8 步｜Feign 反序列化

- 【框架动作】Feign 按方法返回类型 `Result<UserDTO>` 把 JSON 还原成对象。
- 【数据】`[D3]` JSON → `[D4] = Result{code=0, data=UserDTO{...}}`。

### 第 9 步｜判 code，取出对象

```java
// 文件：mall-order/.../remote/RemoteService.java
// 行号：46-49
if (result == null || result.getCode() != 0 || result.getData() == null) {
    throw new BizException(ErrorCode.USER_NOT_FOUND);
}
return result.getData();
```

- 【你写的】`[D4]` 三个条件都不成立 → 返回 `getData()`。
- 【数据】`[D4]` → `[D5] = UserDTO{id=1,...}` · 去向：`OrderServiceImpl` 第 67 行的 `user` 变量。

### 第 10 步｜同构调商品服务

- 【你写的】第 68 行调 `getProduct(1L)`；第 4~9 步完全同构，只是服务名换成 `product-service`、DTO 换成 `ProductDTO`。
- 【数据】`1L` → `[D6] = "http://192.168.x.x:8082/product/1"` → JSON → `Result<ProductDTO>` → `[D7] = ProductDTO{id=1, price=9.90}`。

### 第 11 步｜算钱、写库、返回

- 【你写的】第 70-75 行组装订单，金额 = `9.90 × 2 = 19.80`；第 76 行 `orderMapper.insert(order)`；第 79 行返回 id。
- 【数据】`[D5]`、`[D7]`、`[D1].num` → `Order{amount=19.80}` → 写库后 `[D8] = 5L` → `[D9] = {"code":0,"data":5}`。

---

## 五、结局②逐步行进：对方不可用

**入场**：停掉 product-service，其余同结局①。

第 1~9 步同结局①（用户服务正常，`[D5]` 已拿到）。第 10 步分岔。

### 第 10 步｜Feign 找不到服务 / 连接被拒

- 【框架动作】LoadBalancer 查不到 `product-service` 实例，或拿到实例但连接失败 → 抛异常（`RetryableException` / `ConnectException` 等）。
- 【数据】`[D6]` 可能已拼出但请求失败；`[D7]` 没有产生。

### 第 11 步｜L3 的兜底（历史口径）

- 【你写的】L3 当时在 `OrderServiceImpl` 里 `try/catch` 远程调用异常，抛 `REMOTE_CALL_FAILED(2001)`。
- 【数据】异常 → `BizException{code=2001, message="服务调用失败"}`。

### 第 11 步（L4 后）｜Sentinel fallback 接管

- 【框架动作】`@SentinelResource` 的 `fallback` 方法 `getProductFallback(1, t)` 接住非 `BizException` 异常。
- 【你写的】fallback 里翻译成 `PRODUCT_SERVICE_UNAVAILABLE(2003)`。
- 【数据】`t = ConnectException` → `BizException{code=2003, message="商品服务不可用，请稍后重试"}`。

### 第 12 步｜异常出路

- 【框架动作】`GlobalExceptionHandler` 接住 → `Result.fail(...)`。
- 【数据】`[D9] = {"code":2001,...}`（L3）或 `{"code":2003,...}`（L4 后）。

> L3 的目标只是"不要 500、给友好失败码"；熔断、限流、降级分级是 L4 的事。

---

## 六、版本结论（2026-11-20 核对）

- Spring Cloud **2025.1.4**（官方写明支持 Boot 4.1.2）
- SCA **2025.1.0.0**（官方只写适配 4.0.x，4.1.2 未官方验证，实测可用，是当时最大风险点）
- Nacos Server **3.3.0**：服务端 8848，控制台 8080
- 父 POM **只导 Cloud BOM**；SCA 只给 starter 写版本（整包导 SCA BOM 会把 Cloud 顶回 2025.1.0）

## 七、坑

1. 只加 `openfeign` 不加 `loadbalancer` → 找不到实例。
2. `@PathVariable("id")` 括号里的名字不能省，Feign 靠它替换占位符。
3. Feign 默认超时比 L2 的 3 秒宽松，需要在 yml 覆盖。
4. Nacos 3.3 默认加载 AI 插件，遇 `\\?\` 路径崩溃 → `-f microservice` 启动规避。
5. Nacos 3.3 默认开鉴权，`startup.cmd` 会交互式问密钥 → 先在 `conf/application.properties` 预置。
6. `spring.application.name` 写错（如 product 模块误写 product-service）会导致注册名错误、调用方查不到。

## 八、验收

- 三服务注册成功，Nacos 实例 `healthyInstanceCount=1`。
- 全工程搜索：无 `localhost:8082`、无 `RestTemplate` 引用。
- 停 product 再下单 → `{"code":2001,...}`（L3）/ `{"code":2003,...}`（L4 后）。
