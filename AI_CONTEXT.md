# mini-mall 开发上下文文档（学习优先版）

> 本文档供 AI 助手在**对话轮数过多、需要重启对话**时唤醒记忆，**只定规则和沉淀知识，不记录当前开发进度**。开发进度、遗留清单、下一步计划一律记录在 `DEV_LOG.md`（与本文档同目录）。恢复上下文时：先读本文档（规则+知识），再读 DEV_LOG.md（进度）。
> 最后更新：2026-11-22（新增铁律 11「全链路分支讲解法」与铁律 12「`<<<>>>` 标记处理」；修订铁律 4：框架由"完全不讲"改为"只讲动作级"）

## 〇、文档使用规则（AI 助手必须遵守的铁律）

1. **学习优先讲解法**：讲解/实现任何功能时，顺序永远是——**"怎么写 → 为什么 → 项目对照"**。用户的核心目标是面试手撕代码：面试官不会让他默写这个项目，而是临时出题，所以他需要的是**可迁移的写法套路**（先自问"我需要什么"，再落笔），不是对本项目代码的死记硬背。禁止一上来就贴成品代码；禁止建议无脑背诵，口诀只是简化理解过程的记忆锚点。
2. **文档分工**：`AI_CONTEXT.md` 只定规则、沉淀知识内核（默写模板等），**不写进度状态**；`DEV_LOG.md` 记录开发进度与用户理解确认。**开发进度条目**（做了什么、遗留什么、下一步）AI 可随时追加；**理解确认条目**只在用户明确说出"理解了，记录吧"（或同等明确表态）后才允许写入。
3. **每完成一段开发，更新 DEV_LOG**：进度、遗留、下一步随开发同步写入 DEV_LOG.md；每通关一层，把该层默写模板沉淀到本文档第三节知识内核。
4. **只讲用户代码，框架只讲动作（2026-11-22 修订，以此条为准）**：
   - 讲解主线永远是**数据在用户自己写的代码里怎么走**：哪行代码、变量叫什么、值是多少、从哪来、到哪去（配合断点验证）。
   - **框架只讲"动作级"**：它替用户做了哪几步、这些步骤对数据有什么影响。**不贴框架源码、不展开框架类层级**。
   - **只有"对数据有影响"的框架环节才点类名**（如 `CacheInterceptor` 决定查/写 Redis），并标注归属（本项目 / 哪个框架）；对数据无影响的内部细节（注解解析器、消息转换器内部实现）**一笔带过，不点名**。判断标准：读者不知道这个类名，会不会看不懂数据走向？不会，就略过。
   - **一次只讲一个点**，信息量宁小勿大；**不打比方、不起外号**（禁止"门卫""本尊"这类代称），直接走链路、直接用真实类名。
   - 框架的常见存在形式 = 帮用户完成的几类动作：启动时造对象并塞字段（注入）、请求时把参数塞给方法、把返回值转成 JSON 发回浏览器、按注解改变方法行为（缓存/限流/事务等）。
5. **每个模块必须有新知识**（2026-11-11 用户明确要求）：不允许某个模块只是重复基础增删改查。进入一个模块前，AI 先讲清该模块计划安排的新技术点清单，由用户确认后再动手；模块开发过程围绕这些新技术点展开。
6. **每阶段收尾必须提交 GitHub**（2026-11-18 用户明确要求）：
   - 每完成一个阶段（阶段 0 基线 / 阶段 A 微服务链路 / 阶段 B 硬核专题 / 阶段 C 收尾），必须执行一次完整的 GitHub 提交：`git add` → `git commit` → `git push origin main`。
   - 提交前必须先把本阶段进度写入 `DEV_LOG.md`（完成什么、验收证据、遗留、下一步、当前楼层），**DEV_LOG.md 与代码在同一个提交里一并提交**，保证历史中"代码 + 进度"成对出现。
   - 提交前必须 `mvn install -q -DskipTests` 编译通过；提交信息按 `feat: 阶段X xxx` / `docs: xxx` 格式，中文。
   - **留痕粒度（2026-11-18 用户确认）**：每个可验收单元一个 commit（如新增 DTO、接入编排、容错处理各一次），DEV_LOG 按楼层汇总；留痕载体只认 **Git diff + DEV_LOG**，不额外维护 CHANGELOG 或每次会话的变更文件。
   - 阶段内的楼层可增量提交，但阶段收尾这一次提交不可省略；提交后确认 `git status -sb` 干净且与 origin/main 一致。
7. **大阶段完成自动提交（2026-11-20 用户明确要求）**：每完成一个大阶段（如 L2→L3 这类楼层组收尾），AI **自动执行** Git 提交并推送到 GitHub，**不需要用户提醒**；提交前同样先 `mvn install` 编译通过、先更新 DEV_LOG，规则同铁律 6。
8. **interview.md 同步维护（2026-11-20 用户明确要求）**：每次更新 DEV_LOG 开发记录（落库）时，**同步**在仓库根目录 `interview.md` 追加对应楼层的面试题；**题量按面试高频程度增减**——高频考点（★★★）多写几题并给参考要点，中频适量，低频（★）一笔带过或不写；题目只给要点不写长答案，供用户口头自测。
9. **Bug 追踪必须逐步带学（2026-11-21 用户明确要求）**：遇到环境/服务报错时，AI 必须带用户**一步一步**排查：每步讲清「在哪个窗口/文件看 → 看哪一段 → 这段输出说明什么 → 下一步查什么」，**禁止跳步、禁止直接甩结论**；IDEA 控制台的输出日志也是排查现场，必须带用户逐段读。每次 bug 追踪的完整过程记录到 `v1/docs/binlog/BUG追踪.md`（一次 bug 一个条目：现象 → 排查步骤含命令与输出 → 根因 → 修复 → 预防）。
10. **框架固定 vs 自己动手的区分法（2026-11-21 用户确认的核心读码思想）**——唯一目的：**辅助用户能写项目 + 面试能流畅讲明白**，其他都是手段：
   - **适用边界（2026-11-21 用户明确补充，不得遗忘）**：本条只服务"读码 / 写码"阶段；**讲给面试官听的输出永远是数据流向链路**——按 DEV_LOG 〇节讲解规则（数据从哪来 → 经过哪一行 → 变成什么 → 传到哪去）讲，分桶表格不是面试答案，链路才是。两者并立：分桶管"写"，链路管"讲"。
   - 读任何框架相关代码，先分成两类：
     - **框架固定的**（注解、框架 API、Builder 链、模板配置）：**不需要过多思考**——会照抄 + 能一句话说清"它改变了什么行为"即可；禁止深究词源、原理、源码。
     - **自己写的**（自己起的名字、自己定的数值和策略）：**唯一的思考对象**——每个"自己定的格子"都必须能回答"为什么是这个值"。
   - **面试口径由分类直接生成**：自己定的格子 = 面试考点（如 TTL 为什么 30 分钟、为什么 JSON 序列化、null 缓不缓存的权衡）；框架固定的 = 面试只需一句话说作用。验收时 AI 按此出题。
   - 卡壳时的辅助读法（服从上面的核心分类，不展开成新理论）：看 `()` 写在哪份代码里判"谁给谁"（参数从调用方流向被调方，返回值流回调用点；框架可顺走返回值一份）；链式调用拆行平铺读（中间返回值是接力棒，只有成品有意义）；疑问按"身份级 → 流动级 → 细节级"排序，细节级不挡路就不解决。
11. **全链路分支讲解法（2026-11-22 用户确认，此后所有讲解的默认风格）**——解决"基础薄弱时跟不上"的核心方法：**让用户读文档时只需要顺着数据往下走，不需要回忆上下文、不需要猜数据、不需要二次翻译**。写任何 LEARN 文档、功能讲解、debug 复盘都必须按此执行：
   1. **以一条真实命令为轴**：写清 `方法 + 路径 + 参数的真实值`（如 `GET /product/2`），不写抽象例子。
   2. **分岔先行**：先把"这条命令一共几种结局、分岔点在哪一行、由哪个值决定"列成一张表，再逐个结局走。禁止讲到一半才发现还有别的路。
   3. **数据编号全程跟踪**：给每条被追踪的数据编号 `[D1]`~`[Dn]`，每一步都写 `当前值 · 来源 · 去向`；数据每变一次形状（对象→JSON、String→Long）都必须交代"是不是同一条数据、不是的话从哪来"。
   4. **四标注**：每步用【你写的】/【注解】/【框架动作】/【数据】四类，缺一不可。
   5. **定位到行**：类或方法第一次出现，必须给 `文件路径 + 行号 + 代码原文`；方法名永远带所属类（`ProductServiceImpl.getById`，不能只写 `getById`）。目的：用户不用离开页面去猜"是哪个 Controller"。
   6. **真实名称、标归属**：禁用比喻和外号，统一真实类名，并注明"本项目 / 哪个框架"（如 `CacheInterceptor`（框架：Spring Cache））。
   7. **禁止省略式描述**：不许写"有值/没值/处理一下就返回了"，必须写清是哪个变量、值具体是什么（`null` / 哪段 JSON / `NullValue` 标记）。
   8. **调用关系不能断**：文档开头放一张**调用链表**（谁、哪一行、干了什么）；每次"返回/交回"都要写明回到哪个类的哪一行、返回值给谁用。
   9. **可复用即引用**：与前面分支相同的步骤写"同分支①第 X 步"，不重复展开，避免注水。
   10. **双通道验证**：每篇末尾给断点验证（在哪停、看什么变量、看到什么证明走了这条路）+ 3 道自测题（不附答案）。
   11. **自检四问**（用户读完应能不看文档回答）：几种结局？分岔在哪一行？每个数据从哪来到哪去？哪一行没执行能证明走了哪条路？
12. **`<<< >>>` 标记的处理方式（2026-11-22 用户约定）**：用户会在文档里插入 `<<<问题或意见>>>` 作为就地提问/修改指令。
    - **归纳而非硬套**：先读完全部标记，提炼出背后的**通用规则**，再用规则改全文，而不是只把标记处补一句。
    - **改完要回报**：说明"提炼出了哪几条规则 + 具体改了哪些地方"，让用户判断是否理解到位。
    - 标记中若只是疑问（不涉及改文档），直接回答，并判断是否需要把答案同步进相关文档。
    - 用户表示后续会继续用这种方式提问，属常态交互，不是异常格式。

## 一、项目一句话定位

- **性质**：**面试准备项目**（求职 Java 后端方向），从零搭建的微商城（mini-mall），目标是用户**熟悉到自己能上机手写**全链路代码，并能向面试官讲清微服务架构。**所有代码以"用户自己能写出来"为验收标准，AI 尽量不代写，只讲解 + review**
- **仓库**：`https://github.com/3314117689/mini-mall.git`（分支 `main`）
- **本地路径**：`D:\bishe\workspace\gongju\eclipseworkspace\mini-mall`（git 仓库根），代码在 `v1/` 子目录
- **IDE**：IntelliJ IDEA 2026.3（安装目录 `D:\IDEA\IntelliJ IDEA 2026.3\bin`），2026-11-18 起由 Eclipse 切换而来
- **技术栈**：Spring Boot + **原生 MyBatis**（mybatis-spring-boot-starter，手写 @Select/@Insert/@Update/@Delete 注解 SQL）+ MySQL + Lombok。**已决定不引入 MyBatis-Plus**（手写 SQL 更利于学习，原"引入 MP"计划作废）
- **架构约定**：Controller → Service → Mapper 三层；统一返回 `Result<T>`（code=0 成功）；每个服务独占自己的数据库（db_user / db_product…），服务间将来通过 HTTP 调用，**不许直连对方库**

## 二、盖楼路线图（项目主结构与学习顺序）

> 组织原则：每一层都是因为"上一层解决不了某个新问题"才出现。建楼顺序 = 学习顺序 = 依赖顺序。**每层必须有明确的新技术点**（铁律 5），不做重复的 CRUD。
> 技术栈总纲：**Spring Boot 单体 → Spring Cloud Alibaba 微服务**。全家桶 = Nacos（注册）+ OpenFeign（调用）+ Sentinel（熔断限流）+ Gateway（网关）+ Redis（缓存）+ JWT（鉴权）。
> 约束：**代码量必须可控**——砍掉 MQ / 分布式事务 / Docker / 分布式锁 / Nacos Config（配置中心）这些重量级组件，每层保证 1-2 个 session 能推进完。
> **当前建到哪一层、各层验收状态，见 DEV_LOG.md，本表不记进度。**

### 版本映射（开发前统一对齐）

> 2026-11-20 已查表确认（以官方发布说明为准，升级时重新查）：
> - Boot **4.1.2** → Spring Cloud **2025.1.4**（Spring 官方发布说明明确支持 4.1.2） → Spring Cloud Alibaba **2025.1.0.0**（官方只写适配 Boot 4.0.x，4.1.2 未官方验证，属已知风险）
> - Nacos Server **3.x**（当前 3.3.0）：服务端端口 8848；3.x 控制台端口 8080
> - 父 POM 只导 Cloud BOM；SCA 只给 starter 写显式版本，**不要整包导 SCA BOM**（其 BOM 钉 Cloud 2025.1.0，会把 2025.1.4 顶回去）
> - 兜底方案：SCA 在 4.1.2 上跑不通，全工程降到 Boot 3.5.x + Cloud 2025.0.x + SCA 2025.0.0.0（业务代码基本不用改）

### 分层路线图

| 层 | 模块 | 触发问题 | 新技术点（面试高频） | 代码量级 | 验收标志 |
|---|---|---|---|---|---|
| **L0 地基** | mall-common + mall-user + mall-product | 单个服务增删改查 | 三层架构五件套 + MyBatis 注解 SQL + Lombok + Maven 多模块 | 大（已完成） | 五件套能白纸默写；两个服务 CRUD 验收通过 ✅ |
| **L1 异常框架** | mall-common（共用） | 错误响应散乱 | Result / ErrorCode / BizException / GlobalExceptionHandler 四件套 | 极小（4 个类） | 原理讲透 + 默写 3 遍 ✅ |
| **L2 订单服务 + RestTemplate** | mall-order（新模块） | 订单需用户+商品，但不许连别人的库 | **RestTemplate** HTTP 调用 + DTO 请求对象 + Service 业务编排 + 远程调用失败容错 | 中 | `POST /order` 落库成功；关掉 mall-product 下单能看到友好失败 ★微服务成立门槛 |
| **L3 Nacos + OpenFeign** | 所有服务改 pom + yml | RestTemplate 地址写死，手写拼 URL 容易错 | **Nacos 注册中心**（解压运行+服务注册）+ **OpenFeign** 声明式调用（一个接口一个注解，比 RestTemplate 简洁）+ `@EnableFeignClients` + `@FeignClient` | 极小 | Feign 接口调 mall-product，代码里不再出现 `localhost:8082`，换成服务名；关掉 mall-product 下单不再报错而是走 Feign 的熔断降级 |
| **L4 Sentinel 熔断限流** | 所有服务加依赖 + yml | 下游服务挂了上游跟着死（雪崩）；恶意请求把服务打垮 | **Sentinel**（解压运行+控制台）+ `@SentinelResource` 资源标记 + 降级方法 `fallback` + 流控规则（QPS 阈值） | 极小 | mall-product 接口限流到 QPS=10，快速连续请求第 11 次被 Sentinel 拦截返回提示；关掉 mall-product，mall-order 走 fallback 降级方法返回"商品服务不可用，请稍后重试" |
| **L5 Redis 缓存** | mall-product 改 Service | 商品列表每次都查 MySQL，并发高了扛不住 | **Redis 安装**（Windows 版下载解压）+ Spring Data Redis + `@Cacheable` 注解自动缓存 + 缓存穿透/空值处理 | 小 | 第一次 `GET /product/list` 查 MySQL 写入 Redis，第二次直接返回缓存（断点验证不进 Mapper） |
| **L6 JWT 登录鉴权** | mall-user 补注册登录 | 谁在下单？怎么识别用户？ | **JWT（jjwt，RS256 私钥签名 + JWKS 公钥端点）** + **BCrypt** 密码加密（存哈希不存明文）+ 拦截器统一验 token + **ThreadLocal** 存当前用户上下文 + 角色字段做 RBAC | 中 | 登录拿 token → 下单请求 Header 带 token → 拦截器解析出 userId → Controller 直接注入当前用户（不重复解析）；改 token 后验签失败 |
| **L7 Gateway 网关** | mall-gateway（新模块） | 前端要记 N 个端口，鉴权散在每个服务 | **Spring Cloud Gateway** + RouteLocator 路由配置 + 全局过滤器（统一验 JWT）+ Gateway 内置限流（RequestRateLimiter） | 中 | 前端所有请求只打 `9000`（gateway 端口），路由到对应后端；gateway 层统一验 token，各后端服务可跳过鉴权 |
| **L8 MySQL 优化专题** | mall-order 表 + SQL | 数据量大后深翻页慢、排序走 filesort、历史数据拖慢查询 | **联合索引防 filesort**（explain 前后对比）+ **延迟关联 vs 游标分页** + **按月 RANGE 分区** + 造 10 万条测试数据 | 小 | explain 能指出 type/key/Extra 的变化；深翻页耗时明显下降；分区裁剪命中 |
| **L9 并发与一致性** | mall-order / mall-product | 高并发超卖、串行远程调用慢、ID 生成 | **线程池分级 + 反压**（拒绝策略）+ **CompletableFuture 编排 + orTimeout 超时降级** + **号段发号器**（Redis INCRBY + AtomicLong/CAS）+ **Redis Lua 原子扣减防超卖** | 中 | 并发压测不超卖；依赖超时走降级；订单号来自号段；线程池满时有兜底 |
| **L10 SpringDoc + JSR303** | 所有服务加依赖 | 接口文档手写容易过时，入参校验散在业务代码 | **SpringDoc OpenAPI**（加依赖自动生成 Swagger UI）+ `@Valid` + `@NotBlank/@Min/@Size` 注解校验 | 极小 | 打开 `/swagger-ui/index.html` 能看到接口文档并在线调试；不传必填参数返回友好错误 |

### 选做层（最小单元，主路线通关后按余量逐项加，每项独立可演示）

| 编号 | 内容 | 最小单元 | 验收 |
|---|---|---|---|
| M1 | MongoDB 订单快照 | 下单成功后写一条 order_snapshot 文档；`GET /order/{id}/snapshot` 查询 | 文档结构与订单表字段对齐，能讲清"为什么要文档建模" |
| M2 | RabbitMQ 异步下单 | 下单落库后发 `order.created` 消息，消费者异步写通知记录 | 生产/消费日志可验证，关掉消费者不影响下单主流程 |
| M3 | Sa-Token RBAC | 二选一：用 Sa-Token 替换 L6 自研鉴权，`@SaCheckRole` 保护管理接口 | 无角色访问管理接口返回无权限；与自研方案能讲清差异 |
| M4 | ShardingSphere 读写分离 | 配置 ShardingSphere-JDBC 主从数据源（前置：MySQL 主从复制可用） | 写走主库、读走从库，日志可验证；环境不满足则只讲配置与原理 |

### 三条关键线

- ★ **微服务成立线**（L2）：服务间 HTTP 协作、不共享数据库——最低门槛
- ★ **Spring Cloud Alibaba 全家桶线**（L3+L4）：Nacos + Feign + Sentinel——简历上"熟悉 Spring Cloud Alibaba"的硬指标
- ★ **面试标准线**（L3+L7）：注册中心 + 网关——面试官不会问"这和单体有什么区别"

### 踩坑速查（按楼层归类，新增坑到此追加）

- L0：包少建一层导致 Mapper 扫描不到（必须 `com.mall.<服务名>.mapper`）；判空逻辑写反；表结构与代码不一致（SQL 永远写数据库真实列名）；金额用 BigDecimal；改多模块文件时曾改串 yml
- L1：暂无
- L3 预踩：**Nacos Server 版本必须 3.x**（2.x 及以上客户端连不上 1.x 服务端）；3.x 服务端端口 8848、**控制台端口 8080**；3.3 默认开启鉴权，本地可关闭，但 `startup.cmd` 仍会交互式追问 token 密钥，后台启动需先在 `conf\application.properties` 预置密钥；3.3 默认加载 AI 插件会扫描 PATH，遇 `\\?\` 前缀路径崩溃，用 `-f microservice` 规避
- L3 预踩：**Spring Cloud BOM 版本号别写错**（2023.0.0 而非 2023.0.0.0）；父 POM 中 spring-boot-maven-plugin 必须放到 `<pluginManagement>` 里统一版本，避免 mall-common 这类库模块被 repackage 后 install 失败（common 不需要打 executable jar）
- L3 预踩：**Gateway 用 WebFlux**，业务服务仍用 WebMVC——Gateway 模块别引 `spring-boot-starter-web`，会冲突
- 环境：Eclipse 时期 Ctrl+点击跳转失效，2026-11-18 已切换到 IDEA，该问题不再关注

## 三、"怎么写"知识内核（面试手撕核心，本档最重要章节）

> 使用方法：白纸默写 → 只对照口诀 → 卡壳处标记 → 写完与项目源码比对 → 默写 3 遍全对为过关。每通关一层在此追加该层模板。

### 3.1 三层架构五件套（L0）

**默写顺序（依赖顺序）**：Entity → Mapper → Service 接口 → ServiceImpl → Controller

**全链口诀**：
> Entity 是表格，Mapper 只搬砖；
> Service 立契约，Impl 真正干；
> Controller 门卫瘦三行，接参调用包返回。

**每层"自问 → 写什么 → 为什么"精要**：
1. **Entity**：自问"表的一行数据用什么装" → 一个类，列=字段，`@Data` 生成 getter/setter → 列名下划线↔字段驼峰自动映射；类是空壳，它是全层共用的运输工具
2. **Mapper**：自问"SQL 写哪、谁执行" → 接口 + 注解绑 SQL，`@Mapper` 让 Spring 生成动态代理 → 一个方法=一条 SQL；`#{}` 是预编译占位符防注入（`${}` 才是拼接）；查询返对象（查不到=null），写操作返 int 行数
3. **Service 接口**：自问"为什么不直接写 Impl" → 契约与实现分离，Controller 只依赖接口，换实现不改调用方 → 方法名用动词：getById/save/update/deleteById
4. **ServiceImpl**：自问"业务规则写哪" → 全写这里：判空、校验、编排 → `@Service` 入容器；`@Autowired` 注入而非 new（Mapper 是代理对象，new 出来的不受 Spring 管理）；Controller 保持一行式（瘦控制器）
5. **Controller**：自问"HTTP 请求怎么变成方法调用" → 类 `@RestController`+`@RequestMapping("/xxx")`，方法 `@GetMapping("/{id}")` → 只做三件事：接参数、调 Service、包 Result；`@PathVariable` 取 URL 值（GET 查询），`@RequestBody` 取 JSON 请求体（POST/PUT 写操作）

**易漏 5 注解清单**：`@Data`、`@Mapper`、`@Service`、`@Autowired`、`@RestController`（类级 `@RequestMapping` 别忘）

**自测三问**：Mapper 是接口谁实现了它？（MyBatis 动态代理）/ 为什么 Service 要接口？（解耦+可替换+可测试）/ `#{}` 与 `${}` 区别？（预编译防注入 vs 字符串拼接）

**注入心智模型（2026-11-11 用户确认的理解方式，讲解注入时以此为准）**：
1. **一句话核心**：`@Autowired` 注入了就**只管用**，其余全是框架在做。
2. 字段 = 类里的成员变量，只声明不赋值就是 `null`；**没注入就调它 = 空指针异常（NPE）**。
3. 接口（如 ProductMapper）在源码里没有实现类，`new` 不出来——**能用它的唯一方式就是让框架注入**。
4. 两个时刻：**启动期** Spring 替你执行"字段 = 容器里的对象"这行赋值（源码里看不到）；**请求期** 你只写调用语句 `productMapper.selectById(id)` 拿返回值。
5. 每层只注入下一层：Controller 注入 Service，ServiceImpl 注入 Mapper；Controller 里永远看不到 Mapper 字段。
6. 调接口方法 = 直接执行 Impl 里的方法体，一步到位，中间没有"先执行接口"的过程。
7. Controller 的 `return Result.ok(productService.getById(id))` 从里往外执行：先 getById 返回了值，Result.ok 才会被调用；数据不存在时在 ServiceImpl 被 `throw` 拐走，**Result.ok 不执行**，由全局异常处理器返回失败 Result——两条路都给前端统一格式 JSON。

### 3.2 异常体系四件套（L1）

**手写范围**：只需写 4 个类 + 1 行 throw，框架内部（DispatcherServlet 等）不用写。

**默写顺序（依赖顺序）**：Result → ErrorCode → BizException → GlobalExceptionHandler → Service 里 throw

**口诀**：
> 先定出口 Result，再给错误编个号；
> 异常继承 RuntimeException，文案交给 super 保管；
> 哨兵 Advice 两个注解，一个接业务一个兜底；
> Service 里一行 throw，全链从此通。

**每步"自问 → 写什么 → 为什么"精要**：
1. **Result**：自问"前端怎么区分成败" → `{code, msg, data}` + 静态工厂 `ok/fail` → 静态工厂调用方一行造结果；泛型 T 管 data 类型
2. **ErrorCode**：自问"code 从哪来" → 枚举把编号+文案绑成常量 → 防魔法数字散落；号段约定：0 成功 / 1xxx 业务 / 5000 系统
3. **BizException**：自问"怎么把失败扔到最外层" → `extends RuntimeException`，构造器收枚举，`super(msg)` 交给父类存文案，自己存 code → 非受检不污染方法签名
4. **GlobalExceptionHandler**：自问"谁来接" → `@RestControllerAdvice`（哨兵）+ `@ExceptionHandler(BizException.class)`（接业务，warn 一行日志）+ `@ExceptionHandler(Exception.class)`（兜底，error 带堆栈）→ 匹配规则最具体优先；堆栈给开发者、细节不给前端
5. **throw**：`throw new BizException(ErrorCode.XXX)` → 整链闭合：发射→无人 catch 飞行→哨兵接住→变 JSON

**面试必被追问的 3 个速答**：
- 为什么继承 RuntimeException？→ Exception 受检会污染所有方法签名要写 throws；RuntimeException 非受检直接上飞，且业务层本来就不打算接
- 为什么 HTTP 状态码还是 200？→ 全工程约定"前端只看 body 里的 code 是否为 0"，格式统一
- 为什么 warn 和 error 日志级别不同？→ BizException 是预期内失败（一行 warn）；兜底是意外故障（error 带堆栈排查），且细节不暴露给外部

### 3.3 服务注册与声明式调用（L3）

**默写顺序**：加依赖 → 配注册中心地址 → 启动类开关 → Feign 接口 → Service 注入调用

1. **依赖**：`nacos-discovery`（SCA）+ `openfeign` + `loadbalancer` → 三者缺一不可，loadbalancer 是"把服务名换成实例地址"的执行者
2. **yml**：`spring.cloud.nacos.discovery.server-addr` → 启动即注册，启动类不用加 `@EnableDiscoveryClient`
3. **启动类**：`@EnableFeignClients` → 框架扫描 `@FeignClient` 接口并生成代理（接口没有实现类）
4. **Feign 接口**：`@FeignClient(name="服务名")` + 方法上照抄对方 Controller 的 `@GetMapping("/x/{id}")` + 返回 `Result<DTO>` → `@PathVariable("id")` 名字不能省
5. **Service**：`@Autowired` 注入接口，一行调用即远程请求；`try/catch` 转 `REMOTE_CALL_FAILED`、判 `code`/`data` 的活仍留在自己代码里

**口决**：
> 依赖三件套，yml 填 8848；启动开 Feign，接口写服务名；
> 注解抄对方，超时别忘配；异常自己抓，返回自己判。

**三个易错点**：只加 Feign 没加 LoadBalancer（找不到实例）/ `@PathVariable` 不写名字 / 以为 Feign 自动带熔断降级（要等 L4 Sentinel）

### 3.4 Sentinel 熔断限流（L4）

**默写顺序（依赖顺序）**：三服务加依赖 → yml 配控制台 → 独立收口类 + `@SentinelResource` → fallback/blockHandler 方法 → 控制台配规则

1. **依赖**：`spring-cloud-starter-alibaba-sentinel`（版本跟 SCA，2025.1.0.0 内置客户端 1.8.9）→ 加了它，每个 Controller 接口自动成为 URL 资源
2. **yml**：`spring.cloud.sentinel.transport.dashboard: 127.0.0.1:8858` + `port: 8719`（三服务同机自动顺延 8720/8721）
3. **收口类**：远程调用集中到一个 `@Component`（RemoteService）——注解必须"从外面调进来"才生效，同类内部直接调用无效；方法上 `@SentinelResource(value="getProduct", fallback="getProductFallback", blockHandler="getProductBlocked")`
4. **资源方法本体**：只发调用 + 判业务，异常一律上抛（业务失败抛 BizException，调不通抛原始异常）
5. **fallback**：签名 = 原参数 + `Throwable t` → 按 t 类型分诊：BizException 原样放行（业务失败不能被降级文案掩盖），其余翻译成"XX服务不可用"(2002/2003)
6. **blockHandler**：签名 = 原参数 + `BlockException e` → 规则触发（限流/熔断）走这里，统一 4290；不写它 BlockException 直接上抛变 5000
7. **控制台规则**：dashboard 独立 jar 自己启动（8858 避开 Nacos 控制台 8080）；对资源配 QPS 阈值，URL 资源名是**模板** `/product/{id}` 不是具体路径；推送到客户端内存，改阈值不重启

**口诀**：
> 依赖一加资源来，yml 指路 8719；
> 调用收口独立类，注解起名才管用；
> fallback 接异常分诊，blockHandler 接规则拦截；
> 业务失败原样放，链路断了才降级。

**自测三问**：资源是什么？为什么收口类里的注解才生效？（统一入口检查，内部调用绕过）/ fallback 和 blockHandler 区别？（接异常 vs 接 BlockException）/ 熔断和降级区别？（自动断电有半开恢复 vs 备胎方案无状态）

### 3.5 后续楼层（待通关后沉淀）

- L5 Redis 缓存模板：尚未沉淀
- L8-L10：通关后逐层追加

### 3.6 JWT 登录鉴权（L6）

**默写顺序（依赖顺序）**：密钥对 → 注册哈希 → 登录签发 → 公钥端点 → 验签器 → 拦截器 + 上下文 → 接口取用户 → 角色注解

1. **密钥对**：RSA 2048；私钥只放签发服务，公钥给所有验签方。PEM 去头尾→Base64 解码→`PKCS8EncodedKeySpec`（私钥）/ `X509EncodedKeySpec`（公钥）→ `KeyFactory.getInstance("RSA")`。
2. **注册哈希**：`passwordEncoder.encode(明文)` 写库；BCrypt 自带随机盐，同一密码每次哈希不同；`matches(明文, 哈希)` 验证，不可逆。
3. **登录签发**：查用户 → `matches` → `Jwts.builder().header().keyId(kid).and().subject(userId).claim(...).issuedAt(now).expiration(now+ttl).signWith(privateKey, Jwts.SIG.RS256).compact()`。
4. **公钥端点**：`GET /auth/jwks` 返回 `{"keys":[{kty,use,alg,kid,n,e}]}`；`n/e` 用 Base64URL 无填充，`BigInteger.toByteArray()` 要去掉符号字节 `0x00`。
5. **验签器**：`Jwts.parser().keyLocator(h -> resolveKey(h.get("kid"))).build().parseSignedClaims(token).getPayload()`；公钥按 kid 缓存，首次懒加载 JWKS。
6. **拦截器**：`preHandle` 读 `Authorization: Bearer` → 验签 → 角色校验 → `UserContext.set(user/token)`；`afterCompletion` 必须 `clear()`。
7. **接口取用户**：`@CurrentUser Long userId` + `HandlerMethodArgumentResolver` 从 ThreadLocal 取，Controller 不解析 token。
8. **服务间透传**：L6 是 Feign `RequestInterceptor` 透传原始 token；L7 起改为透传网关注入的三个身份头 `X-Mall-*`，下游不再验 token。异步线程会丢 ThreadLocal。

**口诀**：
> 私钥签、公钥验，kid 指路 JWKS；
> BCrypt 存盐不存明文，登录换票两小时；
> 拦截器验票存线程，Controller 只取 id；
> 调下游带同一张票，角色注解管权限。

**自测三问**：RS256 与 HS256 的本质区别？（非对称 vs 共享密钥）/ ThreadLocal 为什么必须 clear？（Tomcat 线程复用串号）/ token 为什么无法即时吊销？（无状态，服务端不存）

### 3.7 网关（L7）

**默写顺序**：模块（不能引 web）→ 路由 → 验签过滤器 → 服务信任头 → Feign 透传 → 限流 → 错误/CORS

1. **模块**：`spring-cloud-starter-gateway-server-webflux` + nacos-discovery + loadbalancer；**绝不能引 `starter-web`**（WebMVC 与 WebFlux 冲突）。
2. **路由**：`id / uri / predicates / filters`；`lb://服务名` 由 LoadBalancer 从 Nacos 取实例；`StripPrefix=1` 去掉对外的 `/api` 第一段。
3. **验签过滤器**：`GlobalFilter + Ordered(-100)`；白名单 = 登录/注册/JWKS + 商品 GET/HEAD + OPTIONS；验签后**先 remove 再 set** 三个 `X-Mall-*` 头。
4. **服务信任头**：`GatewayAuthInterceptor` 只读头重建 `LoginUser`；`@RequireRole` 仍留服务端；Feign `RequestInterceptor` 透传三个头（服务间直连不经网关）。
5. **限流**：reactive Redis + `KeyResolver`（userId 优先、IP 兜底）+ `RequestRateLimiter`（replenishRate/burstCapacity/requestedTokens）；拒绝时空 429 用响应装饰器改成 `4290`。
6. **统一错误**：`ErrorWebExceptionHandler @Order(-2)` 接 404(4040)/503(5030)；**404 发生在过滤器之前**，GlobalFilter 接不到。
7. **CORS**：`globalcors`；`allowedOriginPatterns: "*"`，不能 `allowedOrigins: "*"` + `allowCredentials: true`。

**口诀**：
> 网关不引 web，路由三件套；
> 先验票再写头，下游只认头；
> 限流 Redis 桶，429 改 4290；
> 404 走异常口，跨域网关收。

**自测三问**：为什么不能引 `starter-web`？（两套容器冲突）/ 下游为什么还保留 `@RequireRole`？（网关看不到方法注解）/ 为什么 404 不能用 `GlobalFilter` 接？（路由没匹配时过滤器链还没开始）

> 当前进度、遗留清单、下一步计划 → 一律看 `DEV_LOG.md`，本文档不记进度。

## 四、版本管理约定

1. **主分支线性开发**，不用分支区分版本
2. **大阶段收尾**：整工程归档到根目录 `v1/`、`v2/`… 文件夹 + 打 tag（v1.0 已存在）+ 推送
3. **文件名不带手工版本号**（版本交给 Git 管理，避免 rename 断史）
4. 提交信息风格：`feat: 任务单#0X xxx` / `docs: xxx` / `chore: xxx`
5. v1.0 tag 指向任务单#01 交付快照（`64f52b7`）
6. **阶段收尾提交（2026-11-18 新增）**：每完成一个阶段（阶段 0 / A / B / C），必须更新 DEV_LOG.md 后连同代码一起 commit 并 push；阶段内按可验收单元增量提交（一个单元一个 commit）；留痕只认 Git diff + DEV_LOG，不建 CHANGELOG。详见第〇节铁律 6。

## 五、协作约定（AI 助手注意）

- 用户是 Java/Spring 初学者，**实现每个功能时用注释讲清"为什么这么设计"**，讲解需通俗；**不用类比，直接按铁律 11 走数据链路**
- 用户重视先理解再动手，常问"为什么这样设计"；回答时先给结论再展开，不要堆术语
- 编码后必须 `mvn install -q -DskipTests`（在 v1/ 目录下）编译验证再提交
- IDE 元数据已在 .gitignore（IDEA 的 `.idea/`、`*.iml`；Eclipse 遗留的 .factorypath/.classpath/.project/.settings/），勿提交
- 文档放 `v1/docs/`，学习笔记放 `v1/notes/`
- 中文交流；提交信息用中文
- 改动涉及多个模块时，逐一确认目标文件路径，避免改串（见踩坑#5）
- **学习优先讲解法**：讲解顺序永远是"怎么写 → 为什么 → 项目对照"，目标是可迁移的手撕代码能力，禁止直接贴成品代替讲解（见第〇节铁律 1）
- **DEV_LOG 双轨制**：开发进度条目 AI 可随时追加；理解确认条目必须等用户明确说"理解了，记录吧"后才写入并带确认标记（见第〇节铁律 2）
- **文档同步义务**：每完成一段开发更新 DEV_LOG.md（进度/遗留/下一步）；每通关一层把默写模板沉淀到本文档第三节知识内核
- 网络备注：github.com 需代理，本仓库已配置仓库级 `git config http.proxy http://127.0.0.1:7891`（端口 7891，非全局）
