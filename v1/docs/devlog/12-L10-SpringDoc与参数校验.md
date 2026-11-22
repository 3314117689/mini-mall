# 12 L10 SpringDoc 与参数校验

> 按铁律 11 写：总览表只做导航，过程在"逐步行进"里。
> 本章的一条轴：**一条调试命令 `GET /swagger-ui/index.html` 打开发文档页面，会话里带上 Bearer token 直接调 `POST /order/create`；非法参数在进入 Service 之前就被 JSR303 拦下，统一返回 1000 并指明字段。**
> 环境事实：Boot **4.1.2**、JDK **17**、SpringDoc **3.2.0**；Hibernate Validator **9.1.5.Final**、`jakarta.validation-api` **3.1.1**；user=8081、product=8082、order=8083、gateway=9000。三个业务服务直连时只认 `X-Mall-*` 三个头（L7-3 起不验 Bearer），走网关 9000 才验 Bearer。

## 一、这一层解决什么

L2~L9 的接口能力已经齐了，但对外"怎么说清楚、怎么挡住坏参数"一直是散的：

1. **文档过时**：接口只存在于 Controller 注解和 devlog 里，参数结构靠翻代码；
2. **校验散落**：`OrderServiceImpl.create` 手写 productId/num 判断，`page/cursor` 各自调 `checkPageArgs`，`UserServiceImpl.save`、`AuthServiceImpl.login` 也各写一段 if；报错只有一句"参数错误"，不说是哪个字段；
3. **异常不成体系**：`GlobalExceptionHandler` 只接 `BizException`、`Exception`、`NoResourceFoundException`，JSR303 的校验异常会落进兜底 `Exception` → 5000，而不是参数错误；
4. **内部接口暴露**：`/product/stock/**` 是给 order 服务 Feign 用的，不该出现在对外文档里；生产环境更不该开放 Swagger。

L10 不加业务功能，只做三件事：**在线文档、注解校验、异常收口**。

## 二、技术点清单

| # | 技术点 | 落点 | 一句话作用 | 面试频度 |
|---|---|---|---|---|
| 1 | SpringDoc + Swagger UI | 三个业务服务 | 扫 Controller/DTO 生成 OpenAPI JSON，页面可在线调试 | ★★★ |
| 2 | 文档分组 | `springdoc.group-configs` | 组名=服务名，一个服务一份文档，不互相混 | ★★ |
| 3 | 生产开关 | `application-prod.yml` | `api-docs`/`swagger-ui` 关闭，不暴露接口清单 | ★★ |
| 4 | Bearer 鉴权调试 | Swagger `securityScheme` + `servers` 指向网关 | 登录拿 token 后，页面直接调受保护接口 | ★★★ |
| 5 | JSR303 注解 | DTO / 实体 / 方法参数 | `@NotNull`/`@NotBlank`/`@Min`/`@Max`/`@Size` 声明式校验 | ★★★ |
| 6 | `@Valid` / `@Validated` | Controller | 请求体用 `@Valid`，方法参数（`@RequestParam`）用类级 `@Validated` | ★★★ |
| 7 | 校验异常收口 | `GlobalExceptionHandler` | `MethodArgumentNotValidException` 等统一转 1000，message 带字段名 | ★★★ |
| 8 | 删除重复手写校验 | Service 层 | 注解能表达的删掉；库存不足、订单不存在这类业务规则保留 | ★★ |

## 三、小步与状态

| 步 | 内容 | 状态 |
|---|---|---|
| L10-0 | 基线：编译、无效参数当前响应 | ✅ |
| L10-1 | 接入 SpringDoc（依赖/分组/prod 开关），三个 Swagger UI 可打开 | ✅ |
| L10-2 | Swagger 指向网关 + Bearer 鉴权，调通下单/分页 | ✅ |
| L10-3 | `GlobalExceptionHandler` 接管 JSR303 / 绑定 / 类型转换异常 | ✅ |
| L10-4 | 登录/注册 DTO 校验，删 `UserServiceImpl`/`AuthServiceImpl` 手写 if | ⬜ |
| L10-5 | 商品与库存接口校验，`/product/stock/**` 文档隐藏 | ⬜ |
| L10-6 | 下单/分页/游标参数校验，删 `checkPageArgs` 与 `MAX_PAGE_SIZE` | ⬜ |
| L10-7 | 总验收、文档、提交推送 | ⬜ |

## 四、L10-0 基线体检

### 第 1 步｜编译

```text
在 v1/ 目录执行：mvn -q install -DskipTests
```

- 【数据】`[C1] 退出码 0，无输出`，改动前工程可编译。

### 第 2 步｜记录无效参数的当前响应

只启动 order（8083），带三个身份头直连（`X-Mall-User-Id: 1`、`X-Mall-Username: tester`、`X-Mall-Role: USER`）：

```powershell
curl.exe -s -H "X-Mall-User-Id: 1" -H "X-Mall-Username: tester" -H "X-Mall-Role: USER" `
  "http://localhost:8083/order/page?userId=1&page=0&size=10"
curl.exe -s -X POST -H "Content-Type: application/json" `
  -H "X-Mall-User-Id: 1" -H "X-Mall-Username: tester" -H "X-Mall-Role: USER" `
  -d '{\"productId\":null,\"num\":1}' "http://localhost:8083/order/create"
curl.exe -s -X POST -H "Content-Type: application/json" `
  -H "X-Mall-User-Id: 1" -H "X-Mall-Username: tester" -H "X-Mall-Role: USER" `
  -d '{\"productId\":1,\"num\":0}' "http://localhost:8083/order/create"
```

- 【数据】`[D0]`：三条命令返回完全相同：

```json
{"code":1000,"data":null,"msg":"参数错误"}
```

- 【结论】`code` 已经是 1000（`OrderServiceImpl.create` 第 101-103 行、`checkPageArgs` 第 272-277 行抛的 `BizException(ErrorCode.PARAM_ERROR)`），但 **message 不带字段名**，调用方不知道改哪个参数。L10-6 之后这三条仍应是 1000，但 message 要变成 `productId: 不能为 null` 这类。
- 【基线】DB 商品 1 库存 `100`；`id_segment.max_id` 与 Redis `mall:order:seq` 水位都是 `7008`，本章验收不清理。

## 五、L10-1 接入 SpringDoc

**命令入场**

```text
GET http://localhost:8083/swagger-ui/index.html     → 200，页面能打开
GET http://localhost:8083/v3/api-docs/order-service → 200，OpenAPI JSON
```

**结局表**

| 结局 | 触发条件 | 现象 |
|---|---|---|
| ① 正常 | 默认 profile | `/swagger-ui/index.html` 200；`/v3/api-docs/{组名}` 200，含本服务接口 |
| ② 生产关闭 | `--spring.profiles.active=prod` | 两个地址都只剩空体（文档端点未注册） |

### 第 1 步｜加依赖

- [pom.xml](file:///d:/bishe/workspace/gongju/eclipseworkspace/mini-mall/v1/pom.xml)：`properties` 增加 `springdoc.version=3.2.0`；`dependencyManagement` 增加 `springdoc-openapi-starter-webmvc-ui`，子模块不写版本。
- [mall-user/pom.xml](file:///d:/bishe/workspace/gongju/eclipseworkspace/mini-mall/v1/mall-user/pom.xml)、[mall-product/pom.xml](file:///d:/bishe/workspace/gongju/eclipseworkspace/mini-mall/v1/mall-product/pom.xml)、[mall-order/pom.xml](file:///d:/bishe/workspace/gongju/eclipseworkspace/mini-mall/v1/mall-order/pom.xml)：`spring-boot-starter-web` 之后各加两个依赖——`spring-boot-starter-validation` 与 `springdoc-openapi-starter-webmvc-ui`。
- [mall-common/pom.xml](file:///d:/bishe/workspace/gongju/eclipseworkspace/mini-mall/v1/mall-common/pom.xml)：加 `spring-boot-starter-validation`，`scope=provided`。理由：L10-3 的 `GlobalExceptionHandler` 要引 `jakarta.validation.ConstraintViolationException`，只需编译期类型，不把校验实现打进公共 jar。

- 【数据】`[D1]` 解析结果（`mvn dependency:tree -pl mall-order`）：

| 依赖 | 版本 | scope |
|---|---|---|
| `org.springdoc:springdoc-openapi-starter-webmvc-ui` | `3.2.0` | compile |
| `org.springframework.boot:spring-boot-starter-validation` | `4.1.2` | compile |
| `org.hibernate.validator:hibernate-validator` | `9.1.5.Final` | compile（由 validation starter 带入） |
| `jakarta.validation:jakarta.validation-api` | `3.1.1` | compile |

- 【为什么要显式加 validation】Boot 4 的 `spring-boot-starter-web` 不再携带校验实现；只写 `@NotNull` 而没有 starter-validation 时，注解不会生效，也不报错。

### 第 2 步｜文档基础信息

新增三个 `OpenApiConfig`，只放 `Info`（标题/描述/版本）：

- [OpenApiConfig.java（user）](file:///d:/bishe/workspace/gongju/eclipseworkspace/mini-mall/v1/mall-user/src/main/java/com/mall/user/config/OpenApiConfig.java#L15-L22)
- [OpenApiConfig.java（product）](file:///d:/bishe/workspace/gongju/eclipseworkspace/mini-mall/v1/mall-product/src/main/java/com/mall/product/config/OpenApiConfig.java#L15-L22)
- [OpenApiConfig.java（order）](file:///d:/bishe/workspace/gongju/eclipseworkspace/mini-mall/v1/mall-order/src/main/java/com/mall/order/config/OpenApiConfig.java#L15-L22)

```java
@Bean
public OpenAPI orderServiceOpenAPI() {
    return new OpenAPI().info(new Info()
            .title("mini-mall 订单服务 API")
            .description("下单、订单分页/游标查询、库存补偿重放；直连端口 8083")
            .version("1.0.0"));
}
```

- 【数据】`[D2]`：`GET /v3/api-docs/order-service` 的 `info.title` = `mini-mall 订单服务 API`，`info.version` = `1.0.0`。
- L10-2 会在同一个 bean 上补 `servers` 与 `securityScheme`（Bearer），这里先不加。

### 第 3 步｜按服务分组

三个 `application.yml` 末尾各加：

```yaml
springdoc:
  group-configs:
    - group: order-service
      paths-to-match:
        - /order/**
```

- user 的 `paths-to-match` 是 `/user/**`、`/auth/**`；product 是 `/product/**`。
- 【数据】`[D3]`：`[D2]` 的 JSON 里 `paths` 只有本服务的 6 条（order）：`/order`、`/order/{id}`、`/order/create`、`/order/page`、`/order/cursor`、`/order/compensate/replay`。
- 组名=服务名，Swagger UI 右上角下拉只有一个选项；三个服务各自独立页面，不做网关聚合页。

### 第 4 步｜生产开关

三个服务新增 `application-prod.yml`：

```yaml
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
```

- 【数据】`[D4]`：用 `java -jar target\mall-order-1.0.0.jar --server.port=8084 --spring.profiles.active=prod` 启动，日志出现 `The following 1 profile is active: "prod"`；`GET /v3/api-docs` 与 `GET /swagger-ui/index.html` 的响应体长度均为 `0`，而默认 profile 的 `/v3/api-docs` 为 `4454` 字节。
- 【已知现象】关闭后访问返回 **HTTP 200 + 空体**，不是 404。原因是 [GlobalExceptionHandler](file:///d:/bishe/workspace/gongju/eclipseworkspace/mini-mall/v1/mall-common/src/main/java/com/mall/common/exception/GlobalExceptionHandler.java#L41-L44) 的 `handleFavicon` 把所有 `NoResourceFoundException` 吞成了空响应。L10-3 会把该处理器改成只对 `/favicon.ico` 静默、其余资源缺失返回 404，届时这里的验收口径同步改为 404。

### 第 5 步｜三服务验证

```powershell
foreach ($p in 8081,8082,8083) { curl.exe -s -o NUL -w "%{http_code}" "http://localhost:$p/swagger-ui/index.html" }
```

- 【数据】`[D5]`：

| 地址 | 状态码 | 数据 |
|---|---|---|
| `8081 /swagger-ui/index.html` | 200 | 用户服务页面 |
| `8082 /swagger-ui/index.html` | 200 | 商品服务页面 |
| `8083 /swagger-ui/index.html` | 200 | 订单服务页面 |
| `8081 /v3/api-docs/user-service` | 200 | title=`mini-mall 用户服务 API` |
| `8082 /v3/api-docs/product-service` | 200 | title=`mini-mall 商品服务 API` |
| `8083 /v3/api-docs/order-service` | 200 | title=`mini-mall 订单服务 API`，6 条 path |

### L10-1 回滚方案

- 撤销：删除四个 pom 中新增的依赖节点与父 pom 的 `springdoc.version`/`dependencyManagement`；删除三个 `OpenApiConfig`、三个 `application-prod.yml`、三个 `application.yml` 的 `springdoc` 段。
- 影响：无数据库、Redis、Nacos 变更；`mvn -q install -DskipTests` 后即回到 L9 行为。
- 现存接口路径不变，直连 8081/8082/8083 的测试方式不受影响（`/v3/api-docs`、`/swagger-ui/**` 是新增路径）。

## 六、L10-2 Swagger 指向网关 + Bearer

**为什么必须指向 9000**：L7-3 之后，order/user 直连只认 `X-Mall-User-Id/Username/Role` 三个头，**不再验 Bearer**；Bearer 只在网关 9000 被验，验完由网关写身份头转发。所以 Swagger 的 `servers` 只能是 `http://localhost:9000/api`，否则页面里填了 token 也会在下游拿到 4010。

**命令入场**

```text
① POST http://localhost:9000/api/auth/login   {"username":"zhangsan","password":"123456"}
② POST http://localhost:9000/api/order/create  Authorization: Bearer <①的 token>  {"productId":1,"num":1}
③ GET  http://localhost:9000/api/order/page?userId=1&page=1&size=3  Authorization: Bearer <①的 token>
```

**结局表**

| 结局 | 触发条件 | 数据 |
|---|---|---|
| ① 登录成功 | 用户名密码正确 | `code=0`，`data.token` 501 字符，`expiresIn=7200` |
| ② 下单成功 | Bearer 有效 + 商品存在 + 库存够 | `code=0`，`data=100157` |
| ③ 未填 token | 没点 Authorize 就调受保护接口 | 网关直接返回 `{"code":4010,"data":null,"msg":"未登录，请先登录"}` |
| ④ token 无效/过期 | 乱填或超过 2 小时 | `{"code":4011,...}`，网关不转发 |

### 第 1 步｜OpenApiConfig 补 servers 与安全方案

三个 `OpenApiConfig` 在 `Info` 之后追加：

```java
.servers(List.of(new Server()
        .url("http://localhost:9000/api")
        .description("经网关：统一验 Bearer token")))
.components(new Components().addSecuritySchemes(BEARER_AUTH,
        new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")))
```

- `BEARER_AUTH = "bearerAuth"` 是常量，Controller 的 `@SecurityRequirement` 直接引用，避免两处字符串写得不一致。
- 【数据】`GET /v3/api-docs/order-service` → `servers[0].url = http://localhost:9000/api`，`components.securitySchemes.bearerAuth = {type: http, scheme: bearer, bearerFormat: JWT}`。

### 第 2 步｜Controller 标注

- [OrderController](file:///d:/bishe/workspace/gongju/eclipseworkspace/mini-mall/v1/mall-order/src/main/java/com/mall/order/controller/OrderController.java#L29-L31)：类上 `@Tag(name="订单服务")` + `@SecurityRequirement(name=OpenApiConfig.BEARER_AUTH)`，8 个方法各一个 `@Operation(summary=...)`。
- [UserController](file:///d:/bishe/workspace/gongju/eclipseworkspace/mini-mall/v1/mall-user/src/main/java/com/mall/user/controller/UserController.java#L27-L29)：类上 `@Tag` + `@SecurityRequirement`（`/user/**` 全部要登录）。
- [AuthController](file:///d:/bishe/workspace/gongju/eclipseworkspace/mini-mall/v1/mall-user/src/main/java/com/mall/user/controller/AuthController.java#L33-L34)：只加 `@Tag(name="认证")`，登录/注册/JWKS **不加** `@SecurityRequirement`（网关白名单）。
- [ProductController](file:///d:/bishe/workspace/gongju/eclipseworkspace/mini-mall/v1/mall-product/src/main/java/com/mall/product/controller/ProductController.java#L26-L27)：类上只加 `@Tag`；`GET /product/{id}` 公开，`save/update/delete` 与 `/stock/**` 逐个方法加 `@SecurityRequirement`（网关只放行商品 GET/HEAD）。

- 【数据】`[D6]`：order 文档里 `/order/create`、`/order/page` 的 `security` 都是 `{"bearerAuth":[]}`；user 文档里 `/auth/login` 的 `security` 为 `null`（公开）。

### 第 3 步｜页面操作与验证

1. 打开 `http://localhost:8081/swagger-ui/index.html`，`POST /auth/login` 填 `{"username":"zhangsan","password":"123456"}`，复制 `data.token`；
2. 打开 `http://localhost:8083/swagger-ui/index.html`，点右上角 **Authorize**，粘贴 token（不用手写 `Bearer ` 前缀，页面会加）；
3. 调 `POST /order/create`，body `{"productId":1,"num":1}`；再调 `GET /order/page`，`userId=1&page=1&size=3`。

- 【数据】`[D7]`：

| 请求 | 结果 |
|---|---|
| 登录 | `code=0`，token 长度 `501` |
| 下单 | `code=0`，订单 id=`100157` |
| 分页 | `code=0`，返回 3 条，首条 `id=100007, userId=1, amount=29.70`（L8 造的历史数据，`orderNo=null`） |
| 库存 | 下单前 `100` → 下单后 `99` |

- 说明：历史 10 万条是 L8 造的，没有 `order_no`；L9 之后新下的单才有订单号。测试订单不清理，记录在此。

### L10-2 回滚方案

- 撤销：三个 `OpenApiConfig` 去掉 `.servers(...)` 与 `.components(...)`，Controller 去掉 `@Tag/@Operation/@SecurityRequirement` 及其 import。
- 影响：只影响文档元数据，接口行为、网关鉴权、直连测试方式都不变。

## 七、L10-3 校验异常收口

**改动**：[GlobalExceptionHandler](file:///d:/bishe/workspace/gongju/eclipseworkspace/mini-mall/v1/mall-common/src/main/java/com/mall/common/exception/GlobalExceptionHandler.java) 新增 8 个分支，统一规则：**参数类全部 `code=1000`，message 带字段名**。

| 异常 | 触发场景 | message 形如 |
|---|---|---|
| `MethodArgumentNotValidException` | 请求体 `@Valid` 校验失败 | `productId: 不能为 null；num: 最小不能小于 1` |
| `BindException` | 查询参数/表单绑定失败 | 同上（`MethodArgumentNotValidException` 的父类，兜住其他入口） |
| `ConstraintViolationException` | 类上 `@Validated` + 参数约束（AOP 校验） | `page: 最小不能小于 1` |
| `HandlerMethodValidationException` | Spring 6.1+ 内置方法参数校验 | `size: 最大不能超过 100` |
| `MissingServletRequestParameterException` | 缺必填查询参数 | `缺少必填参数: userId` |
| `MethodArgumentTypeMismatchException` | `userId=abc` 转 Long 失败 | `参数 userId 类型不正确，应为 Long` |
| `HttpMessageNotReadableException` | 请求体不是合法 JSON | `请求体格式错误` |
| `NoResourceFoundException` | 资源不存在（含 prod 关闭后的文档端点） | HTTP **404** 空体 |

- 【为什么改 `NoResourceFoundException`】L10-1 留下的是"200 空体"（原 `handleFavicon` 吞掉所有 `NoResourceFoundException`）。生产关闭 Swagger 后必须能区分"没有这个接口"，所以改成 `@ResponseStatus(HttpStatus.NOT_FOUND)`。
- 【数据】`[D8]`（直连 8083，带三个身份头）：

| 请求 | 返回 |
|---|---|
| `GET /order/page?page=1&size=10`（缺 userId） | `{"code":1000,"msg":"缺少必填参数: userId"}` |
| `GET /order/page?userId=abc&page=1&size=10` | `{"code":1000,"msg":"参数 userId 类型不正确，应为 Long"}` |
| `POST /order/create` body=`{bad` | `{"code":1000,"msg":"请求体格式错误"}` |
| `GET /nope` | HTTP `404`，空体 |

- 前两个分支需要 DTO/参数上的约束注解才会触发，L10-4~L10-6 加完注解后在 L10-7 一并复验。

### L10-3 回滚方案

- 撤销：`GlobalExceptionHandler` 恢复为 L9 版本（只留 `BizException`、`Exception`、`handleFavicon`）。
- 影响：无数据变更；回滚后 JSR303 异常会落回 5000，prod 关闭文档后又变回 200 空体。
