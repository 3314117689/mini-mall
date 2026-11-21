# 04 L3 Nacos 注册中心 + OpenFeign

## 一、这一层解决什么

L2 里地址是写死的（`localhost:8082`），服务换机器就废。L3 让服务**自己注册**到 Nacos，调用方**用服务名**找它。

## 二、启动期：服务注册链路（无业务代码）

| 顺序 | 谁 | 干了什么 |
|---|---|---|
| 1 | `pom.xml` | 加 `spring-cloud-starter-alibaba-nacos-discovery` |
| 2 | `application.yml` | 配 `spring.cloud.nacos.discovery.server-addr: 127.0.0.1:8848` |
| 3 | 启动类 | **不用改**，框架自动注册，不需要 `@EnableDiscoveryClient` |
| 4 | 框架 | 启动完成后把 `服务名 + IP + 端口` 发给 Nacos |
| 5 | Nacos | 保存实例，供调用方查询 |

> 数据：`服务名=product-service`、`IP=192.168.x.x`、`端口=8082` 三个值由框架从配置和运行环境读出，写入 Nacos。你的代码里没有一行。

## 三、请求期：Feign 调用链路

**命令入场**：`POST /order/create`，body `{"userId":1,"productId":1,"num":2}`

### 调用链

| 顺序 | 谁 | 干了什么 |
|---|---|---|
| 1 | `OrderServiceImpl.create` | 调 `remoteService.getProduct(1L)` |
| 2 | `ProductClient.getById` | `@FeignClient(name="product-service")` 的接口方法 |
| 3 | 框架（Feign 代理） | 把接口方法翻译成 HTTP 请求 |
| 4 | LoadBalancer | 向 Nacos 要 `product-service` 的实例列表，选一个 |
| 5 | 框架 | 按方法上的 `@GetMapping("/product/{id}")` 拼出 `http://IP:8082/product/1` |
| 6 | `ProductController.getById` | 对方服务接住，返回 `Result<ProductDTO>` |
| 7 | 框架（Feign 解码） | 把 JSON 按 `Result<ProductDTO>` 反序列化 |

### 数据编号

| 编号 | 数据 | 形状 | 谁产生 | 去向 |
|---|---|---|---|---|
| `[D1]` | 商品 id | `1L` | `OrderServiceImpl` | 作为参数传给 `productClient.getById` |
| `[D2]` | HTTP URL | 字符串 `http://192.168.x.x:8082/product/1` | Feign + LoadBalancer 拼出 | 发到 product-service |
| `[D3]` | 返回 JSON | `{"code":0,"data":{...}}` | product-service | 由 Feign 解码 |
| `[D4]` | 结果对象 | `Result<ProductDTO>` | Feign 反序列化 | 你判 `code`、取 `getData()` |
| `[D5]` | 商品对象 | `ProductDTO{id=1,price=9.90}` | `[D4].getData()` | 参与算金额 |

### 两个结局（L3 口径）

| 结局 | 条件 | 谁处理 | 浏览器收到 |
|---|---|---|---|
| ① 正常 | Nacos 有实例、对方健康 | 一路走完 | `code=0` |
| ② 对方不可用 | 实例不存在或连接失败 | 你捕获异常 → `REMOTE_CALL_FAILED(2001)` | `{"code":2001,...}` |

> L3 阶段**不做熔断降级**，那是 L4 的事。这里只要求"友好失败"。

## 四、版本结论（2026-11-20 核对）

- Spring Cloud **2025.1.4**（官方写明支持 Boot 4.1.2）
- SCA **2025.1.0.0**（官方只写适配 4.0.x，4.1.2 未官方验证 → 当时最大风险点，实测运行可用）
- Nacos Server **3.3.0**：服务端 8848，控制台 8080
- 父 POM **只导 Cloud BOM**；SCA 只给 starter 写版本（整包导 SCA BOM 会把 Cloud 顶回 2025.1.0）

## 五、坑

1. 只加 `openfeign` 没加 `loadbalancer` → 找不到实例，必须两个一起加。
2. `@PathVariable("id")` 括号里的名字不能省，Feign 靠它替换路径占位符。
3. Feign 默认超时比 L2 的 3 秒宽松，需要在 yml 覆盖。
4. Nacos 3.3 默认加载 AI 插件，遇 `\\?\` 前缀路径崩溃 → 用 `-f microservice` 启动规避。
5. Nacos 3.3 默认开鉴权，`startup.cmd` 会交互式问密钥 → 先在 `conf/application.properties` 预置。

## 六、验收

- 三服务注册成功，`healthyInstanceCount=1`。
- 全工程搜索：代码中无 `localhost:8082`、无 `RestTemplate` 引用。
- 停掉 product 再下单 → `{"code":2001,"msg":"服务调用失败，请稍后重试"}`。
