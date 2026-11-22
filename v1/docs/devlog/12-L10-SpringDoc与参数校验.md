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
| L10-2 | Swagger 指向网关 + Bearer 鉴权，调通下单/分页 | ⬜ |
| L10-3 | `GlobalExceptionHandler` 接管 JSR303 / 绑定 / 类型转换异常 | ⬜ |
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
