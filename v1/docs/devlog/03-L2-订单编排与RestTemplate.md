# 03 L2 订单服务 + RestTemplate

> 按铁律 11：总览表只做导航，过程在"逐步行进"里。
> 本章记录 **L2 当时**的实现（RestTemplate + 写死地址）。L3 已换成 Feign（见 04 章），当前代码里搜不到 `RestTemplate`。

## 一、这一层解决什么

订单要"用户存在 + 商品价格"，但**不许直连别人的库**。所以订单服务必须通过 HTTP 去问 user-service 和 product-service —— 这是"微服务成立"的第一步。

## 二、`POST /order/create` 的调用链

**命令入场**：`POST http://localhost:8083/order/create`，body `{"userId":1,"productId":1,"num":2}`

| 顺序 | 谁 | 干了什么 |
|---|---|---|
| 1 | Tomcat 线程 | 找到 `OrderController` 的下单方法 |
| 2 | 框架 | JSON body → `OrderCreateDTO` |
| 3 | `OrderController.create` | 调 `orderService.create(dto)` |
| 4 | `OrderServiceImpl.create` | 四个字段校验 |
| 5 | `OrderServiceImpl.create` | 用 `RestTemplate` 发 `GET http://localhost:8081/user/1` |
| 6 | `OrderServiceImpl.create` | 用 `RestTemplate` 发 `GET http://localhost:8082/product/1` |
| 7 | `OrderServiceImpl.create` | 服务端算金额 `price × num` |
| 8 | `OrderMapper.insert` | 写库，自增 id 回填 |
| 9 | `Result.ok(orderId)` | 包装返回 |

**数据编号**

| 编号 | 数据 | 初始值 | 谁产生 |
|---|---|---|---|
| `[D1]` | 下单请求 | `OrderCreateDTO{userId=1, productId=1, num=2}` | 框架从 JSON 转换 |
| `[D2]` | 用户 URL | `"http://localhost:8081/user/1"` | 你拼接 |
| `[D3]` | 用户结果 | `Result<UserDTO>` | RestTemplate 反序列化 |
| `[D4]` | 用户对象 | `UserDTO{id=1,...}` | `[D3].getData()` |
| `[D5]` | 商品结果 | `Result<ProductDTO>` | RestTemplate 反序列化 |
| `[D6]` | 商品对象 | `ProductDTO{id=1, price=9.90}` | `[D5].getData()` |
| `[D7]` | 金额 | `BigDecimal 19.80` | 服务端算 |
| `[D8]` | 订单 id | `Long 5` | MySQL 自增回填 |
| `[D9]` | 响应 | `{"code":0,"data":5}` | 框架序列化 |

**四个结局（总览）**

| 结局 | 条件 | 谁处理 | 浏览器 |
|---|---|---|---|
| ① 正常 | 用户存在、商品存在、num>0 | 一路走完 | `code=0` |
| ② 参数非法 | 任一字段为 null 或 num≤0 | `OrderServiceImpl` 抛 `PARAM_ERROR(1000)` | `code=1000` |
| ③ 用户不存在 | user-service 返回 `code=1001` | 你判 code 后抛 `USER_NOT_FOUND` | `code=1001` |
| ④ 商品服务挂了 | HTTP 连接失败 | 捕获 `RestClientException` → `REMOTE_CALL_FAILED(2001)` | `code=2001` |

> 分岔点全在 `OrderServiceImpl.create` 的方法体里，没有框架魔改——L2 阶段最好理解。

---

## 三、结局①逐步行进：正常

### 第 1 步｜请求到达

- 【框架动作】Tomcat 派线程；按路径找到 Controller；body 转 `OrderCreateDTO`。
- 【数据】`[D1] = OrderCreateDTO{userId=1, productId=1, num=2}`。

### 第 2 步｜字段校验

- 【你写的】`if (null 或 num<=0) throw PARAM_ERROR`。
- 【数据】`[D1]` 合法 → 继续。

### 第 3 步｜调用户服务

```java
// 文件：mall-order/.../service/impl/OrderServiceImpl.java（L2 版本）
// 方法：create
String url = "http://localhost:8081/user/" + dto.getUserId();
ResponseEntity<Result<UserDTO>> resp = restTemplate.exchange(
        url, HttpMethod.GET, null,
        new ParameterizedTypeReference<Result<UserDTO>>() {});
Result<UserDTO> userResult = resp.getBody();
```

- 【你写的】拼 URL、发 GET、把响应体读成 `Result<UserDTO>`。
- 【框架动作】`RestTemplate` 建连接、发请求、把响应 JSON 反序列化；`ParameterizedTypeReference` 把 `<UserDTO>` 这个尖括号信息带到运行时，否则 `data` 会变成 `Map`。
- 【数据】`userId=1` → `[D2] = "http://localhost:8081/user/1"` → `[D3] = Result{code=0, data=UserDTO{...}}`。

### 第 4 步｜判 code，取用户

- 【你写的】`userResult == null || code != 0 || data == null` → 抛 `USER_NOT_FOUND`；否则取 `getData()`。
- 【数据】`[D3]` → `[D4] = UserDTO{id=1,...}`。

### 第 5 步｜调商品服务

- 【你写的】同第 3~4 步，URL 换成 `http://localhost:8082/product/1`，类型换成 `Result<ProductDTO>`。
- 【数据】`productId=1` → `[D5] = Result{code=0, data=ProductDTO{id=1, price=9.90}}` → `[D6] = ProductDTO{id=1, price=9.90}`。

### 第 6 步｜服务端算钱

```java
order.setAmount(product.getPrice().multiply(BigDecimal.valueOf(dto.getNum())));
```

- 【你写的】金额 = 商品单价 × 数量；请求体里的金额一律不信任。
- 【数据】`9.90 × 2` → `[D7] = BigDecimal 19.80`。

### 第 7 步｜组装订单

- 【你写的】`order.setUserId([D4].getId())`、`setProductId([D6].getId())`、`setNum([D1].num)`、`setAmount([D7])`。
- 【数据】四个字段 → `Order{userId=1, productId=1, num=2, amount=19.80, id=null}`。

### 第 8 步｜写库

- 【框架动作】MyBatis 执行 insert，自增主键回填到 `order.id`。
- 【数据】`Order{id=null}` → `Order{id=5}`；`[D8] = 5L`。

### 第 9 步｜返回

- 【你写的】`return order.getId()` → Controller 里 `Result.ok(5L)`。
- 【数据】`[D8]` → `[D9] = {"code":0,"data":5}`。

---

## 四、结局②逐步行进：参数非法

**入场**：body `{"userId":1,"productId":1,"num":0}`。

### 第 1 步｜请求到达并转换

- 【数据】`[D1] = OrderCreateDTO{userId=1, productId=1, num=0}`。

### 第 2 步｜校验命中

- 【你写的】`dto.getNum() <= 0` 为 true → 抛 `BizException(PARAM_ERROR)`。
- 【数据】`[D1]` → `BizException{code=1000, message="参数错误"}`。

### 第 3 步｜异常飞行

- 【你写的】后续远程调用、写库全部不执行。
- 【数据】异常沿 `create` → Controller → 框架 飞出。

### 第 4 步｜全局处理器接住

- 【框架动作】`GlobalExceptionHandler` 匹配 `BizException` → `Result.fail(1000,...)`。
- 【数据】`[D9] = {"code":1000,"msg":"参数错误"}`。

---

## 五、结局③逐步行进：用户不存在

**入场**：`userId=999`，user-service 正常响应但 `code=1001`。

第 1 步同结局①。

### 第 2 步｜校验通过

- 【数据】`[D1]` 合法（999 不为 null）。

### 第 3 步｜请求用户服务

- 【你写的】URL 拼成 `http://localhost:8081/user/999`。
- 【框架动作】HTTP 200，body `{"code":1001,"data":null}`，**不抛异常**。
- 【数据】`[D3] = Result{code=1001, data=null}`。

### 第 4 步｜你判 code 后主动抛

- 【你写的】`code != 0` 成立 → 抛 `BizException(USER_NOT_FOUND)`。
- 【数据】`[D3]` → `BizException{code=1001, message="用户不存在"}`；`[D4]` 没有产生。

### 第 5 步｜异常出路

- 【框架动作】`GlobalExceptionHandler` 接住。
- 【数据】`[D9] = {"code":1001,"msg":"用户不存在"}`。

---

## 六、结局④逐步行进：商品服务挂了

**入场**：停掉 product-service，`userId=1` 正常。

第 1~4 步同结局①（`[D4]` 已拿到）。

### 第 5 步｜HTTP 连接失败

- 【框架动作】`RestTemplate` 连不上 `localhost:8082`，抛 `ResourceAccessException`（`RestClientException` 的子类）。
- 【数据】`[D5]`、`[D6]` 都没有产生。

### 第 6 步｜你捕获并翻译

```java
// L2 版本：create 里包 try/catch
try {
    ... 两次远程调用 ...
} catch (RestClientException e) {
    log.error("远程调用失败: {}", e.getMessage());
    throw new BizException(ErrorCode.REMOTE_CALL_FAILED);
}
```

- 【你写的】捕获后抛 `REMOTE_CALL_FAILED(2001)`，不让 500 直接暴露给前端。
- 【数据】`ResourceAccessException` → `BizException{code=2001, message="服务调用失败"}`。

### 第 7 步｜异常出路

- 【框架动作】`GlobalExceptionHandler` 接住。
- 【数据】`[D9] = {"code":2001,"msg":"服务调用失败"}`。

> 注意：这里 `catch` 的范围如果包住了"判 code 后抛的 BizException"，会把 1001 误翻译成 2001。L4 用 fallback 分诊正是为了解决这个隐患。

---

## 七、本层新知识

1. **DTO 而不是实体**：服务间传 `UserDTO` / `ProductDTO`，不共享 Entity。
2. **金额服务端算**：客户端只传数量。
3. **容错**：对方业务失败仍是 HTTP 200，要判 `code`；只有连接层失败才抛异常。

## 八、泛型卡点（记录项）

- `<>` 在声明处是"起名"（`Result<T>`），在使用处是"填值"（`Result<UserDTO>`）。
- 泛型擦除擦的是"data 装 UserDTO"这条记号，不是 `UserDTO` 类。
- `ParameterizedTypeReference` 是把尖括号信息搬到运行时的搬运工；不用它，`data` 会被解析成 `Map`。
- 结论：记固定写法 + 一句话解释，不深扣。

## 九、验收与遗留

- 验收：正常下单 `code=0`；`userId=999` → 1001；`productId=999` → 1002；`num=0` → 1000；停商品服务 → 2001。
- 遗留：`POST /order` 纯写库接口仍暴露；库存只查不扣（L9 解决）。
