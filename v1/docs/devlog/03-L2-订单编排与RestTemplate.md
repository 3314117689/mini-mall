# 03 L2 订单服务 + RestTemplate

## 一、为什么要有这一层

订单要"用户存在 + 商品价格"，但**不许直连别人的库**。所以订单服务必须通过 HTTP 去问 user-service 和 product-service —— 这是整个项目"微服务成立"的第一步。

## 二、`POST /order/create` 的数据全链路

**命令入场**：`POST http://localhost:8083/order/create`，body `{"userId":1,"productId":1,"num":2}`

### 调用链

| 顺序 | 谁 | 干什么 |
|---|---|---|
| 1 | Tomcat 线程 | 找到 `OrderController` 的下单方法 |
| 2 | 框架 | 把 JSON body 转成 `OrderCreateDTO` |
| 3 | `OrderController.create` | 调 `orderService.create(dto)` |
| 4 | `OrderServiceImpl.create` | 三个字段非空校验 |
| 5 | `OrderServiceImpl` | 调 user-service：`restTemplate` 发 `GET /user/1` |
| 6 | `OrderServiceImpl` | 调 product-service：发 `GET /product/1` |
| 7 | `OrderServiceImpl` | 服务端算金额 `单价 × 数量` |
| 8 | `OrderMapper.insert` | 写库，自增 id 回填到 `order.id` |
| 9 | `Result.ok(orderId)` | 包装返回 |

### 数据编号

| 编号 | 数据 | 初始值 | 谁产生 | 去向 |
|---|---|---|---|---|
| `[D1]` | 请求体 | `OrderCreateDTO{userId=1, productId=1, num=2}` | 框架从 JSON 转换 | 传给 `orderService.create` |
| `[D2]` | 用户对象 | `UserDTO{id=1,...}` | user-service 返回，经 `Result<UserDTO>` 解析 | 只用来确认用户存在 |
| `[D3]` | 商品对象 | `ProductDTO{id=1, price=9.90}` | product-service 返回 | 取 `price` 参与算钱 |
| `[D4]` | 订单金额 | `BigDecimal 19.80` | `OrderServiceImpl` 用 `9.90 × 2` 算出 | 写进 `orders.amount` |
| `[D5]` | 订单对象 | `Order{id=?,...}` | 你组装 | 交给 `orderMapper.insert` |
| `[D6]` | 订单 id | `Long 4` | MySQL 自增回填 | 包装进 `Result.data` |
| `[D7]` | 响应 | `{"code":0,"data":4}` | 框架序列化 | 浏览器 |

### 四个结局

| 结局 | 条件 | 谁处理 | 浏览器收到 |
|---|---|---|---|
| ① 正常 | 用户存在、商品存在、num>0 | 一路走完 | `code=0, data=订单id` |
| ② 参数非法 | 任一字段为 null | `OrderServiceImpl` 抛 `PARAM_ERROR(1000)` | `{"code":1000,...}` |
| ③ 用户不存在 | user-service 返回 `code=1001` | 你判 code 后抛 `USER_NOT_FOUND` | `{"code":1001,"msg":"用户不存在"}` |
| ④ 商品服务挂了 | HTTP 连接失败 | 捕获 `RestClientException` → `REMOTE_CALL_FAILED(2001)` | `{"code":2001,"msg":"服务调用失败"}` |

> ①②③④ 的分岔点都在 `OrderServiceImpl.create` 的方法体里，**没有框架魔改**——这是 L2 阶段最好理解的一点。

## 三、本层新知识

1. **DTO 而不是实体**：服务间传 `UserDTO` / `ProductDTO`，不共享 Entity；对方表结构变了不会连带崩你。
2. **金额服务端算**：客户端只传数量，价格来自商品服务，防止前端改价。
3. **容错**：`RestTemplate` 抛 `RestClientException`；对方业务失败仍返回 HTTP 200，需要你判 `code`。

## 四、泛型卡点（用户当时未通过，属记录项）

卡在 `Result<UserDTO>`、`new ParameterizedTypeReference<Result<UserDTO>>() {}`、`getData()` 的返回类型。已单独讲解：

- `<>` 在声明处是"起名"（`Result<T>`），在使用处是"填值"（`Result<UserDTO>`）。
- 泛型擦除擦的是"data 装 UserDTO"这条记号，不是 `UserDTO` 类。
- `ParameterizedTypeReference` 是把尖括号信息搬到运行时的搬运工；不用它，`data` 会被解析成 `Map`。
- 结论：记固定写法 + 一句话解释即可，不深扣。

## 五、验收与遗留

- 验收：正常下单 `code=0, orderId=2, amount=29.70`；userId=999 → 1001；productId=999 → 1002；num=0 → 1000；停商品服务 → 2001。
- 遗留：`POST /order` 纯写库接口仍暴露；库存只查不扣（L9 解决）。
