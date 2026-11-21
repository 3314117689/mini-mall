# BUG 追踪日志

> 规则（AI_CONTEXT.md 铁律 9）：每次 bug 追踪，AI 必须带用户一步一步走，不跳步骤；本文件记录完整过程——一次 bug 一个条目，格式固定：**现象 → 排查步骤（含命令与实际输出）→ 根因 → 修复 → 预防**。
> 目的：换机器、下次遇到同类报错时，照着这份文件能自己走一遍。

---

## BUG-001：product 服务启动失败「Failed to start bean 'webServerStartStop'」，Redis 连接被 reset，Nacos 掉线（三连）

**日期**：2026-11-21　**环境**：Boot 4.1.2 + Nacos 3.3.0 + Redis 5.0.5 + Sentinel 1.8.9

### 一、现象（三个症状一起出现）

1. 在 IDEA 启动 mall-product → 报错 `Failed to start bean 'webServerStartStop'`，服务起不来。
2. 之前跑得好好的服务日志里（14:50）突然出现大量 `java.net.SocketException: Connection reset`，随后 `ConnectionWatchdog - Cannot reconnect to [127.0.0.1:6379]: Connection refused`。
3. 去看 Nacos——也挂了。

背景：用户停掉了一批后台进程，但忘了停的是哪些；重启了 redis-server 后去 IDEA 启动 product 才报错。

### 二、排查步骤（按顺序走，每步都有依据）

**第 1 步：读 IDEA 控制台的报错块，找真正的死因。**

Spring Boot 启动失败的报错是「三层结构」，从上往下读：

```
第 1 层：Failed to start bean 'webServerStartStop'   ← 只告诉你"哪个 Bean 起不来"
第 2 层：cancelling refresh attempt                   ← 框架宣布放弃启动
第 3 层：APPLICATION FAILED TO START                  ← 真正的死因在 Description 里
         Description: Web server failed to start. Port 8082 was already in use.
         Action: Identify and stop the process that's listening on port 8082...
```

> 读法：**永远不要只看第一行**。第一行只是"案发现场"（webServerStartStop = 内嵌 Tomcat 的启动器），死因在下方的 `Description:`。这段翻译成人话：**8082 端口已经被别的进程占了**。

**第 2 步：从「端口被占」推出下一步动作——查谁占了 8082。**

命令（任意终端可跑，IDEA 底部 Terminal 也行）：

```
netstat -ano | findstr :8082
```

输出格式：`TCP  0.0.0.0:8082  0.0.0.0:0  LISTENING  <PID>`，最后一列就是占用进程的 PID。
（本次排查时用 PowerShell 的 `Get-NetTCPConnection -LocalPort 8082` 拿到的是同一个信息。）

**第 3 步：用 PID 查进程身份。**

```
tasklist /fi "PID eq <上一步的PID>"
```

或看更详细的命令行（能看出它是谁启动的、跑的什么）：

```powershell
Get-CimInstance Win32_Process -Filter "ProcessId = <PID>" | Select-Object ProcessId, Name, CommandLine
```

**本次的实际身份**：一个 `java.exe`，命令行是 `java -jar ...mall-product-1.0.0.jar`——是**之前由 AI 用后台进程方式启动的旧 product 实例**，一直没停。IDEA 里再启动一个新的，端口当然冲突。

**第 4 步：处理占用者。**

- 拿命令行确认它就是遗留实例后，`taskkill /PID <PID> /F` 杀掉（或如果它是 TRAE 后台任务，直接在任务面板停止）。
- 杀完用第 2 步的 netstat 复查，8082 无输出 = 已释放。
- 上午排查时（同一类问题的另一现场）：占用者是 **IDEA 自己启动的旧代码实例**（带 `idea_rt.jar` 参数、classpath 是 `target\classes`，没有 Redis 依赖）——同样是「旧实例没停」。

**第 5 步：排查症状 2（Connection reset）——看服务日志的时间线。**

14:50 的日志三段连着读：

```
Unexpected exception during request: java.net.SocketException: Connection reset
   ← Redis 那头的连接被强行掐断（对面进程没了）
ConnectionWatchdog - Reconnecting, last destination was /127.0.0.1:6379
   ← Lettuce（Redis 客户端）的看门狗自动重连
Cannot reconnect to [127.0.0.1:6379]: Connection refused
   ← 重连连不上：6379 上已经没有进程在监听 → Redis 进程死了
```

结论链：reset 不是网络问题，是 **Redis 进程被停了**（用户停的那批后台进程里包含 AI 启动的 redis-server）。用户随后重启的 redis-server 用 `redis-cli ping` → `PONG` 验证存活。

> 附带发现：Redis 重启后 `keys product::*` 是空的——Redis 是内存数据库，进程一死数据全没（今天的缓存丢了，重新查询会自动重建，不是 bug）。

**第 6 步：排查症状 3（Nacos 挂了）——端口探活，不猜。**

```powershell
Get-NetTCPConnection -LocalPort 8848 -State Listen -ErrorAction SilentlyContinue
```

8848 无监听 = Nacos 服务端确实没在跑。控制台端口 8080 同样无监听。

**第 7 步：按依赖顺序恢复环境（顺序不能乱）。**

Redis（用户已重启 ✅）→ Nacos（`D:\bishe\environment\nacos\bin\startup.cmd -m standalone -f microservice`）→ 等 Nacos 起来后再启动 mall-product（IDEA 直接跑 ProductServiceApplication）→ 验证。

### 三、根因

1. **IDEA 启动失败的根因**：8082 被遗留的旧 product 实例（AI 后台启动的 `java -jar` 进程）占用。新旧实例抢同一个端口，后者必然失败。
2. **Connection reset 的根因**：Redis 进程被停，Lettuce 长连接被掐断后重连失败。
3. **环境失忆的根因**：多个组件（Nacos/Sentinel/Redis/三个业务服务）分散在「TRAE 后台任务 / 用户终端 / IDEA」三处启动，**没有一张「谁在哪启动」的清单**，停了什么都记不清。

### 四、修复

1. 停掉/确认遗留 java 进程，释放 8082。
2. Redis 重启（用户已做），ping 验证。
3. Nacos 重新启动，8080 控制台确认服务列表。
4. IDEA 启动 mall-product，`GET /product/1` 验证 200。

### 五、预防（教训沉淀）

1. **换 jar / 重启服务前先查端口**：`netstat -ano | findstr :8082`——有监听就先弄清楚是谁（L4-2 已踩过一次 jar 被锁，这次是端口被占，同族坑）。
2. **环境清单**：见本文件下方「环境台账」，每次启动/停止环境组件后对照更新。
3. **读报错永远读到 `Description:` 为止**，第一行只是现场不是死因。
4. **别把「先后发生」当「因果」**：本次 Nacos 挂和 product 启动失败是两件独立的事，真凶是 8082 端口冲突。判定依据：失败日志死在 `Starting ProtocolHandler`（之后没有 `Tomcat started`，更没有 `nacos registry`），程序在碰到 Nacos 之前就死了；**Nacos 掉线的症状**是服务照样 `Started`、只是刷 `failed to register ... 8848` 的 ERROR、服务列表里没有它。两种病两张脸。
5. **服务起来了 ≠ 定位正确**：修复生效可能是多个障碍同时被清掉（本次 = 8082 释放 + Nacos 恢复），下结论前用日志证据链交叉验证。

---

## 环境台账（谁在哪启动，停止前先看这张表）

| 组件 | 端口 | 启动方式 | 启动命令/入口 |
|---|---|---|---|
| Nacos Server 3.3.0 | 8848（服务端）/ 8080（控制台） | 用户终端 | `D:\bishe\environment\nacos\bin\startup.cmd -m standalone -f microservice` |
| Redis 5.0.5 | 6379 | 用户终端 | `D:\bishe\environment\redis-5.0.5-bin\redis-5.0.5-bin\redis-server.exe` |
| Sentinel 控制台 1.8.9 | 8858 | 用户终端 | `java "-Dserver.port=8858" -jar sentinel-dashboard-1.8.9.jar`（jar 在 `D:\bishe\environment\sentinel\`） |
| mall-user | 8081 | IDEA | `UserServiceApplication` |
| mall-product | 8082 | IDEA | `ProductServiceApplication` |
| mall-order | 8083 | IDEA | `OrderServiceApplication` |

> 约定：业务服务一律 IDEA 启动（开发期改代码即改即重启）；中间件一律用户终端启动；AI 不再用后台进程拉起业务服务，避免再出现「两处启动互相打架」。
