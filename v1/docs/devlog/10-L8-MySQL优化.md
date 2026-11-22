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
| L8-3 | 深翻页三版对比（limit / 延迟关联 / 游标） | 待做 |
| L8-4 | 按月 RANGE 分区（`orders_p` + rename） | 待做 |
| L8-5 | 总验收、收尾、提交 | 待做 |

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
