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
| L10-4 | 登录/注册 DTO 校验，删 `UserServiceImpl`/`AuthServiceImpl` 手写 if | ✅ |
| L10-5 | 商品与库存接口校验，`/product/stock/**` 文档隐藏 | ✅ |
| L10-6 | 下单/分页/游标参数校验，删 `checkPageArgs` 与 `MAX_PAGE_SIZE` | ✅ |
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

## 八、L10-4 登录/注册参数校验

**命令入场**

```text
POST http://localhost:8081/auth/register  {}
POST http://localhost:8081/auth/register  {"username":"ab","password":"123456"}
POST http://localhost:8081/auth/register  {"username":"l10tester","password":"123456","phone":"13800000000"}
POST http://localhost:8081/auth/login     {}
POST http://localhost:8081/auth/login     {"username":"zhangsan","password":"123456"}
```

**结局表**

| 结局 | 触发条件 | 现象 |
|---|---|---|
| ① 必填缺失 | 注册/登录 body 为 `{}` | `code=1000`，message 两个字段都点出来 |
| ② 格式不对 | 用户名 2 位、密码 3 位 | `code=1000`，指明长度区间 |
| ③ 合法注册 | 用户名不重复 | `code=0`，`data` 为自增 id |
| ④ 用户名重复 | 同一用户名再注册 | 仍走业务码 `1004` 用户名已存在（业务规则保留） |
| ⑤ 登录成功 | 用户名密码正确 | `code=0`，返回 token |
| ⑥ 登录失败 | 密码错误 | 仍是 `1005`，不会被校验注解改成 1000 |

### 第 1 步｜注册 DTO

新增 [RegisterDTO.java](file:///d:/bishe/workspace/gongju/eclipseworkspace/mini-mall/v1/mall-user/src/main/java/com/mall/user/dto/RegisterDTO.java)：

| 字段 | 注解 | message |
|---|---|---|
| `username` | `@NotBlank` + `@Size(min=3,max=20)` | `不能为空` / `长度需在 3~20 个字符之间` |
| `password` | `@NotBlank` + `@Size(min=6,max=32)` | `不能为空` / `长度需在 6~32 个字符之间` |
| `phone` | `@Size(max=20)`（可选） | `长度不能超过 20 个字符` |

- 【为什么 message 里不写"用户名"】`GlobalExceptionHandler.formatFieldError` 会拼成 `字段: 原因`，注解里再写字段名就重复成 `username: 用户名不能为空`。
- 【为什么注册要单独 DTO】原来直接收 `User`，客户端可以顺手传 `id`/`role`；DTO 只放开 `username`/`password`/`phone`，多余字段被忽略。

### 第 2 步｜登录 DTO

[LoginDTO.java](file:///d:/bishe/workspace/gongju/eclipseworkspace/mini-mall/v1/mall-user/src/main/java/com/mall/user/dto/LoginDTO.java)：两个字段加 `@NotBlank`，只加 `@Size(max=...)` 上限。

- 【为什么登录不校验密码最小长度】密码短了本来就会走到"用户名或密码错误"的 `1005`；如果校验成 `1000`，反而把"格式错误"和"账号密码错误"区分开，多给了攻击者一点信息。

### 第 3 步｜Controller 加 `@Valid`

- [UserController](file:///d:/bishe/workspace/gongju/eclipseworkspace/mini-mall/v1/mall-user/src/main/java/com/mall/user/controller/UserController.java)：`save(@Valid @RequestBody RegisterDTO dto)`。
- [AuthController](file:///d:/bishe/workspace/gongju/eclipseworkspace/mini-mall/v1/mall-user/src/main/java/com/mall/user/controller/AuthController.java)：`login(@Valid @RequestBody LoginDTO dto)`、`register(@Valid @RequestBody RegisterDTO dto)`。
- `update` 仍收 `User`：更新没有必填字段，先不动；要加约束时再拆 `UserUpdateDTO`。

### 第 4 步｜Service 删手写校验

- [UserService.save](file:///d:/bishe/workspace/gongju/eclipseworkspace/mini-mall/v1/mall-user/src/main/java/com/mall/user/service/UserService.java) 签名改为 `Long save(RegisterDTO dto)`。
- [UserServiceImpl.save](file:///d:/bishe/workspace/gongju/eclipseworkspace/mini-mall/v1/mall-user/src/main/java/com/mall/user/service/impl/UserServiceImpl.java)：删掉 username/password 非空判断，保留"用户名唯一"业务规则；DTO 转 `User` 后再哈希落库（`UserMapper.insert` 不写 role，走库默认值 USER）。
- [AuthServiceImpl.login](file:///d:/bishe/workspace/gongju/eclipseworkspace/mini-mall/v1/mall-user/src/main/java/com/mall/user/service/impl/AuthServiceImpl.java)：删掉 username/password 非空判断，直接查库。

### 第 5 步｜验证

直连 8081，`/auth/**` 不需要身份头：

- 【数据】`[D9]`：

| 请求 | 返回 |
|---|---|
| `POST /auth/register` body=`{}` | `{"code":1000,"msg":"password: 不能为空；username: 不能为空"}` |
| `POST /auth/register` body=`{"username":"ab","password":"123456"}` | `{"code":1000,"msg":"username: 长度需在 3~20 个字符之间"}` |
| `POST /auth/register` body=`{"username":"l10tester","password":"123456","phone":"13800000000"}` | `{"code":0,"data":9,"msg":"ok"}` |
| 同一用户名再注册 | `{"code":1004,"msg":"用户名已存在"}` |
| `POST /auth/login` body=`{}` | `{"code":1000,"msg":"username: 不能为空；password: 不能为空"}` |
| `POST /auth/login` `zhangsan/123456` | `code=0`，token 正常签发 |
| `POST /auth/login` `zhangsan/bad` | `{"code":1005,"msg":"用户名或密码错误"}` |
| `POST /user`（带三个身份头）body=`{"username":"ab"}` | `{"code":1000,"msg":"password: 不能为空；username: 长度需在 3~20 个字符之间"}` |

- 【数据】`GET /v3/api-docs/user-service` 已含 `RegisterDTO`，`username.minLength=3`，`/auth/register` 的 requestBody 指向它。
- 【数据】测试用户 `l10tester`（id=9）保留在库里，用于后续验收；不清理。

### L10-4 回滚方案

- 撤销：删除 `RegisterDTO`；`LoginDTO` 去掉校验注解；两个 Controller 去掉 `@Valid` 并改回收 `User`；`UserService.save` 改回 `Long save(User user)`；恢复 `UserServiceImpl.save` 与 `AuthServiceImpl.login` 的两段 if。
- 影响：无表结构、无 Redis 变更；回滚后注册仍可传 `id/role`，坏参数只返回不带字段名的 1000。

## 九、L10-5 商品与库存接口校验

**命令入场**

```text
POST http://localhost:8082/product                  {}                                  → 1000
POST http://localhost:8082/product                  {"name":"L10测试商品","price":0,...} → 1000
POST http://localhost:8082/product                  {"name":"L10测试商品","price":9.90,"stock":10} → 0
POST http://localhost:8082/product/stock/deduct     ?orderNo=L10TEST&productId=1&num=0  → 1000
GET  http://localhost:8082/v3/api-docs/product-service                                  → 无 /product/stock
```

**结局表**

| 结局 | 触发条件 | 现象 |
|---|---|---|
| ① 必填缺失 | `POST /product` body=`{}` | `code=1000`，name/price/stock 三个字段都点出来 |
| ② 价格非法 | `price=0` | `code=1000`，`price: 必须大于 0` |
| ③ 合法新增 | 三字段齐全 | `code=0`，返回自增 id |
| ④ 库存参数非法 | `num=0` | `code=1000`，`num: 不能小于 1` |
| ⑤ 缺内部参数 | 扣减不传 orderNo | `code=1000`，`缺少必填参数: orderNo` |
| ⑥ 文档隐藏 | 打开 `/v3/api-docs/product-service` | 只剩 `/product`、`/product/{id}`，`/product/stock/**` 不出现 |

### 第 1 步｜实体加约束

[Product.java](file:///d:/bishe/workspace/gongju/eclipseworkspace/mini-mall/v1/mall-product/src/main/java/com/mall/product/entity/Product.java)：

| 字段 | 注解 | message |
|---|---|---|
| `name` | `@NotBlank` + `@Size(max=100)` | `不能为空` / `长度不能超过 100 个字符` |
| `price` | `@NotNull` + `@DecimalMin(value="0.00", inclusive=false)` | `不能为空` / `必须大于 0` |
| `stock` | `@NotNull` + `@Min(0)` | `不能为空` / `不能小于 0` |

- 【为什么 `id` 不加 `@NotNull`】新增时 id 由数据库自增，加了会挡住正常插入；更新时缺 id 会因"受影响 0 行"落到既有的 `1003 商品不存在`，不必为它拆 DTO 或引入校验分组。
- 【为什么约束写在实体上】商品只有 `Product` 一个入参模型，新增/更新字段一致；L10-4 的用户之所以单独拆 `RegisterDTO`，是因为注册不能收 `id/role`。

### 第 2 步｜Controller 加 `@Valid`

[ProductController](file:///d:/bishe/workspace/gongju/eclipseworkspace/mini-mall/v1/mall-product/src/main/java/com/mall/product/controller/ProductController.java)：`save`、`update` 的 `@RequestBody Product` 前加 `@Valid`。`getById`/`deleteById` 不涉及请求体，不加。

### 第 3 步｜内部接口文档隐藏 + 参数约束

三个 `/product/stock/**` 方法加 `@Hidden`（`io.swagger.v3.oas.annotations.Hidden`），同时给 `@RequestParam` 加约束：

- `orderNo`：`@NotBlank`
- `productId`、`num`：`@NotNull` + `@Min(1)`

- 【为什么不用类级 `@Validated`】Spring 6.1+ 对 Controller 方法参数上的约束有内建校验，失败抛 `HandlerMethodValidationException`，L10-3 已接住；`@Validated` 是 AOP 路线，这里没必要。
- 【为什么 Service 的 `checkArgs` 保留】Controller 校验只覆盖 HTTP 入口；Service 还可能被内部代码调用，保留判空避免 NPE。它和注解不冲突，都是 1000。

### 第 4 步｜验证

直连 8082，写操作带三个身份头：

- 【数据】`[D10]`：

| 请求 | 返回 |
|---|---|
| `POST /product` body=`{}` | `{"code":1000,"msg":"price: 不能为空；name: 不能为空；stock: 不能为空"}` |
| `POST /product` `price=0` | `{"code":1000,"msg":"price: 必须大于 0"}` |
| `POST /product` `{"name":"L10测试商品","price":9.90,"stock":10}` | `{"code":0,"data":889,"msg":"ok"}` |
| `GET /product/1` | `{"code":0,"data":{"id":1,"name":"测试商品","price":9.90,"stock":99}}` |
| `POST /product/stock/deduct?orderNo=L10TEST&productId=1&num=0` | `{"code":1000,"msg":"num: 不能小于 1"}` |
| `POST /product/stock/deduct?productId=1&num=1` | `{"code":1000,"msg":"缺少必填参数: orderNo"}` |

- 【数据】`GET /v3/api-docs/product-service`：`paths` 只有 `/product`、`/product/{id}`；`Product` schema 带 `maxLength=100`、`minimum=0`。
- 【数据】测试商品 id=`889`（名称 `L10测试商品`）保留，商品 1 库存仍为 `99`。

### L10-5 回滚方案

- 撤销：`Product` 去掉 5 个校验注解及 import；`ProductController.save/update` 去掉 `@Valid`；三个 stock 方法去掉 `@Hidden` 与参数约束。
- 影响：无表结构、无 Redis 变更；回滚后坏参数只会被 Service 的 `checkArgs` 兜成不带字段名的 1000，文档里会重新出现 `/product/stock/**`。

## 十、L10-6 下单/分页/游标参数校验

**命令入场**

```text
GET  http://localhost:8083/order/page?userId=1&page=0&size=10
GET  http://localhost:8083/order/page?userId=1&page=1&size=101
GET  http://localhost:8083/order/cursor?userId=1&size=0
POST http://localhost:8083/order/create  {}
POST http://localhost:8083/order/create  {"productId":1,"num":0}
POST http://localhost:8083/order/create  {"productId":1,"num":1}
```

**结局表**

| 结局 | 触发条件 | 现象 |
|---|---|---|
| ① 页码越界 | `page=0` | `code=1000`，`page: 不能小于 1` |
| ② 单页过大 | `size=101` | `code=1000`，`size: 不能大于 100` |
| ③ 游标 size 非法 | `size=0` | `code=1000`，`size: 不能小于 1` |
| ④ 下单缺字段 | body=`{}` | `code=1000`，productId/num 都点出来 |
| ⑤ 数量非法 | `num=0` | `code=1000`，`num: 不能小于 1` |
| ⑥ 正常下单 | `{"productId":1,"num":1}` | `code=0`，返回订单 id，库存真实扣减 |

### 第 1 步｜OrderCreateDTO 加约束

[OrderCreateDTO.java](file:///d:/bishe/workspace/gongju/eclipseworkspace/mini-mall/v1/mall-order/src/main/java/com/mall/order/dto/OrderCreateDTO.java)：

| 字段 | 注解 | message |
|---|---|---|
| `productId` | `@NotNull` + `@Min(1)` | `不能为空` / `不能小于 1` |
| `num` | `@NotNull` + `@Min(1)` | `不能为空` / `不能小于 1` |

### 第 2 步｜Controller 参数注解

[OrderController](file:///d:/bishe/workspace/gongju/eclipseworkspace/mini-mall/v1/mall-order/src/main/java/com/mall/order/controller/OrderController.java)：

- `create`：`@Valid @RequestBody OrderCreateDTO`；
- `page`：`userId @NotNull @Min(1)`，`page @Min(1)`，`size @Min(1) @Max(MAX_PAGE_SIZE)`；
- `cursor`：`userId` 同上，`lastId @Min(1)`（可选参数，不传不校验），`size` 同上；
- `replayCompensate`：`orderNo @NotBlank`；
- `MAX_PAGE_SIZE = 100` 从 Service 搬到 Controller，注解的 `@Max` 直接引用它。

### 第 3 步｜Service 删重复校验

[OrderServiceImpl](file:///d:/bishe/workspace/gongju/eclipseworkspace/mini-mall/v1/mall-order/src/main/java/com/mall/order/service/impl/OrderServiceImpl.java)：

- `create` 删掉 `dto.getProductId() == null || dto.getNum() == null || dto.getNum() <= 0`；
- `page`/`cursor` 删掉 `checkPageArgs(...)` 调用，删除 `checkPageArgs` 方法；
- 删除 `MAX_PAGE_SIZE` 常量；
- 库存不足（`PRODUCT_STOCK_NOT_ENOUGH`）、订单不存在（`ORDER_NOT_FOUND`）等业务异常全部保留。

### 第 4 步｜踩坑：`@Validated` 让字段名变成 `page.page`

第一版给 `OrderController` 加了类级 `@Validated`，走 AOP 方法校验，返回：

```text
{"code":1000,"msg":"page.page: 不能小于 1"}
{"code":1000,"msg":"cursor.size: 不能小于 1"}
```

- 原因：`ConstraintViolation.getPropertyPath()` 会把**方法名**作为第一段，所以变成 `方法名.参数名`。
- 处理：去掉 `@Validated`，改用 Spring 6.1+ 对 Controller 方法参数的内建校验（和 L10-5 商品库存接口一致），失败抛 `HandlerMethodValidationException`，直接取参数名：

```text
{"code":1000,"msg":"page: 不能小于 1"}
{"code":1000,"msg":"size: 不能大于 100"}
```

- 【结论】Spring 6.1+ 项目不需要为了 `@RequestParam` 约束再挂 `@Validated`；只有非 Controller 的 Bean 方法校验才需要它。

### 第 5 步｜验证

直连 8083，带三个身份头：

- 【数据】`[D11]`：

| 请求 | 返回 |
|---|---|
| `GET /order/page?userId=1&page=0&size=10` | `{"code":1000,"msg":"page: 不能小于 1"}` |
| `GET /order/page?userId=1&page=1&size=101` | `{"code":1000,"msg":"size: 不能大于 100"}` |
| `GET /order/page?userId=1&page=1&size=3` | `code=0`，3 条（id=100007/100006/100005） |
| `GET /order/cursor?userId=1&size=0` | `{"code":1000,"msg":"size: 不能小于 1"}` |
| `POST /order/create` body=`{}` | `{"code":1000,"msg":"num: 不能为空；productId: 不能为空"}` |
| `POST /order/create` `{"productId":1,"num":0}` | `{"code":1000,"msg":"num: 不能小于 1"}` |
| `POST /order/create` `{"productId":1,"num":1}` | `{"code":0,"data":100158}`，orderNo=`ORD000000008009` |
| `GET /product/1` | 库存 `99 → 98` |

- 【数据】`GET /v3/api-docs/order-service` 含 `OrderCreateDTO`，`minimum=1`；paths 共 6 条（`/order`、`/order/{id}`、`/order/create`、`/order/page`、`/order/cursor`、`/order/compensate/replay`）。
- 【水位】`id_segment.max_id` 与 Redis `mall:order:seq` 同步到 `8009`；测试订单 `100158` 保留，不清理。

### L10-6 回滚方案

- 撤销：`OrderCreateDTO` 去掉 4 个注解；Controller 去掉 `@Valid` 与所有参数约束、删除 `MAX_PAGE_SIZE`；`OrderServiceImpl` 恢复 `MAX_PAGE_SIZE` 常量、`create` 的三条件 if、`checkPageArgs` 方法及两处调用。
- 影响：无表结构变更；回滚后非法参数仍返回 1000，但 message 不带字段名（回到 L9 行为）。



