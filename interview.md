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

## L5 Redis 缓存（进行中）

**1.（★★★）为什么要给商品查询加缓存？不加会怎样？**
参考要点：商品详情/列表是读多写少的热点数据，每次都查 MySQL，并发一高数据库连接池和磁盘 IO 先扛不住；Redis 数据在内存里，单机十万级 QPS，给数据库挡在读前面。代价是要处理数据不一致（更新商品后缓存还是旧值）。

**2.（★★）你的项目里 Redis 是怎么接入的？**
参考要点：pom 加 `spring-boot-starter-data-redis`（自带 Lettuce 客户端），yml 配 `spring.data.redis.host/port`（注意 Boot 3+ 前缀带 `data.`，老教程的 `spring.redis` 已废弃）；连接由框架自动完成，且是懒加载——启动时不连，第一次读写缓存才真正建立连接。

**3.（★）Redis 在你的机器上怎么部署的？**
参考要点：Windows 版 Redis 5.0.5（社区维护版，官方不出 Windows 版），解压直接 `redis-server.exe` 启动，默认端口 6379；`redis-cli ping` 返回 PONG 即存活。

**4.（★★★）@Cacheable 是怎么工作的？怎么验证缓存真的生效了？**
参考要点：方法执行前框架先按 `cacheNames::key` 拼 key 查 Redis，命中直接返回缓存值、**方法体一行不执行**；没命中才进方法体查库，返回值被框架顺手写进 Redis。验证：第 2 次请求服务日志零 SQL（方法里 log.info 都没打印）、Redis 里出现 `product::1`、命中时 TTL 只衰减不重置。

**5.（★★★）为什么默认的缓存序列化不能用，你要自己配 RedisCacheManager？**
参考要点：默认用 JDK 序列化——redis-cli 里全是乱码没法看，且实体必须实现 Serializable、跨语言读不了；自己声明 RedisCacheManager Bean：TTL 30 分钟防旧值永久滞留 + JSON 序列化（`enableDefaultTyping` 让 JSON 带 @class 字段，反序列化才知道转回哪个类，BigDecimal 这类也会带类型包裹）。

**6.（★★）@Cacheable 的 key 是怎么拼出来的？**
参考要点：`cacheNames::key`——本项目 key 是 `product::1`；`key="#id"` 是 SpEL，把方法参数当缓存 key。同一个 cacheNames 就是一个分区，删缓存/统计都按分区来。

**7.（★★★）什么是缓存穿透？你怎么解决的？**
参考要点：查一个数据库里根本不存在的数据（如 id=999），缓存永远没有、每次请求都打到 MySQL，恶意刷就能打垮数据库。解决：**空值缓存**——查不到也往 Redis 写一个 null（框架用 NullValue 标记存，需序列化器 `enableSpringCacheNullValueSupport()`），下次同 id 直接命中、不再回源。注意 null 也有 TTL，到期会再回源一次。

**8.（★★★）为了做空值缓存，为什么要把查询方法拆到另一个类？**
参考要点：两个约束叠加——① 空值缓存要求"查不到返回 null"，不能抛异常（**异常永远不会被缓存**，抛异常就等于每次都打库）；② `@Cacheable` 只在被**别的类**调用时生效，同类 `this.` 调用绕过代理。所以把回源查询放进 `ProductCacheService.queryById`，由 `ProductServiceImpl` 注入调用；判空抛 1002 留在 ProductServiceImpl。

**9.（★★）空值缓存有什么副作用？怎么缓解？**
参考要点：① 数据后来真的新增了，缓存里还留 null，最长 TTL 内查不到 → 用较短 TTL（如 1-5 分钟）缓解；② 大量不同 id 的 null 会占内存 → 加 key 前缀/限流，或用布隆过滤器提前挡掉。本项目从简：null 与正常值共用 30 分钟 TTL。

**10.（★★★）更新数据库后缓存怎么办？为什么选删缓存而不是更新缓存？**
参考要点：只读缓存不会跟着写操作变，必须处理，否则 TTL 内读到旧值（**缓存与数据库不一致**）。本项目用 `@CacheEvict` 删缓存，下次查询回源重建。不选"更新缓存"的原因：并发写时两个请求的更新顺序可能颠倒，缓存被旧值覆盖；而且如果某次更新后立刻又更新一次，多写一次缓存纯属浪费。删缓存最简单、最不容易错。

**11.（★★）`@CacheEvict` 什么时候删？失败了会删吗？**
参考要点：默认 `beforeInvocation=false`——方法**成功返回之后**才删。方法抛异常（如更新 0 行报"商品不存在"）就不删，因为库也没改，缓存保持原样正好一致。若业务要求"无论成败都清"，写 `beforeInvocation=true`。实测过：DELETE 不存在的 id 抛 1002 后，缓存 key 仍在。

**12.（★★）`@CacheEvict` 的 key 写错了会怎样？**
参考要点：删的是另一个 key，等于没删，缓存还是旧的——**不会报错，静默失效**，最难查。所以 evict 和 cacheable 的 `cacheNames` + `key` 拼法必须完全一致（本项目都是 `product::id`）。

---

## L6 JWT 登录鉴权

**1.（★★★）JWT 是什么？由哪三部分组成？为什么能防篡改？**
参考要点：三段 `base64url(header).base64url(payload).signature`。header 存 `alg`/`kid`，payload 存 `sub`/自定义 claim/`iat`/`exp`，signature 是对前两段的签名。防篡改靠签名：改任意一个字符，验签就对不上。注意 payload 只是 Base64URL 编码，**不是加密**，任何人都能解开看，不能放密码等敏感信息。

**2.（★★★）为什么用 RS256 而不是 HS256？JWKS 是干什么的？**
参考要点：HS256 是共享密钥——签发方和验签方用同一个 secret，任何验签方都能伪造 token，微服务里一人泄露全盘皆输。RS256 是非对称：**私钥只留在用户服务签名**，其他服务只用公钥验签，公钥泄露也无法伪造。JWKS（RFC 7517）是把公钥按 `kid` 公布成标准 JSON 的端点，密钥轮换时验签方按 `kid` 自动选新钥匙，不用改代码、不用重新部署。

**3.（★★★）请求进来后，验 token 的完整链路是什么？ThreadLocal 为什么要 clear？**
参考要点：拦截器 `preHandle` 读 `Authorization: Bearer` → 去掉前缀 → 按 header 里的 `kid` 从 JWKS 取公钥（本地缓存，首次懒加载）→ jjwt 验签名和 `exp` → claims 还原成 `LoginUser` → 存入 `UserContext`（ThreadLocal）→ Controller 用 `@CurrentUser` 直接拿 userId → 请求结束 `afterCompletion` 调 `clear()`。Tomcat 线程池复用线程，不清就会把上一个请求的用户身份带给下一个请求，属于严重串号事故。

**4.（★★）密码为什么用 BCrypt，不用 MD5/SHA？盐有什么用？**
参考要点：MD5/SHA 是快速哈希，GPU 每秒能试上亿次，加彩虹表基本等于明文；BCrypt 故意慢（强度 10 = 2^10 轮）且自带随机盐，同一密码每次哈希都不同，彩虹表失效、批量破解成本极高。哈希不可逆，登录只能 `matches(明文, 哈希)` 重新算一遍比对，不能"解密"。

**5.（★★）用户不存在和密码错误，为什么返回同一个错误码？**
参考要点：防用户名枚举。如果"用户不存在"单独返回 1001，攻击者可以批量试用户名，先摸清哪些账号存在，再做定向撞库。统一返回"用户名或密码错误"，让两种失败对外不可区分。

**6.（★★★）微服务之间怎么传递用户身份？异步调用会有什么问题？**
参考要点：用户身份在 `UserContext`（ThreadLocal）里。L6 是 Feign 的 `RequestInterceptor` 把原始 token 加到下游 `Authorization` 头，下游再验一遍；L7 接入网关后改为透传网关注入的 `X-Mall-User-Id/Username/Role`，下游不再验 token，只校验角色。ThreadLocal 只在当前线程有效：Feign 默认同步调用没问题，一旦换成线程池/`@Async`/`CompletableFuture`，子线程读不到，需要显式传递或做上下文装饰。

**7.（★★）JWT 是无状态的，那"退出登录"和"token 被盗"怎么处理？**
参考要点：服务端不存 session，签发出去的 token 在 `exp` 前始终有效，无法单个作废。常见方案：① 缩短 TTL + refresh token；② Redis 黑名单存 jti，验签时多查一次；③ 改密码/角色时给用户一个 `tokenVersion`，token 里带上旧版本就失效。本项目从简，只靠短 TTL。

**8.（★★）RBAC 怎么实现的？角色改了旧 token 为什么还生效？**
参考要点：用户表加 `role` 字段，签发时把 role 写进 claims；Controller 方法标 `@RequireRole("ADMIN")`，拦截器用 `HandlerMethod` 反射读注解，比较 `user.getRole()`，不匹配抛 4030。role 是签发那一刻的快照，改库后旧 token 在过期前仍是旧角色——要立即生效只能重新登录或校验版本号。

**9.（★★）401 和 403 有什么区别？你的项目为什么 HTTP 状态码还是 200？**
参考要点：401 = 没登录/凭证无效（本项目 4010/4011），403 = 登录了但没权限（4030）。项目沿用全工程约定：HTTP 一律 200，成功失败看 body 里的 `code`，前端只需要一套解析逻辑；代价是不符合 REST 语义，网关/监控要按 body 判断。

---

## L7 Gateway 网关

**1.（★★★）为什么要加网关？它和 Nginx 有什么区别？**
参考要点：网关把"前端要记 N 个端口、每个服务各写一遍鉴权/限流/跨域"收口成一个入口（9000），按路径路由到不同服务，鉴权、限流、CORS 只做一次。Nginx 偏流量入口和静态资源，规则偏运维、改配置要 reload；Spring Cloud Gateway 是应用内网关，能写 Java 过滤器、集成注册中心和业务鉴权，适合做业务级统一入口。生产常见是 Nginx 在最外层，Gateway 在内层。

**2.（★★★）Gateway 为什么用 WebFlux？误引 `spring-boot-starter-web` 会怎样？**
参考要点：Gateway 基于响应式（Netty）实现，少量线程扛高并发 IO，不能阻塞。`spring-boot-starter-web` 会引入 Servlet/Tomcat 和 WebMVC，与 WebFlux 两套容器冲突，启动失败或行为异常。所以网关模块只能用 `spring-cloud-starter-gateway-server-webflux`，业务服务的 MVC 代码（拦截器、`@RestControllerAdvice`）也不能直接复用。

**3.（★★）路由三要素是什么？`lb://` 和 `StripPrefix` 各解决什么问题？**
参考要点：`id`（路由名）、`uri`（目标地址）、`predicates`（匹配条件，如 `Path=/api/product/**`）。`lb://product-service` 表示不写死 IP，由 LoadBalancer 从 Nacos 取实例并负载均衡；`StripPrefix=1` 去掉对外多出来的 `/api` 第一段，让下游 Controller 路径保持不变。

**4.（★★★）网关鉴权的完整链路？为什么角色校验还留在服务里？**
参考要点：`AuthGlobalFilter`（order -100）先判白名单（登录/注册/JWKS、商品 GET/HEAD、OPTIONS）→ 读 `Authorization: Bearer` → `JwtVerifier` 按 kid 取公钥验签 → **先删客户端伪造的 `X-Mall-*` 头，再写入验签后的三个身份头** → 放行。服务端 `GatewayAuthInterceptor` 只读三个头重建 `LoginUser`，再做 `@RequireRole` 校验。角色校验不能上移：网关只看 URL，看不到 Controller 方法上的注解。

**5.（★★★）下游无条件信任 `X-Mall-User-*` 有什么风险？生产怎么防？**
参考要点：任何人只要能直连服务端口，就能自己伪造这三个头冒充任意用户，网关验签完全绕开。生产做法：① 服务只开内网，安全组/NACL 只允许网关访问；② 网关额外加服务间共享密钥头，服务先校验密钥再信任身份头；③ 服务间 mTLS。本地项目接受该风险，但面试要能说清边界在哪。

**6.（★★）网关限流怎么实现？`KeyResolver` 和令牌桶参数分别是什么含义？**
参考要点：`RequestRateLimiter` + reactive Redis，`RedisRateLimiter` 用 Lua 在 Redis 里维护令牌桶。`KeyResolver` 决定"账记在谁头上"：本项目优先 `user:<id>`，未登录用 `ip:<地址>`。`replenishRate` 每秒补多少令牌，`burstCapacity` 桶最多攒多少（瞬间并发上限），`requestedTokens` 每请求消耗多少。按路由分桶，product 和 order 的额度互不影响。

**7.（★★）限流器拒绝时只返回空 429，怎么变成统一的 `code=4290`？**
参考要点：`RequestRateLimiter` 拒绝时设 429 后直接 `setComplete()`，不写响应体。`RateLimitResponseFilter`（order -90）用 `ServerHttpResponseDecorator` 包住响应，在 `setComplete()` 被调用时检查状态 429，改成 200 并写入 `Result` JSON。必须在响应提交前拦截，提交后再写会报错。

**8.（★★）网关的 404 和 503 怎么统一成业务格式？为什么不能用 `GlobalFilter`？**
参考要点：自己实现 `ErrorWebExceptionHandler`（`@Order(-2)`，早于 Boot 默认的 -1），把 `ResponseStatusException` 404 映射成 `4040`、`NotFoundException` 503 映射成 `5030`，写成 `Result` JSON。404 发生在"路由还没匹配"的阶段，`GlobalFilter` 属于路由过滤器链，根本不会执行，所以只能在异常出口接。

---

## L8 MySQL 优化

**1.（★★★）一条"按用户查订单、按时间倒序、分页"的 SQL 为什么会慢？怎么优化？**
参考要点：没有合适索引时 `type=ALL` 全表扫，`ORDER BY create_time` 触发 `Using filesort`，`LIMIT offset,size` 还要丢弃前 offset 条。优化：建联合索引 `(user_id, create_time)`，等值列在前、排序列在后，让索引同时完成过滤和排序；EXPLAIN 里 `type` 变 `ref`、`key` 命中索引、`Extra` 的 `Using filesort` 消失。再深的分页用延迟关联或游标分页。

**2.（★★★）联合索引的列顺序怎么定？`(user_id, create_time)` 和 `(create_time, user_id)` 有什么区别？**
参考要点：最左前缀原则——索引按第一列、第二列依次有序。查询是 `where user_id=? order by create_time`，所以 `user_id` 在前才能先定位到该用户的连续区间，且区间内 `create_time` 天然有序，排序免掉。反过来的索引第一列是时间，无法用 `user_id` 定位，只能扫全索引或全表，排序也用不上。经验：等值条件列在前，范围/排序列在后。

**3.（★★★）什么是 filesort？为什么加了索引就没有了？**
参考要点：`filesort` 不是"用磁盘文件排序"，而是 MySQL 无法利用索引顺序，必须把结果取出来再排一次。联合索引 `(user_id, create_time)` 中，同一 `user_id` 下的记录按 `create_time` 有序，范围扫描直接按索引顺序读出来就是结果顺序，不需要额外排序。注意 `ORDER BY` 的方向要与索引一致（本项目倒序，MySQL 5.7 会反向扫描索引）。

**4.（★★★）深翻页 `LIMIT 79990,10` 为什么慢？延迟关联和游标分页分别怎么解决？**
参考要点：offset 越大，数据库要先取出并丢弃前 79990 条（有索引时是扫索引项 + 回表）。延迟关联：子查询只查主键 `select id ... limit 79990,10`，再拿这 10 个 id 回表，回表次数从 8 万降到 10；游标分页：记住上一页最后的 `(create_time,id)`，用 `where (create_time,id) < (?,?) limit 10` 直接定位，代价恒定。游标分页缺点：只能下一页、不能跳页，且必须有唯一稳定的排序键。

**5.（★★★）MySQL 分区表怎么按月分？有什么限制和代价？**
参考要点：`PARTITION BY RANGE COLUMNS(create_time)`，每月一个分区，末尾留 `pmax` 兜底；查询条件带 `create_time` 范围时触发分区裁剪（`EXPLAIN PARTITIONS` 只显示命中分区）。限制：**每个唯一键（含主键）都必须包含分区列**，所以主键要改成 `(id, create_time)`；分区列不能是 `NULL` 语义不清的表达式。代价：按 `id` 单查不带分区列时会打开所有分区；分区数不宜过多；`ALTER TABLE ... ADD PARTITION` 要提前做，不能等数据写不进去。

**6.（★★）MySQL 5.7 没有 `EXPLAIN ANALYZE`，怎么证明优化真的生效？**
参考要点：`EXPLAIN` 的 `rows/filtered` 是估算值，不能当实际值。5.7 用 `SHOW SESSION STATUS LIKE 'Handler_read%'`：`Handler_read_rnd_next` 高说明全表扫，`Handler_read_next` 才是有序索引扫描；配合 `SET profiling=1; SHOW PROFILES;` 看每条 SQL 的真实耗时。8.0 才有 `EXPLAIN ANALYZE`。

**7.（★★）为什么 `ORDER BY` 要补一个 `id`？**
参考要点：`create_time` 是秒级 `datetime`，同一秒可能有多条，排序键不唯一时分页会出现重复或漏行（数据库对相等值的顺序不保证）。补 `id desc` 让排序键唯一；联合索引 `(user_id, create_time)` 的二级索引尾部隐含主键，所以 `create_time desc, id desc` 仍能利用索引顺序。

**8.（★）10 万条测试数据怎么快速造？**
参考要点：建 0~9 的数字表，五表交叉连接得到 0~99999 的序列，再用 `INSERT ... SELECT` 配合 `DATE_ADD` 生成跨月时间，一条 SQL 完成，不需要存储过程。注意让重点用户的数据量足够大，否则深翻页测不出效果。

---

## L9 并发与一致性

**1.（★★★）为什么要给下单加线程池？两个池怎么分级？**
参考要点：原来两次 Feign 在 Tomcat 线程上串行执行，慢依赖会占满 Tomcat 线程。分两个池：`remoteExecutor`（在线请求，8/16/64，有界队列 + AbortPolicy，队列满快速失败返回 4291）；`compensateExecutor`（库存回补，1/2/32，CallerRunsPolicy，任务不能丢，池满退回调用线程执行）。分级的目的是按任务重要性隔离，一个慢依赖不能同时打满补偿任务。

**2.（★★★）为什么线程池必须用有界队列？无界队列有什么问题？**
参考要点：无界队列（默认 `Integer.MAX_VALUE`）会把任务无限堆在内存里，响应时间被越拖越长，最终 OOM，等于没有反压。有界队列 + 拒绝策略才是背压：接不住就明确拒绝，让上游降级或重试，而不是静默积压。

**3.（★★★）`CompletableFuture` 在你项目里怎么用的？`orTimeout` 和 Feign 超时是什么关系？**
参考要点：用户、商品两个查询互不依赖，用两个 `supplyAsync` 提交到同一个池并行，`thenCombine` 等齐后组装。`orTimeout(2000ms)` 是外层总等待上限，Feign 的连接/读超时是单次调用的超时；谁先到就返回谁。停 product 时实测有时返回 2004（orTimeout 先到）、有时 2003（连接失败先到），这是两层超时竞速。

**4.（★★★）`orTimeout` 到点会取消底层任务吗？有什么风险？**
参考要点：不会。它只让等待方拿到 `TimeoutException`，底层 Feign 调用继续跑。本项目两个调用都是只读，没有副作用；如果底层是扣库存这类写操作，就会出现"调用方认为失败、实际已成功"的不一致，需要幂等 + 对账。

**5.（★★★）异步线程里为什么必须显式传递 `UserContext`？**
参考要点：`UserContext` 是 ThreadLocal，只对当前 Tomcat 线程有效。Feign 的 `FeignAuthInterceptor` 从 `UserContext` 取 `X-Mall-*` 身份头；换到线程池线程后 ThreadLocal 为空，下游收到空头会返回 4010。做法：提交任务前取 `LoginUser` 快照，worker 里 `set`，`finally clear`（线程复用，不清会串号）。

**6.（★★★）号段发号器怎么实现的？为什么比每单 `INCR` 快？代价是什么？**
参考要点：Redis `INCRBY mall:order:seq step` 一次领一段（本项目 step=1000），本地用 `AtomicLong` CAS 发号，段用完再进 `synchronized` 双重检查换段。大部分请求不访问 Redis，所以快。代价：进程重启会丢掉当前段没发完的号（号段空洞）；删 Redis key 会从 1 重发，和历史订单号重复；多实例各自领段，不重号但都会浪费段尾。

**7.（★★★）Redis 扣库存为什么要用 Lua？`GET` 再 `DECR` 为什么不行？**
参考要点：`GET` 判断和 `DECR` 是两条命令，中间可插入其他请求；两个请求都读到 1 就会扣成 -1。Lua 在 Redis 单线程里一次执行"读 + 判断 + 扣减"，天然原子。脚本返回剩余库存、-1 库存不足、-2 key 不存在。

**8.（★★★）既然 Lua 已经判断了库存，为什么数据库还要 `where stock >= num`？**
参考要点：Redis 可能丢数据、被清空、初始值不对，不能当唯一真相。数据库条件更新由行锁保证，是防超卖的最后防线；更新 0 行时会把 Redis 加回去并返回 1006，避免少卖。Redis 不可用时也直接退化为数据库条件扣减，仍然不会超卖。

**9.（★★★）扣了库存但订单落库失败怎么办？补偿失败又怎么办？**
参考要点：`insert` 包 try-catch，失败后把 `releaseStock` 提交到补偿池回补库存，再返回 5000。补偿池用 `CallerRunsPolicy` 保证任务不丢；如果 product 也挂了导致补偿失败，只能记 error 并人工对账。这是没有分布式事务时的最终兜底，不是强一致。

**10.（★★）你的 L9 验收数据是什么？**
参考要点：200 并发抢 100 库存 → `ok=100 / 库存不足=100 / 超时=0 / 池满=0`，DB 和 Redis 库存都归零、无负数；108 个非空订单号去重后仍 108 个；`slow-ms=4000` 时 2.03 秒返回 2004 且不落单；小池 1/1/1 五并发出现 3 个 4291；金额溢出触发落库失败后库存从 2 亿回补到 2 亿。**要主动说缺口**：扣减超时会少卖、补偿失败要人工、号段依赖 Redis。

---

## 待补楼层

- L10 及以后：逐层追加
