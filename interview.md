# interview.md — 面试题库（与 DEV_LOG 楼层同步维护）

> 维护规则：AI 每次更新 DEV_LOG 开发记录时，同步在此追加对应楼层的面试题。
> 题量按面试高频程度增减：★★★ 高频多写，★★ 中频适量，★ 低频一笔带过。
> 每题只给「参考要点」用于口头自测，不写长篇答案——目标是能用自己的话讲清。

---

## L0 三层架构与 MyBatis 基础

**1.（★★★）说说你项目的三层架构，每层干什么？**
参考要点：Controller 收请求/参数校验/调 Service → Service 写业务逻辑/编排/事务 → Mapper 只管 SQL；Entity 在层间流转，DTO 出入接口。

**2.（★★★）`#{}` 和 `${}` 的区别？**
参考要点：`#{}` 预编译占位符，值当参数，防 SQL 注入；`${}` 字符串拼接，有注入风险；ORDER BY 列名这类不能用 `#{}` 的场景才权衡用 `${}`（白名单校验）。

**3.（★★）insert 之后怎么拿到自增主键？**
参考要点：`useGeneratedKeys="true"` + `keyProperty="id"`，框架自动完成回填，插入后直接用实体 getId()。

**4.（★★）数据库下划线字段怎么映射成 Java 驼峰属性？**
参考要点：MyBatis `map-underscore-to-camel-case: true`，create_time → createTime，框架自动完成。

**5.（★）`@Mapper` 和 `@MapperScan` 的区别？**
参考要点：`@Mapper` 标在单个接口上；`@MapperScan` 在启动类扫整个包，项目里二选一。

---

## L1 统一响应与异常体系

**1.（★★★）为什么要有统一返回格式 Result<T>？**
参考要点：前端只认一种结构（code/msg/data）；成功失败同构，不用每次猜；code 是业务约定（0 成功，1xxx 用户，2xxx 远程调用）。

**2.（★★★）说下你的异常处理流程：Service 抛异常后发生了什么？**
参考要点：Service `throw new BizException(ErrorCode.XXX)` → `@RestControllerAdvice` 的 GlobalExceptionHandler 捕获 → 按 BizException 里的 ErrorCode 转成 `Result.fail(code,msg)` → JSON 返回。业务代码里不写 try-catch 拼响应。

**3.（★★）BizException 为什么继承 RuntimeException 而不是 Exception？**
参考要点：非受检异常，不强制每层 throws/try-catch，事务默认也只回滚 RuntimeException；受检异常会污染所有方法签名。

**4.（★★）多个 ExceptionHandler 都能匹配时谁生效？**
参考要点：优先级是"越具体越优先"——先精确类型（BizException），再兜底（Exception.class），框架自动完成匹配。

**5.（★）ErrorCode 怎么设计的？**
参考要点：枚举/常量，code 分段约定（0 成功、1xxx 用户、2xxx 远程调用），报错只传枚举，不散落魔法数字。

---

## L2 订单编排与 RestTemplate（Spring Cloud 前置）

**1.（★★★）讲一下你下单接口的完整流程。**
参考要点：Controller 收 JSON → 框架自动转 `OrderCreateDTO` → Service 编排：getUser 调用户服务查用户 → getProduct 调商品服务查商品和价格 → **金额服务端算**（单价×数量，不信前端） → 组装 Order 落库 → 返回 orderId。

**2.（★★★）Entity 和 DTO 为什么要分开？**
参考要点：Entity 对应表结构，含敏感/内部字段；DTO 是接口出入参的"视图"，隔离数据库结构变化对外的影响；跨服务传 DTO 而不是 Entity，防止别的服务依赖我的表结构。

**3.（★★★）远程调用失败了怎么处理的？**
参考要点：两类失败分开——HTTP 层失败（连不上/超时/解析失败）catch 后转 `REMOTE_CALL_FAILED(2001)`，提示可重试；业务层失败（code!=0 或 data 为空，如用户不存在）转对应业务错误码。全局搜索不存在裸抛。

**4.（★★）RestTemplate 怎么配置超时的？为什么要设？**
参考要点：`SimpleClientHttpRequestFactory` 设 connect/read 超时各 3 秒；不设的话默认无限等，下游一卡上游线程全被占死（雪崩入口）。L3 后由 Feign 配置项 `connect-timeout/read-timeout` 接管。

**5.（★★）`Result<UserDTO>` 这种泛型嵌套怎么理解？**
参考要点：尖括号是"类型参数"——Result 是个盒子，盒子里装的东西的类型由调用方声明；反序列化时框架按声明的类型把 JSON 转成对应对象。编译期检查，取出来不用强转。

**6.（★★）`new ParameterizedTypeReference<Result<UserDTO>>() {}` 这段匿名内部类在干嘛？**
参考要点：修复泛型擦除——运行期 JVM 不记得 T 是什么，这个匿名子类把完整的 `Result<UserDTO>` 类型信息保留下来（存在 getClass().getGenericSuperclass() 里），exchange 据此反序列化。固定写法，记住模板即可。

**7.（★）泛型擦除是什么？**
参考要点：编译后泛型信息被抹掉，`List<String>` 和 `List<Integer>` 运行期都是 List；所以无法 `new T()`、无法直接拿到 T 的 Class。需要真实类型时用 ParameterizedTypeReference / TypeReference 这类"类型令牌"。

---

## L3 Nacos 注册中心与 OpenFeign

**1.（★★★）注册中心解决什么问题？没有它行不行？**
参考要点：解决"服务地址写死"——原来 yml 里写 localhost:8082，换机器/扩容就失效。注册中心是张实时通讯录：服务名 → 实例列表。没有它，多实例负载均衡和动态上下线都做不了。

**2.（★★★）说下服务注册和发现的完整过程。**
参考要点：提供者启动时把"服务名+IP+端口"报给 Nacos 并定时心跳（掉线自动摘除）；消费者调用时拿服务名查 Nacos 要实例列表（本地有缓存），选一个实例发请求。注册由框架自动完成，启动类连 `@EnableDiscoveryClient` 都不用写。

**3.（★★★）Feign 怎么用？它帮你省了什么？**
参考要点：接口上加 `@FeignClient(name="product-service")`，方法上照抄对方 Controller 的 `@GetMapping("/product/{id}")`，返回 `Result<ProductDTO>`；启动类 `@EnableFeignClients`。省掉了拼 URL、ParameterizedTypeReference、exchange 四参数；但超时配置和异常处理仍要自己写。

**4.（★★★）服务名是怎么变成真实 IP 的？（Feign 调用链）**
参考要点：`@FeignClient` 的 name → LoadBalancer 向 Nacos 要实例列表 → 默认轮询选一个 → 按 `@GetMapping` 路径拼出 `http://IP:port/product/1` → 命中对方 Controller → JSON 按方法签名反序列化。服务名不是域名，不走 DNS，只走注册中心这张表。

**5.（★★）只用 Feign 不加 LoadBalancer 会怎样？**
参考要点：启动正常，调用时报 `No instances available`——因为"查表选实例"这一步没人干。三件套缺一不可：nacos-discovery + openfeign + loadbalancer。

**6.（★★）Feign 方法上 `@PathVariable("id")` 为什么必须写名字？**
参考要点：Feign 靠参数名替换路径占位符 `{id}`；编译默认不保留参数名，不写名字就替换不了，直接报错。

**7.（★★）下游服务挂了，你的代码里会发生什么？**
参考要点：Feign 调用抛异常 → catch 转 `REMOTE_CALL_FAILED`，前端拿到友好提示；实测：停掉商品服务后下单返回 `{"code":2001,"msg":"服务调用失败，请稍后重试"}`。注意 Feign 本身不带熔断降级，那是 L4 Sentinel 的事。

**8.（★）Nacos 心跳是干嘛的？**
参考要点：客户端定时（几秒级）上报"我还活着"；超时没心跳的实例被标记不健康/摘除，调用方就不会选到死实例。

---

## L4 Sentinel 熔断限流（进行中）

**1.（★★★）什么是 Sentinel 的"资源"？你项目里资源是怎么来的？**
参考要点：资源 = 被保护代码的单位，流控/熔断规则都挂在资源名上。两个来路：加依赖后每个 Controller 接口自动成为 URL 资源（如 `/product/1`）；`@SentinelResource("名字")` 标在方法上自造资源。

**2.（★★）你的 Sentinel 控制台怎么部署的？客户端怎么连上它的？**
参考要点：控制台是独立 jar（1.8.9）自己启动，与业务工程的 Boot 版本无关（本项目跑在 8858，避开 Nacos 控制台 8080）；工程加 starter + yml 配 `spring.cloud.sentinel.transport.dashboard`，客户端起一个本地端口（8719 起，冲突自动顺延）定时向控制台发心跳/拉规则。

**3.（★★★）QPS 流控怎么配的？超阈值的请求经历了什么？**
参考要点：控制台对资源 `/product/{id}` 配 QPS=10（grade=1）；超阈值的请求进不来业务代码，被框架拦截，走我实现的 BlockExceptionHandler 返回 HTTP 429 + `{"code":4290,"msg":"请求过于频繁，请稍后再试"}`。实测：1 秒内 15 个请求 = 10 过 + 5 拦。规则在控制台配、推送到客户端内存，改阈值不用重启服务。

**4.（★★）为什么 URL 资源名是 `/product/{id}` 而不是 `/product/1`？**
参考要点：Sentinel 的 Web 适配器按 Spring MVC 的 URL 模板登记资源，同一模式共用一条规则（/product/1、/product/2 共享阈值）；配规则时写模板不写具体路径，否则规则永远匹配不上。

**5.（★★★）fallback 和 blockHandler 的区别？**
参考要点：都是降级方法，区别在**触发源**——fallback 接"方法抛出的异常"（调用失败、超时、业务异常），blockHandler 接"BlockException"（规则触发：限流/熔断）。方法签名差异：fallback 是原参数 + Throwable（框架把出了什么事传进来），blockHandler 是原参数 + BlockException。我的项目里 fallback 按 BizException 类型分诊：业务失败（1001）原样放行，服务不可用才翻译成 2002/2003。

**6.（★★★）`@SentinelResource` 为什么标在独立类的方法上，不能标在原来的私有方法上？**
参考要点：注解检查发生在方法调用的统一入口（框架自动完成）；同类内部 `this.getUser()` 直接调用绕过了入口，注解不生效。把远程调用搬进独立的 RemoteService、通过注入调用，调用走正常入口，注解才起作用。

**7.（★★★）熔断和降级的区别？**
参考要点：**降级是"备胎方案"**——出了事给个备用响应（fallback 文案），是"怎么办"；**熔断是"自动断电"**——失败率/慢调用率超阈值后，一段时间内直接拒绝请求不再真调下游，是"先别调"。熔断有半开恢复（放一小部分流量试探），降级没有状态机。关系：熔断触发后，请求走 blockHandler/fallback 兜底，两者常配合出现。

**8.（★★）你的降级文案为什么从统一的 2001 变成 2002/2003？**
参考要点：L2/L3 时代 try/catch 统一转 2001"服务调用失败"；L4 后 fallback 按被调服务翻译——用户服务不可用 2002、商品服务不可用 2003，排障时一眼看出是哪条链路断了；2001 保留在 Git 历史里，代码中已删。

---

## 待补楼层

- L5 及以后：逐层追加
