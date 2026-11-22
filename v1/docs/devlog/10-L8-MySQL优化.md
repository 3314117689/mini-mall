# 10 L8 MySQL 优化专题

> 按铁律 11 写：总览表只做导航，过程在"逐步行进"里。
> 本章的一条轴：**一条按用户查订单的分页 SQL，从"全表扫 + filesort"改到"索引定位 + 无排序"，再改到游标分页，最后按月分区。**
> 环境事实：MySQL **5.7.32**（无 `EXPLAIN ANALYZE`、无窗口函数/CTE），所以"实际读了多少行"用 `SHOW SESSION STATUS LIKE 'Handler_read%'`，耗时用 `SET profiling=1` + `SHOW PROFILES`。

## 一、这一层解决什么

L0~L7 的订单查询只有一种：`select ... from orders where id = ?`，主键点查，数据量小的时候看不出问题。数据量上来后会出现三类症状：

1. 按用户查"我的订单"并按时间倒序，数据库要全表扫 + 额外排序（filesort）；
2. 翻到很深的页（`limit 79990,10`）时，越翻越慢；
3. 历史订单越积越多，一次时间范围查询要扫全表。

L8 不加新框架，只改 **SQL、索引、表结构**，把这三类症状逐个量化。

## 二、技术点清单

| # | 技术点 | 落点 | 一句话作用 | 面试频度 |
|---|---|---|---|---|
| 1 | B+ 树、聚簇索引 vs 二级索引、回表 | 概念底座 | 解释"为什么快、为什么慢" | ★★★ |
| 2 | 联合索引 + 最左前缀 + 列顺序（等值在前、排序在后） | `orders` 索引 | 消除 filesort | ★★★ |
| 3 | filesort 的成因与消除 | `ORDER BY create_time` | 本层核心验收点 | ★★★ |
| 4 | `EXPLAIN` 关键列 `type / key / rows / filtered / Extra` | 每条对照 SQL | 面试看执行计划就靠它 | ★★★ |
| 5 | 覆盖索引 + 延迟关联 | 深翻页优化 | 大偏移的标准解法 | ★★★ |
| 6 | 深翻页 `LIMIT offset,size` 的代价 + keyset 游标分页及局限 | 深翻页优化 | 高频追问"游标分页缺点" | ★★★ |
| 7 | 5.7 下用 `Handler_read_*` 量化真实扫描行数 | 每条对照 SQL | 不被 `rows` 估算值骗 | ★★ |
| 8 | `RANGE COLUMNS(create_time)` 按月分区、分区裁剪、唯一键必须含分区列 | `orders` 重建 | 分区三连问 | ★★★ |
| 9 | 分区表的代价：按 `id` 查会打开所有分区、分区 DDL 成本 | 与 #8 配套 | 面试官必反问 | ★★ |
| 10 | `INSERT ... SELECT` 交叉连接批量造数 | 造数 SQL | 工具技巧 | ★ |

## 三、小步与状态

| 步 | 内容 | 状态 |
|---|---|---|
| L8-0 | 前置体检：编译、中间件、9 行基线 | ✅ |
| L8-1 | 造 10 万跨月数据 + `/order/page`、`/order/cursor` | ✅ |
| L8-2 | 联合索引 `(user_id, create_time)`，EXPLAIN 前后对比 | ✅ |
| L8-3 | 深翻页三版对比（limit / 延迟关联 / 游标） | ✅ |
| L8-4 | 按月 RANGE 分区（`orders_p` + rename） | ✅ |
| L8-5 | 总验收、收尾、提交 | ✅ |

## 四、L8-0 基线体检

### 第 1 步｜编译与端口

- 【你写的】无，只执行命令。
- 【框架动作】`mvn -q -DskipTests install` 编译全部模块；服务未启动时 8081/8083/9000 不监听。
- 【数据】`[C1] 编译通过`；`[C2] Nacos 8848 / Redis 6379 / MySQL 3306 在监听`。

### 第 2 步｜记录优化前状态

```sql
-- 执行库：db_order
show index from orders;
select count(*), min(create_time), max(create_time) from orders;
```

- 【数据】`[D0]`：

| 项 | 值 |
|---|---|
| 索引 | 只有 `PRIMARY(id)`，没有任何二级索引 |
| 行数 | 9（造数前） |
| 时间跨度 | 2026-11-12 ~ 2026-11-22 |

### 第 3 步｜记录优化前执行计划

```sql
explain select id, user_id, product_id, num, amount, create_time
from orders
where user_id = 1
order by create_time desc, id desc
limit 0, 10;
```

- 【数据】`[D0-1]` 优化前 EXPLAIN：

| 列 | 值 | 含义 |
|---|---|---|
| `type` | `ALL` | 全表扫描 |
| `key` | `NULL` | 没有用任何索引 |
| `rows` | `9` | 估算要扫 9 行（当时只有 9 行） |
| `filtered` | `11.11` | 估算过滤后剩 11.11% |
| `Extra` | `Using where; Using filesort` | 先过滤，再额外排序 |

## 五、L8-1 造数 + 分页接口

**命令入场**

```text
A. GET http://localhost:9000/api/order/page?userId=1&page=1&size=10
   Authorization: Bearer <zhangsan 的 token>

B. GET http://localhost:9000/api/order/page?userId=1&page=8000&size=10
   Authorization: Bearer <zhangsan 的 token>

C. GET http://localhost:9000/api/order/cursor?userId=1&size=10
   Authorization: Bearer <zhangsan 的 token>

D. GET http://localhost:9000/api/order/cursor?userId=1&size=10&lastCreateTime=2026-11-30T01:33:00&lastId=99996
   Authorization: Bearer <zhangsan 的 token>
```

**涉及文件**

| 名字 | 归属 | 作用 |
|---|---|---|
| `v1/docs/sql/L8/01-backup-orders.sql` | 本项目 | 造数前备份 9 行到 `orders_bak_l8` |
| `v1/docs/sql/L8/02-seed-orders.sql` | 本项目 | 交叉连接生成 0~99999，插入 10 万条跨 24 个月的订单 |
| `OrderMapper.selectPage` | 本项目，`mall-order/.../mapper/OrderMapper.java` 第 36-42 行 | offset 分页 SQL |
| `OrderMapper.selectCursor` | 本项目，同文件第 44-55 行 | 游标分页 SQL |
| `OrderServiceImpl.page` | 本项目，`mall-order/.../service/impl/OrderServiceImpl.java` 第 104-111 行 | 算 offset、校验参数 |
| `OrderServiceImpl.cursor` | 本项目，同文件第 113-123 行 | 第一页转 `page`，之后走游标 |
| `OrderController.page` | 本项目，`mall-order/.../controller/OrderController.java` 第 63-72 行 | 接 `userId/page/size` |
| `OrderController.cursor` | 本项目，同文件第 74-85 行 | 接 `lastCreateTime/lastId` |

**调用链（命令 B）**

| 顺序 | 谁 | 在哪 | 干了什么 |
|---|---|---|---|
| 1 | Netty | 9000 | 收到 `GET /api/order/page`，读 `Authorization` |
| 2 | `AuthGlobalFilter` | 网关，L7-2 | 验签，删伪造头，写 `X-Mall-User-Id=1` 等三个头 |
| 3 | 路由过滤器 | 网关 | `lb://order-service` 换实例，`StripPrefix=1` 去掉 `/api` |
| 4 | `GatewayAuthInterceptor.preHandle` | order，L7-3 | 读三个头重建 `LoginUser`，`UserContext.set` |
| 5 | `OrderController.page` | 第 67-72 行 | 接收 `userId=1`、`page=8000`、`size=10` |
| 6 | `OrderServiceImpl.page` | 第 105-111 行 | 校验参数，算 `offset=(8000-1)*10=79990` |
| 7 | `OrderMapper.selectPage` | 第 40-42 行 | 执行带 `limit 79990,10` 的 SQL |
| 8 | MySQL | — | 无索引：扫全表 + filesort，取第 79991~80000 条 |
| 9 | `OrderController.page` | 第 71 行 | `Result.ok(list)` 包成统一返回体 |

**数据编号**

| 编号 | 数据 | 初始值 | 谁产生 |
|---|---|---|---|
| `[D1]` | 用户 id | `1` | 请求参数 |
| `[D2]` | 页码/页大小 | `8000 / 10` | 请求参数 |
| `[D3]` | offset | `79990` | `OrderServiceImpl.page` 第 108 行 |
| `[D4]` | 实际 SQL | `... where user_id=1 order by create_time desc, id desc limit 79990,10` | `OrderMapper.selectPage` |
| `[D5]` | 查询结果 | 10 行，`id` 从 `26` 到 `15` | MySQL |
| `[D6]` | 响应体 | `{"code":0,"data":[...10 条...],"msg":"ok"}` | `Result.ok` |
| `[D7]` | 网关耗时 | 约 `0.10 ~ 0.13s` | 实测 |

**四个结局（命令 A/B）**

| 结局 | 分岔点 | 条件 | 浏览器 |
|---|---|---|---|
| ① 浅页正常 | `selectPage` 返回 | `offset` 在总行数内 | `code=0`，10 条 |
| ② 深页正常但慢 | 同上 | `offset=79990` | `code=0`，10 条，耗时更高 |
| ③ 页码超出范围 | 同上 | `offset` ≥ 该用户总行数 | `code=0`，`data=[]` |
| ④ 参数非法 | `checkPageArgs` 第 126-130 行 | `page<1` 或 `size>100` 或 `userId=null` | `code=1000` |

### 结局①逐步行进：浅页正常

#### 第 1 步｜网关验签并注入身份

- 【框架动作】同 [09-L7-Gateway网关.md](09-L7-Gateway网关.md) 第十一节结局①第 1~4 步：验签通过后写 `X-Mall-User-Id=1`、`X-Mall-Username=zhangsan`、`X-Mall-Role=ADMIN`。
- 【数据】`[D1]` 在网关阶段还没有，它来自请求参数；网关只负责证明"调用者是谁"。

#### 第 2 步｜Controller 接参

```java
// 文件：mall-order/src/main/java/com/mall/order/controller/OrderController.java
// 行号：67-72
@GetMapping("/page")
public Result<List<Order>> page(@RequestParam Long userId,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int size) {
    return Result.ok(orderService.page(userId, page, size));
}
```

- 【你写的】三个 `@RequestParam`：`userId` 必填，`page/size` 有默认值。
- 【数据】`[D1]=1`、`[D2]=1/10`。
- 注意：`userId` 是查询参数，不是从 token 取的；当前与 `GET /order/{id}` 一样，**没有校验"只能看自己的订单"**，属已知缺口。

#### 第 3 步｜Service 算 offset

```java
// 文件：mall-order/src/main/java/com/mall/order/service/impl/OrderServiceImpl.java
// 行号：104-111
@Override
public List<Order> page(Long userId, int page, int size) {
    checkPageArgs(userId, page, size);
    // offset 分页的代价就在这里：page 越深，数据库要跳过的行越多
    int offset = (page - 1) * size;
    log.info("分页查询订单, userId={}, page={}, size={}, offset={}", userId, page, size, offset);
    return orderMapper.selectPage(userId, offset, size);
}
```

- 【你写的】`page` 从 1 开始，`offset=(page-1)*size`。
- 【数据】`[D3] = (1-1)*10 = 0`。

#### 第 4 步｜Mapper 执行 SQL

```java
// 文件：mall-order/src/main/java/com/mall/order/mapper/OrderMapper.java
// 行号：36-42
@Select("select id, user_id, product_id, num, amount, create_time from orders "
        + "where user_id = #{userId} order by create_time desc, id desc limit #{offset}, #{size}")
List<Order> selectPage(@Param("userId") Long userId, @Param("offset") int offset, @Param("size") int size);
```

- 【你写的】`@Param` 给每个参数起名，SQL 里用 `#{userId}` 对应。
- 【框架动作】MyBatis 把 `#{offset}` 变成预编译占位符，执行 `limit ?,?`。
- 【数据】`[D4]`，`limit 0,10`。

#### 第 5 步｜MySQL 执行并返回

- 【框架动作】无索引，只能扫全表；`order by create_time desc, id desc` 需要额外排序（filesort），最后取前 10 条。
- 【数据】`[D5] = 10 行`（首屏 `id` 从 `100007` 开始）。
- 注意：`create_time` 是 `datetime`（秒级），造数时每 630 秒一条，同一秒内也可能有多条；加 `id desc` 是为了让排序键唯一，分页不会重复/漏行。

### 结局②逐步行进：深页正常但慢

#### 第 1 步｜offset 变大

- 【数据】`[D2] = 8000/10` → `[D3] = 79990`。

#### 第 2 步｜数据库仍要扫全表

- 【框架动作】没有索引时，`limit 79990,10` 并不是"直接跳到第 79991 条"：MySQL 要先扫出满足 `user_id=1` 的所有行、全部排序，再丢弃前 79990 条。
- 【数据】`[D5]` 变成 `id 26 ~ 15`；`Handler_read_rnd_next = 100010`（全表 100009 行 + 1 次收尾读）。
- 这也解释了为什么"浅页"和"深页"都慢：优化前两者代价几乎一样，都是一次全表扫。

### 结局③逐步行进：页码超出范围

- 【数据】`offset` ≥ 该用户总行数（user 1 有 80005 行）时，SQL 返回 0 行。
- 【你写的】Service 不抛异常，`Result.ok(空 list)`。
- 【数据】`{"code":0,"data":[],"msg":"ok"}`；这是"查询成功但没数据"，不是错误。

### 结局④逐步行进：参数非法

- 【你写的】`checkPageArgs` 第 126-130 行：`userId == null || page < 1 || size < 1 || size > 100` → 抛 `BizException(PARAM_ERROR)`。
- 【框架动作】`GlobalExceptionHandler` 接住，返回 `Result.fail(1000)`。
- 【数据】`{"code":1000,"msg":"参数错误","data":null}`；Mapper 不执行。

### 游标分页（命令 C/D）逐步行进

#### 第 1 步｜第一页没有游标

```java
// 文件：mall-order/src/main/java/com/mall/order/service/impl/OrderServiceImpl.java
// 行号：113-123
@Override
public List<Order> cursor(Long userId, LocalDateTime lastCreateTime, Long lastId, int size) {
    if (lastCreateTime == null || lastId == null) {
        // 第一页没有游标，等价于 offset=0
        return page(userId, 1, size);
    }
    checkPageArgs(userId, 1, size);
    log.info("游标查询订单, userId={}, lastCreateTime={}, lastId={}, size={}",
            userId, lastCreateTime, lastId, size);
    return orderMapper.selectCursor(userId, lastCreateTime, lastId, size);
}
```

- 【你写的】两个游标参数任一为空就退回 `page(userId,1,size)`，复用同一套校验。
- 【数据】命令 C → `offset=0`，返回 `id 100007 ~ 99996`，最后一条 `createTime=2026-11-30T01:33:00`、`id=99996`。

#### 第 2 步｜带上上一页最后一条继续

```java
// 文件：mall-order/src/main/java/com/mall/order/mapper/OrderMapper.java
// 行号：44-55
@Select("select id, user_id, product_id, num, amount, create_time from orders "
        + "where user_id = #{userId} "
        + "and (create_time < #{lastCreateTime} or (create_time = #{lastCreateTime} and id < #{lastId})) "
        + "order by create_time desc, id desc limit #{size}")
List<Order> selectCursor(@Param("userId") Long userId,
        @Param("lastCreateTime") LocalDateTime lastCreateTime,
        @Param("lastId") Long lastId,
        @Param("size") int size);
```

- 【你写的】`create_time` 更早的都要；`create_time` 相同则用 `id` 更小的，保证同一秒内的行也能稳定续页。
- 【数据】命令 D → 返回 `id 99995 ~ 99984`，与第一页**无重叠**（实测 overlap=0）。

#### 第 3 步｜Controller 的日期参数

```java
// 文件：mall-order/src/main/java/com/mall/order/controller/OrderController.java
// 行号：79-85
@GetMapping("/cursor")
public Result<List<Order>> cursor(@RequestParam Long userId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime lastCreateTime,
        @RequestParam(required = false) Long lastId,
        @RequestParam(defaultValue = "10") int size) {
    return Result.ok(orderService.cursor(userId, lastCreateTime, lastId, size));
}
```

- 【注解】`@DateTimeFormat(iso = DATE_TIME)` 把 `2026-11-30T01:33:00` 解析成 `LocalDateTime`。
- 【数据】`lastCreateTime` 类型是 `LocalDateTime`，不是字符串。

### L8-1 实测

```text
造数：orders 共 100009 行；min=2024-12-01 00:00:00，max=2026-11-30 03:49:30；user_id=1 有 80005 行

page=1    size=10 → code=0，10 条，firstId=100007
page=8000 size=10 → code=0，10 条，firstId=26，lastId=15

网关耗时（curl.exe，3 次，间隔 3 秒）：
page=1    0.0649s / 0.0808s / 0.1000s
page=8000 0.1124s / 0.1288s / 0.1001s

cursor 第一页：100007 ~ 99996，lastTime=2026-11-30T01:33:00
cursor 第二页：99995 ~ 99984，overlap=0
```

### L8-1 坑

1. **造数必须跨月且分布偏斜**：都挤在同一个月，分区裁剪看不出效果；`user_id` 均匀分布时单个用户只有几百行，深翻页没意义。脚本让 user 1 占 80%。
2. **排序键必须唯一**：`create_time` 是 `datetime`（秒级），同秒多行时只按 `create_time` 排序，翻页可能重复或漏行；统一加 `id desc`。
3. **`limit #{offset}, #{size}` 在 MyBatis 里是合法写法**：预编译后是 `limit ?,?`，不要用 `${}` 拼接。
4. **游标接口不能跳页**：只能"下一页"，这是 keyset 分页的固有限制，L8-3 会量化对比。
5. **订单路由有限流**：网关 order 路由是 2/秒、桶 5，连续测必须间隔 ≥3 秒，否则会拿到 `4290`，耗时数据失真。
6. **`userId` 来自查询参数**：当前没有"只能看自己订单"的校验，与 `GET /order/{id}` 的现状一致，属已知安全缺口。

### L8-1 断点验证与自测题

| 停在哪 | 看什么 | 看到什么证明走对了 |
|---|---|---|
| `OrderController.page` 第 68 行 | `userId/page/size` | `1 / 8000 / 10` |
| `OrderServiceImpl.page` 第 108 行 | `offset` | `79990` |
| `OrderMapper.selectPage` 执行时控制台 SQL | `limit` 后面的值 | `limit 79990,10` |
| `OrderServiceImpl.cursor` 第 115 行 | `lastCreateTime/lastId` | 第一页为 `null/null`，第二页有值 |
| MySQL 客户端 | `Handler_read_rnd_next` | 优化前约 `100010` |

### 自测题（不附答案）

1. `page=8000&size=10` 时 `offset` 是多少？为什么没有索引时它和 `page=1` 一样慢？
2. 为什么 `order by` 要写成 `create_time desc, id desc`，只写 `create_time desc` 会出什么问题？
3. 游标分页第一页为什么可以直接复用 `selectPage`？第二页的 SQL 为什么能保证不重复？

## 六、L8-2 联合索引消除 filesort

**命令入场**

```sql
-- 执行库：db_order
-- 建索引
alter table orders add index idx_user_create(user_id, create_time);

-- 同一条 SQL，只改 offset
A. select id, user_id, product_id, num, amount, create_time from orders
   where user_id = 1 order by create_time desc, id desc limit 0, 10;

B. select id, user_id, product_id, num, amount, create_time from orders
   where user_id = 1 order by create_time desc, id desc limit 79990, 10;
```

**本步改动**

| 文件 | 归属 | 作用 |
|---|---|---|
| `v1/docs/sql/L8/03-add-index.sql` | 本项目 | 建索引语句 + 回滚语句 |
| `orders.idx_user_create` | 数据库对象 | `(user_id, create_time)`，等值列在前、排序列在后 |

> Java 代码本步**零改动**。变的只有数据库里多了一棵二级索引树。

**调用链（命令 B）**

- 第 1~4 步同 L8-1 结局②第 1~4 步：网关验签 → `OrderController.page` → `OrderServiceImpl.page` 算出 `offset=79990` → `OrderMapper.selectPage` 发出同一条 SQL。
- 第 5 步分岔在这里：上次 MySQL 走全表扫 + filesort，这次走 `idx_user_create` 反向扫描。

**数据编号**

| 编号 | 数据 | 初始值 | 谁产生 |
|---|---|---|---|
| `[D1]` | 用户 id | `1` | 请求参数 |
| `[D2]` | offset | `0`（命令 A）/ `79990`（命令 B） | `OrderServiceImpl.page` 第 108 行 |
| `[D3]` | 索引定义 | `(user_id, create_time)` | DDL |
| `[D4]` | 执行计划 | `type=ref`、`key=idx_user_create`、`key_len=8`、`rows=49982`、`Extra=Using where` | MySQL 优化器 |
| `[D5]` | 索引扫描量 | `Handler_read_prev=9`（A）/ `79999`（B） | MySQL |
| `[D6]` | SQL 耗时 | A 约 `0.0004s`；B 约 `0.57~0.71s` | `SHOW PROFILES` |

**四个结局（总览）**

| 结局 | 分岔点 | 条件 | 结果 |
|---|---|---|---|
| ① 浅页命中索引 | MySQL 选索引 | `offset` 小 | 0.0004s 级，filesort 消失 |
| ② 深页命中索引 | 同上 | `offset=79990` | 反而比全表扫慢 5~7 倍 |
| ③ 列顺序写反 | 索引定义 | 建成 `(create_time, user_id)` | 无法先按用户定位，排序也用不上（反例推演） |
| ④ 查询缺最左列 | 条件 | 只写 `where create_time > ?`，不带 `user_id` | 用不上该索引（最左前缀，反例推演） |

### 结局①逐步行进：浅页命中索引

#### 第 1 步｜建索引

- 【你写的】`alter table orders add index idx_user_create(user_id, create_time);`
- 【框架动作】InnoDB 为 100009 行数据建一棵二级索引树，叶子节点存 `(user_id, create_time, id)`——**二级索引尾部隐含主键**。
- 【数据】`[D3]`；`show index` 显示两列：`user_id`（cardinality 196）、`create_time`（cardinality 99964）。

#### 第 2 步｜优化器改走索引

```text
id  select_type  table   partitions  type  possible_keys     key              key_len  ref    rows   filtered  Extra
1   SIMPLE       orders  NULL        ref   idx_user_create   idx_user_create  8        const  49982  100.00    Using where
```

- 【框架动作】MySQL 优化器比较"全表扫 + 排序"和"索引范围扫"的代价，选择后者。
- 【数据】`[D4]`。三个关键变化：

| 列 | 优化前 | 优化后 | 说明 |
|---|---|---|---|
| `type` | `ALL` | `ref` | 从全表扫变成按等值条件定位 |
| `key` | `NULL` | `idx_user_create` | 真正用了哪棵索引 |
| `Extra` | `Using where; Using filesort` | `Using where` | **filesort 消失** |

- 注意 `rows=49982` 是**估算值**，实际 user 1 有 80005 行；优化器按"总行数 ÷ user_id 去重数"估算，不能当真实扫描行数。

#### 第 3 步｜为什么 filesort 消失

- 【框架动作】索引按 `(user_id, create_time)` 排序存储；`user_id=1` 的所有记录在索引里是连续的一段，段内 `create_time` 天然有序。MySQL 反向扫描这段索引，读出来就是 `create_time desc` 的顺序，不需要再排一次。
- 【数据】命令 A 的 `Handler_read_prev=9`、`Handler_read_next=0`：从索引尾部往前读了 9 次就凑够 10 行。
- 补充：`create_time` 相同时还需要 `id desc`，而二级索引叶子尾部正好带主键，所以 `create_time desc, id desc` 仍与索引顺序一致。

#### 第 4 步｜回表取剩余列

- 【框架动作】`select` 要的 `product_id/num/amount` 不在索引里，每返回一行要按主键回表一次。
- 【数据】命令 A 回表 10 次；因为只取 10 行，代价可以忽略。

#### 第 5 步｜真实耗时

- 【数据】`[D6]`：命令 A 三次 `0.0076s / 0.00038s / 0.00040s`；优化前同一查询是 `0.0748s / 0.0642s / 0.0604s`。
- 第一次偏慢是索引页还没进缓冲池，第二、三次是稳定值。

### 结局②逐步行进：深页命中索引反而更慢

#### 第 1 步｜offset 仍是 79990

- 【数据】`[D2]=79990`，`[D4]` 与浅页相同，优化器仍选 `idx_user_create`。

#### 第 2 步｜索引要走过 79999 条才够 10 条

- 【框架动作】反向扫描索引，跳过 79990 条，再取 10 条；每取一条还要回表。
- 【数据】`[D5] = Handler_read_prev=79999`（对比浅页的 9）。
- 【数据】耗时 `0.57~0.71s`，而无索引全表扫 + filesort 只要 `0.09~0.14s`。

#### 第 3 步｜为什么反而慢了

- 【数据】全表扫是**顺序读** 10 万行，顺序 IO 便宜；索引深翻页是**顺序读 8 万条索引项 + 8 万次随机回表**，随机 IO 贵。
- 结论：**索引解决的是"过滤 + 排序"，不解决"大偏移"**。大偏移要靠 L8-3 的延迟关联和游标分页。
- 这条也说明 `EXPLAIN` 里"用上索引"不等于"更快"，要结合 `offset` 和实际耗时判断。

### 结局③逐步行进：列顺序写反（反例推演，不实际执行）

- 【数据】如果把索引建成 `(create_time, user_id)`：树的第一层按时间有序，`where user_id=1` 无法定位连续区间，只能在整棵树里过滤。
- 【框架动作】优化器大概率放弃该索引，回到全表扫；即使强行用，也拿不到 `user_id` 的等值定位，`filesort` 仍然存在。
- 规则：**等值条件列放前面，范围/排序列放后面**。

### 结局④逐步行进：查询缺最左列（反例推演）

- 【数据】`select ... where create_time > '2026-01-01' order by create_time`：条件里没有 `user_id`，不满足最左前缀，`idx_user_create` 用不上。
- 【框架动作】优化器可能走全表扫，或另建 `(create_time)` 索引；这与 L8-4 的分区裁剪是两条不同的优化线。

### L8-2 实测

| 场景 | 优化前 EXPLAIN | 优化后 EXPLAIN | 优化前实际读行 | 优化后实际读行 | 优化前耗时 | 优化后耗时 |
|---|---|---|---|---|---|---|
| `limit 0,10` | `ALL / key=NULL / Using filesort` | `ref / idx_user_create / Using where` | `Handler_read_rnd_next=100010` | `Handler_read_prev=9` | 0.0604~0.0748s | 0.0076s → 0.00038~0.00040s |
| `limit 79990,10` | 同上 | 同上 | `Handler_read_rnd_next=100010` | `Handler_read_prev=79999` | 0.0903~0.1419s | 0.5712~0.7111s |

网关 9000 端到端（curl，间隔 3 秒）：

```text
优化前：page=1  0.0649 / 0.0808 / 0.1000s   page=8000  0.1124 / 0.1288 / 0.1001s
优化后：page=1  0.0389 / 0.0316 / 0.0226s   page=8000  0.5479 / 0.6294 / 0.5311s
```

### L8-2 坑

1. **"用上索引"不等于"更快"**：深翻页时索引扫描 + 随机回表比全表顺序扫还慢，必须结合 offset 看。
2. **联合索引列顺序不能反**：`(user_id, create_time)` 能同时完成过滤和排序；`(create_time, user_id)` 两件事都做不好。
3. **最左前缀**：查询条件里必须有 `user_id`，`idx_user_create` 才可能被用上。
4. **二级索引尾部隐含主键**：所以 `order by create_time desc, id desc` 仍能利用索引顺序，不必再建 `(user_id, create_time, id)`。
5. **`EXPLAIN.rows` 是估算值**：本例估算 49982，实际 80005；判断真实扫描量要看 `Handler_read_*`。
6. **MySQL 5.7 没有 invisible index**：想验证"优化前"必须真的 drop 索引（本步已实测 drop → 测 → 重新 add），8.0 才支持 `INVISIBLE`。
7. **索引有写放大成本**：每次 insert/update 都要维护二级索引；索引不是越多越好，按真实查询建。

### L8-2 断点验证与自测题

| 停在哪 | 看什么 | 看到什么证明走对了 |
|---|---|---|
| MySQL：`show index from orders` | `Key_name` | 出现 `idx_user_create`，两列顺序 `user_id`、`create_time` |
| MySQL：`explain` 命令 A | `type / key / Extra` | `ref` / `idx_user_create` / 不含 `Using filesort` |
| MySQL：`show session status like 'Handler_read%'` | `Handler_read_prev` | 浅页 9、深页 79999；`Handler_read_rnd_next=0` |
| Java：`OrderServiceImpl.page` 第 108 行 | `offset` | 仍是 `79990`，说明 Java 层没变，变化全在数据库 |

### 自测题（不附答案）

1. 加了索引后为什么浅页快了、深页反而慢了？分别是什么 IO 模式？
2. 为什么索引建 `(user_id, create_time)` 就能消掉 `filesort`，而不是必须把 `order by` 的列单独建索引？
3. `EXPLAIN` 显示 `rows=49982`，但用户 1 实际有 80005 行，怎么解释这个差异？

## 七、L8-3 深翻页三版对比

**命令入场**

```sql
-- 统一取 offset=39990（第 4000 页，每页 10 条）
A. select id, user_id, product_id, num, amount, create_time
   from orders where user_id = 1
   order by create_time desc, id desc limit 39990, 10;

B. select o.id, o.user_id, o.product_id, o.num, o.amount, o.create_time
   from orders o join (
       select id from orders where user_id = 1
       order by create_time desc, id desc limit 39990, 10
   ) t on o.id = t.id
   order by o.create_time desc, o.id desc;

C. select id, user_id, product_id, num, amount, create_time
   from orders where user_id = 1
     and (create_time < '2025-11-30 14:52:30'
          or (create_time = '2025-11-30 14:52:30' and id < 50015))
   order by create_time desc, id desc limit 10;
```

对应的 HTTP 命令：

```text
A. GET http://localhost:9000/api/order/page?userId=1&page=4000&size=10
C. GET http://localhost:9000/api/order/cursor?userId=1&size=10&lastCreateTime=2025-11-30T14:52:30&lastId=50015
```

**本步改动**

| 文件 | 归属 | 作用 |
|---|---|---|
| `v1/docs/sql/L8/04-deep-page.sql` | 本项目 | 三版 SQL 与游标边界的取法 |
| `OrderMapper.selectCursor` | 本项目，L8-1 已建 | C 版落点，本步只做对比不改代码 |

> 本步 Java 零改动。A 走 `/order/page`，C 走 `/order/cursor`，B 只在 MySQL 客户端验证。

**调用链（C 版）**

| 顺序 | 谁 | 在哪 | 干了什么 |
|---|---|---|---|
| 1 | `OrderController.cursor` | 第 79-85 行 | 接收 `userId/lastCreateTime/lastId/size` |
| 2 | `OrderServiceImpl.cursor` | 第 113-123 行 | 两个游标参数都不为空 → 走 `selectCursor` |
| 3 | `OrderMapper.selectCursor` | 第 48-55 行 | `where user_id=? and (create_time<? or (create_time=? and id<?))` |
| 4 | MySQL | — | 从 `(2025-11-30 14:52:30, 50015)` 位置直接反向读 10 条 |

**数据编号**

| 编号 | 数据 | 初始值 | 谁产生 |
|---|---|---|---|
| `[D1]` | 用户 id | `1` | 请求参数 |
| `[D2]` | A/B 的 offset | `39990` | `OrderServiceImpl.page` 第 108 行 |
| `[D3]` | C 的游标边界 | `create_time=2025-11-30 14:52:30, id=50015` | 上一页最后一条 |
| `[D4]` | 三版实际 SQL | A/B/C 三条 | Mapper / MySQL 客户端 |
| `[D5]` | 索引扫描量 | A `prev=39999`；B `prev=39999 + rnd=10`；C `prev=9` | `Handler_read_*` |
| `[D6]` | SQL 耗时 | A `0.065~0.079s`；B `0.021~0.027s`；C `0.0003~0.0005s` | `SHOW PROFILES` |

**四个结局（总览）**

| 结局 | 方案 | 条件 | 结果 |
|---|---|---|---|
| ① 能翻但慢 | A：offset | 任意页 | 0.065~0.079s，页越深越慢 |
| ② 快约 3 倍 | B：延迟关联 | 子查询能走覆盖索引 | 0.021~0.027s |
| ③ 快约 200 倍 | C：游标 | 客户端带上一页边界 | 0.0003~0.0005s，与页深无关 |
| ④ 三版一起退化 | 无索引 | 索引被删/条件缺最左列 | B 的子查询也变成全表扫，收益消失 |

### 结局①逐步行进：A 版 offset 分页

#### 第 1 步｜执行计划

```text
type=ref  key=idx_user_create  key_len=8  ref=const  rows=49982  Extra=Using where
```

- 【框架动作】同 L8-2：索引反向扫描，先跳过 39990 条，再取 10 条。
- 【数据】`[D5] = Handler_read_prev=39999`，`Handler_read_rnd_next=0`。

#### 第 2 步｜耗时

- 【数据】`[D6] = 0.0792s / 0.0647s / 0.0735s`。
- 成本与 `offset` 成正比：offset 79990 时实测 `0.57~0.71s`。

### 结局②逐步行进：B 版延迟关联

#### 第 1 步｜执行计划

```text
id=1 PRIMARY   table=<derived2>  type=ALL       key=NULL   rows=49982  Extra=Using temporary; Using filesort
id=1 PRIMARY   table=o           type=eq_ref    key=PRIMARY rows=1     Extra=NULL
id=2 DERIVED   table=orders      type=ref       key=idx_user_create    Extra=Using where; Using index
```

- 【你写的】子查询只 `select id`，列全在 `idx_user_create` 里，所以 `Extra=Using index`（覆盖索引，不回表）。
- 【框架动作】先物化 10 个 id 到临时表，再用主键 `eq_ref` 回表 10 次。
- 【数据】`[D5] = prev=39999`（子查询仍要跳过 4 万条索引项）+ `Handler_read_rnd=10`（回表 10 次）。
- 注意：外层 `Using temporary; Using filesort` 排的是**临时表里的 10 行**，不是 L8-2 那种 8 万行 filesort，不能混为一谈。

#### 第 2 步｜耗时

- 【数据】`[D6] = 0.0271s / 0.0209s / 0.0206s`，约 A 的 1/3。

#### 第 3 步｜为什么只快 3 倍而不是几十倍

- 【数据】B 的子查询仍然 `prev=39999`：跳过 4 万条索引项这件事没有消失，只是把"取整行"限制在最后 10 条。
- 结论：延迟关联优化的是**回表成本**，不优化**偏移量本身**；offset 越大它也会越慢，只是斜率更小。

### 结局③逐步行进：C 版游标分页

#### 第 1 步｜执行计划

```text
type=range  key=idx_user_create  key_len=21  rows=6  Extra=Using index condition
```

- 【数据】`key_len=21` = `user_id(8) + create_time(5) + id(8)`，说明三列都参与了范围定位。
- 【框架动作】从 `(2025-11-30 14:52:30, 50015)` 这个位置直接反向读，不经过前面 4 万条。

#### 第 2 步｜读行数

- 【数据】`[D5] = Handler_read_key=2`、`Handler_read_prev=9`：读约 10 条就够，和页深无关。
- 对比 A 的 `prev=39999`，这是数量级差异的来源。

#### 第 3 步｜耗时

- 【数据】`[D6] = 0.00049s / 0.00040s / 0.00028s`。

#### 第 4 步｜代价

- 只能"下一页"，不能跳页：没有上一页边界的绝对位置。
- 必须带唯一稳定的排序键：本项目用 `(create_time, id)`，只传时间会在同秒重复时出错。
- 翻页期间有新数据插入/删除，可能出现漏行或重复（不是快照读）。

### L8-3 实测对比（offset=39990）

| 版本 | EXPLAIN 关键列 | `Handler_read_prev` | `Handler_read_rnd` | SQL 耗时 |
|---|---|---|---|---|
| A offset | `ref / idx_user_create / Using where` | 39999 | 0 | 0.0647~0.0792s |
| B 延迟关联 | 子查询 `ref / idx_user_create / Using index` | 39999 | 10 | 0.0206~0.0271s |
| C 游标 | `range / idx_user_create / Using index condition` | 9 | 0 | 0.00028~0.00049s |

网关 9000 端到端（curl，间隔 3 秒）：

```text
A /api/order/page?page=4000   0.0858 / 0.0650 / 0.0826s
C /api/order/cursor          0.0319 / 0.0270 / 0.0176s
```

> 端到端差距只有 3 倍左右，因为网关验签、HTTP、JSON 序列化的固定开销约 20ms，把 SQL 层的 200 倍差距稀释了。面试时要说清"SQL 层"和"接口层"两个口径。

### L8-3 坑

1. **外层 `Using filesort` 不等于没优化**：B 版排的是 10 行临时表，要跟 L8-2 的 8 万行 filesort 区分开。
2. **延迟关联不消除 offset**：它只减少回表，子查询仍要扫过 offset 条索引项。
3. **游标分页不能跳页**：产品要求"跳到第 N 页"时不能用它，只能 offset 或额外维护页码映射。
4. **游标必须传复合键**：只传 `create_time` 会在同秒多行时重复/漏行；本项目传 `(create_time, id)`。
5. **翻页期间数据变化会漏/重**：游标不是快照，新增数据可能出现在已翻过的位置。
6. **A 版接口仍暴露深页**：`/order/page` 没限制最大页码，前端传 `page=999999` 也只是返回空列表，但数据库仍要扫到尾；生产可加"最大可翻页数"或直接下线 offset 分页。
7. **网关 order 路由限流 2/秒**：三次测量之间必须间隔 ≥3 秒，否则拿到 `4290`。

### L8-3 断点验证与自测题

| 停在哪 | 看什么 | 看到什么证明走对了 |
|---|---|---|
| `OrderServiceImpl.page` 第 108 行 | `offset` | `39990` |
| `OrderServiceImpl.cursor` 第 115 行 | `lastCreateTime/lastId` | 都非空，走 `selectCursor` |
| `OrderMapper.selectCursor` 执行时控制台 SQL | `where` 条件 | 含 `create_time < ? or (create_time = ? and id < ?)` |
| MySQL：`explain` C 版 | `type / key_len` | `range` / `21` |
| MySQL：`Handler_read_prev` | 三版对比 | 39999 / 39999 / 9 |

### 自测题（不附答案）

1. 延迟关联为什么只快了 3 倍，而游标分页能快 200 倍？两者分别消除了哪部分成本？
2. B 版 EXPLAIN 里出现 `Using filesort`，为什么说它仍然比 A 好？
3. 游标分页在什么业务场景下不能用？如果产品要求"跳页"怎么办？

## 八、L8-4 按月 RANGE 分区

**命令入场**

```sql
-- A. 按时间范围统计某个月
select count(*) from orders
where create_time >= '2026-01-01' and create_time < '2026-02-01';

-- B. 按主键查一条
select id, user_id, product_id, num, amount, create_time
from orders where id = 50015;

-- C. 分页接口用的查询（不带分区列范围）
select id, user_id, product_id, num, amount, create_time
from orders where user_id = 1
order by create_time desc, id desc limit 0, 10;
```

**本步改动**

| 文件 | 归属 | 作用 |
|---|---|---|
| `v1/docs/sql/L8/05-create-partitioned.sql` | 本项目 | 建 `orders_p`（按月分区）+ 导数据 + 校验 |
| `v1/docs/sql/L8/06-rename-orders.sql` | 本项目 | 原子 rename：`orders` → `orders_bak_l8_part`，`orders_p` → `orders` |
| `v1/docs/sql/L8/07-maintain-partition.sql` | 本项目 | `REORGANIZE pmax` 新增下月分区 |
| `orders` 表定义 | 数据库对象 | `PARTITION BY RANGE COLUMNS(create_time)`，主键 `(id, create_time)` |

> Java 代码本步仍**零改动**。rename 之后 Mapper 里的表名 `orders` 不变，MyBatis 无感知。

**调用链**

- SQL 层之前：`OrderMapper` 发出的仍是 `from orders ...`，与 L8-1/2/3 完全一样。
- SQL 层新增：MySQL 解析出 `create_time` 条件后，先做**分区裁剪**（决定打开哪几个分区），再在每个分区内走 `idx_user_create`。

**数据编号**

| 编号 | 数据 | 初始值 | 谁产生 |
|---|---|---|---|
| `[D1]` | 分区键 | `create_time` | DDL |
| `[D2]` | 分区表 | `orders_p`（26 个分区：p202412~p202612 + pmax） | DDL |
| `[D3]` | 主键 | `(id, create_time)` | DDL，分区表强制要求 |
| `[D4]` | 时间范围 | `2026-01-01 ~ 2026-02-01` | 查询条件 |
| `[D5]` | 命中分区 | `p202601` | 分区裁剪 |
| `[D6]` | 实际读行 | `Handler_read_next=4251` | MySQL |
| `[D7]` | 切换动作 | 两条 rename 一条语句原子执行 | `06-rename-orders.sql` |

**四个结局（总览）**

| 结局 | 分岔点 | 条件 | 结果 |
|---|---|---|---|
| ① 命中单分区 | 分区裁剪 | 条件里直接给 `create_time` 范围 | 只打开 `p202601`，读 4251 行 |
| ② 打开全部分区 | 分区裁剪 | 只给 `id`，不给 `create_time` | 26 个分区全部打开，每个分区各查一次主键 |
| ③ 落到 pmax | 分区定义 | `create_time` 超出已建月份 | 写入/查询 pmax，裁剪收益消失 |
| ④ 裁剪失效 | 条件写法 | 对分区列套函数，如 `date(create_time)=...` | 无法裁剪，退化成扫全部分区（规则推演） |

### 结局①逐步行进：时间范围命中单分区

#### 第 1 步｜建分区表

```sql
create table orders_p (
    ...
    primary key (id, create_time),
    key idx_user_create (user_id, create_time)
) partition by range columns(create_time) (
    partition p202412 values less than ('2025-01-01'),
    ...
    partition p202612 values less than ('2027-01-01'),
    partition pmax values less than (maxvalue)
);
```

- 【你写的】26 个分区，每个分区管一个月；`pmax` 兜底。
- 【框架动作】MySQL 为每个分区单独建一棵 `idx_user_create`（**局部索引**，没有跨分区的全局索引）。
- 【数据】`[D2]`、`[D3]`。
- 为什么主键要加 `create_time`：分区表的规则是**每个唯一键都必须包含分区列**，否则建表直接报错。

#### 第 2 步｜导数据并校验

```sql
insert into orders_p (id, user_id, product_id, num, amount, create_time)
select id, user_id, product_id, num, amount, create_time from orders;
```

- 【数据】`count(*)=100009`，与原表一致；各月分区行数 3840~4252，`p202612`、`pmax` 为 0。

#### 第 3 步｜分区裁剪

```text
explain partitions select count(*) from orders
where create_time >= '2026-01-01' and create_time < '2026-02-01';

partitions = p202601
type = index  key = idx_user_create  rows = 4251  Extra = Using where; Using index
```

- 【框架动作】优化器只把 `p202601` 加入执行计划，其余 25 个分区不打开。
- 【数据】`[D5]=p202601`，`[D6]=Handler_read_next=4251`。
- 对比分区前：同一条 SQL 在 `orders_bak_l8_part` 上读 `Handler_read_next=100009`。

#### 第 4 步｜耗时对比

- 【数据】分区前 `0.0285~0.0533s`，分区后 `0.0021~0.0087s`，约 10 倍。
- 原因：读的行数从 10 万降到 4251，且这些行在同一个分区里连续存放。

### 结局②逐步行进：按 id 查询打开所有分区

```text
explain partitions select ... from orders where id = 50015;

partitions = p202412,p202501,...,p202612,pmax   （26 个全在）
type = ref  key = PRIMARY  key_len = 8
```

- 【框架动作】`id` 不是分区列，MySQL 不知道这条记录在哪个月，只能把 26 个分区的主键索引都查一遍。
- 【数据】结果仍然正确（`type=ref`、只返回 1 行），但代价是 26 次索引查找。
- 结论：**分区裁剪只认分区列**。按 `id` 点查在分区表上不会更快，反而多打开 25 个分区。

### 结局③逐步行进：落到 pmax

- 【数据】如果写入 `create_time='2030-01-01'`，超出 `p202612` 的范围，会落进 `pmax`。
- 【框架动作】`pmax values less than (maxvalue)` 是兜底，保证不会因为"没有对应分区"而插入失败。
- 代价：`pmax` 里的数据时间跨度不受控，按时间范围查询时如果命中 `pmax`，裁剪就失去意义。所以生产要**提前建下个月分区**。

### 结局④逐步行进：裁剪失效（规则推演）

- 【数据】`where date(create_time) = '2026-01-01'`：对分区列套了函数，MySQL 5.7 无法在优化阶段确定分区范围。
- 【框架动作】退化成打开所有分区逐行判断。
- 规则：条件直接写在分区列上，用范围表达式，不要套函数。

### 第 5 步｜切换到正式表

```sql
rename table orders to orders_bak_l8_part,
             orders_p to orders;
```

- 【框架动作】两条 rename 在同一条语句里原子完成，业务不会看到"表不存在"的中间状态。
- 【数据】`[D7]`；切换后 `count(*)=100009`，`page`、`getById`、`cursor` 三个接口实测都返回 `code=0`。
- 回滚：`rename table orders to orders_p_bad, orders_bak_l8_part to orders;`

### 第 6 步｜分区维护（新增下个月）

```sql
alter table orders reorganize partition pmax into (
    partition p202701 values less than ('2027-02-01'),
    partition pmax values less than (maxvalue)
);
```

- 【框架动作】因为 `pmax` 已经是最后一个分区，不能直接 `ADD PARTITION` 到它后面；必须把 `pmax` 拆成"新月分区 + 新 pmax"。
- 【数据】实测拆完后 `p202701` 排在 `pmax` 之前；再 `drop partition p202701` 可删除空分区（有数据时会连数据一起删）。

### 第 7 步｜对现有分页查询的影响

```text
explain select ... from orders where user_id=1 order by create_time desc, id desc limit 0,10;

partitions = 26 个全在
type = ref  key = idx_user_create  Extra = Using where（没有 filesort）
```

- 【框架动作】每个分区各有一棵按 `(user_id, create_time)` 有序的索引，MySQL 对多个分区做有序归并，所以仍然不需要 filesort。
- 【数据】浅页 `0.0005~0.0094s`；深页 `limit 79990,10` 实测 `0.31~0.32s`（分区前 `0.57~0.71s`）。
- 注意：这是"每个分区内有序 + 归并"的结果，不代表分区表自动优化了排序；一旦查询跨分区且排序键不在索引里，仍会 filesort。

### L8-4 实测

| 查询 | 分区前 | 分区后 |
|---|---|---|
| `create_time` 单月范围 | 26 个分区不适用；`Handler_read_next=100009`，0.0285~0.0533s | `partitions=p202601`；`Handler_read_next=4251`，0.0021~0.0087s |
| `where id=50015` | 主键一次定位 | 26 个分区各查一次主键，结果仍正确 |
| 分页浅页 | 0.0004s 级 | 0.0005~0.0094s，无 filesort |
| 分页深页 `offset=79990` | 0.57~0.71s | 0.31~0.32s |
| 接口回归 | — | `page/getById/cursor` 全部 `code=0` |

### L8-4 坑

1. **唯一键必须包含分区列**：主键从 `(id)` 变成 `(id, create_time)`；这是分区表的硬性规则，不是可选优化。
2. **按 id 查会打开所有分区**：分区裁剪只认 `create_time`，主键点查在分区表上反而多打开 25 个分区。
3. **分区列不要套函数**：`date(create_time)=...` 会让裁剪失效。
4. **`pmax` 只是兜底**：所有超范围数据都堆进 `pmax`，它的数据量会越来越大；要按月提前 `REORGANIZE` 建好下个月。
5. **不能直接 `ADD PARTITION`**：`pmax` 是最后一个分区时，只能 `REORGANIZE pmax`。
6. **`DROP PARTITION` 是删数据**，不是归档；要保留历史数据必须先导出或交换分区。
7. **分区不是索引**：它解决的是"按时间范围只打开部分数据"和"按分区快速清理历史"，不替代联合索引；本例两者是叠加使用的。
8. **分区表不支持外键**：本项目没有外键，所以不受影响；有外键的表要先去掉外键。
9. **别用 `ALTER TABLE ... PARTITION BY` 原地改造大表**：会整表重建、耗时长且难回滚；本项目用"建新表 + 导数据 + rename"，失败可一条 rename 退回。
10. **rename 后连接池不用重启**：表名没变，MyBatis 无感知；但正在执行的长事务可能短暂阻塞 rename，生产要在低峰做。

### L8-4 断点验证与自测题

| 停在哪 | 看什么 | 看到什么证明走对了 |
|---|---|---|
| MySQL：`show create table orders` | 末尾 | 出现 `PARTITION BY RANGE COLUMNS(create_time)` |
| MySQL：`explain partitions` 时间范围 | `partitions` 列 | 只有 `p202601` |
| MySQL：`explain partitions` 按 id | `partitions` 列 | 26 个分区全在 |
| MySQL：`information_schema.partitions` | `partition_name` | `p202412`~`p202612` + `pmax` |
| 网关接口 | `page/getById/cursor` | rename 后仍返回 `code=0` |

### 自测题（不附答案）

1. 为什么分区表的主键必须带上 `create_time`？不加会怎样？
2. 按 `id` 查一条订单，分区表比普通表快还是慢？为什么？
3. 已有 `pmax` 的情况下，怎么新增下个月分区？为什么不能直接 `ADD PARTITION`？

## 九、L8 总验收

### 验收命令与结果

```text
1. GET  /api/order/page?userId=1&page=1&size=3
   → code=0，3 条，first=100007

2. GET  /api/order/page?userId=1&page=8000&size=10
   → code=0，10 条，first=26

3. GET  /api/order/cursor?userId=1&size=3
   → code=0，3 条，last=100005

4. GET  /api/order/cursor?...&lastCreateTime=<第3步最后一条时间>&lastId=100005&size=3
   → code=0，3 条，first=100004（与上一页无重叠）

5. GET  /api/order/100007
   → code=0，id=100007（分区表主键查询仍正常）

6. GET  /api/order/page?userId=1&page=0&size=10
   → code=1000，参数错误
```

### 验收表

| # | 验收项 | 证据 | 结果 |
|---|---|---|---|
| 1 | 造数 | `orders` 100009 行，2024-12 ~ 2026-11，user 1 占 80005 | ✅ |
| 2 | 优化前基线 | `type=ALL`、`key=NULL`、`Extra=Using filesort`、`Handler_read_rnd_next=100010` | ✅ |
| 3 | 联合索引消除 filesort | `type=ref`、`key=idx_user_create`、`Extra` 无 filesort | ✅ |
| 4 | 浅页提速 | SQL `0.0604~0.0748s` → `0.00038~0.00040s` | ✅ |
| 5 | 深页反直觉 | `offset=79990` 时索引版 `0.57~0.71s`，全表版 `0.09~0.14s` | ✅ 已记录 |
| 6 | 延迟关联 | 同 offset 下 `0.021~0.027s`，约 3 倍 | ✅ |
| 7 | 游标分页 | 同位置 `0.0003~0.0005s`，`Handler_read_prev=9` | ✅ |
| 8 | 分区裁剪 | 单月查询 `partitions=p202601`，读 4251 行（原 100009 行） | ✅ |
| 9 | 按 id 查代价 | `partitions` 显示 26 个分区全部打开 | ✅ 已记录 |
| 10 | 分区维护 | `REORGANIZE pmax` 新增 `p202701`，可 `drop partition` 回退 | ✅ |
| 11 | 切换与回滚 | rename 后三个接口全部 `code=0`，回滚 SQL 已写入 `06` | ✅ |
| 12 | 编译 | `mvn -q -DskipTests install` 通过 | ✅ |

### 面试口径（三句话）

1. **索引解决过滤和排序，不解决大偏移**：`(user_id, create_time)` 让浅页从 60ms 降到 0.4ms，但深页反而从 0.1s 变成 0.6s，因为是 8 万次索引遍历 + 随机回表。
2. **延迟关联减少回表，游标分页消除偏移**：前者把同位置耗时降到 20ms，后者降到 0.3ms 且与页深无关，代价是不能跳页。
3. **分区解决时间范围裁剪，不替代索引**：按月分区后单月查询只读 4251 行；但 `id` 不是分区列，按主键查会打开全部分区，所以分区键要和查询模式匹配。

## 十、L8 遗留与回滚

| 项 | 现状 | 回滚方式 |
|---|---|---|
| `orders` 已分区 | 26 个分区，主键 `(id, create_time)` | `rename table orders to orders_p_bad, orders_bak_l8_part to orders;` |
| 旧非分区全量表 | `orders_bak_l8_part`（100009 行） | 同上 |
| 造数前原始 9 行 | `orders_bak_l8` | `truncate orders; insert into orders select * from orders_bak_l8;`（会丢掉分区表结构，仅恢复数据） |
| 索引 | `idx_user_create(user_id, create_time)` | `alter table orders drop index idx_user_create;` |
| 测试数据 | 10 万条跨月数据仍在库中 | 按需清理，不影响代码 |
