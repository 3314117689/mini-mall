# 09 L7 Gateway 网关

> 按铁律 11 写：总览表只做导航，真正的过程在"逐步行进"里。本章随 L7-1~L7-5 逐步补全。
> 本章的一条轴：**前端只打 9000 → 网关按 `/api` 前缀选路由 → 去掉 `/api` → 转发到服务实例**。

## 一、这一层解决什么

L0~L6 的现状：

1. 前端要记三个端口：用户 `8081`、商品 `8082`、订单 `8083`；
2. 每个服务各自注册 `JwtInterceptor` 验 token，鉴权规则散在三个服务里；
3. 限流、跨域将来也要每个服务各配一遍。

L7 增加一个网关（`9000`）：

1. 前端所有请求只打 `http://localhost:9000/api/...`；
2. 网关按路径前缀把请求转发给对应服务，下游 Controller 的路径不变；
3. 鉴权（L7-2）、限流（L7-4）、跨域（L7-5）都在网关做一次。

## 二、技术点清单

| # | 技术点 | 落点 | 一句话作用 |
|---|---|---|---|
| 1 | Spring Cloud Gateway 5.0.4（WebFlux + Netty） | `mall-gateway` | 响应式网关，和业务服务的 WebMVC 是两套容器 |
| 2 | 路由三要素 `id / uri / predicates` | `application.yml` | 什么请求、转到哪个服务 |
| 3 | `lb://服务名` + Nacos + LoadBalancer | 依赖 + yml | 不写死 IP，从注册中心取实例 |
| 4 | `StripPrefix=1` | 路由 filters | 去掉对外多出来的 `/api` 第一段 |
| 5 | `GlobalFilter` 统一验 JWT | L7-2 | 鉴权从三个服务收口到网关 |
| 6 | 信任边界：`X-Mall-User-*` | L7-2 / L7-3 | 下游不再验 token，只认网关给的身份 |
| 7 | Feign 内部调用透传身份头 | L7-3 | 服务间直连不经网关，身份要自己带 |
| 8 | `RequestRateLimiter` + Redis | L7-4 | 网关层限流 |
| 9 | WebFlux 错误响应 + CORS | L7-5 | 网关不认 MVC 的全局异常处理器 |

## 三、小步与状态

| 步 | 内容 | 状态 |
|---|---|---|
| L7-1 | 新建 `mall-gateway`，三条路由打通 | ✅ |
| L7-2 | `AuthGlobalFilter`：白名单 + 验签 + 注入身份头 | ✅ |
| L7-3 | 服务改信任网关头，Feign 透传身份 | ✅ |
| L7-4 | 网关限流 `RequestRateLimiter` | ✅ |
| L7-5 | CORS、错误统一、全链路验收、收尾 | 未开始 |

## 四、涉及文件与归属（L7-1）

| 名字 | 归属 | 作用 |
|---|---|---|
| `v1/pom.xml` | 本项目 | `<modules>` 增加 `mall-gateway` |
| `mall-gateway/pom.xml` | 本项目 | 网关依赖：gateway-webflux + nacos-discovery + loadbalancer |
| `GatewayApplication` | 本项目，`mall-gateway/src/main/java/com/mall/gateway/GatewayApplication.java` | 启动类，默认扫描 `com.mall.gateway` |
| `application.yml` | 本项目，`mall-gateway/src/main/resources/application.yml` | 端口、Nacos 地址、三条路由 |
| `RoutePredicateHandlerMapping` | 框架：Spring Cloud Gateway | 请求进来后按 `predicates` 找路由 |
| `ReactiveLoadBalancerClientFilter` | 框架：Spring Cloud Gateway | 把 `lb://服务名` 换成真实实例地址 |
| `StripPrefixGatewayFilterFactory` | 框架：Spring Cloud Gateway | 按配置去掉路径前 N 段 |
| `NettyRoutingFilter` | 框架：Spring Cloud Gateway | 真正把 HTTP 请求发到下游 |

## 五、启动期：网关启动做了什么（只发生一次）

### 第 1 步｜确定 Web 容器类型

- 【你写的】`pom.xml` 只引 `spring-cloud-starter-gateway-server-webflux`，**没有引** `spring-boot-starter-web`。
- 【框架动作】Spring Boot 检查 classpath：只有 WebFlux 没有 WebMVC → 选择响应式容器 Netty，而不是 Tomcat。
- 【数据】`[C1] 容器类型 = reactive（Netty）`。
- 如果误引了 `starter-web`：两个容器同时满足条件，启动时会报 "Spring MVC found on classpath, which is incompatible with Spring Cloud Gateway"，或者行为不可预期。

### 第 2 步｜读配置，编译路由表

```yaml
# 文件：mall-gateway/src/main/resources/application.yml
# 行号：14-32
    gateway:
      server:
        webflux:
          routes:
            - id: product-service
              uri: lb://product-service
              predicates:
                - Path=/api/product/**
              filters:
                - StripPrefix=1
```

- 【你写的】三条路由，每条由 `id`、`uri`、`predicates`、`filters` 组成。
- 【注解】无，这里是配置绑定，不是代码注解。
- 【框架动作】启动时把 yml 的 `routes` 读成 `RouteDefinition` 列表，交给 `RoutePredicateHandlerMapping` 用于后续匹配。
- 【数据】`[D0] 路由表`：

| 路由 id | uri | predicates | filters |
|---|---|---|---|
| `user-service` | `lb://user-service` | `/api/auth/**`、`/api/user/**` | `StripPrefix=1` |
| `product-service` | `lb://product-service` | `/api/product/**` | `StripPrefix=1` |
| `order-service` | `lb://order-service` | `/api/order/**` | `StripPrefix=1` |

> 注意：启动时 **不会** 把 `lb://product-service` 解析成 IP。路由表里存的就是这串字符，真实地址等请求来了才查。

### 第 3 步｜注册到 Nacos

- 【你写的】`application.yml` 第 10-13 行：`spring.cloud.nacos.discovery.server-addr=127.0.0.1:8848`。
- 【框架动作】启动完成后，Nacos 客户端把 `gateway-service` 和本机 `9000` 登记到注册中心。
- 【数据】Nacos 服务列表新增 `[C2] gateway-service → 192.168.126.72:9000`。
- 日志证据：`nacos registry, DEFAULT_GROUP gateway-service 192.168.126.72:9000 register finished`。

### 第 4 步｜Netty 开始监听

- 【框架动作】`Netty started on port 9000 (http)` 之后，网关才真正能接请求。
- 【数据】`[C3] 9000 端口处于监听状态`。

## 六、请求期：`GET http://localhost:9000/api/product/1` 逐步行进

**命令入场**：`GET http://localhost:9000/api/product/1`，无请求头、无请求体。

**调用链**

| 顺序 | 谁 | 在哪 | 干了什么 |
|---|---|---|---|
| 1 | Netty | — | 在 9000 收到请求，路径 `/api/product/1` |
| 2 | `RoutePredicateHandlerMapping` | 框架 | 用路径去匹配三条路由的 `Path` 条件 |
| 3 | `RoutePredicateHandlerMapping` | 框架 | 命中 `id=product-service` 的路由 |
| 4 | `ReactiveLoadBalancerClientFilter` | 框架 | 把 `lb://product-service` 换成 Nacos 里的实例地址 |
| 5 | `StripPrefixGatewayFilterFactory` | 框架 | 去掉第一段 `/api`，路径变 `/product/1` |
| 6 | `NettyRoutingFilter` | 框架 | 向实例发 `GET http://<实例IP>:8082/product/1` |
| 7 | Tomcat（product） | — | 按 `/product/1` 找到 `ProductController.getById` |
| 8 | `ProductServiceImpl.getById` | 本项目 | 查缓存/查库，返回商品 |
| 9 | `NettyRoutingFilter` | 框架 | 把下游响应原样写回 9000 的连接 |

> 第 4 步和第 5 步的先后由过滤器顺序决定；本步只需知道两件事都发生了：`lb://` 被换成真实地址、`/api` 被去掉。

**数据编号**

| 编号 | 数据 | 初始值 | 谁产生 |
|---|---|---|---|
| `[D1]` | 原始路径 | `/api/product/1` | 浏览器 |
| `[D2]` | 命中的路由 | `id=product-service` | 路由匹配 |
| `[D3]` | 路由目标 | `lb://product-service` | yml |
| `[D4]` | 实例地址 | `192.168.126.72:8082` | LoadBalancer + Nacos |
| `[D5]` | 剥离后路径 | `/product/1` | `StripPrefix=1` |
| `[D6]` | 发往下游的请求 | `GET http://192.168.126.72:8082/product/1` | `NettyRoutingFilter` |
| `[D7]` | 下游响应体 | `{"code":0,"data":{...},"msg":"ok"}` | product 服务 |
| `[D8]` | 浏览器最终收到 | 同 `[D7]` | 网关原样回写 |

**四个结局（总览）**

| 结局 | 分岔点 | 条件 | 浏览器 |
|---|---|---|---|
| ① 转发成功 | 第 3 步路由匹配 + 第 4 步拿到实例 | 路径命中且有可用实例 | HTTP 200 + 业务 JSON |
| ② 路径不匹配 | 第 3 步 | 没有任何路由的 `Path` 命中 | HTTP 404 |
| ③ 没有实例 | 第 4 步 | 路由命中，但 Nacos 里该服务没有实例 | HTTP 503 |
| ④ 未登录（L7-2 起） | 网关验签过滤器 | 需要鉴权的路径没带 token | `{"code":4010}` |

### 结局①逐步行进：转发成功

#### 第 1 步｜请求进入网关

- 【框架动作】Netty 在 9000 收到请求，构造一个 `ServerWebExchange`，里面带着请求路径。
- 【数据】`[D1] = /api/product/1`，来源是浏览器 URL，去向是路由匹配。

#### 第 2 步｜按路径选路由

- 【框架动作】`RoutePredicateHandlerMapping` 依次拿三条路由的 `Path` 条件去试：
  - `/api/auth/**`、`/api/user/**`：不匹配；
  - `/api/product/**`：匹配；
  - `/api/order/**`：不匹配。
- 【数据】`[D2] = product-service 路由`；`[D3] = lb://product-service`。

#### 第 3 步｜服务名换实例地址

- 【框架动作】`ReactiveLoadBalancerClientFilter` 看到 `uri` 以 `lb://` 开头，取出服务名 `product-service`，去 Nacos 查实例列表，按负载均衡策略选一个。
- 【数据】`[D4] = 192.168.126.72:8082`。
- 如果 Nacos 里没有实例：`[D4]` 取不到 → 结局③。
- 为什么必须引 `spring-cloud-starter-loadbalancer`：Nacos 只负责"告诉你有哪个实例"，`lb://` 这个协议的解析和选实例是 LoadBalancer 干的；少了它，路由会报找不到实例。

#### 第 4 步｜去掉 `/api`

- 【你写的】路由 `filters` 里写了 `StripPrefix=1`。
- 【框架动作】把路径按 `/` 切分，去掉前 1 段，再拼回去。
- 【数据】`[D1] /api/product/1` → `[D5] /product/1`。**这是同一条路径的变形，不是新数据**。

#### 第 5 步｜发到下游

- 【框架动作】`NettyRoutingFilter` 用 `[D4]` 的地址 + `[D5]` 的路径发 HTTP 请求；请求头、请求体、HTTP 方法原样带过去。
- 【数据】`[D6] = GET http://192.168.126.72:8082/product/1`。

#### 第 6 步｜下游处理

- 【框架动作】product 的 Tomcat 按 `/product/1` 匹配到 `@GetMapping("/{id}")`（`ProductController` 第 24-27 行）。
- 【你写的】Controller 调 `productService.getById(1)`，L5 的缓存逻辑照常生效。
- 【数据】`[D7] = {"code":0,"data":{"id":1,"name":"测试商品",...},"msg":"ok"}`。

#### 第 7 步｜响应回写

- 【框架动作】网关把下游的状态码、响应头、响应体原样写回浏览器。
- 【数据】`[D8] = [D7]`，浏览器看到的和直连 8082 一样。

### 结局②逐步行进：路径不匹配

- 【框架动作】三条路由的 `Path` 都不匹配 `/nope`，`RoutePredicateHandlerMapping` 找不到路由。
- 【数据】`[D2]` 不存在，后续步骤全部不执行。
- 【数据】浏览器收到 Spring 默认 404 响应（`{"timestamp":...,"path":"/nope","status":404,"error":"Not Found"}`），不是我们的 `Result` 格式——L7-5 再统一。

### 结局③逐步行进：没有可用实例

- 【框架动作】路由匹配成功，但 LoadBalancer 在 Nacos 里查不到 `product-service` 的实例。
- 【数据】`[D4]` 取不到，`[D5]`、`[D6]` 不产生。
- 【数据】浏览器收到 HTTP 503：

```json
{"timestamp":"2026-11-22T12:05:47.372Z","path":"/api/product/1","status":503,"error":"Service Unavailable","requestId":"0cf1012b-1"}
```

- 这是网关自己生成的错误响应，和业务服务的 `Result` 无关。

### 结局④：未登录

- L7-1 还没有验签过滤器，所以 `/api/order/**` 目前会直接转发到 order 服务，由 order 自己的 `JwtInterceptor` 返回 4010。
- L7-2 把这一步前移到网关后，本结局改由网关产生。

## 七、L7-1 实测

```text
启动：java -jar mall-product/target/mall-product-1.0.0.jar
      java -jar mall-gateway/target/mall-gateway-1.0.0.jar

product 日志：nacos registry ... product-service 192.168.126.72:8082 register finished
gateway 日志：Netty started on port 9000 (http)
              nacos registry ... gateway-service 192.168.126.72:9000 register finished

GET http://127.0.0.1:8082/product/1（直连）
→ HTTP 200
{"code":0,"data":{"createTime":"2026-11-09T17:04:46","id":1,"name":"测试商品","price":9.90,"stock":100},"msg":"ok"}

GET http://127.0.0.1:9000/api/product/1（走网关）
→ HTTP 200
{"code":0,"data":{"createTime":"2026-11-09T17:04:46","id":1,"name":"测试商品","price":9.90,"stock":100},"msg":"ok"}

GET http://127.0.0.1:9000/nope
→ HTTP 404

停掉 product 后再请求 http://127.0.0.1:9000/api/product/1
→ HTTP 503 {"status":503,"error":"Service Unavailable",...}
```

## 八、L7-1 坑

1. **Gateway 5.x 配置前缀变了**：本次从 jar 内的 `spring-configuration-metadata.json` 核实，属性名是 `spring.cloud.gateway.server.webflux.routes`；旧教程里的 `spring.cloud.gateway.routes` 在 5.0.4 里已不存在，写了不生效，表现是"网关起来了但所有请求 404"。
2. **starter 名字变了**：5.x 是 `spring-cloud-starter-gateway-server-webflux`，不是老的 `spring-cloud-starter-gateway`（老名字在 5.0.4 BOM 里没有）。
3. **不能引 `spring-boot-starter-web`**：网关是 WebFlux（Netty），业务服务是 WebMVC（Tomcat）；同一个应用里两套容器会冲突。
4. **`lb://` 必须配 LoadBalancer**：只加 Nacos discovery 不够，否则 `lb://product-service` 无法解析成实例地址。
5. **`StripPrefix=1` 是"去几段"**：`/api/product/1` 去 1 段得 `/product/1`；写成 2 就会变成 `/1`，下游 404。
6. **一条 `Path` 可以写多个模式**：用逗号分隔，如 `/api/auth/**,/api/user/**`，不用拆成两条路由。
7. **网关自己也注册到 Nacos**：日志里会看到 `gateway-service`，这不影响路由，只是说明注册中心里多了一个服务。
8. **下游没启动时的 503 是网关默认错误页**：不是 `Result` 格式，浏览器/前端如果只认 `code` 会解析失败；L7-5 统一成 `{"code":...}`。

## 九、L7-1 断点验证与自测题

| 停在哪 | 看什么 | 看到什么证明走对了 |
|---|---|---|
| 启动日志（不用断点） | `Netty started on port 9000` | 网关是 Netty，不是 Tomcat |
| 启动日志 | `nacos registry ... gateway-service ...:9000` | 网关已注册到 Nacos |
| `RoutePredicateHandlerMapping` 的 `lookupRoute`（框架） | `exchange.getRequest().getPath()` | 值是 `/api/product/1`，与 yml 里的 `Path` 对得上 |
| `StripPrefixGatewayFilterFactory` 的 `apply`（框架） | 过滤器执行后的 path | 从 `/api/product/1` 变成 `/product/1` |
| Nacos 控制台服务列表 | `product-service` 实例 IP:端口 | 与 `lb://` 解析出的地址一致 |

### 自测题（不附答案）

1. 前端请求 `/api/order/1`，网关转发给 order 服务时路径是什么？是哪一步改的？
2. 为什么 `lb://product-service` 在启动日志里查不到具体 IP，但请求时能连上 8082？
3. 如果网关 pom 里误加了 `spring-boot-starter-web`，会发生什么？

---

## 十、L7-2 网关统一验 JWT

**命令入场**

```text
A. GET  http://localhost:9000/api/order/1          （不带 Authorization）
B. GET  http://localhost:9000/api/order/1          （Authorization: Bearer <zhangsan 的 token>）
C. GET  http://localhost:9000/api/product/1        （不带 token，公开读）
D. POST http://localhost:9000/api/product          （不带 token，写操作）
```

**涉及文件**

| 名字 | 归属 | 作用 |
|---|---|---|
| `AuthGlobalFilter` | 本项目，`mall-gateway/src/main/java/com/mall/gateway/filter/AuthGlobalFilter.java` | 白名单、验签、删/写身份头、写错误 JSON |
| `GatewayAuthConfig` | 本项目，`mall-gateway/src/main/java/com/mall/gateway/config/GatewayAuthConfig.java` | 用 `mall.jwt.jwks-uri` 造一个 `JwtVerifier` Bean |
| `JwtVerifier` | 本项目，`mall-common/.../auth/JwtVerifier.java` | L6 的验签器，网关直接复用 |
| `GlobalFilter` / `Ordered` | 框架：Spring Cloud Gateway | 所有路由都会经过的过滤器；`getOrder()` 决定先后 |
| `Result` | 本项目，`mall-common/.../result/Result.java` | 错误响应仍用统一返回体 |

**调用链**

| 顺序 | 谁 | 在哪 | 干了什么 |
|---|---|---|---|
| 1 | Netty | — | 收到请求，构造 `ServerWebExchange` |
| 2 | `AuthGlobalFilter.filter` | 第 70-77 行 | 取 path，先判断是不是公开路径 |
| 3 | `AuthGlobalFilter.filter` | 第 79-83 行 | 受保护路径：读 `Authorization`，没有 `Bearer ` 前缀直接 4010 |
| 4 | `AuthGlobalFilter.filter` | 第 85-93 行 | 截出 token，交 `JwtVerifier.verify` 验签 |
| 5 | `JwtVerifier.verify` | 第 52-67 行 | 按 `kid` 取公钥（首次拉 JWKS），验签名和 `exp` |
| 6 | `AuthGlobalFilter.filter` | 第 95-106 行 | 删伪造头 → 写 `X-Mall-User-Id/Username/Role` → 继续过滤链 |
| 7 | 路由过滤器 | 框架 | `lb://` 换实例 + `StripPrefix=1` 去 `/api` |
| 8 | 下游 Controller | 本项目 | 正常执行业务 |
| 9 | 下游响应 | — | 原路回到浏览器 |

**数据编号**

| 编号 | 数据 | 初始值 | 谁产生 |
|---|---|---|---|
| `[D1]` | 请求路径 | `/api/order/1` | 浏览器 |
| `[D2]` | 请求方法 | `GET` | 浏览器 |
| `[D3]` | Authorization 头 | `"Bearer eyJraWQiOi..."` 或 `null` | 浏览器 |
| `[D4]` | token | `eyJraWQiOi...` | 第 85 行截取 |
| `[D5]` | 当前用户 | `LoginUser{userId=1, username=zhangsan, role=ADMIN}` | `JwtVerifier` |
| `[D6]` | 注入的身份头 | `X-Mall-User-Id: 1` 等三个 | 第 95-106 行 |
| `[D7]` | 下游响应 | `{"code":0,"data":{"id":1,...},"msg":"ok"}` | order 服务 |
| `[D8]` | 浏览器最终收到 | 同 `[D7]` | 网关回写 |

**五个结局（总览）**

| 结局 | 分岔点 | 条件 | 浏览器 |
|---|---|---|---|
| ① 公开路径放行 | 第 75 行 `isPublic` | 登录/注册/JWKS；或商品 GET/HEAD | 直接转发，`code=0` |
| ② 受保护 + 有效 token | 第 88 行 `verify` 返回 | 签名对、未过期 | 转发，返回业务结果 |
| ③ 没带 token | 第 80 行 | 头为 `null` 或不以 `Bearer ` 开头 | `{"code":4010}` |
| ④ token 无效 | 第 88 行抛 `BizException` | 签名错/过期/格式错 | `{"code":4011}` |
| ⑤ 拉不到 JWKS | `JwtVerifier.refreshKeys` | 用户服务没起且本地没缓存公钥 | `{"code":4012}` |

### 结局①逐步行进：公开路径放行

#### 第 1 步｜判断白名单

```java
// 文件：mall-gateway/src/main/java/com/mall/gateway/filter/AuthGlobalFilter.java
// 行号：113-122
private boolean isPublic(String path, HttpMethod method) {
    if (HttpMethod.OPTIONS.equals(method)) {
        return true;
    }
    if (PUBLIC_PATHS.contains(path)) {
        return true;
    }
    return path.startsWith("/api/product")
            && (HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method));
}
```

- 【你写的】三段判断：预检、三个完全公开路径、商品读接口。
- 【数据】`GET /api/product/1` → `[D1]` 命中 `startsWith("/api/product")` 且方法是 GET → `true`。

#### 第 2 步｜删掉客户端伪造的身份头再放行

```java
// 行号：75-77、124-133
if (isPublic(path, request.getMethod())) {
    return chain.filter(withoutIdentityHeaders(exchange));
}
```

- 【你写的】即使是公开接口，也先 `remove` 三个 `X-Mall-*` 头。
- 【数据】客户端如果带了 `X-Mall-User-Id: 999`，到这里被删除，`[D6]` 不会产生。
- 为什么公开接口也要删：下游以后只认这三个头，公开读接口不校验身份；不删的话，客户端可以自己造一个身份头混进下游。

#### 第 3 步｜继续过滤链

- 【框架动作】`chain.filter(...)` 把请求交给后面的路由过滤器：`lb://` 换实例 → `StripPrefix=1` → 发往下游。
- 【数据】`[D1] /api/product/1` → 下游 `/product/1`；浏览器收到商品 JSON。

### 结局②逐步行进：受保护 + 有效 token

#### 第 1 步｜不在白名单

- 【数据】`[D1] = /api/order/1`，`[D2] = GET`：既不在 `PUBLIC_PATHS`，也不是商品路径 → `isPublic` 返回 `false`，进入验签分支。

#### 第 2 步｜读 Authorization

```java
// 行号：79-83
String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
    log.warn("网关鉴权失败, path={}, 原因=没有 Bearer token", path);
    return writeError(exchange, ErrorCode.UNAUTHORIZED);
}
```

- 【你写的】只认 `Authorization: Bearer ` 这一种写法。
- 【数据】`[D3] = "Bearer eyJraWQiOi..."` → 前缀匹配 → 进入下一步。

#### 第 3 步｜截 token、验签

```java
// 行号：85-93
String token = authorization.substring(BEARER_PREFIX.length()).trim();
LoginUser user;
try {
    user = jwtVerifier.verify(token);
} catch (BizException e) {
    log.warn("网关鉴权失败, path={}, code={}", path, e.getCode());
    return writeError(exchange, e.getCode(), e.getMessage());
}
```

- 【你写的】截掉 `"Bearer "`，把 token 交给 L6 的验签器。
- 【框架动作】`JwtVerifier` 内部：按 token 头的 `kid` 找公钥，第一次请求时 GET `http://localhost:8081/auth/jwks` 并缓存；用公钥验签名、检查 `exp`。
- 【数据】`[D3]` → `[D4]`；验签通过后 claims → `[D5] = LoginUser{userId=1, username=zhangsan, role=ADMIN}`。
- 日志证据：`JWKS 已加载, kid=mall-l6-dev` 只在第一次请求出现。

#### 第 4 步｜删伪造头、写真实身份

```java
// 行号：95-106
ServerHttpRequest mutated = request.mutate()
        .headers(headers -> {
            headers.remove(HEADER_USER_ID);
            headers.remove(HEADER_USERNAME);
            headers.remove(HEADER_ROLE);
            headers.set(HEADER_USER_ID, String.valueOf(user.getUserId()));
            headers.set(HEADER_USERNAME, user.getUsername());
            headers.set(HEADER_ROLE, user.getRole());
        })
        .build();
return chain.filter(exchange.mutate().request(mutated).build());
```

- 【你写的】先 `remove` 再 `set`，顺序不能反：先删掉客户端可能伪造的值，再写验签得到的值。
- 【数据】`[D5]` → `[D6]`：三个头变成 `1`、`zhangsan`、`ADMIN`。
- 注意：`Authorization` 头没有删，仍会原样转发给下游；L7-2 阶段下游还在自己验 token（双验），L7-3 才切换成只认身份头。

#### 第 5 步｜转发与返回

- 【框架动作】路由过滤器把请求发到 order 实例，order 自己的 `JwtInterceptor` 再验一次同一张 token。
- 【数据】`[D7] = {"code":0,"data":{"id":1,"amount":1.00,...},"msg":"ok"}` → `[D8]` 浏览器。

### 结局③逐步行进：没带 token

- 【你写的】第 80 行条件成立 → 第 82 行 `writeError(UNAUTHORIZED)`。
- 【数据】`[D4]`、`[D5]`、`[D6]` 都不产生；响应体：

```json
{"code":4010,"msg":"未登录，请先登录","data":null}
```

- 【框架动作】`writeError` 把 `Result` 序列化成字节，包成 `DataBuffer` 写出；HTTP 状态是 **200**，沿用全工程"只看 body.code"的约定。

### 结局④逐步行进：token 无效

- 【框架动作】jjwt 拆 token 失败或验签失败，抛 `JwtException`；`JwtVerifier` 第 63-66 行捕获后转成 `BizException(TOKEN_INVALID)`。
- 【你写的】第 89-93 行捕获 `BizException`，取它的 `code` 和 `msg` 写响应。
- 【数据】`[D5]`、`[D6]` 不产生；响应 `{"code":4011,"msg":"登录凭证无效或已过期","data":null}`。
- 为什么不在网关 `catch` 后统一写 4010：4011 和 4012 是不同原因（凭证错 vs 认证服务不可用），前端/排查需要区分。

### 结局⑤逐步行进：拉不到 JWKS

- 【框架动作】`JwtVerifier` 第一次验签需要拉 JWKS，`httpClient.send` 失败 → `BizException(AUTH_UNAVAILABLE)`（4012）。
- 【你写的】同样被第 89-93 行捕获，按 4012 返回。
- 【数据】响应 `{"code":4012,"msg":"认证服务不可用，请稍后重试","data":null}`。
- 注意：公钥一旦缓存，用户服务短暂宕机不影响已有 `kid` 的验签；只有遇到没缓存的 `kid` 才会触发拉取。

### L7-2 实测

```text
1. POST /api/auth/login {"username":"zhangsan","password":"123456"}
   → code=0, token 长度 501

2. GET /api/product/1（无 token）
   → HTTP 200 {"code":0,"data":{"id":1,"name":"测试商品",...},"msg":"ok"}

3. POST /api/product（无 token）
   → {"code":4010,"msg":"未登录，请先登录","data":null}

4. GET /api/order/1（无 token）
   → {"code":4010,"msg":"未登录，请先登录","data":null}

5. GET /api/order/1（Authorization: Bearer abc.def.ghi）
   → {"code":4011,"msg":"登录凭证无效或已过期","data":null}

6. GET /api/order/1（zhangsan 的有效 token）
   → HTTP 200 {"code":0,"data":{"id":1,"amount":1.00,...},"msg":"ok"}

7. GET /api/order/1（只带 X-Mall-User-Id: 1，不带 token）
   → {"code":4010,"msg":"未登录，请先登录","data":null}

网关日志：
WARN AuthGlobalFilter : 网关鉴权失败, path=/api/product, 原因=没有 Bearer token
WARN AuthGlobalFilter : 网关鉴权失败, path=/api/order/1, 原因=没有 Bearer token
WARN AuthGlobalFilter : 网关鉴权失败, path=/api/order/1, code=4011
INFO JwtVerifier       : JWKS 已加载, kid=mall-l6-dev
```

### L7-2 坑

1. **Boot 4 默认 Jackson 3，jjwt 带的是 Jackson 2**：按 `com.fasterxml.jackson.databind.ObjectMapper` 从容器注入会启动失败，报 `Consider defining a bean of type 'com.fasterxml.jackson.databind.ObjectMapper'`。本项目改为过滤器内自建 Jackson 2 实例，只用来写固定结构的错误 JSON。
2. **白名单必须写 `/api/...`**：`AuthGlobalFilter` 的 `getOrder()` 是 `-100`，排在 `StripPrefix` 之前，此时路径还没被剥掉。写成 `/order/**` 永远匹配不上。
3. **WebFlux 不能复用 MVC 的错误处理**：网关没有 `@RestControllerAdvice` 的适用场景，异常不会自动变成 `Result`；必须在过滤器里自己写响应。
4. **错误响应仍是 HTTP 200**：与 L6 保持一致，前端只判断 body 的 `code`；代价是监控/网关层不能靠 HTTP 状态判断成败。
5. **商品只放行读**：`POST/PUT/DELETE /api/product/**` 仍要 token；公开的只有 `GET/HEAD`。
6. **OPTIONS 必须放行**：浏览器的 CORS 预检不带 token，不放行会在 L7-5 配 CORS 后卡住。
7. **`X-Mall-*` 头必须先删后写**：只 `set` 不 `remove` 的话，同名多值会让下游取到第一个（可能是伪造值）。
8. **L7-2 是双验阶段**：网关验一次、服务再验一次；这不是最终形态，L7-3 把服务改成只认身份头后才真正"鉴权收口"。

### L7-2 断点验证与自测题

| 停在哪 | 看什么 | 看到什么证明走对了 |
|---|---|---|
| `AuthGlobalFilter.filter` 第 72 行 | `path`、`request.getMethod()` | `GET` / `/api/order/1` |
| 第 75 行 | `isPublic(...)` 的返回值 | 商品 GET 为 `true`，order 为 `false` |
| 第 88 行执行后 | `user` | `LoginUser{userId=1, username=zhangsan, role=ADMIN}` |
| 第 106 行前 | `mutated.getHeaders()` | 三个 `X-Mall-*` 头都是 token 里的值 |
| 第 146 行 | `bytes` 转成字符串 | 与浏览器收到的 `{"code":4010,...}` 一致 |

### 自测题（不附答案）

1. 为什么网关的白名单路径要写成 `/api/order/**` 而不是 `/order/**`？
2. 客户端带 `X-Mall-User-Id: 999` 但没有 token，能否混进受保护接口？为什么？
3. 网关已经验过 token 了，L7-2 为什么下游还要再验一次？L7-3 会怎么改？

---

## 十一、L7-3 服务端改为信任网关

**命令入场**

```text
POST http://localhost:9000/api/order/create
Authorization: Bearer <lisi 的 token>
X-Mall-User-Id: 1                 ← 故意伪造，验证网关会不会覆盖
body: {"productId":1,"num":2}
```

**本步改动**

| 文件 | 归属 | 改了什么 |
|---|---|---|
| `AuthHeaders` | 本项目，`mall-common/.../auth/AuthHeaders.java` | 三个身份头名的唯一常量来源，纯常量、不碰 Servlet |
| `GatewayAuthInterceptor` | 本项目，`mall-common/.../auth/GatewayAuthInterceptor.java` | 新增：只读三个头重建 `LoginUser`，保留 `@RequireRole` 校验 |
| `JwtWebConfig`（order / user） | 本项目 | 改为注册 `GatewayAuthInterceptor`，不再创建 `JwtVerifier` |
| `FeignAuthInterceptor` | 本项目，`mall-order/.../config/` | 替换原 `FeignTokenInterceptor`：透传三个身份头，不再透传 token |
| order / user 的 `application.yml` | 本项目 | 删除 `mall.jwt.jwks-uri`，只有网关需要验签 |
| `JwtInterceptor` | 本项目，`mall-common/.../auth/` | 保留类但不再注册，L6 的"服务自己验签"模式停用 |

**调用链**

| 顺序 | 谁 | 在哪 | 干了什么 |
|---|---|---|---|
| 1 | `AuthGlobalFilter` | 第 95-106 行 | 验签后删伪造头，写 `X-Mall-User-Id=2` 等三个头 |
| 2 | 路由过滤器 | 框架 | `lb://` 换实例 + `StripPrefix=1` |
| 3 | `GatewayAuthInterceptor.preHandle`（order） | 第 26-46 行 | 读三个头 → 重建 `LoginUser` → 角色校验 → `UserContext.set` |
| 4 | `OrderController.create` | 第 41-44 行 | `@CurrentUser Long userId` 从上下文取到 `2` |
| 5 | `OrderServiceImpl.create` | 第 60-77 行 | 校验、调远程、算金额、落库 |
| 6 | `FeignAuthInterceptor.apply` | 第 26-34 行 | 从 `UserContext` 取用户，写三个身份头到 Feign 请求 |
| 7 | `GatewayAuthInterceptor.preHandle`（user） | 第 26-46 行 | 读同样三个头，放行 `GET /user/2` |
| 8 | `UserController.getById` | 第 29-32 行 | 返回 `Result<User>` |
| 9 | `OrderServiceImpl.create` | 第 69-77 行 | `order.userId = 2`，金额 `9.90 × 2` |
| 10 | `OrderController.create` | 第 44 行 | `Result.ok(orderId)` |

**数据编号**

| 编号 | 数据 | 初始值 | 谁产生 |
|---|---|---|---|
| `[D1]` | 请求头 `X-Mall-User-Id` | `1`（伪造） | 客户端 |
| `[D2]` | token 里的身份 | `LoginUser{userId=2, username=lisi, role=USER}` | 网关验签 |
| `[D3]` | 网关改写后的头 | `2 / lisi / USER` | 网关第 95-106 行 |
| `[D4]` | order 的 `LoginUser` | 同 `[D3]` | order 拦截器第 35 行 |
| `[D5]` | `@CurrentUser` 参数 | `2L` | 参数解析器从 `UserContext` 取 |
| `[D6]` | Feign 请求头 | 三个 `X-Mall-*` | `FeignAuthInterceptor` |
| `[D7]` | 用户服务响应 | `UserDTO{id=2, username=lisi}` | user 服务 |
| `[D8]` | 订单金额 | `19.80` | `price × num` |
| `[D9]` | 新订单 id | `8` | MySQL 自增回填 |

**四个结局（总览）**

| 结局 | 分岔点 | 条件 | 浏览器 |
|---|---|---|---|
| ① 正常下单 | 网关验签 + 服务读头 | token 有效、三个头齐全 | `code=0`，订单 `user_id=2` |
| ② 直连服务没带头 | `GatewayAuthInterceptor` 第 31 行 | 请求绕过网关，无 `X-Mall-*` | `code=4010` |
| ③ 角色不够 | 第 38-40 行 | `@RequireRole("ADMIN")` 但 `role=USER` | `code=4030` |
| ④ 直连服务伪造头 | 第 27-29 行 | 绕过网关，自己带三个头 | 被当成合法身份（风险） |

### 结局①逐步行进：正常下单

#### 第 1 步｜网关覆盖伪造头

- 同 L7-2 结局②第 1~4 步：`[D1] = 1` 被 `remove`，随后写入 `[D3] : 2 / lisi / USER`。
- 【数据】客户端传的 `1` 到这里已经不存在。

#### 第 2 步｜order 拦截器读头

```java
// 文件：mall-common/src/main/java/com/mall/common/auth/GatewayAuthInterceptor.java
// 行号：26-46
public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    String userId = request.getHeader(AuthHeaders.USER_ID);
    String username = request.getHeader(AuthHeaders.USERNAME);
    String role = request.getHeader(AuthHeaders.ROLE);
    if (userId == null || username == null || role == null) {
        throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    LoginUser user = new LoginUser(Long.valueOf(userId), username, role);
    if (handler instanceof HandlerMethod handlerMethod) {
        RequireRole requireRole = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (requireRole != null && !Arrays.asList(requireRole.value()).contains(user.getRole())) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
    }
    UserContext.set(user);
    return true;
}
```

- 【你写的】读三个头 → 缺一个就 4010 → 拼 `LoginUser` → 有 `@RequireRole` 就比角色 → 存 ThreadLocal。
- 【数据】`[D3]` → `[D4] = LoginUser{2, lisi, USER}`。
- 注意：这里**没有任何验签代码**；网关验过之后，服务只做"头是否齐全"和"角色够不够"。

#### 第 3 步｜Controller 取 userId

```java
// 文件：mall-order/src/main/java/com/mall/order/controller/OrderController.java
// 行号：41-44
@PostMapping("/create")
public Result<Long> create(@RequestBody OrderCreateDTO dto, @CurrentUser Long userId) {
    return Result.ok(orderService.create(userId, dto));
}
```

- 【框架动作】`CurrentUserArgumentResolver` 从 `UserContext.get().getUserId()` 取值。
- 【数据】`[D5] = 2L`。

#### 第 4 步｜Feign 补身份头

```java
// 文件：mall-order/src/main/java/com/mall/order/config/FeignAuthInterceptor.java
// 行号：26-34
public void apply(RequestTemplate template) {
    LoginUser user = UserContext.get();
    if (user == null) {
        return;
    }
    template.header(AuthHeaders.USER_ID, String.valueOf(user.getUserId()));
    template.header(AuthHeaders.USERNAME, user.getUsername());
    template.header(AuthHeaders.ROLE, user.getRole());
}
```

- 【你写的】Feign 发请求前，把当前用户写成三个头。
- 【框架动作】Feign 在真正发 HTTP 前调用所有 `RequestInterceptor`。
- 【数据】`[D4]` → `[D6]`。
- 为什么必须做：Feign 是 order 直接连 user 的 `8081`，**不经过网关**；user 服务只认这三个头，不带就是 4010。

#### 第 5 步｜user 服务读头放行

- 【框架动作】user 的 `GatewayAuthInterceptor` 读 `[D6]`，重建 `LoginUser{2, lisi, USER}`，`GET /user/2` 正常执行。
- 【数据】`[D7] = UserDTO{id=2}`。
- 如果第 4 步漏了：user 返回 `code=4010`，order 的 `RemoteService` 看到 `code != 0` 会抛 1001（用户不存在），最终表现成"下单失败但提示用户不存在"，很容易误判。

#### 第 6 步｜算钱、落库、返回

- 【你写的】`order.setUserId(user.getId())`，金额 `price × num`。
- 【数据】`[D8] = 19.80`，INSERT 后 `[D9] = 8`；响应 `{"code":0,"data":8,"msg":"ok"}`。

### 结局②逐步行进：直连服务没带头

- 【你写的】`GatewayAuthInterceptor` 第 31 行：三个头都是 `null` → 第 32 行抛 4010。
- 【数据】响应 `{"code":4010,"msg":"未登录，请先登录","data":null}`；`UserContext` 全程为空。
- 实测：`GET http://127.0.0.1:8083/order/1` 和 `GET http://127.0.0.1:8081/user/2` 都返回 4010。

### 结局③逐步行进：角色不够

- 【数据】lisi 的 token → `[D4].role = USER`；`UserController.deleteById` 上有 `@RequireRole("ADMIN")`。
- 【你写的】第 39 行条件成立 → 第 40 行抛 4030；`UserServiceImpl.deleteById` 不执行。
- 【数据】响应 `{"code":4030,"msg":"无权限访问","data":null}`。
- 为什么 RBAC 不上移网关：网关只能看到 URL，看不到 Controller 方法上的注解；所以"认证"在网关，"授权"仍在服务端。

### 结局④逐步行进：直连服务伪造头

- 【你写的】第 27-29 行无条件相信三个头，因此直接请求 `8083` 并自带 `X-Mall-User-Id/Username/Role` 会被当成合法用户。
- 【数据】实测 `GET http://127.0.0.1:8083/order/1` + 三个伪造头 → `code=0`。
- 这是"服务信任网关"的代价：**安全边界从服务端口转移到了网络层**。生产做法：
  1. 服务只监听内网，安全组/网络策略只允许网关访问；
  2. 或网关额外加一个服务间共享密钥头，服务校验密钥后再信任身份头。
- 本项目本地学习接受该风险，但面试被问到时要能说清"为什么直连能绕过、生产怎么防"。

### L7-3 实测

```text
1. 网关 GET /api/order/1（lisi token）           → code=0（订单属于 zhangsan，越权读仍未校验）
2. 网关 GET /api/user/2（lisi token）            → code=0, username=lisi, role=USER
3. 网关 DELETE /api/user/999（lisi，USER）       → code=4030
4. 网关下单：lisi token + 伪造 X-Mall-User-Id=1  → code=0, orderId=8
5. 回查订单 8                                     → userId=2, amount=19.80（伪造的 1 被覆盖）
6. 直连 GET :8083/order/1（无头）                 → code=4010
7. 直连 GET :8081/user/2（无头）                  → code=4010
8. 直连 GET :8083/order/1 + 伪造三个头            → code=0（风险演示）
```

### L7-3 坑

1. **Feign 必须改成透传身份头**：服务已经不信 token，只透传旧 token 没用，user 服务会 4010，order 侧再翻译成 1001，提示会误导。
2. **直连服务可伪造头**：见结局④，生产靠网络隔离或共享密钥，别把"网关验签"当成服务端口的安全保障。
3. **`JwtInterceptor` 还在仓库里，但没有注册**：看代码时不要以为服务还在验签；L7 起验签只有网关一处。
4. **`mall.jwt.jwks-uri` 只剩网关需要**：user/order 已删；user 仍保留私钥、公钥、kid、TTL，因为要签发和公布 JWKS。
5. **三个头缺一就 4010**：`GatewayAuthInterceptor` 只要有一个 `null` 就拒绝；上游新增服务要接网关时，先确认网关会注入三个头。
6. **常量类不能是拦截器类**：`AuthHeaders` 故意做成纯常量类，因为网关（WebFlux）引用 `GatewayAuthInterceptor` 会触发 `jakarta.servlet` 类加载而报错。
7. **`UserContext.clear()` 仍在 `afterCompletion`**：身份虽然来自请求头，ThreadLocal 仍会跨请求复用，漏清就是串号。
8. **用户名和角色是 token 快照**：改了角色要重新登录才生效，原因同 L6。

### L7-3 断点验证与自测题

| 停在哪 | 看什么 | 看到什么证明走对了 |
|---|---|---|
| `GatewayAuthInterceptor.preHandle`（order）第 27 行 | 三个 `request.getHeader(...)` | `2`、`lisi`、`USER` |
| 第 31 行 | 三个变量 | 都不是 `null`，否则走 4010 |
| 第 44 行 | `UserContext.get()` | 已变成 `LoginUser{2,lisi,USER}` |
| `FeignAuthInterceptor.apply` 第 27 行 | `UserContext.get()` | 非 null，随后三个头写进 `template` |
| order 的 `JwtWebConfig` 第 31 行 | 注册的拦截器类型 | `GatewayAuthInterceptor`，不再是 `JwtInterceptor` |

### 自测题（不附答案）

1. 网关已经注入了正确身份，为什么 Feign 调 user 还要再写一遍头？
2. 为什么 `@RequireRole` 的校验不能上移到网关？
3. 有人拿到服务端口后自己伪造 `X-Mall-User-Id`，网关的验签还能挡住吗？生产上怎么解决？

---

## 十二、L7-4 网关限流 RequestRateLimiter

**命令入场**

```text
A. 连续 10 次  GET http://localhost:9000/api/product/1     （未登录）
B. 连续 10 次  GET http://localhost:9000/api/order/1       （Authorization: Bearer <zhangsan>）
```

**本步改动**

| 文件 | 归属 | 作用 |
|---|---|---|
| `pom.xml` | 本项目 | 加 `spring-boot-starter-data-redis-reactive` |
| `application.yml` 第 9-13 行 | 本项目 | 配 Redis 地址（令牌桶存这里） |
| `application.yml` 第 38-45、52-57 行 | 本项目 | 两条路由挂 `RequestRateLimiter` |
| `RateLimitConfig` | 本项目，`mall-gateway/.../config/` | `KeyResolver`：登录按 userId，未登录按 IP |
| `RateLimitResponseFilter` | 本项目，`mall-gateway/.../filter/` | 把限流器的空 429 改写成 `{"code":4290}` |
| `RequestRateLimiterGatewayFilterFactory` | 框架：Spring Cloud Gateway | 内置限流路由过滤器 |
| `RedisRateLimiter` | 框架：Spring Cloud Gateway | 用 Redis + Lua 执行令牌桶算法 |

**令牌桶三个参数**

| 参数 | 本步值 | 含义 |
|---|---|---|
| `replenishRate` | 2 | 每秒往桶里补 2 个令牌 |
| `burstCapacity` | 5 | 桶最多攒 5 个令牌（瞬间并发上限） |
| `requestedTokens` | 1 | 每个请求消耗 1 个令牌 |

**调用链**

| 顺序 | 谁 | 在哪 | 干了什么 |
|---|---|---|---|
| 1 | `AuthGlobalFilter` | order = -100 | 公开路径删身份头；受保护路径验签、写身份头 |
| 2 | `RateLimitResponseFilter` | order = -90 | 给响应包一层装饰器，准备接住 429 |
| 3 | `StripPrefix` | 路由 filters | 去掉 `/api` |
| 4 | `RequestRateLimiter` | 路由 filters | 调 `KeyResolver` 得到 Key → 调 `RedisRateLimiter` 判断有没有令牌 |
| 5 | `RedisRateLimiter` | 框架 | 执行 Lua：取桶 → 补令牌 → 扣 1 → 写回 Redis |
| 6 | 下游 / `setComplete` | — | 有令牌继续转发；没令牌设 429 并结束响应 |
| 7 | `RateLimitResponseFilter` 的装饰器 | 第 43-57 行 | 看到 429 → 改成 200，写 `{"code":4290}` |

**数据编号**

| 编号 | 数据 | 初始值 | 谁产生 |
|---|---|---|---|
| `[D1]` | 路由 id | `product-service` / `order-service` | 路由匹配 |
| `[D2]` | 限流 Key | `ip:127.0.0.1` 或 `user:1` | `RateLimitConfig` |
| `[D3]` | Redis 桶键 | `request_rate_limiter.{路由id}.{Key}.*` | `RedisRateLimiter` |
| `[D4]` | 剩余令牌 | 0~5 | Redis Lua 计算 |
| `[D5]` | 是否放行 | `true` / `false` | `RedisRateLimiter` |
| `[D6]` | 浏览器响应 | 业务 JSON 或 `{"code":4290,...}` | 下游 / 装饰器 |

**四个结局（总览）**

| 结局 | 分岔点 | 条件 | 浏览器 |
|---|---|---|---|
| ① 有令牌 | `[D5] = true` | 桶里令牌 ≥ 1 | 正常转发，`code=0` |
| ② 令牌不足 | `[D5] = false` | 桶里令牌 < 1 | `{"code":4290,"msg":"请求过于频繁，请稍后再试"}` |
| ③ 等一会儿恢复 | Redis 补令牌 | 时间流逝，`replenishRate` 补齐 | 再次 `code=0` |
| ④ Redis 不可用 | `RedisRateLimiter` 报错 | Redis 没起/断开 | 5xx（限流器无法判断） |

### 结局①逐步行进：有令牌

#### 第 1 步｜先鉴权，再限流

- 【框架动作】GlobalFilter 按 `getOrder()` 排序：`AuthGlobalFilter(-100)` → `RateLimitResponseFilter(-90)` → 路由 filters（从 1 开始）。
- 【数据】到限流这一步时，请求头已经带好 `X-Mall-User-Id`（受保护路径）或被清空（公开路径）。

#### 第 2 步｜KeyResolver 算 Key

```java
// 文件：mall-gateway/src/main/java/com/mall/gateway/config/RateLimitConfig.java
// 行号：26-36
public KeyResolver userKeyResolver() {
    return exchange -> {
        String userId = exchange.getRequest().getHeaders().getFirst(AuthHeaders.USER_ID);
        if (userId != null && !userId.isBlank()) {
            return Mono.just("user:" + userId);
        }
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        String ip = remote == null ? "unknown" : remote.getAddress().getHostAddress();
        return Mono.just("ip:" + ip);
    };
}
```

- 【你写的】有 userId 用 `user:<id>`，没有就用 `ip:<地址>`。
- 【数据】商品 GET 未登录 → `[D2] = ip:127.0.0.1`；order GET 带 zhangsan token → `[D2] = user:1`。

#### 第 3 步｜Redis 令牌桶判定

- 【你写的】路由上的 YAML 第 39-45 行把 `key-resolver` 指向 `#{@userKeyResolver}`，并给出三个参数。
- 【框架动作】`RedisRateLimiter` 执行内置 Lua：
  1. 读桶当前令牌数（没有就按满桶算）；
  2. 按距离上次的时间 × `replenishRate` 补令牌，上限 `burstCapacity`；
  3. 够 `requestedTokens` 就扣掉并返回放行，否则返回拒绝。
- 【数据】`[D3]` 形如 `request_rate_limiter.product-service.ip:127.0.0.1.tokens`；`[D4]` 扣减后写回 Redis；`[D5] = true`。

#### 第 4 步｜放行

- 【框架动作】`chain.filter(exchange)` 继续走到下游，响应头会带 `X-RateLimit-Remaining`、`X-RateLimit-Replenish-Rate`、`X-RateLimit-Burst-Capacity`。
- 【数据】浏览器收到商品/订单 JSON，`[D6].code = 0`。

### 结局②逐步行进：令牌不足

- 【框架动作】`RedisRateLimiter` 返回 `allowed=false`；`RequestRateLimiter` 把响应状态设为 429，然后调用 `setComplete()`，**不写响应体**。
- 【数据】`[D5] = false`，`[D6]` 此时还没有内容。
- 【你写的】`RateLimitResponseFilter` 的装饰器接住这次 `setComplete`：

```java
// 文件：mall-gateway/src/main/java/com/mall/gateway/filter/RateLimitResponseFilter.java
// 行号：41-57
ServerHttpResponseDecorator decorated = new ServerHttpResponseDecorator(exchange.getResponse()) {
    @Override
    public Mono<Void> setComplete() {
        if (HttpStatus.TOO_MANY_REQUESTS.equals(getStatusCode())) {
            setStatusCode(HttpStatus.OK);
            getHeaders().setContentType(MediaType.APPLICATION_JSON);
            try {
                byte[] bytes = objectMapper.writeValueAsBytes(Result.fail(ErrorCode.FLOW_LIMITED));
                DataBuffer buffer = bufferFactory().wrap(bytes);
                return super.writeWith(Mono.just(buffer));
            } catch (JsonProcessingException e) {
                log.error("网关写限流响应失败", e);
            }
        }
        return super.setComplete();
    }
};
```

- 【数据】状态从 429 改成 200；`[D6] = {"code":4290,"msg":"请求过于频繁，请稍后再试","data":null}`。
- 为什么不能"限流后再补写响应"：`setComplete()` 已经把响应提交了，提交后再写会报错；必须在提交前拦截，所以用装饰器。

### 结局③逐步行进：等一会儿恢复

- 【框架动作】Redis 里的桶按 `replenishRate=2/秒` 自动补令牌，最多回到 `burstCapacity=5`。
- 【数据】等待 3 秒后，桶里至少 5 个令牌，下一次请求 `[D5] = true`，`code=0`。
- 注意：限流器不需要定时任务，"补令牌"是每次请求时按时间差算出来的。

### 结局④逐步行进：Redis 不可用

- 【框架动作】`RedisRateLimiter` 连不上 Redis，返回错误，网关无法判断是否放行。
- 【数据】请求返回 5xx，而不是 4290；这类故障要靠监控发现，不能当成"被限流"。
- 本地验证方式：停掉 Redis 后再请求 `/api/product/1`。

### L7-4 实测

```text
配置：replenishRate=2，burstCapacity=5，requestedTokens=1

1. 连续 10 次 GET /api/product/1（未登录，key=ip:127.0.0.1）
   返回 code 序列: 0,0,0,0,0,0,4290,4290,4290,4290
   成功=6 限流=4

2. 等待 3 秒后再次请求
   code=0

3. 连续 10 次 GET /api/order/1（zhangsan，key=user:1）
   返回 code 序列: 0,0,0,0,0,0,0,0,4290,4290
   成功=8 限流=2

4. 等待 3 秒后再次请求
   code=0
```

> 为什么成功数大于 5：10 次请求不是同一毫秒发完的，请求本身要花时间，桶在这期间按 2/秒补了令牌。`burstCapacity` 限的是"瞬间最多攒多少"，不是"总共只能过多少"。

### L7-4 坑

1. **必须引 reactive 版 Redis**：网关是 WebFlux，用阻塞式 `spring-boot-starter-data-redis` 会破坏响应式链路；`RedisRateLimiter` 依赖 `ReactiveStringRedisTemplate`。
2. **`key-resolver` 是 SpEL 引用 Bean**：写 `"#{@userKeyResolver}"`，不是类名、不是方法名；写错启动时报找不到 SpEL。
3. **过滤器顺序不能反**：`AuthGlobalFilter(-100)` 必须在限流之前，否则 `X-Mall-User-Id` 还没写，所有已登录用户都会被算成 `ip:` 桶。
4. **限流器默认返回空 429**：前端只认 body.code 会解析失败，所以加了 `RateLimitResponseFilter`；它必须排在路由过滤器之前才能包住响应。
5. **按路由分桶**：Redis 键里带路由 id，`/api/product/**` 和 `/api/order/**` 是两个独立的桶，互不影响。
6. **参数是演示值**：2/秒、5 突发对真实接口太低；生产按接口容量、机器数、Redis 性能调，并通过配置中心下发。
7. **Redis 故障不是限流**：连不上 Redis 会 5xx；如果业务不能接受"限流器故障导致全站不可用"，需要降级策略（例如放行 + 告警）。
8. **登录接口没有挂限流**：本步只给商品和订单路由配了限流；真实项目登录接口更该限流（防撞库），可作为扩展。

### L7-4 断点验证与自测题

| 停在哪 | 看什么 | 看到什么证明走对了 |
|---|---|---|
| `RateLimitConfig` 第 29 行 | `userId` | 未登录商品请求是 `null`；带 token 的订单请求是 `"1"` |
| 第 35 行 | 返回的 `Mono` 值 | `ip:127.0.0.1` 或 `user:1` |
| `RateLimitResponseFilter` 第 45 行 | `getStatusCode()` | 限流请求进来时是 `429` |
| 第 49 行 | `bytes` 转字符串 | `{"code":4290,"msg":"请求过于频繁，请稍后再试","data":null}` |
| Redis 客户端 | `KEYS request_rate_limiter.*` | 能看到按路由 id + Key 生成的桶键 |

### 自测题（不附答案）

1. `replenishRate=2`、`burstCapacity=5` 分别限制了什么？为什么连续 10 次请求可能通过 6 次而不是 5 次？
2. 为什么限流的 Key 要优先用 userId，而不是全部按 IP？
3. `RequestRateLimiter` 拒绝时已经调用了 `setComplete()`，为什么还能把响应体改成 `code=4290`？
