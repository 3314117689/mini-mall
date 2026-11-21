# mini-mall 开发日志

> **文档分工**：本文件记录**开发进度**（做了什么、遗留什么、下一步）与**用户理解确认台账**。规则和默写知识内核在 `AI_CONTEXT.md`，本文件不重复。
> **写入规则**：
> 1. 开发进度条目：AI 完成一段开发后即可追加，保证随时可接续
> 2. 理解确认条目：只在用户明确说"理解了，记录吧"（或同等明确表态）后才写入，带 ✅ 确认标记
> 3. 新条目追加在对应区块顶部（最新在前）
> 4. 开发记录用**链路风格**（2026-11-20 用户定）：请求入口开始按代码顺序走，每步 `类:行号 代码 → 产出什么/跳到哪`，框架步骤只标"框架自动完成"；验收结果和坑压缩在条目末尾，不写大段说明

## 〇、讲解规则（2026-11-20 用户新增，AI 必须遵守）

1. **按代码里的实际执行顺序讲数据流**，只讲用户自己写的代码，**不要讲框架源码**。
2. 框架替用户完成的地方，只说一句 **"框架自动完成"** 即可。
3. 每一步必须说清四件事：**数据从哪来 → 经过哪一行 → 变成什么 → 传到哪去**。
4. 涉及跨服务调用时必须说明：**URL 怎么拼出来 → 命中哪个服务的哪个 Controller 的哪一行 → 返回结果按什么类型解析**。

> 本条是 `AI_CONTEXT.md` 铁律 4 的具体化，两者冲突时以本条为准。

## 一、开发记录

### 2026-11-21（L5-1/L5-2 完成：Redis 环境就绪 + mall-product 接入 Spring Data Redis——commit `待填`）

改动一句话：mall-product 的 pom 加 `spring-boot-starter-data-redis`，yml 加 `spring.data.redis` 连接配置；**无 Java 代码改动**。Redis 用本机已有 5.0.5（用户指定，免安装）。

**L5-1 环境链路（无代码）**

1. 用户已有 Redis 5.0.5：`D:\bishe\environment\redis-5.0.5-bin\redis-5.0.5-bin\`（redis-server.exe / redis-cli.exe / redis.conf）。
2. 启动：`redis-server.exe` 直接运行（默认端口 6379，无密码，读写当前目录 dump.rdb）。
3. 验证：`redis-cli ping` → PONG。

**L5-2 接入链路（启动视角）**

1. `mall-product/pom.xml` 加 `spring-boot-starter-data-redis`（版本由 Boot 父工程仲裁）→ 引入 Lettuce 客户端 + Spring Data Redis 自动装配。
2. `application.yml` 加 `spring.data.redis.host=127.0.0.1 / port=6379`（Boot 3+ 前缀是 `spring.data.redis`，老教程的 `spring.redis` 已废弃）。
3. `mvn install -q -DskipTests` 四模块编译通过。
4. 启动 mall-product → `GET /product/1` 返回 200 `{"code":0,...}`。
5. Redis 连接是**懒加载**的：服务启动时并不真正连 Redis，第一次有缓存读写才建立连接（L5-3 实测证明连通性）。

**坑**：① 8082 被一个**从 IDEA 启动的旧版 product-service 进程**占用（用户以为已停，实际还活着且是没带 Redis 依赖的旧 classpath）——新 jar 启动报 `Port 8082 already in use`，先杀旧进程（PID 43232）再启动新 jar 才通过；教训：**repackage/换 jar 前先确认端口占用进程不是旧实例**。② 沙箱 shell 里用 Start-Process 拉起的 redis-server 会随会话被回收，Redis 需用托管后台方式启动（用户以后在 IDEA/自己终端里跑则无此问题）。

**下一步**：L5-3 @Cacheable 商品查询缓存 + RedisCacheManager（TTL/JSON 序列化），断点验证"第二次查询不进 Mapper"。
**当前楼层**：L5-1/L5-2 完成

### 2026-11-20（L4-3 完成：mall-order 降级——commit `08e530c`）

改动一句话：远程调用收进新文件 `RemoteService`，getUser/getProduct 变成 Sentinel 资源；`ErrorCode` 删 2001、加 2002/2003；`OrderServiceImpl` 只换调用入口，编排逻辑一行没动。

**一、从 /order/create 开始，按代码顺序走（正常路径）**

请求：`POST /order/create`，body `{"userId":1,"productId":1,"num":2}`

1. `OrderController:40-42` `orderService.create(dto)` → 跳 `OrderServiceImpl:59`。
2. `OrderServiceImpl:60-63` 三个字段非空校验，非法 → `BizException(PARAM_ERROR)`（`ErrorCode:15`，1000），合法通过。
3. `OrderServiceImpl:67` `remoteService.getUser(1)` → 跳 `RemoteService:44`：
   - `RemoteService:43` `@SentinelResource(value="getUser", fallback="getUserFallback", blockHandler="getUserBlocked")` → 框架自动把 `getUser` 登记为资源（调用从外面进来，检查才拦得到）。
   - `RemoteService:45` `userClient.getById(1)` → Feign 发 HTTP 到 user-service，返回按 `Result<UserDTO>` 解析 → `{code:0, data:{id:1, username:...}}`。
   - `RemoteService:46-49` code=0 且 data 非空 → 返回 `UserDTO{id:1}`。
4. `OrderServiceImpl:68` `remoteService.getProduct(1)` → `RemoteService:73-79` 同构 → 返回 `ProductDTO{id:1, price:9.90}`。
5. `OrderServiceImpl:70-76` 金额服务端算：`9.90 × 2 = 19.80` → `orderMapper.insert` → 自增主键回填到 `order.id`。
6. `OrderServiceImpl:78-79` 返回 orderId → 前端收到 `{"code":0,"data":5}`。

**二、同一链路，product 停机时走哪（降级路径）**

第 1-3 步同上（getUser 正常）。到 `OrderServiceImpl:68` → `RemoteService:74` Feign 连接被拒抛异常 → 框架捕获，跳 `getProductFallback(1, t)`：

- `RemoteService:83-84` `t` 不是 BizException（是连接异常）→ 不放行。
- `RemoteService:87` → `BizException(PRODUCT_SERVICE_UNAVAILABLE)`（`ErrorCode:30`）→ 全局异常处理器 → `{"code":2003,"msg":"商品服务不可用，请稍后重试"}`。

**三、业务失败不能被降级掩盖（fallback 里的分诊）**

userId=999 下单：`RemoteService:45` HTTP 正常返回 `{code:1001}` → `RemoteService:75-76` 抛 `BizException(USER_NOT_FOUND)` → 框架照样跳 fallback → `RemoteService:83-84` `t` 是 BizException → **原样重抛** → 用户收到 `{"code":1001,"msg":"用户不存在"}`，不是 2003。

**四、规则拦截走 blockHandler（拦截路径）**

给资源 `getUser` 配 QPS=1 后的第 2 单：`RemoteService:43` 注解处框架直接判 BlockException → 跳 `getUserBlocked(1, e)`（`RemoteService:66-68`）→ 抛 `FLOW_LIMITED`（`ErrorCode:33`）→ `{"code":4290,"msg":"请求过于频繁，请稍后再试"}`。

**验收四连（实测）**：正常单 `code=0, orderId=5`；停 product → 2003；userId=999 → 1001 原样放行；QPS=1 第 2 单 → 4290。测完规则已删，控制台干净。

**坑**：① `@SentinelResource` 不配 blockHandler，BlockException 直接上抛变 5000——fallback/blockHandler 要成对写；② PowerShell 传 JSON 把 `\"` 原样发出 → 服务端 5000，像 bug 其实是测试命令问题，POST body 走文件。

- **路线变更备注（2026-11-20 用户决定）**：AI_CONTEXT.md 原「明确砍掉的技术」章节已由用户删除——用户计划在后续纳入 MQ 等此前被砍的技术，具体排期待重新规划，后续窗口不要再按"这些技术已被砍掉"的旧口径回答

### 2026-11-20（L4-2 完成：mall-product QPS 流控——commit `00a2152`）

改动一句话：`ErrorCode` 加 `FLOW_LIMITED(4290)`；mall-product 新增 `SentinelBlockConfig`（本子步唯一新代码）。规则走控制台推送，不硬编码。

**拦截链路（1 秒 15 连发视角）**

请求：`GET /product/1`

1. 启动日志 `[Sentinel Starter] register SentinelWebInterceptor with urlPatterns: [/**]` → 框架自动把每个接口登记为 URL 资源，资源名是 **MVC 模板 `/product/{id}`**，不是 `/product/1`（客户端 `/clusterNode` 实测确认）——配规则必须写模板。
2. 控制台 `POST 8858/v2/flow/rule`：`grade=1`(QPS)、`count=10` → 推到 product 客户端内存（`8720/getRules?type=flow` 可回读；改阈值不重启服务）。
3. 第 1~10 个请求：框架查规则未超 → 放行 → `ProductController:25-26` → 正常 `Result` 返回。
4. 第 11 个：规则命中 BlockException → 框架调我注册的 `blockExceptionHandler` Bean → `SentinelBlockConfig:33-34` 设 HTTP 429 + JSON 头 → `:37-40` 按 `ErrorCode:33` 拼出 `{"code":4290,"msg":"请求过于频繁，请稍后再试","data":null}` 写回响应。
5. 实测：单进程一秒内 15 连发 = **pass=10, block=5**。逐个 curl 是 13/2——跨了两个秒级窗口，验证口径必须"单进程一秒内连发"。

**坑**：① Boot 4 用 Jackson 3，容器没有 `com.fasterxml...ObjectMapper` Bean，注入启动失败 → 手工拼 JSON，code/msg 仍以 ErrorCode 为唯一来源；② 控制台规则 API 单条新增是 `POST /v2/flow/rule`（复数路径 POST 405）；③ PowerShell 传 JSON 吞双引号，body 写文件 `--data @file`；④ 旧 jar 被运行中服务锁定，repackage 前先停服务。

### 2026-11-20（L4-1 完成：三服务接入 Sentinel，控制台冒烟通过——commit `7095c34`）

改动一句话：三个 pom 各加 `spring-cloud-starter-alibaba-sentinel`（SCA 2025.1.0.0 → 内置客户端 1.8.9）；三个 yml 加 `sentinel.transport`。**无 Java 代码改动。**

**接线链路（启动视角）**

1. `pom.xml` 加 starter → 引入 Sentinel 1.8.9 客户端。
2. `application.yml` `spring.cloud.sentinel.transport`：dashboard=`127.0.0.1:8858`（避开 Nacos 控制台 8080）+ 通信端口 8719（同机三服务自动顺延：product=8720、user=8721、order=8722）。
3. 启动日志出现 `register SentinelWebInterceptor with urlPatterns: [/**]` → 每个接口自动成为 URL 资源（"来路 A"，零代码）。
4. 客户端定时向控制台发心跳 → `GET 8858/app/names.json` 返回 `["product-service","user-service","order-service"]` → 接线完成，Boot 4.1.2 兼容风险解除。

环境：dashboard 1.8.9 在 `D:\bishe\environment\sentinel\`，启动命令 `java "-Dserver.port=8858" -jar sentinel-dashboard-1.8.9.jar`。

**坑**：① PowerShell 下 `-D` 参数必须加引号，否则拆参数报 ClassNotFoundException；② GitHub release tag 无 v 前缀，写 `v1.8.9` 下载 404。

### 2026-11-20（L3 完成：OpenFeign 替换 RestTemplate，按服务名下单）

- **代码变更（只在 mall-order）**：
  1. pom 加 `spring-cloud-starter-openfeign` + `spring-cloud-starter-loadbalancer`
  2. 启动类 `OrderServiceApplication` 加 `@EnableFeignClients`（默认扫描 `com.mall.order` 子包）
  3. 新增 `com.mall.order.client.UserClient` / `ProductClient`：`@FeignClient(name="user-service"/"product-service")`，方法注解照抄对方 Controller，返回 `Result<UserDTO>` / `Result<ProductDTO>`
  4. `OrderServiceImpl`：删掉 RestTemplate 字段和两个 `@Value` base-url；`getUser`/`getProduct` 各自缩成一行 `userClient.getById(...)` / `productClient.getById(...)`，异常仍统一转 `REMOTE_CALL_FAILED(2001)`
  5. 删除 `RestTemplateConfig`（已无引用；旧实现见 commit `24de3e8`）
  6. yml：删掉 `mall.*.base-url`，新增 `spring.cloud.openfeign.client.config.default` 连接/读取超时各 3000ms
- **调用链（口头面试口径）**：调用 `productClient.getById(1L)` → `@FeignClient` 里的 `product-service` 作为服务名 → LoadBalancer 向 Nacos 要实例列表（本地缓存）→ 轮询选一个实例 → 按 `@GetMapping` 拼出 `http://IP:8082/product/1` → 命中 `ProductController#getById`（24-27 行）→ JSON 按 `Result<ProductDTO>` 反序列化返回
- **验收证据（本机实测）**：
  1. 三服务注册成功，`healthyInstanceCount=1`
  2. 正常下单：`POST /order/create {userId:1,productId:1,num:2}` → `code=0, orderId=4`；`GET /order/4` 金额 `19.80`（服务端算的 9.90×2）
  3. 停掉 mall-product 再下单 → `{"code":2001,"msg":"服务调用失败，请稍后重试"}`
  4. 全工程搜索：代码中无 `localhost:8082`、无 RestTemplate 引用
- **踩坑**：
  1. 只加 openfeign 不加 loadbalancer → 调用时找不到实例，必须显式加 `spring-cloud-starter-loadbalancer`
  2. `@PathVariable("id")` 括号里的名字不能省，Feign 靠它替换路径占位符
  3. Feign 默认超时是 10s/60s，比 L2 的 3s 宽松，已在 yml 覆盖
  4. 停服务后 Nacos 实例列表有缓存延迟，期间调用是"连得上地址但连不上服务"，同样转 2001
- **遗留**：`POST /order`（纯写库）仍暴露；库存只查不扣（L9-2 解决）
- **下一步**：L4 Sentinel 熔断限流（`@SentinelResource` + fallback + QPS 规则）
- **当前楼层**：**L3 完成**

### 2026-11-20（L3-1 验收通过：Nacos 3.3.0 + 三服务注册成功）

- **环境**：Nacos Server **3.3.0**（1.4.7 已弃用——Nacos 官方规定 2.x 以上客户端连不上 1.x 服务端），解压到 `D:\bishe\environment\nacos`
- **本地配置**（`conf\application.properties`，不在仓库内）：
  1. 关闭鉴权：`nacos.core.auth.enabled/admin.enabled/console.enabled=false`（3.3 默认开启，本地学习环境关闭）
  2. 预置 `nacos.plugin.auth.nacos.token.secret.key`（Base64，≥32 字节）与 `server.identity.key/value`——不预置的话 `startup.cmd` 会交互式追问，后台启动会卡住
- **启动方式**：`bin\startup.cmd -m standalone -f microservice`；服务端 **8848**，控制台 **8080**
- **踩坑**：Nacos 3.3 默认加载 AI 插件，启动时会扫描 PATH，遇到 `\\?\` 前缀路径直接抛 `InvalidPathException` 退出；用 `-f microservice` 限定功能模式可规避
- **验收证据**：
  1. `GET http://127.0.0.1:8848/nacos/v3/admin/ns/service/list` 返回 3 个服务，`healthyInstanceCount=1` 各一个：`product-service`、`user-service`、`order-service`
  2. 8081/8082/8083 三个端口均监听
- **结论**：**SCA 2025.1.0.0 在 Boot 4.1.2 上运行期可用**，路线 B（降级 Boot 3.5.x）不再需要，仅保留为备选
- **遗留**：三个服务与 Nacos 由工具后台进程启动，后续可在 IDEA 里重启接管
- **下一步**：L3-2 服务发现心智模型 → L3-3 OpenFeign 声明式调用（替换 RestTemplate）
- **当前楼层**：L3-1 完成

### 2026-11-20（L3-1 Nacos 注册中心接入，待启动验证）

- **完成**：
  1. 父 POM 新增 `spring-cloud.version=2025.1.4`、`spring-cloud-alibaba.version=2025.1.0.0`；dependencyManagement **只导 Cloud BOM**，不导 SCA BOM（防它把 Cloud 顶回 2025.1.0）
  2. mall-user / mall-product / mall-order 各加 `spring-cloud-starter-alibaba-nacos-discovery`（版本由父工程属性提供）
  3. 三个 application.yml 加 `spring.cloud.nacos.discovery.server-addr: 127.0.0.1:8848`
  4. 三个启动类**未改动**——注册由框架自动完成，不需要 `@EnableDiscoveryClient`
- **验证**：
  1. `mvn install -q -DskipTests` 四模块编译通过
  2. `dependency:tree` 核对：SCA 为 2025.1.0.0，`spring-cloud-commons` 实际解析为 **5.0.4**（未被 SCA 顶回 5.0.0），BOM 顺序风险已排除
  3. **未做**：Nacos Server 未启动，实例注册未实测
- **遗留/风险**：SCA 2025.1.0.0 只官方适配 Boot 4.0.x，Boot 4.1.2 的运行期兼容性仍待实测；失败则转路线 B（Boot 3.5.x + Cloud 2025.0.x + SCA 2025.0.0.0）
- **下一步**：启动 Nacos Server 3.3.0（`startup.cmd -m standalone`）→ 控制台 `http://127.0.0.1:8080` 初始化管理员密码 → 依次启动三个服务 → 看服务列表 3 个实例健康
- **当前楼层**：L3-1 代码完成，待运行验证

### 2026-11-20（L3 开工前：版本结论 + 讲解规则）

- **版本结论（用户选择路线 A：保持 Boot 4.1.2）**：
  1. Spring Cloud **2025.1.4**——官方发布说明写明基于 Boot 4.0.9、同时支持 Boot 4.1.2
  2. Spring Cloud Alibaba **2025.1.0.0**——官方只写"适配 Boot 4.0.x + Cloud 2025.1.x"，**4.1.2 未官方验证，是当前最大风险点**
  3. Nacos Server **3.3.0**——SCA 2025.1 内部客户端 3.1.1；Nacos 3.x 控制台端口 8080、服务端端口 8848
  4. 兜底路线 B：L3-1 冒烟测试跑不通，立即全工程降到 Boot 3.5.x + Cloud 2025.0.x + SCA 2025.0.0.0（业务代码不改）
- **L3 验收口径调整（用户确认）**：原"停服务走 Feign 熔断降级"挪到 L4 Sentinel；L3 只验"服务名调用成功 + 停服务返回 2001 友好失败"
- **讲解规则**：新增本文件〇节（数据流讲法），后续讲解一律按此执行
- **提交**：L2 全量代码已入库（`24de3e8`），本规则与版本结论随后单独提交
- **下一步**：L3-1 注册中心（父 POM 导 Cloud BOM + 3 个子模块加 Nacos 依赖 + 3 个 yml 配 server-addr，启动后在 Nacos 控制台看 3 个实例）
- **当前楼层**：L2 完成；L3 未开始编码

### 2026-11-20（L2 泛型专场讲解：5 条疑惑逐条过）

- **完成**：本轮不改代码，只补 Java 泛型基础，按「三、台账 [L2]」的 5 条疑惑逐条讲，一次一条
  1. `<>` 两个场合：声明处起名（`class Result<T>`）、使用处填值（`Result<UserDTO>`）；嵌套从最里层往外读，`T` 只在类花括号内有效，出了类必须填真类型
  2. 类就是类型：Java 只有 8 个基本类型，其余 `String`/`Long`/`BigDecimal`/`Result` 全是类；`Result` 是"打包 code+msg+data"的容器类（一个方法只能返一个值）；带 `<T>` 是因为 user/product/order 三处 data 不同、要复用同一份外壳
  3. 泛型擦除：被擦的不是 `UserDTO` 类，而是"data 装 UserDTO"这条记号；编译后 data 一律当 Object，取值处编译器自动插强转，壳 `Result` 本身是真实类、从未被擦
  4. `ParameterizedTypeReference`：把带尖括号的类型信息搬到运行时的搬运工（靠匿名子类的父类签名，擦除唯一漏掉的地方）；不用它 data 会被转成 Map
  5. `return result` 交的是整盒 `Result<UserDTO>`，`return result.getData()` 才是 `UserDTO`；壳只在 `getUser` 内部用于判 `code`/判 `data` 空，方法外拿不到
- **用户确认**：前 4 条当场表示懂；第 4 条主动决定"只记固定模板 + 面试一句话解释"，不再深扣细节
- **遗留**：手写 `getUser` 未做——已降级为笔试专项，不阻塞 L3
- **下一步**：直接进 L3（先查 Boot 4.1.2 兼容的 Cloud/Alibaba 版本，确认技术点清单后动手）
- **当前楼层**：L2 口头验收通过

### 2026-11-20（L2 理解确认：泛型卡点）

- 用户已能完整复述下单链路（Controller → Service 编排 → RestTemplate → 用户/商品服务 → 返回 → 落库），DTO、`@Value`、异常分支均已理解
- **卡点**：泛型相关代码（`Result<UserDTO>`、`ParameterizedTypeReference<...>`、`getData()` 的返回类型）无法独立理解，详见「三、理解确认台账 [L2]」条目
- 影响：L2 验收未通过，暂不进入 L3；先补 Java 泛型与匿名内部类基础

### 2026-11-18（L2：RestTemplate 服务调用与订单编排）

- **完成**：
  1. 修复 mall-order 服务名：`product-service` → `order-service`
  2. 新增 `RestTemplateConfig`：连接/读取超时各 3 秒
  3. 新增 DTO：`OrderCreateDTO`（userId/productId/num）、`ProductDTO`、`UserDTO`，服务间不共享实体
  4. `OrderService#create`：调用户服务确认用户存在 → 调商品服务取价格 → 服务端按单价×数量算金额 → 落库
  5. 容错：`RestClientException` → `REMOTE_CALL_FAILED(2001)`；对方业务失败 → `USER_NOT_FOUND(1001)` / `PRODUCT_NOT_FOUND(1002)`；参数非法 → `PARAM_ERROR(1000)`
  6. 新增 `POST /order/create` 接口
- **验收（三服务本机启动实测）**：
  - 正常下单：`code=0, orderId=2, amount=29.70`（9.90×3，服务端计算正确）
  - userId=999 → 1001；productId=999 → 1002；num=0 → 1000
  - 停掉商品服务再下单 → `2001 服务调用失败，请稍后重试`
- **遗留**：
  1. `POST /order`（纯写库、金额由客户端传）仍暴露，真实业务应删除或加权限限制
  2. 库存只查未扣，未做防超卖（L9-2 Lua 解决）
  3. 本轮代码未提交
- **下一步**：L3 Nacos + OpenFeign（开工前先查 Boot 4.1.2 兼容的 Cloud/Alibaba 版本）；或先做 L2 手写复述
- **当前楼层**：L0/L1 完成；L2 代码与验收完成，待手写复述

### 2026-11-18（整体规划 + 真实进度核对）

- **真实进度核对**（DEV_LOG 此前滞后，按代码与数据库实际状态补正）：
  1. mall-user 增删改已完成：UserMapper 新增 insert/update/deleteById，Service/Impl/Controller 补齐；密码字段由 `@JsonIgnore` 改为 `@JsonProperty(WRITE_ONLY)`（`@JsonIgnore` 双向忽略，会把注册时传入的密码也丢掉）
  2. mall-order 新模块已建：五件套 + 8083 端口 + db_order；OrderServiceImpl 复用 L1 异常体系
  3. ErrorCode 新增 `ORDER_NOT_FOUND(1003)`；父 POM 的 modules 已登记 mall-order
  4. 数据库核对：db_order.orders 已存在且有 1 条数据，说明下单链路至少跑通过一次
  5. 以上改动全部未提交；仓库内无任何建表 SQL，库表为手工创建，换机器不可复现
- **整体规划（三阶段）**：
  - **阶段 0 基线**：修 mall-order 服务名、补三份建表 SQL、提交现有代码、补记进度
  - **阶段 A 微服务链路**：L2 RestTemplate + DTO + 编排 + 容错 → L3 Nacos/OpenFeign → L4 Sentinel → L5 Redis 缓存 → L6 JWT(RS256+JWKS) → L7 Gateway
  - **阶段 B 硬核专题**：L8 MySQL 优化（联合索引/深翻页/游标分页/按月分区）→ L9 并发与一致性（线程池反压、CompletableFuture 超时降级、号段发号器、Lua 防超卖）
  - **阶段 C 收尾**：L10 SpringDoc + JSR303；选做 M1 MongoDB 订单快照、M2 RabbitMQ 异步下单、M3 Sa-Token RBAC、M4 ShardingSphere 读写分离
- **规划决策**：
  1. 主路线扩到 L10，L6 由 HS256 简版升级为 RS256 + JWKS
  2. 选做项只做最小单元，主路线通关后按余量逐项加
  3. 不纳入 Spring AI + MCP / RAG（版本风险 + 代码量重），面试口述
  4. Boot 4.1.2 与 Cloud/Alibaba 兼容性在 L3 开工前查表；不适配则全工程降到 3.5.x
  5. 保留原生 MyBatis，不引 MyBatis-Plus（面试口径口头说明）
- **遗留**：
  1. 阶段 0 未执行（服务名、建表 SQL、代码提交）
  2. `v1/docs/api/02商品服务接口文档.md` 仍缺
  3. L1 异常四件套白纸默写 3 遍未完成
- **下一步**：执行阶段 0 → 确认 L2 技术点清单（RestTemplate / DTO / Service 编排 / 调用容错）后开工
- **当前楼层**：L0 完成；L1 待默写；L2 五件套已写、核心新知识未开始

### 2026-11-18（IDE 切换）

- **完成**：开发环境由 Eclipse 切换为 IntelliJ IDEA 2026.3（`D:\IDEA\IntelliJ IDEA 2026.3\bin`）；AI_CONTEXT 的 IDE 约定与 .gitignore 说明同步更新（`.idea/`、`*.iml` 本就已忽略）
- **下一步**：在 IDEA 中按 Maven 多模块方式导入工程，后续开发与运行均在 IDEA 完成

### 2026-11-11 之后（补记：实际完成的开发）

- **mall-user 增删改**：`UserMapper` 新增 insert/update/deleteById（insert 用 `@Options(useGeneratedKeys=true)` 回填 id）；Service/Impl/Controller 补齐增删改接口，update/delete 受影响行数为 0 时抛 `USER_NOT_FOUND`
- **mall-user 密码字段修正**：`@JsonIgnore` → `@JsonProperty(access = WRITE_ONLY)`，保证注册/修改能写入、查询响应不回显
- **mall-order 新模块**：Entity/Mapper/Service/Impl/Controller 五件套 + 8083 端口 + db_order 数据源；基础 CRUD 完成
- **错误码与依赖**：ErrorCode 新增 `ORDER_NOT_FOUND(1003)`；父 POM modules 登记 mall-order
- **验证**：db_order.orders 现存 1 条数据，说明下单接口试跑通过
- **遗留**：全部改动未提交；DEV_LOG 未记录；mall-order 的 `spring.application.name` 误写为 `product-service`

### 2026-11-11（阶段 1 收尾归档）

- **完成**：
  - L0 理解深化轮：以"数据在自己写的代码里怎么走"为主线，走通 GET /product/1 全链路（Controller 接参 → Impl 判空 → Mapper 查库 → Result.ok 包装），明确两条路（正常走 Result.ok / 异常在 Service 被 throw 拐走走全局异常处理器）
  - 注入心智模型确立并经用户确认：@Autowired 注入了只管用，其余全是框架在做；字段没注入=null 调用即 NPE；接口 new 不出来，注入是唯一获取方式；每层只注入下一层；调接口=直接执行 Impl 方法体
  - 断点调试方法教学：F5/F6/F7/F8 分工、调用栈看"值从哪来"、三层各打一个断点用 F8 跳层看链路、条件断点抓 product == null
  - **讲解铁律 4 写入 AI_CONTEXT**：只讲用户代码、框架一律黑盒（"Spring/MyBatis 内部干的"）、一次只讲一个点、不打比方（`f0a3c42`）
  - **git 归档**：提交 `f0a3c42` 后创建 `stage-1` 存档分支并推送远端（= 阶段 1 快照，将来可 `git checkout stage-1` 单独回看）；main 已同步推送，工作区干净
- **遗留**（承接 2026-11-10，均未开始）：
  1. mall-user 补增删改——用户自己动手写，AI 只 review 不代写
  2. 商品服务接口文档 `v1/docs/api/02商品服务接口文档.md`
  3. L1 巩固：异常四件套白纸默写 3 遍 + 自测 3 个追问
- **下一步**：进入 L1 异常框架讲解（Result / ErrorCode / BizException / GlobalExceptionHandler，按 AI_CONTEXT 3.2 + 铁律 4 风格讲）或先动手写 mall-user 增删改，由用户选择
- **当前楼层**：L0 主体完成（剩 mall-user 增删改 + 02 文档）；L1 原理学完待默写；**阶段 1 已存档于 stage-1 分支**

### 2026-11-10

- **完成**：
  - 任务单#03 商品服务（mall-product 五件套 + 增删改查接口）编译验证、提交并推送（`cf72df6`）；mall-product 写操作已于 2026-11-09 用 PowerShell 验收通过（负向 PUT id=999 返回 code=1002）
  - git push 打通：代理端口实为 7891，已配置仓库级 `git config http.proxy http://127.0.0.1:7891`
  - 文档分工重构：AI_CONTEXT 只定规则不记进度，进度统一记录在本文件
- **遗留**：
  1. mall-user 补增删改——**用户自己动手写，AI 只做 review 不代写**（下一步）
  2. 商品服务接口文档 `v1/docs/api/02商品服务接口文档.md`（照 01 用户服务格式）
  3. L1 巩固：按 AI_CONTEXT 3.2 白纸默写异常四件套 3 遍 + 自测 3 个追问
- **下一步**：用户在 Eclipse 动手写 mall-user 的增删改（UserMapper 注解 SQL → UserService/Impl → Controller），卡住贴代码给 AI review → 之后补 02 接口文档 → L1 默写 → 进入 L2（先 mall-order 单体五件套，再学服务间 HTTP 调用）
- **当前楼层**：L0 主体完成（剩 mall-user 增删改 + 02 文档）；L1 原理学完待默写

## 二、项目规划与知识点对照表

> 规划原则：每层一个新知识簇，只做最小单元；验收标准 = 能跑通 + 能讲清 + 能手写。进入每层前先确认该层知识点清单，再动手。

### 2.1 楼层 × 知识点 × 验收

| 楼层 | 对照简历技能 | 要学到的知识点 | 最小代码产出 | 验收证据 | 状态 |
|---|---|---|---|---|---|
| L0 | SpringBoot / MyBatis | 三层架构分工、`@Data/@Mapper/@Service/@Autowired/@RestController`、`#{}` vs `${}`、自增主键回填、驼峰映射、Maven 多模块依赖 | user/product 各五件套 | 白纸默写五件套；CRUD 接口通过 | 已完成 |
| L1 | 统一响应（基座） | Result / ErrorCode / BizException / GlobalExceptionHandler、非受检异常、异常匹配优先级、code 约定 | 四件套 + Service 中 throw | 不存在的 id 返回 code=1001/1002 | 已完成 |
| L2 | Spring Cloud（前置） | RestTemplate Bean 与超时、DTO 与 Entity 的区别、Service 业务编排、远程调用失败转 BizException | `RestTemplateConfig`、`OrderCreateDTO/ProductDTO/UserDTO`、下单编排 | 服务端算金额；关掉商品服务返回友好失败 | 代码完成；泛型已口头讲通，手写延后至笔试专项（见三、台账） |
| L3 | Spring Cloud | Nacos 注册与发现、`@EnableFeignClients`/`@FeignClient`、服务名替代 IP、客户端负载均衡 | 两个 Feign 接口，替换 RestTemplate | 代码无 `localhost:8082`，按服务名调用成功 | 已完成 |
| L4 | Spring Cloud | Sentinel 资源、`@SentinelResource`、fallback、QPS 流控、熔断与降级区别 | 商品查询资源 + fallback 方法 + 控制台规则 | 超阈值被拦截；停服务走降级文案 | 已完成 |
| L5 | Redis / Spring Cache | `@Cacheable/@CacheEvict`、RedisCacheManager、TTL、缓存穿透与空值缓存、更新时删缓存 | 商品查询缓存 + 更新删缓存 | 第二次查询无 SQL 日志；空值也被缓存 | 未开始 |
| L6 | JWT（RS256+JWKS）/ BCrypt / RBAC | RSA 密钥对、RS256 签名与验签、JWKS 公钥端点、BCrypt、拦截器统一验 token、ThreadLocal 上下文与清理、角色校验 | `JwtUtil`、`/auth/login`、`/auth/jwks`、`LoginInterceptor`、`UserContext` | 登录→下单链路通；篡改 token 被拒；无角色被拒 | 未开始 |
| L7 | Spring Cloud Gateway | 路由断言与过滤器、全局过滤器、统一鉴权、WebFlux 与 WebMVC 差异 | `mall-gateway` 路由 + `JwtAuthGlobalFilter` | 只打 9000 可访问三服务；无 token 被网关拦截 | 未开始 |
| L8 | MySQL 索引与 SQL 优化 | B+ 树与最左前缀、filesort 成因、explain 关键字段、延迟关联、游标分页、RANGE 分区与分区裁剪 | 测试数据脚本、联合索引、三种分页写法 | explain 前后对比 + 耗时对比；分区裁剪命中 | 未开始 |
| L9-1 | 原子类与 CAS / 号段发号器 | Redis `INCRBY` 取号段、本地 `AtomicLong` 发号、CAS 无锁思路 | 订单号生成器 | 并发下订单号不重复 | 未开始 |
| L9-2 | Redis Lua 防超卖 | Lua 原子性、缓存与库存一致性、并发正确性 | 库存扣减 Lua 脚本 + 调用 | 库存 10、并发 100，恰好成功 10 单 | 未开始 |
| L9-3 | 线程池分级与反压 | `ThreadPoolTaskExecutor`、核心/最大线程数、队列、拒绝策略、线程池水位 | 快/慢两个线程池 | 池满触发拒绝时有兜底日志 | 未开始 |
| L9-4 | CompletableFuture 编排 | 并行调用、`orTimeout`、异常兜底、超时降级 | 订单详情并行聚合 | 商品服务 sleep 1s 时接口按时返回兜底 | 未开始 |
| L10 | 接口文档 / 参数校验 | SpringDoc OpenAPI、`@Valid`、`@NotBlank/@Min`、参数校验异常处理 | 各服务加依赖 + DTO 注解 + 异常分支 | Swagger 可在线调试；非法入参返回友好错误 | 未开始 |
| M1 | MongoDB 文档建模 | 文档结构设计、Spring Data Mongo、快照与多表查询的选型 | 订单快照写入与查询 | 能讲清为什么用文档库 | 选做 |
| M2 | RabbitMQ / Kafka（了解） | 交换机/队列/绑定、生产者消费者、异步解耦与削峰 | 下单发消息 + 消费者 | 关掉消费者不影响下单主流程 | 选做 |
| M3 | Sa-Token RBAC | 会话与权限模型、`@SaCheckRole`、与自研 JWT 方案的差异 | RBAC 替换或对照实现 | 无角色访问管理接口被拒 | 选做 |
| M4 | ShardingSphere 读写分离 | 主从复制、读写分离路由、分库分表基本概念 | ShardingSphere-JDBC 数据源配置 | 写主读从，日志可验证（环境不足则只讲原理） | 选做 |

### 2.2 简历技能覆盖核对

| 简历声明 | 覆盖楼层 | 面试可讲内容 |
|---|---|---|
| SpringBoot / MyBatis / 多模块 | L0-L2 | 三层架构、注解 SQL、服务编排 |
| Spring Cloud | L3/L4/L7 | Nacos 注册、Feign 调用、Sentinel 熔断限流、Gateway 网关 |
| MyBatis-Plus | 不落地 | 口径：本项目为练习手写 SQL 选原生 MyBatis，MP 在过往项目使用 |
| MySQL 索引 / 深翻页 / 分区 | L8 | 联合索引防 filesort、延迟关联/游标分页、分区裁剪 |
| MySQL MGR / ShardingSphere | M4 | 读写分离配置与主从原理；MGR 口头补充 |
| Java 并发 / CAS / 线程池 / CompletableFuture | L9 | 号段发号器、线程池反压、并行编排与超时降级 |
| Redis / Lua / Spring Cache / 过期监听 | L5/L9 | 缓存注解、空值防穿透、Lua 原子扣库存；过期监听可在 L5 追加订单超时取消 |
| MongoDB 文档建模 | M1 | 订单快照的文档结构设计与选型理由 |
| JWT RS256 + JWKS / BCrypt / RBAC | L6 | 非对称签名验签、公钥端点、密码哈希、角色校验 |
| OAuth 2.1 / Sa-Token | M3 | Sa-Token 权限模型；OAuth 2.1（PKCE/令牌轮换/吊销）口头讲原理 |
| RabbitMQ / Kafka | M2 | 异步解耦与削峰；Kafka 口头补充 |
| Spring AI / MCP / RAG | 不落地 | 面试用过往经历口述，本工程不投入 |
| Linux / Docker / Git / Maven | 全程 | 命令行编译、Git 提交规范、后续可选 Docker 部署 |

### 2.3 执行顺序

阶段 0 基线 → 阶段 A（L2→L3→L4→L5→L6→L7）→ 阶段 B（L8→L9）→ 阶段 C（L10→M1→M2→M3→M4）

## 三、理解确认台账

## [L2] 泛型与 Result<T> 阅读卡点 —— 2026-11-20
- **状态**：✅ 口头验收通过，手写延后到笔试专项（2026-11-20 用户决定：当前优先级是口头面试，手写默写另抽时间）
- **背景**：L2 下单链路（Controller → 编排 → RestTemplate → 下游服务 → 返回）已能完整复述，唯独涉及泛型的代码无法独立理解
- **具体疑惑（仅记录用户疑问，不含讲解）**：
  1. 一看到 `<>` 就懵，`<>` 外面再套一层 `<>` 更无法理解
  2. 不理解 `Result<UserDTO>` 这种写法，想象不出它实际是什么样
  3. 不理解泛型擦除：为什么 `UserDTO` 被擦掉，外壳 `Result<>` 却没有被擦掉
  4. 不理解 `new ParameterizedTypeReference<Result<UserDTO>>() {}` 为什么必须这样写；匿名内部类没学明白，叠加泛型后完全看不懂
  5. 对 `return result.getData();` 似懂非懂：getUser 明明要返回 UserDTO，为什么中间要先得到 Result 类型；这个 Result 数据返回后到底谁需要
- **影响**：`getUser()` / `getProduct()` 只能照着念，不能独立手写；不满足 L2 验收标准，暂缓进入 L3
- **解除条件（2026-11-20 调整）**：能用大白话讲清上述 5 点即解除；"不看代码手写 getUser" 降级为笔试专项，不计入本卡点解除条件
- **本轮讲解结论（2026-11-20）**：
  - 第 1 条：`<>` 分声明处起名 / 使用处填值；嵌套从里往外读；`T` 出了类就失效，使用处必须填真类型
  - 第 2 条：类就是类型，`Result` 与 `String`/`Order` 同级；造它是为了把 code+msg+data 打包成一个返回值；带 `<T>` 是为三种 data 复用一份外壳
  - 第 3 条：被擦的是"data 装 UserDTO"这条记号，不是 `UserDTO` 类；壳 `Result` 是真实类，从未被擦
  - 第 4 条：`ParameterizedTypeReference` 靠匿名子类的父类签名把尖括号信息带到运行时；用户决定只记固定模板 + 面试一句话解释，不再深扣
  - 第 5 条：`return result` 交整盒，`return result.getData()` 才是 `UserDTO`；壳只在 `getUser` 内部判 code / 判 data 空
- **下一步**：卡点按口头标准解除；手写 `getUser` 推迟到笔试专项。下一窗口直接进 L3（先查 Boot 4.1.2 兼容的 Cloud/Alibaba 版本，确认技术点清单后动手）

## [L1] 异常体系四件套 —— 2026-11-09
- 学了什么：Result/ErrorCode/BizException/GlobalExceptionHandler 四件套的原理与全链路（throw → 飞行 → 哨兵接住 → 变 JSON），含 3 个面试追问的速答
- 踩坑：无
- 遗留：白纸默写 3 遍巩固
- 用户确认：✅ 2026-11-09

## [L0] 三层架构五件套 —— 2026-11-09
- 学了什么：Entity/Mapper/Service/Impl/Controller 五件套的依赖顺序、每层自问自答、易漏 5 注解
- 踩坑：包少建一层 Mapper 扫描不到；判空逻辑写反；DB 列名与代码不一致；金额用 BigDecimal；改多模块文件改串 yml
- 遗留：mall-user 增删改由用户自己动手写
- 用户确认：✅ 2026-11-09
