# 06 L5 Redis 缓存

> 详细逐行版见仓库根目录 `LEARN_L5-3_一条请求的全部结局.md`；本章是日志版索引 + 结论。

## 一、五个小步

| 小步 | 内容 | 关键产物 |
|---|---|---|
| L5-1 | 装 Redis 并验证 | `redis-cli ping` → `PONG` |
| L5-2 | mall-product 接入 Spring Data Redis | pom + `spring.data.redis` 配置 |
| L5-3 | `@Cacheable` 查询缓存 | `RedisCacheConfig` + 缓存命中 |
| L5-4 | 空值缓存防穿透 | `ProductCacheService` + 允许缓存 null |
| L5-5 | `@CacheEvict` 更新/删除删缓存 | `ProductServiceImpl.update/deleteById` |

## 二、启动期 vs 请求期（用户曾混淆）

| 阶段 | 发生什么 | 涉及位置 |
|---|---|---|
| 启动期（只一次） | 开启缓存能力；造缓存管理器（TTL 30 分钟、JSON 序列化、允许 null） | `ProductServiceApplication` 的 `@EnableCaching`、`RedisCacheConfig.cacheManager`、`application.yml` 的 redis 配置 |
| 请求期（每次） | 查缓存 → 决定进不进方法体 → 必要时查库 → 写回缓存 | `ProductController` → `ProductServiceImpl` → `ProductCacheService` → `ProductMapper` |

> 断点停在 `RedisCacheConfig` = 启动期在造对象，和当前请求无关。

## 三、`GET /product/{id}` 的四个结局

**命令入场**：`GET http://localhost:8082/product/2`（或 999）

### 调用链

| 顺序 | 谁 | 干了什么 |
|---|---|---|
| 1 | `ProductController.getById` | 接 `id`，调 `productService.getById` |
| 2 | `ProductServiceImpl.getById` | 打印"查询商品"，调 `productCacheService.queryById` |
| 3 | `CacheInterceptor`（框架：Spring Cache） | 拼 key、查 Redis，决定放不放行 |
| 4 | `ProductCacheService.queryById` | **未命中才执行**：打印"回源"，调 Mapper |
| 5 | `ProductMapper.selectById` | MyBatis 执行 SQL |
| 6 | 返回路线 | Mapper → `queryById` → 代理（命中则从这里直接出）→ `ProductServiceImpl` → Controller |

### 数据编号

| 编号 | 数据 | 形状 | 谁产生 | 去向 |
|---|---|---|---|---|
| `[D1]` | 商品 id | `"2"` → `2L` | 浏览器 URL，框架转 Long | Controller 参数 |
| `[D2]` | 缓存 key | `"product::2"` | `CacheInterceptor` 按 `cacheNames + "::" + id` 拼 | Redis |
| `[D3]` | Redis 取出的值 | 商品 JSON 字符串 / `NullValue` 标记 / `null` | Redis | `CacheInterceptor` 判断 |
| `[D4]` | `product` 变量 | `Product` 对象 或 `null` | `CacheInterceptor`（命中）或 MyBatis（回源） | `ProductServiceImpl` 第 30 行 |
| `[D5]` | 返回体 | `Result{code,msg,data}` | `Result.ok` / `Result.fail` | Controller 返回值 |
| `[D6]` | 响应 | JSON 字符串 | 框架序列化 | 浏览器 |

### 结局表

| 结局 | `[D3]` | 进方法体 | `[D4]` | 浏览器 |
|---|---|---|---|---|
| ① 命中真值 | 商品 JSON | 不进 | `Product` 对象 | `{"code":0,"data":{...}}` |
| ② 命中空值 | `NullValue` | 不进 | `null` | `{"code":1002,"msg":"商品不存在"}` |
| ③ 未命中·库里有 | `null` | 进 | `Product` 对象（回源） | `{"code":0,"data":{...}}` |
| ④ 未命中·库里没有 | `null` | 进 | `null`（回源） | `{"code":1002,"msg":"商品不存在"}` |

## 四、L5-4 的结构决策（重要）

**为什么把回源方法拆到 `ProductCacheService`**：

1. 空值缓存要求"查不到返回 `null`"，不能抛异常——**异常永远不会被写进 Redis**，抛异常等于每次都打库（缓存穿透）。
2. `@Cacheable` 只在被**别的类**调用时生效；同类 `this.` 调用会绕过代理。

两个约束叠加 → 回源单独成类，`ProductServiceImpl` 注入调用；判空抛 1002 留在 `ProductServiceImpl`。

## 五、L5-5 更新/删除删缓存

| 方法 | 注解 | 效果 |
|---|---|---|
| `ProductServiceImpl.update` | `@CacheEvict(cacheNames="product", key="#product.id")` | 更新成功后删 `product::id` |
| `ProductServiceImpl.deleteById` | `@CacheEvict(cacheNames="product", key="#id")` | 删除成功后删 `product::id` |

- 默认在**方法成功返回后**才删；方法抛异常（如更新 0 行报"商品不存在"）则不删——库没改，缓存保持原样是一致的。
- `key` 必须和 `@Cacheable` 完全一致，写错不报错、静默失效。

## 六、实测验收

| 场景 | 证据 |
|---|---|
| 第一次查（miss） | 日志有"回源"、有 SQL；Redis 出现 `product::id`，TTL 1799 |
| 第二次查（hit） | 无"回源"、无 SQL；TTL 只衰减不重置 |
| 查不存在（第一次） | SQL `Total: 0`；Redis 写入 `NullValue` |
| 查不存在（第二次） | 无"回源"、无 SQL；返回 1002 |
| 改价后 | `product::2` 消失；再查回源，拿到新价 |
| 删除后 | `product::id` 消失 |

## 七、坑

1. `RedisCacheManager` 在 Spring Data Redis 4.x 里包路径是 `org.springframework.data.redis.cache`，老教程的 `org.springframework.cache.redis` 编译报错。
2. `GenericJacksonJsonRedisSerializer` 没有无参构造，必须走 builder。
3. 只放开 null 缓存还不够，序列化器必须 `enableSpringCacheNullValueSupport()`。
4. 缓存里的 `NullValue` 是框架的空值标记，不是乱码。
5. 旧 jar 被运行中服务锁定时 repackage 失败，先停服务再打包。

## 八、下一步

L6 JWT 登录鉴权。
