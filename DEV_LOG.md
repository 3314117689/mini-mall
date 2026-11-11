# mini-mall 开发日志

> **文档分工**：本文件记录**开发进度**（做了什么、遗留什么、下一步）与**用户理解确认台账**。规则和默写知识内核在 `AI_CONTEXT.md`，本文件不重复。
> **写入规则**：
> 1. 开发进度条目：AI 完成一段开发后即可追加，保证随时可接续
> 2. 理解确认条目：只在用户明确说"理解了，记录吧"（或同等明确表态）后才写入，带 ✅ 确认标记
> 3. 新条目追加在对应区块顶部（最新在前）

## 一、开发记录

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

## 二、理解确认台账

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
