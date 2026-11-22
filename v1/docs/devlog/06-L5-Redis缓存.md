# 06 L5 Redis 缓存

> 本章按铁律 11 写：**总览表只做导航，真正的过程在"逐步行进"里**。数据编号与 `LEARN_L5-3_一条请求的全部结局.md` 一致。

## 一、五个小步与涉及位置

| 小步 | 内容 | 关键产物 |
|---|---|---|
| L5-1 | 装 Redis 并验证 | `redis-cli ping` → `PONG` |
| L5-2 | mall-product 接入 Spring Data Redis | pom + `spring.data.redis` 配置 |
| L5-3 | `@Cacheable` 查询缓存 | `RedisCacheConfig` + 缓存命中 |
| L5-4 | 空值缓存防穿透 | `ProductCacheService` + 允许缓存 null |
| L5-5 | `@CacheEvict` 更新/删除删缓存 | `ProductServiceImpl.update/deleteById` |

## 二、启动期：造缓存能力（只发生一次，与请求无关）

### 1. 开启缓存能力

```java
// 文件：mall-product/src/main/java/com/mall/product/ProductServiceApplication.java
// 位置：启动类上
@EnableCaching
```

- 【你写的】声明"这个应用要用 Spring Cache"。
- 【框架动作】启动时给带缓存注解的方法套上代理（`CglibAopProxy`，框架：Spring AOP），调用时先经过 `CacheInterceptor`（框架：Spring Cache）。
- 【数据】此时不涉及请求数据。

### 2. 造缓存管理器

```java
// 文件：mall-product/src/main/java/com/mall/product/config/RedisCacheConfig.java
// 行号：28-42（cacheManager 方法）
@Bean
public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) { ... }
```

- 【你写的】自己定义"缓存怎么存"：TTL 30 分钟、JSON 序列化、允许缓存 null、写 JSON 时带 `@class`。
- 【框架动作】启动时调用这个方法**一次**，把返回值当作容器里的 `cacheManager` Bean；并把 `LettuceConnectionFactory` 塞进方法参数。
- 【数据】产出物：一个 `RedisCacheManager` 对象。请求期不再执行这个方法。

> 断点停在这里 = 启动期造对象。此时 Tomcat 还没监听端口，浏览器会"拒绝连接"。

## 三、请求期：`GET /product/{id}` 的调用链

| 顺序 | 谁 | 在哪 | 干了什么 |
|---|---|---|---|
| 1 | Tomcat 线程 | — | 按路径找到 `ProductController.getById` |
| 2 | `ProductController.getById` | 第 25-26 行 | 接收 `id`，调 `productService.getById(id)` |
| 3 | `ProductServiceImpl.getById` | 第 28-30 行 | 打"查询商品"日志，调 `productCacheService.queryById(id)` |
| 4 | `CglibAopProxy` + `CacheInterceptor` | — | 拼 key、查 Redis、决定放不放行 |
| 5 | `ProductCacheService.queryById` | 第 30-32 行 | **未命中才执行**：打"回源"日志，调 `productMapper.selectById(id)` |
| 6 | `ProductMapper.selectById` | 第 20-21 行 | MyBatis 执行 SQL |
| 7 | 返回路线 | — | Mapper → `queryById` → 代理（命中则从这里直接出）→ `ProductServiceImpl` 第 30 行 → 第 36 行 → `ProductController` 第 26 行 → 框架 |

**数据编号**

| 编号 | 数据 | 初始值 | 谁产生 |
|---|---|---|---|
| `[D1]` | 商品 id | `"2"` → `2L` | 浏览器 URL，框架转 Long |
| `[D2]` | 缓存 key | `"product::2"` | `CacheInterceptor` 拼出 |
| `[D3]` | Redis 取出值 | 商品 JSON / `NullValue` / `null` | Redis |
| `[D4]` | `product` 变量 | `Product` 对象 或 `null` | `CacheInterceptor` 或 MyBatis |
| `[D5]` | 返回体 | `Result{code,msg,data}` | `Result.ok` / `Result.fail` |
| `[D6]` | 响应文本 | JSON 字符串 | 框架序列化 |

**四个结局（总览，过程见后）**

| 结局 | `[D3]` | 进方法体 | `[D4]` | 浏览器 |
|---|---|---|---|---|
| ① 命中真值 | 商品 JSON | 不进 | `Product` | `code=0` |
| ② 命中空值 | `NullValue` | 不进 | `null` | `code=1002` |
| ③ 未命中·库里有 | `null` | 进 | `Product`（回源） | `code=0` |
| ④ 未命中·库里没有 | `null` | 进 | `null`（回源） | `code=1002` |

---

## 四、结局①逐步行进：命中真值

**入场**：`GET http://localhost:8082/product/2`，Redis 里已有 `product::2`。

### 第 1 步｜请求到达，框架找到 Controller

```java
// 文件：mall-product/src/main/java/com/mall/product/controller/ProductController.java
// 行号：24-26
@GetMapping("/{id}")                                       // 24
public Result<Product> getById(@PathVariable Long id) {     // 25
    return Result.ok(productService.getById(id));           // 26
}
```

- 【你写的】第 24 行声明路径；第 25 行 `@PathVariable Long id` 表示从 URL 路径取值。
- 【注解】`@GetMapping` 让框架能按路径找到方法；`@PathVariable` 指定值来自路径。
- 【框架动作】Tomcat 派线程；按路径匹配到方法；把 URL 里的 `2` 转成 `Long` 塞进参数。
- 【数据】`[D1]` `"2"` → `2L` · 来源：URL · 去向：`getById` 的参数 `id`。

### 第 2 步｜算括号里的

- 【你写的】第 26 行从里往外执行：先 `productService.getById(2)`。
- 【数据】`[D1] = 2L` · 去向：作为参数传进 `ProductServiceImpl.getById`；`[D5]` 尚未产生。

### 第 3 步｜进入 `ProductServiceImpl`

```java
// 文件：mall-product/src/main/java/com/mall/product/service/impl/ProductServiceImpl.java
// 行号：27-30
@Override
public Product getById(Long id) {                          // 28
    log.info("查询商品, id={}", id);                         // 29
    Product product = productCacheService.queryById(id);    // 30
```

- 【你写的】第 29 行打印"查询商品, id=2"；第 30 行调用 `queryById` 并把返回值装进 `product`。
- 【数据】`[D1] = 2L` · 从 Controller 传入 · `[D4]` 此时还不存在。

> 这行日志 **4 个结局都会打印**（它在你自己的方法里，本类无缓存注解）。区分走哪条路看的是 `ProductCacheService` 第 31 行"回源"日志。

### 第 4 步｜跨类调用，进入代理

- 【你写的】调用 `productCacheService.queryById(id)`。
- 【框架动作】`productCacheService` 字段里装的是 `CglibAopProxy`（框架：Spring AOP）生成的代理，不是 `ProductCacheService` 对象本身；调用先进代理，再由 `CacheInterceptor` 处理。
- 【数据】`[D1] = 2L` · 进入代理 · 代理尚未决定是否转给 `ProductCacheService` 对象本身。

### 第 5 步｜`CacheInterceptor` 算 key、查 Redis

```java
// 文件：mall-product/src/main/java/com/mall/product/service/impl/ProductCacheService.java
// 行号：29-30
@Cacheable(cacheNames = "product", key = "#id")             // 29
public Product queryById(Long id) {                          // 30
```

- 【注解】两个时刻：**启动时**框架把 `cacheNames="product"`、`key="#id"` 记在 `queryById` 名下（只记规则）；**请求时**由 `CacheInterceptor` 按规则执行，`#id` 此刻才求值。
- 【你写的】`"product"` 是字符串字面量（不是变量），就是缓存区名；`"#id"` 是 SpEL 表达式，`#id` 取方法参数 `id` 的值。
- 【框架动作】规则是 Spring Cache 约定的 `key = 区名 + "::" + 求值结果`；`::` 只是分隔符。代入本请求：`"product" + "::" + 2` = `"product::2"`，然后对 Redis 执行 `GET product::2`。
- 【数据】`[D1] = 2L` → 参与拼成 `[D2] = "product::2"` → Redis 返回 `[D3] = "{\"@class\":\"com.mall.product.entity.Product\",\"id\":2,...}"`。

### 第 6 步｜JSON 还原成对象

- 【框架动作】`[D3]` 非 null，`CacheInterceptor` 反序列化；能还原成 `Product` 是因为 JSON 里有 `"@class"` 字段（由 `RedisCacheConfig` 的序列化配置决定）。
- 【数据】`[D3]` JSON 字符串 → `[D4] = Product{id=2, name="测试商品2", price=19.90, stock=50}`。`[D4]` 是新数据，来自 `[D3]`。

### 第 7 步｜直接返回，方法体零执行

- 【框架动作】调用方是 `ProductServiceImpl.getById` 第 30 行；`CacheInterceptor` 把 `[D4]` 当作 `queryById(2)` 的返回值交回那条赋值语句。`ProductCacheService` 第 31、32 行**一行都没执行**。
- 【数据】`[D4] Product` · 由 `CacheInterceptor` 给出 · 落到 `ProductServiceImpl.getById` 第 30 行的 `product` 变量。

### 第 8 步｜判空

```java
// 文件：mall-product/.../service/impl/ProductServiceImpl.java
// 行号：32-36
if (product == null) {                                     // 32
    log.warn("商品不存在, id={}", id);                       // 33
    throw new BizException(ErrorCode.PRODUCT_NOT_FOUND);    // 34
}
return product;                                            // 36
```

- 【你写的】`[D4]` 非 null → 条件 false → 跳过 33、34 → 第 36 行返回。
- 【数据】`[D4] Product` · 变成 `ProductServiceImpl.getById` 的返回值（第 36 行）· 交给 `ProductController.getById` 第 26 行，作为 `productService.getById(id)` 的结果。

### 第 9 步｜包装 `Result`

```java
// 文件：mall-common/src/main/java/com/mall/common/result/Result.java
// 行号：25-31
public static <T> Result<T> ok(T data) { ... }
```

- 【你写的】`ProductController` 第 26 行调用 `Result.ok([D4])`，得到 `code=0`、`msg="ok"`、`data=[D4]`。
- 【数据】`[D4] Product` → `[D5] = Result{code=0, msg="ok", data=Product{...}}` · 成为 Controller 方法返回值。

### 第 10 步｜序列化

- 【框架动作】Spring MVC（框架）把 `[D5]` 转成 JSON 写进响应体。
- 【数据】`[D5] Result` → `[D6] = {"code":0,"data":{"id":2,...},"msg":"ok"}` · 去向：浏览器。

---

## 五、结局②逐步行进：命中空值

**入场**：`GET /product/999`，Redis 里 `product::999` 是 `NullValue`。

第 1~4 步同结局①（`[D1] = 999L` 走到 `ProductServiceImpl` 第 30 行，进入代理）。

### 第 5 步｜`CacheInterceptor` 查 Redis

- 【框架动作】key 拼法同结局①第 5 步，`[D1] = 999L` → `[D2] = "product::999"` → 执行 `GET product::999`。
- 【数据】`[D3]` = `NullValue` 标记（Redis 里存的是序列化后的 `org.springframework.cache.support.NullValue` 对象，不是字符串 `"null"`）。

### 第 6 步｜`NullValue` 还原成 `null`

- 【框架动作】`CacheInterceptor` 识别空值标记（由 `RedisCacheConfig` 第 42 行 `enableSpringCacheNullValueSupport()` 决定），还原成 `null`。
- 【数据】`[D3] NullValue` → `[D4] = null`。

### 第 7 步｜返回 `null`，方法体零执行

- 【框架动作】调用方是 `ProductServiceImpl.getById` 第 30 行；`CacheInterceptor` 把 `null` 当作 `queryById(999)` 的返回值交回。`ProductCacheService` 第 31、32 行没执行。
- 【数据】`[D4] = null` · 落到 `ProductServiceImpl.getById` 第 30 行的 `product` 变量。

### 第 8 步｜判空成立，造异常

- 【你写的】第 32 行条件 true → 第 33 行 warn → 第 34 行 `throw new BizException(ErrorCode.PRODUCT_NOT_FOUND)`。
- 【数据】`[D4] = null` → 使用 `ErrorCode`（`mall-common/.../exception/ErrorCode.java`，`code=1002`、`msg="商品不存在"`）。

### 第 9 步｜枚举被拆进异常对象

```java
// 文件：mall-common/src/main/java/com/mall/common/exception/BizException.java
// 行号：18-21
public BizException(ErrorCode errorCode) {
    super(errorCode.getMsg());
    this.code = errorCode.getCode();
}
```

- 【你写的】把枚举的 `msg`、`code` 分别存成异常的 message 和 code。
- 【数据】`ErrorCode(1002,"商品不存在")` → `BizException{code=1002, message="商品不存在"}`。

### 第 10 步｜异常开始飞

- 【你写的】第 34 行 `throw` 之后，第 36 行 `return` **不执行**；Controller 第 26 行 `Result.ok(...)` **也不执行**。
- 【数据】`BizException` 沿调用链往回飞：`ProductServiceImpl.getById` → `ProductController.getById` → 框架。

### 第 11 步｜`GlobalExceptionHandler` 接住

```java
// 文件：mall-common/src/main/java/com/mall/common/exception/GlobalExceptionHandler.java
// 行号：25-29
@ExceptionHandler(BizException.class)
public Result<Void> handleBizException(BizException e) { ... }
```

- 【注解】`@ExceptionHandler(BizException.class)` 声明"这类异常归我接"。
- 【框架动作】框架把飞行中的异常交给这个方法作为参数 `e`。
- 【数据】`BizException{code=1002,...}` → `[D5] = Result{code=1002, msg="商品不存在", data=null}`。

### 第 12 步｜序列化

- 【数据】`[D5]` → `[D6] = {"code":1002,"data":null,"msg":"商品不存在"}` · 去向：浏览器。

---

## 六、结局③逐步行进：未命中·库里有

**入场**：`GET /product/2`，刚 `del product::2`；MySQL 里有 id=2。

第 1~4 步同结局①。

### 第 5 步｜Redis 没有这个 key

- 【框架动作】`GET product::2` → 没有。
- 【数据】`[D1] = 2L` → `[D2] = "product::2"` → `[D3] = null`（Redis 明确返回"没有"）。

### 第 6 步｜`CacheInterceptor` 放行

- 【框架动作】`[D3]` 为 null → 把调用转给 `ProductCacheService` 对象本身 → 第 31 行执行。
- 【数据】`[D1] = 2L` · 进入方法参数 `id`。

### 第 7 步｜打"回源"日志

```java
// 文件：mall-product/.../service/impl/ProductCacheService.java
// 行号：31-32
log.info("回源 MySQL 查询商品, id={}", id);                  // 31
return productMapper.selectById(id);                        // 32
```

- 【你写的】第 31 行打印"回源 MySQL 查询商品, id=2"。
- 【数据】`[D1] = 2L`。

> 出现这行日志 = 未命中的证明；命中时永远看不到它。

### 第 8 步｜进 Mapper 执行 SQL

```java
// 文件：mall-product/src/main/java/com/mall/product/mapper/ProductMapper.java
// 行号：20-21
@Select("select id, name, price, stock, create_time from product where id = #{id}")
Product selectById(Long id);
```

- 【你写的】第 32 行调用 `productMapper.selectById(2)`。
- 【注解】`@Select` 把 SQL 绑在方法上；`#{id}` 是预编译占位符，不是字符串拼接。
- 【框架动作】MyBatis（框架）给接口造代理，把 `#{id}` 换成 `2`，连 MySQL 执行。
- 【数据】`[D1] = 2L` · 作为参数传进 `selectById(2)` → SQL 的 `?` 填 `2`。

### 第 9 步｜结果集映射成对象

- 【框架动作】MyBatis 按 `map-underscore-to-camel-case: true`（`application.yml` 第 38 行）把 `create_time` 映射成 `createTime`，造出 `Product`。
- 【数据】MySQL 一行 → `[D4] = Product{id=2, name="测试商品2", price=19.90, stock=50}`（新数据，与 `[D1]` 只是同一个 id 的关系）· 作为 `selectById(2)` 的返回值回到 `queryById` 第 32 行。

### 第 10 步｜返回时顺手写缓存

- 【你写的】第 32 行 `return`，把 `[D4]` 作为 `queryById(2)` 的返回值交给代理。
- 【框架动作】`CacheInterceptor` 拿到非 null 返回值，执行 `SETEX product::2 <JSON> 1800`（30 分钟，来自 `RedisCacheConfig` 第 31 行 `entryTtl`），再把 `[D4]` 交回 `ProductServiceImpl.getById` 第 30 行。
- 【数据】`[D4] Product` → 序列化成 `[D3']` 写入 Redis `product::2`，过期 30 分钟；`[D4]` 本身落回第 30 行的 `product` 变量。

> 你的代码里没有一行 Redis 代码，这一步完全是 `CacheInterceptor` 做的。

### 第 11 步｜判空 → 成功返回

- 【你写的】第 32 行条件 false → 第 36 行 `return product` → 回到 `ProductController` 第 26 行，作为 `Result.ok` 的参数。
- 【数据】`[D4]` → `[D5] = Result{code=0,...}` → `[D6] = {"code":0,"data":{...},"msg":"ok"}`。

---

## 七、结局④逐步行进：未命中·库里没有

**入场**：`GET /product/999`，刚 `del product::999`；MySQL 也没有 999。

第 1~9 步同结局③（走到 `ProductMapper.selectById(999)`）。

### 第 9 步的结果｜SQL 返回 0 行

- 【框架动作】SQL 执行完 `Total: 0`，MyBatis 没有对象可造。
- 【数据】MySQL 空结果 → `[D4] = null`（来自"查不到"，不是 Redis 的 null）。

### 第 10 步｜这里返回 null，不抛异常

- 【你写的】`ProductCacheService` 第 32 行把 `null` 作为 `queryById(999)` 的返回值交给代理。
- 【数据】`[D4] = null` · 去向：进入代理，由 `CacheInterceptor` 拿到。

> **为什么不能在这里抛异常**：`CacheInterceptor` 只缓存"正常返回值"，**异常永远不会写进 Redis**。一查不到就 `throw`，等于每次都打库——这就是缓存穿透，L5-4 改的就是这里。

### 第 11 步｜null 被写成 `NullValue`

- 【框架动作】`CacheInterceptor` 拿到 `null`，因为 `RedisCacheConfig` 第 42 行开了 `enableSpringCacheNullValueSupport()`，它写一个 `NullValue` 标记，而不是序列化 `null`。写完再把 `null` 交回 `ProductServiceImpl.getById` 第 30 行。
- 【数据】`[D4] = null` → `[D3'] = NullValue` → `SETEX product::999`（30 分钟）；`null` 落回第 30 行的 `product` 变量。

### 第 12 步｜判空成立，抛异常

- 【你写的】第 32 行条件 true → 第 33 行 warn → 第 34 行 `throw new BizException(ErrorCode.PRODUCT_NOT_FOUND)`。
- 【数据】`[D4] = null` → `BizException{code=1002, message="商品不存在"}` · 沿调用链往回飞。

### 第 13 步｜异常被接住

- 【框架动作】`GlobalExceptionHandler` 第 25-28 行接住 → `Result.fail(1002,"商品不存在")`。
- 【数据】`BizException` → `[D5] = Result{code=1002,...}` → `[D6] = {"code":1002,...}`。

### 第 14 步｜下一次同样请求走结局②

- 【框架动作】Redis 已有 `NullValue`，下次 `CacheInterceptor` 直接命中，不进方法体、不打库。
- 【数据】`NullValue` → `[D4] = null` → 判空 → 1002。

---

## 八、L5-5：改价后缓存怎么失效

**入场**：`PUT /product`，body `{"id":2,"name":"测试商品2","price":99.90,"stock":50}`

| 步 | 谁 | 数据 |
|---|---|---|
| 1 | 框架 | JSON body → `Product{id=2,...price=99.90}` |
| 2 | `ProductServiceImpl.update` | 校验 id 非空；`productMapper.update(product)` 执行 `Updates: 1` |
| 3 | `ProductServiceImpl.update` | 返回 `true` |
| 4 | `CacheInterceptor`（框架） | 方法成功返回后，执行 `DEL product::2` |
| 5 | 下次 `GET /product/2` | `[D3] = null` → 回源 → 拿到 `99.90` → 写回缓存 |

- 【注解】`@CacheEvict(cacheNames="product", key="#product.id")`：`#product` 指的是方法参数名 `product`，所以 key 是这个对象的 id。
- 【坑】key 必须和 `@Cacheable` 拼法完全一致，写错不报错、静默失效。
- 【坑】`@CacheEvict` 默认"方法成功返回后才删"。删除一个不存在的 id 会抛 1002，此时缓存不删——库本来没变，这是一致的。

## 九、实测验收

| 场景 | 证据 |
|---|---|
| 第一次查（miss） | 有"回源"、有 SQL；Redis 出现 `product::id`，TTL 1799 |
| 第二次查（hit） | 无"回源"、无 SQL；TTL 只衰减不重置 |
| 查不存在（第一次） | SQL `Total: 0`；Redis 写入 `NullValue` |
| 查不存在（第二次） | 无"回源"、无 SQL；返回 1002 |
| 改价后 | `product::2` 消失；再查回源，拿到新价 |
| 删除后 | `product::id` 消失 |

## 十、踩坑

1. `RedisCacheManager` 在 Spring Data Redis 4.x 里包路径是 `org.springframework.data.redis.cache`，老教程的 `org.springframework.cache.redis` 编译报错。
2. `GenericJacksonJsonRedisSerializer` 没有无参构造，必须走 builder。
3. 只放开 null 缓存不够，序列化器必须 `enableSpringCacheNullValueSupport()`。
4. 缓存里的 `NullValue` 是框架的空值标记，不是乱码。
5. 旧 jar 被运行中服务锁定时 repackage 失败，先停服务再打包。
