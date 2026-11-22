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
| L7-2 | `AuthGlobalFilter`：白名单 + 验签 + 注入身份头 | 未开始 |
| L7-3 | 服务改信任网关头，Feign 透传 | 未开始 |
| L7-4 | 网关限流 `RequestRateLimiter` | 未开始 |
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
