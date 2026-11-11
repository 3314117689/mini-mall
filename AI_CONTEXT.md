# mini-mall 开发上下文文档（学习优先版）

> 本文档供 AI 助手在**对话轮数过多、需要重启对话**时唤醒记忆，**只定规则和沉淀知识，不记录当前开发进度**。开发进度、遗留清单、下一步计划一律记录在 `DEV_LOG.md`（与本文档同目录）。恢复上下文时：先读本文档（规则+知识），再读 DEV_LOG.md（进度）。
> 最后更新：2026-11-11（项目定位更正：面试准备项目非毕设，验收标准=用户能上机手写；新增讲解铁律 4 与注入心智模型）

## 〇、文档使用规则（AI 助手必须遵守的铁律）

1. **学习优先讲解法**：讲解/实现任何功能时，顺序永远是——**"怎么写 → 为什么 → 项目对照"**。用户的核心目标是面试手撕代码：面试官不会让他默写这个项目，而是临时出题，所以他需要的是**可迁移的写法套路**（先自问"我需要什么"，再落笔），不是对本项目代码的死记硬背。禁止一上来就贴成品代码；禁止建议无脑背诵，口诀只是简化理解过程的记忆锚点。
2. **文档分工**：`AI_CONTEXT.md` 只定规则、沉淀知识内核（默写模板等），**不写进度状态**；`DEV_LOG.md` 记录开发进度与用户理解确认。**开发进度条目**（做了什么、遗留什么、下一步）AI 可随时追加；**理解确认条目**只在用户明确说出"理解了，记录吧"（或同等明确表态）后才允许写入。
3. **每完成一段开发，更新 DEV_LOG**：进度、遗留、下一步随开发同步写入 DEV_LOG.md；每通关一层，把该层默写模板沉淀到本文档第三节知识内核。
4. **只讲用户代码，不讲框架源码（2026-11-11 用户明确要求）**：
   - 讲解主线永远是**"数据在用户自己写的代码里怎么走"**：哪行代码、变量叫什么、值是多少、从哪来、到哪去（配合断点验证）。
   - 框架一律当黑盒，一句话带过："Spring/MyBatis 内部干的"。**禁止**展开 DispatcherServlet、HandlerMapping、动态代理、MapperProxy 等框架内部结构，用户明确表示看不懂且不需要。
   - **一次只讲一个点**，信息量宁小勿大；不打比方（用户要求直接走链路）。
   - 框架的唯一存在形式 = 帮用户完成的三类动作：启动时造对象并塞字段（注入）、请求时把参数塞给方法、把返回值转成 JSON 发回浏览器。

## 一、项目一句话定位

- **性质**：**面试准备项目**（求职 Java 后端方向），从零搭建的微商城（mini-mall），目标是用户**熟悉到自己能上机手写**全链路代码，并能向面试官讲清微服务架构。**所有代码以"用户自己能写出来"为验收标准，AI 尽量不代写，只讲解 + review**
- **仓库**：`https://github.com/3314117689/mini-mall.git`（分支 `main`）
- **本地路径**：`D:\bishe\workspace\gongju\eclipseworkspace\mini-mall`（git 仓库根），代码在 `v1/` 子目录
- **IDE**：Eclipse（不用 IDEA）
- **技术栈**：Spring Boot + **原生 MyBatis**（mybatis-spring-boot-starter，手写 @Select/@Insert/@Update/@Delete 注解 SQL）+ MySQL + Lombok。**已决定不引入 MyBatis-Plus**（手写 SQL 更利于学习，原"引入 MP"计划作废）
- **架构约定**：Controller → Service → Mapper 三层；统一返回 `Result<T>`（code=0 成功）；每个服务独占自己的数据库（db_user / db_product…），服务间将来通过 HTTP 调用，**不许直连对方库**

## 二、盖楼路线图（项目主结构与学习顺序）

> 组织原则：每一层都是因为"上一层解决不了某个新问题"才出现。建楼顺序 = 学习顺序 = 依赖顺序。**当前建到哪一层、各层验收状态，见 DEV_LOG.md，本表不记进度。**

| 层 | 触发问题 | 解法 | 验收标志 |
|---|---|---|---|
| **L0 地基：三层架构五件套** | 单个服务的增删改查怎么做 | entity/mapper/service/impl/controller + 统一返回 + 多模块（mall-common/mall-user:8081/mall-product:8082） | 五件套能白纸默写；mall-user、mall-product 的 CRUD 验收通过 |
| **L1 异常框架** | 失败时错误响应散乱，前端没法统一判断 | Result/BizException/ErrorCode/GlobalExceptionHandler 四件套 | 原理讲透 + 白纸默写 3 遍 |
| **L2 订单服务 + 服务间调用** | 订单需要"用户+商品"数据，但不许连别人的库 | 新建 mall-order + HTTP 调用（先 RestTemplate 后 Feign） | `POST /order` 落库成功；**关掉 mall-product 再下单能看到友好失败提示**（服务容错素材）★最小可演示版本线（必须到达） |
| **L3 Nacos 注册中心** | 调用地址写死 localhost:8081，服务多了管不过来 | 服务注册与按名发现、负载均衡 | 调用代码里不再出现硬编码 ip:port |
| **L4 Gateway 网关** | 前端要记 N 个端口，鉴权没处放 | 统一入口 9000 + 路由转发 | 前端所有请求只发 9000 ★面试标准线（强烈建议到达） |
| **L5+ 可选加分层** | 锦上添花 | JWT 登录鉴权 / 配置中心 / 分布式事务 / 前端页面 / 部署 | — （按时间余量决定做不做） |

**两条关键线的定义**：
- 最少加 2 层（L1+L2）= 最初目标达成。硬标准：**微服务成立的最低门槛 = 服务之间通过 HTTP 接口协作，而不是共享数据库**
- 标准 4 层（+L3+L4）= 面试说服力保障：没有注册中心和网关，"微服务"三个字在面试时容易被问"这和两个单体有什么区别"

**该层的坑（踩坑速查，按楼层归类）**：
- L0：包少建一层导致 Mapper 扫描不到（必须 `com.mall.<服务名>.mapper`）；判空逻辑写反（查到返回、查不到才抛）；表结构与代码不一致（DB 列名下划线、Java 驼峰、靠 map-underscore-to-camel-case 映射，SQL 永远写数据库真实列名，不一致时定一方为标准另一方迁就）；金额用 BigDecimal 不用 double；改多模块文件时曾改串 yml（mall-user 的库被误改成 db_product），逐一确认路径
- L1：暂无
- 环境：Eclipse Ctrl+点击跳转在本项目失效（其他项目正常，已搁置，不阻塞）

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

### 3.3 后续楼层（待通关后沉淀）

- L2 订单服务五件套 + RestTemplate 调用模板：通关后在此追加
- L3 Nacos / L4 Gateway：通关后在此追加

> 当前进度、遗留清单、下一步计划 → 一律看 `DEV_LOG.md`，本文档不记进度。

## 四、版本管理约定

1. **主分支线性开发**，不用分支区分版本
2. **大阶段收尾**：整工程归档到根目录 `v1/`、`v2/`… 文件夹 + 打 tag（v1.0 已存在）+ 推送
3. **文件名不带手工版本号**（版本交给 Git 管理，避免 rename 断史）
4. 提交信息风格：`feat: 任务单#0X xxx` / `docs: xxx` / `chore: xxx`
5. v1.0 tag 指向任务单#01 交付快照（`64f52b7`）

## 五、协作约定（AI 助手注意）

- 用户是 Java/Spring 初学者，**实现每个功能时用注释讲清"为什么这么设计"**，讲解需通俗（善用类比、从调用方视角倒推设计）
- 用户重视先理解再动手，常问"为什么这样设计"；回答时先给结论再展开，不要堆术语
- 编码后必须 `mvn install -q -DskipTests`（在 v1/ 目录下）编译验证再提交
- Eclipse 元数据（.factorypath/.classpath/.project/.settings/）已在 .gitignore，勿提交
- 文档放 `v1/docs/`，学习笔记放 `v1/notes/`
- 中文交流；提交信息用中文
- 改动涉及多个模块时，逐一确认目标文件路径，避免改串（见踩坑#5）
- **学习优先讲解法**：讲解顺序永远是"怎么写 → 为什么 → 项目对照"，目标是可迁移的手撕代码能力，禁止直接贴成品代替讲解（见第〇节铁律 1）
- **DEV_LOG 双轨制**：开发进度条目 AI 可随时追加；理解确认条目必须等用户明确说"理解了，记录吧"后才写入并带确认标记（见第〇节铁律 2）
- **文档同步义务**：每完成一段开发更新 DEV_LOG.md（进度/遗留/下一步）；每通关一层把默写模板沉淀到本文档第三节知识内核
- 网络备注：github.com 需代理，本仓库已配置仓库级 `git config http.proxy http://127.0.0.1:7891`（端口 7891，非全局）
