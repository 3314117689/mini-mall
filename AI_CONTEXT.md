# mini-mall 开发上下文文档

> 本文档供 AI 助手在新对话中快速恢复上下文使用。每次完成一个任务单后更新"已完成任务"和"下一步"章节。
> 最后更新：2026-11-08（任务单#02 完成后）

## 一、项目定位

- **性质**：毕业设计项目，从零搭建的学习型微商城（mini-mall），核心目标是学习 Spring Boot / 微服务全链路
- **仓库**：`https://github.com/3314117689/mini-mall.git`（分支 `main`）
- **本地路径**：`D:\bishe\workspace\gongju\eclipseworkspace\mini-mall`（git 仓库根），代码在 `v1/` 子目录
- **IDE**：Eclipse（注意：不用 IDEA）

## 二、工程结构（多模块 Maven）

```
v1/                        ← 当前开发目录（整工程归档在 v1/ 文件夹内）
├── pom.xml                ← 父工程，只做依赖管理和聚合，不写代码
├── mall-common/           ← 公共模块：Result 统一返回体、异常体系
└── mall-user/             ← 用户服务（唯一可启动的 Spring Boot 应用）
    ├── 启动类 UserServiceApplication，端口 8081
    └── 包结构：controller / service / service.impl / mapper / entity
```

- 技术栈：Spring Boot（spring-boot-starter-parent）、MyBatis、MySQL（db_user 库）、Lombok
- 架构模式：Controller → Service → Mapper 三层，数据以 `Result<T>` 统一返回（code=0 成功）

## 三、版本管理约定（重要，已写入笔记）

1. **主分支线性开发**，不用分支区分版本
2. **大阶段收尾**：整工程归档到根目录 `v1/`、`v2/`… 文件夹 + 打 tag（v1.0 已存在）+ 推送
3. **文件名不带手工版本号**（版本交给 Git 管理，避免 rename 断史）
4. 提交信息风格：`feat: 任务单#0X xxx` / `docs: xxx` / `chore: xxx`
5. v1.0 tag 指向任务单#01 交付快照（`64f52b7`）

## 四、已完成任务

### 任务单#01：工程搭建 + 用户查询接口（已完成，tag v1.0）
- 多模块工程骨架、Result 统一返回体（静态泛型工厂 ok/fail）
- mall-user 提供 `GET /user/{id}`，三层架构，MySQL db_user 查询

### 任务单#02：全局异常处理（已完成编译验证，已本地提交，**待推送**）
- `ErrorCode` 枚举（com.mall.common.exception）：1001 用户不存在、5000 系统异常；code 分段约定：0 成功 / 1xxx 通用业务 / 5000 系统兜底
- `BizException extends RuntimeException`，Service 层业务不满足直接抛
- `GlobalExceptionHandler`（@RestControllerAdvice）：BizException → warn+错误码；Exception → error+5000 兜底
- **关键设计点**：启动类改为 `@SpringBootApplication(scanBasePackages = "com.mall")`，否则 mall-common 里的处理器 Bean 扫描不到
- mall-common 的 pom 中 spring-boot-starter-web 用 `provided` 作用域（只编译期用，运行时由业务服务自带，公共模块不绑架依赖方）
- 用户不存在响应由 `code=0,data=null` 改为 `code=1001,msg=用户不存在`，接口文档已同步

## 五、当前待办

1. **网络恢复后 `git push`**：本地有 2 个未推送 commit（ff3600f 任务单#02、b4ffc77 清理 .factorypath）。之前推送失败原因是 github.com 443 连不上（需代理/VPN，如走本地代理：`git config --global http.proxy http://127.0.0.1:7890`）
2. **验收任务单#02**：Eclipse 重启 mall-user → 访问 `http://localhost:8081/user/999` → 期望 `{"code":1001,"msg":"用户不存在","data":null}`
3. **写任务单#02 学习笔记**：用户习惯用自己的话写笔记（v1/notes/ 目录，包含踩坑记录；本次踩坑：漏 import Result 报"找不到符号"）

## 六、下一步计划（按原路线图顺序）

1. **任务单#03：引入 MyBatis-Plus** 替代手写 Mapper SQL（参考官方文档：依赖 + BaseMapper + 分页插件）
2. **任务单#04+：新服务 mall-product / mall-order**，复用 mall-common 与三层架构，对应 db_product / db_order
3. **阶段5：Nacos + 网关** 微服务化（端口统一 9000 入口）
4. 每个阶段收尾执行版本管理约定（归档 v2/ 文件夹 + tag）

## 七、协作约定（AI 助手注意）

- 用户是 Java/Spring 初学者，**实现每个功能时用注释讲清"为什么这么设计"**，讲解需通俗（善用类比、从调用方视角倒推设计）
- 编码后必须 `mvn install -q -DskipTests`（在 v1/ 目录下）编译验证再提交
- Eclipse 元数据（.factorypath/.classpath/.project/.settings/）已在 .gitignore，勿提交
- 文档放 `v1/docs/`，学习笔记放 `v1/notes/`
- 中文交流；提交信息用中文
