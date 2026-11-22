# 07 L6 JWT 登录鉴权

> 按铁律 11 写：总览表只做导航，真正的过程在"逐步行进"里。本章随 L6-1~L6-6 逐步补全。
> 本章的一条轴：**登录拿 token → 带 token 调接口 → 拦截器验签 → Controller 拿当前用户**。L6-1 先解决"钥匙从哪来、公钥怎么公布"。

## 一、这一层解决什么

L0~L5 的 `POST /order/create` 里，`userId` 是客户端自己填的：谁都能替别人下单，也能把自己写成别人。L6 让服务端自己确认"你是谁"：

1. 用户用用户名 + 密码换一张**有签名、有过期时间**的 token（JWT）；
2. 之后每个请求带这张 token，服务端验签后得到 `userId` 和 `role`；
3. 下单不再接收 `userId`，只认 token 里的身份。

## 二、技术点清单

| # | 技术点 | 落点 | 一句话作用 |
|---|---|---|---|
| 1 | RSA 密钥对 + RS256 签名 | `mall-user` | 私钥只留在发令牌的服务，其他服务用公钥验签 |
| 2 | JWKS 公钥端点 | `GET /auth/jwks` | 标准格式公布公钥，按 `kid` 取，换密钥不改代码 |
| 3 | BCrypt 密码哈希 | `mall-user` + `spring-security-crypto` | 库里只存 60 字符哈希 |
| 4 | 登录签发 JWT | `POST /auth/login` | 校验密码后签出 `sub=userId / role / exp` |
| 5 | 拦截器统一验 token | `mall-common` + 各服务注册 | 不过验签直接抛 4010 |
| 6 | ThreadLocal 用户上下文 | `mall-common` `UserContext` | Controller 直接拿 userId，不重复解析 |
| 7 | RBAC 角色校验 | `@RequireRole` + `user.role` | 普通用户访问管理接口 → 4030 |

## 三、小步与状态

| 步 | 内容 | 状态 |
|---|---|---|
| L6-1 | 生成 RSA 密钥对；`mall-user` 出 JWKS 端点 | ✅ |
| L6-2 | 注册走 BCrypt；用户名唯一；存量密码迁移 | ✅ |
| L6-3 | `role` 列；登录签发 RS256 token | ✅ |
| L6-4 | `mall-common` 鉴权组件 + order 注册拦截器 | ✅ |
| L6-5 | 下单取当前用户；user 鉴权；Feign 透传 token | 未开始 |
| L6-6 | `@RequireRole` RBAC 演示 | 未开始 |
| L6-7 | 全链路实测 + 文档收尾 | 未开始 |

## 四、涉及文件与归属（L6-1）

| 名字 | 归属 | 作用 |
|---|---|---|
| `JwtKeyLoader` | 本项目，`mall-user/src/main/java/com/mall/user/auth/JwtKeyLoader.java` | 启动时把 PEM 读成 `PrivateKey` / `RSAPublicKey` |
| `AuthController` | 本项目，`mall-user/src/main/java/com/mall/user/controller/AuthController.java` | `/auth/**` 认证入口，本步先出 `/auth/jwks` |
| `keys/jwt-private.pem` | 本项目资源，`mall-user/src/main/resources/keys/` | PKCS#8 私钥，只有 user 服务用 |
| `keys/jwt-public.pem` | 本项目资源，同上 | X.509 公钥，公布给别人验签 |
| `Resource` | 框架：Spring Core | 把 `classpath:...` 读成文件流 |
| JWKS | 协议：RFC 7517 | 公钥的 JSON 表示（`kty/kid/n/e`） |

## 五、启动期：密钥怎么进内存（只发生一次，与请求无关）

### 第 1 步｜生成 RSA 密钥对

生成命令**不写进项目**（一次性工具，跑在 `v1/target/` 下，生成完即删）：

```text
java target\KeyGen.java mall-user\src\main\resources\keys
→ written to ...\mall-user\src\main\resources\keys
```

- 【你写的】用 JDK `KeyPairGenerator.getInstance("RSA")` + `initialize(2048)` 生成一对钥匙，按 PEM 写到两个文件。
- 【框架动作】无，这一步纯 JDK。
- 【数据】
  - `[D1]` 私钥文件内容（`-----BEGIN PRIVATE KEY-----`，PKCS#8，约 1.7 KB）；
  - `[D2]` 公钥文件内容（`-----BEGIN PUBLIC KEY-----`，X.509，约 0.45 KB）。
- 为什么 2048：RS256 要求 RSA；2048 位是当前通用下限，短了不安全，长了签名计算变慢。

### 第 2 步｜配置告诉程序文件在哪

```yaml
# 文件：mall-user/src/main/resources/application.yml
# 行号：31-37
# JWT（L6）：私钥只在 user 服务，其他服务通过 GET /auth/jwks 取公钥验签
# kid 是密钥编号，换密钥时改它，验签方按 kid 选公钥
mall:
  jwt:
    kid: mall-l6-dev
    private-key: classpath:keys/jwt-private.pem
    public-key: classpath:keys/jwt-public.pem
```

- 【你写的】三个字符串：`kid`、两个 `classpath:` 路径。
- 【数据】`[D3] kid = "mall-l6-dev"`；`[D4] 私钥路径`；`[D5] 公钥路径`。
- 为什么 `kid` 要有：以后可能同时有两把钥匙（换钥过渡期），JWT 头里带 `kid`，验签方才知道用哪把。

### 第 3 步｜`JwtKeyLoader` 构造器执行

```java
// 文件：mall-user/src/main/java/com/mall/user/auth/JwtKeyLoader.java
// 行号：32-42
public JwtKeyLoader(@Value("${mall.jwt.kid}") String kid,
        @Value("${mall.jwt.private-key}") Resource privateKeyResource,
        @Value("${mall.jwt.public-key}") Resource publicKeyResource) {
    this.kid = kid;
    try {
        this.privateKey = readPrivateKey(privateKeyResource);
        this.publicKey = readPublicKey(publicKeyResource);
    } catch (Exception e) {
        throw new IllegalStateException("加载 JWT 密钥失败，检查 mall.jwt.* 配置与 keys/ 下的 pem 文件", e);
    }
}
```

- 【你写的】构造器接三个参数，把 PEM 文件读成 Java 的钥匙对象。
- 【注解】`@Value("${mall.jwt.kid}")` 让框架把 yml 的字符串塞进来；参数类型是 `Resource` 时，框架会把 `classpath:` 路径解析成可读对象。
- 【框架动作】启动时创建 `JwtKeyLoader` 这个 Bean → 解析 `@Value` → 调构造器。此时 Tomcat **还没监听端口**，浏览器连不上是正常的。
- 【数据】
  - `[D3]`/`[D4]`/`[D5]` → 分别变成参数 `kid`、`privateKeyResource`、`publicKeyResource`；
  - 读文件后产生 `[D6] PrivateKey` 和 `[D7] RSAPublicKey`（2048 位模数）；
  - 这两个对象存成字段，**请求期不再读文件**。

PEM 怎么变成钥匙（同一个文件第 56-77 行）：

```java
// 行号：66-77
private static byte[] decodePem(Resource resource, String type) throws Exception {
    String text;
    try (InputStream in = resource.getInputStream()) {
        text = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
    }
    String base64 = text
            .replace("-----BEGIN " + type + "-----", "")
            .replace("-----END " + type + "-----", "")
            .replaceAll("\\s", "");
    return Base64.getDecoder().decode(base64);
}
```

- 【你写的】去掉头尾标记和所有空白，剩下的 Base64 解码成 DER 字节；再交给 `KeyFactory` 的 `PKCS8EncodedKeySpec`（私钥）/ `X509EncodedKeySpec`（公钥）。
- 【数据】`[D1]` → DER 字节 → `[D6]`；`[D2]` → DER 字节 → `[D7]`。
- 注意：私钥和公钥的编码格式不同（PKCS#8 / X.509），用错 `KeySpec` 会在这一步抛异常。

## 六、请求期：`GET /auth/jwks` 逐步行进

**命令入场**：`GET http://localhost:8081/auth/jwks`，无参数、无请求体、无 token。

**调用链**

| 顺序 | 谁 | 在哪 | 干了什么 |
|---|---|---|---|
| 1 | Tomcat 线程 | — | 按路径 `/auth/jwks` 找到 `AuthController.jwks` |
| 2 | `AuthController.jwks` | 第 34-35 行 | 从 `jwtKeyLoader` 取出公钥 |
| 3 | `AuthController.jwks` | 第 36-43 行 | 组装 JWK Map（`kty/use/alg/kid/n/e`） |
| 4 | `AuthController.jwks` | 第 42-43 行 | 调 `base64Url` 把 `n`、`e` 编码 |
| 5 | `AuthController.jwks` | 第 44 行 | 包成 `{"keys":[...]}` 返回 |
| 6 | Spring MVC | — | Map → JSON 写回浏览器 |

**数据编号**

| 编号 | 数据 | 初始值 | 谁产生 |
|---|---|---|---|
| `[D7]` | 公钥对象 | `RSAPublicKey`（2048 位） | 启动期 `JwtKeyLoader` |
| `[D8]` | `n` | Base64URL 字符串（342 字符） | `base64Url(modulus)` |
| `[D9]` | `e` | 字符串 `"AQAB"` | `base64Url(65537)` |
| `[D10]` | JWK Map | `{kty=RSA, use=sig, alg=RS256, kid=..., n=..., e=...}` | 第 36-43 行 |
| `[D11]` | 响应 JSON | `{"keys":[{...}]}` | 框架序列化 |

**结局**：请求期只有一种结局（HTTP 200 返回公钥）。如果密钥文件读不到，服务在启动期就抛 `IllegalStateException` 起不来，不会走到请求期。

### 第 1 步｜请求到达

- 【框架动作】Tomcat 派线程；Spring MVC 按 `@RequestMapping("/auth")`（第 23 行）+ `@GetMapping("/jwks")`（第 33 行）匹配到方法。
- 【数据】无业务数据进入。

### 第 2 步｜取公钥

```java
// 文件：mall-user/src/main/java/com/mall/user/controller/AuthController.java
// 行号：34-35
public Map<String, Object> jwks() {
    RSAPublicKey key = jwtKeyLoader.getPublicKey();
```

- 【你写的】一行取值。
- 【数据】`[D7]` 从 `JwtKeyLoader` 的字段 → 局部变量 `key`。

### 第 3 步｜组装 JWK

```java
// 行号：36-43
Map<String, Object> jwk = new LinkedHashMap<>();
jwk.put("kty", "RSA");
jwk.put("use", "sig");
jwk.put("alg", "RS256");
jwk.put("kid", jwtKeyLoader.getKid());
jwk.put("n", base64Url(key.getModulus()));
jwk.put("e", base64Url(key.getPublicExponent()));
```

- 【你写的】五个固定字段 + 两个由公钥算出的字段。
- 【数据】`[D3] kid` → `jwk.kid`；`[D7].modulus` → `[D8]`；`[D7].publicExponent` → `[D9]`；六个键值 → `[D10]`。

### 第 4 步｜`n`/`e` 的编码细节

```java
// 行号：48-54
private static String base64Url(BigInteger value) {
    byte[] bytes = value.toByteArray();
    if (bytes.length > 1 && bytes[0] == 0) {
        bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
}
```

- 【你写的】① `toByteArray()` 取大端字节；② 最高位为 0 时去掉多出来的符号字节（JWK 要求无符号表示）；③ 用 URL 安全编码、不带 `=` 填充。
- 【数据】2048 位模数 → 256 字节 → `[D8]`；`65537` → `0x010001` → `[D9] = "AQAB"`。

### 第 5 步｜返回

```java
// 行号：44
return Map.of("keys", List.of(jwk));
```

- 【你写的】包成 JWKS 标准外层结构。
- 【数据】`[D10]` → `[D11]`。

### 第 6 步｜序列化

- 【框架动作】Spring MVC 把 `Map` 转成 JSON 写入响应体。
- 【数据】`[D11]` → 浏览器收到 `{"keys":[{"kty":"RSA",...,"e":"AQAB"}]}`。

## 七、L6-1 实测

```text
GET http://localhost:8081/auth/jwks
HTTP 200
{"keys":[{"kty":"RSA","use":"sig","alg":"RS256","kid":"mall-l6-dev",
          "n":"lZhf9SouRSCrTl6MzvhH2BbTnlI2ZNRGtfhubSxc_lQv...",
          "e":"AQAB"}]}
```

另外用一次性脚本（`v1/target/JwkCheck.java`，生成后已删）校验过：把 `n`/`e` 还原成公钥，与 `jwt-public.pem` 解析出的 `RSAPublicKey` 比对 —— `modulus equal: true`、`exponent equal: true`，证明公布出去的确实是同一把钥匙。

## 八、L6-1 坑

1. PEM 头尾标记和换行必须去掉再 Base64 解码，直接整体解码会报 `Illegal base64 character`。
2. 私钥是 PKCS#8（`BEGIN PRIVATE KEY`）、公钥是 X.509（`BEGIN PUBLIC KEY`），`KeySpec` 用错会抛 `InvalidKeySpecException`。
3. JWK 的 `n`/`e` 用 **Base64URL 无填充**，不是标准 Base64；写成标准 Base64 时 `+` `/` 会让 URL 解析出问题。
4. `BigInteger.toByteArray()` 可能带符号字节 `0x00`，不去掉的话模数会变成 257 字节，验签方拼出的公钥不对。
5. **安全提醒**：`jwt-private.pem` 提交进仓库等于公开私钥，仅限本地学习；生产必须放密钥管理服务，仓库里只留公钥。

## 九、L6-1 断点验证与自测题

| 停在哪 | 看什么 | 看到什么证明走对了 |
|---|---|---|
| `JwtKeyLoader` 构造器第 37 行 | `privateKeyResource`、`privateKey.getAlgorithm()` | 路径是 `classpath:keys/jwt-private.pem`，算法 `RSA` |
| `AuthController.jwks` 第 35 行 | `key.getModulus().bitLength()` | `2048` |
| 第 44 行前 | `jwk` | 六个键齐全，`e = "AQAB"` |

### 自测题（不附答案）

1. 为什么私钥只放 `mall-user`，而公钥要公布成 `/auth/jwks`？
2. `kid` 在什么场景下才会真正用到第二把钥匙？
3. `n` 为什么必须先去掉 `0x00` 再 Base64URL？

---

## 十、L6-2 注册与 BCrypt 密码哈希

**命令入场**：`POST http://localhost:8081/auth/register`
body：`{"username":"l6test","password":"123456","phone":"13900000001"}`

**调用链**

| 顺序 | 谁 | 在哪 | 干了什么 |
|---|---|---|---|
| 1 | Tomcat 线程 | — | 按路径找到 `AuthController.register` |
| 2 | 框架 | — | JSON body → `User` 对象（`password` 是 WRITE_ONLY，只进不出） |
| 3 | `AuthController.register` | 第 41-43 行 | 调 `userService.save(user)` |
| 4 | `UserServiceImpl.save` | 第 42 行 | 打"新增用户"日志 |
| 5 | `UserServiceImpl.save` | 第 44-47 行 | 校验用户名/密码非空 |
| 6 | `UserServiceImpl.save` | 第 49-52 行 | 按用户名查重 |
| 7 | `UserServiceImpl.save` | 第 54 行 | 明文 → BCrypt 哈希，覆盖 `user.password` |
| 8 | `UserMapper.insert` | 第 30 行 | 执行 INSERT，自增主键回填 |
| 9 | `AuthController.register` | 第 43 行 | `Result.ok(id)` |

**数据编号**

| 编号 | 数据 | 初始值 | 谁产生 |
|---|---|---|---|
| `[D1]` | 注册请求体 | `User{username=l6test, password=123456, phone=139...}` | 框架从 JSON 转换 |
| `[D2]` | 查重结果 | `null`（没人用过这个用户名） | `selectByUsername` |
| `[D3]` | BCrypt 哈希 | `$2a$10$3GX2...`（60 字符） | `passwordEncoder.encode` |
| `[D4]` | 落库的行 | `user{username=l6test, password=[D3]}` | MyBatis INSERT |
| `[D5]` | 用户 id | `7` | MySQL 自增回填 |
| `[D6]` | 响应 | `{"code":0,"data":7,"msg":"ok"}` | `Result.ok` + 框架序列化 |

**三个结局（总览）**

| 结局 | 分岔点 | 条件 | 浏览器 |
|---|---|---|---|
| ① 注册成功 | 第 49 行查重结果 | `[D2] = null` | `code=0, data=7` |
| ② 用户名已存在 | 第 49 行 | `[D2]` 是 `User`（非 null） | `code=1004` |
| ③ 参数缺失 | 第 44 行 | username 或 password 为 null/空白 | `code=1000` |

### 结局①逐步行进：注册成功

#### 第 1 步｜请求到达，JSON 变对象

- 【框架动作】Tomcat 派线程；Spring MVC 按 `@PostMapping("/register")`（第 41 行）匹配；`@RequestBody`（第 42 行）把 JSON 转成 `User`。
- 【数据】`[D1]` 产生：`username="l6test"`、`password="123456"`、`phone="13900000001"`、`id=null`。

#### 第 2 步｜进入 Service

```java
// 文件：mall-user/src/main/java/com/mall/user/controller/AuthController.java
// 行号：41-44
@PostMapping("/register")
public Result<Long> register(@RequestBody User user) {
    return Result.ok(userService.save(user));
}
```

- 【你写的】Controller 只做转发，不处理密码。
- 【数据】`[D1]` → 作为 `save` 的参数 `user`。

#### 第 3 步｜必填校验

```java
// 文件：mall-user/src/main/java/com/mall/user/service/impl/UserServiceImpl.java
// 行号：44-47
if (user.getUsername() == null || user.getUsername().isBlank()
        || user.getPassword() == null || user.getPassword().isBlank()) {
    throw new BizException(ErrorCode.PARAM_ERROR);
}
```

- 【你写的】四个条件任一成立就抛 1000。
- 【数据】`[D1]` 两个字段都非空 → 条件 false → 继续。

#### 第 4 步｜用户名查重

```java
// 行号：49-52
if (userMapper.selectByUsername(user.getUsername()) != null) {
    log.warn("注册失败, 用户名已存在: {}", user.getUsername());
    throw new BizException(ErrorCode.USERNAME_EXISTS);
}
```

- 【你写的】按用户名查一次库，查到就抛 1004。
- 【框架动作】MyBatis 执行 `select ... where username = ?`，查不到返回 `null`。
- 【数据】`[D2] = null` → 条件 false → 继续。

#### 第 5 步｜明文变哈希

```java
// 行号：53-54
// 明文 → BCrypt 哈希（同一个密码每次生成的哈希都不同，因为带随机盐）
user.setPassword(passwordEncoder.encode(user.getPassword()));
```

- 【你写的】调 `PasswordEncoder.encode`，把返回值写回 `user.password`。
- 【框架动作】`BCryptPasswordEncoder`（框架：Spring Security Crypto）内部：生成随机盐 → 2^10 轮哈希 → 输出 `$2a$10$<salt+hash>`。
- 【数据】`[D1].password = "123456"` → `[D3] = "$2a$10$3GX2YIDtJ..."`（60 字符）；`[D1]` 被就地改写，原明文不再存在。

> 同一个密码注册两次，两个哈希也不一样（盐不同），但都能用 `matches` 验证回原文。

#### 第 6 步｜写库

```java
// 行号：55
userMapper.insert(user);
```

- 【框架动作】MyBatis 执行 `insert into user(username, password, phone) values(?,?,?)`，`?` 依次填 `l6test`、`[D3]`、`13900000001`；`create_time` 走数据库默认值。
- 【数据】`[D4]` 落库；`[D5] = 7` 回填到 `user.id`。

#### 第 7 步｜返回

- 【你写的】第 57 行 `return user.getId()` → Controller 第 43 行 `Result.ok(7)`。
- 【数据】`[D5] = 7` → `[D6] = {"code":0,"data":7,"msg":"ok"}`。

### 结局②逐步行进：用户名已存在

第 1~3 步同结局①（`[D1]` 通过必填校验）。

#### 第 4 步｜查重命中

- 【框架动作】`selectByUsername("l6test")` 查到一行 → 返回 `User{id=7,...}`。
- 【数据】`[D2] = User{id=7, username=l6test}`，不是 null。

#### 第 5 步｜抛业务异常

- 【你写的】第 51 行 `throw new BizException(ErrorCode.USERNAME_EXISTS)`；第 54 行加密、第 55 行插入**都不执行**。
- 【数据】`BizException{code=1004, message="用户名已存在"}`。

#### 第 6 步｜全局异常处理器接住

- 【框架动作】`GlobalExceptionHandler.handleBizException` 接住 → `Result.fail(1004, "用户名已存在")`。
- 【数据】`[D6] = {"code":1004,"data":null,"msg":"用户名已存在"}`，库里没有新增行。

### 结局③逐步行进：参数缺失

- 【你写的】第 46 行直接抛 `BizException(PARAM_ERROR)`；查重、加密、插入都不执行。
- 【数据】`[D2]`、`[D3]`、`[D4]`、`[D5]` 都不产生；`[D6] = {"code":1000,"msg":"参数错误"}`。

### 存量密码迁移（L6-2 附带）

库里原有 3 条用户是明文 `123456`，登录将无法通过 BCrypt 校验。用一次性工具生成哈希后执行：

```sql
UPDATE db_user.user
SET password = '$2a$10$bwSq6Kjb3O7qDe8m.cF.Hu.J2LKhnBKYLM8H5T/qnP.ZTYP7nKbf6'
WHERE username IN ('zhangsan','lisi','zhangsan2');
-- Rows affected: 3
```

- 这条哈希就是 `123456`，3 个演示账号统一成它；新注册用户的哈希由 `encode` 现场生成。
- 上线系统不能这样批量刷密码，正常做法是"用户下次登录时用明文校验旧密码 → 成功后顺手升级成哈希"。

### L6-2 实测

```text
POST /auth/register {"username":"l6test","password":"123456","phone":"13900000001"}
→ {"code":0,"data":7,"msg":"ok"}

再发一次同 body
→ {"code":1004,"data":null,"msg":"用户名已存在"}

{"username":"","password":"123456"}
→ {"code":1000,"data":null,"msg":"参数错误"}

查询库：SELECT password, CHAR_LENGTH(password) ... → $2a$10$3GX2YIDtJ... , 60
```

### L6-2 坑

1. 不引 `spring-security-crypto` 就编译不过 `PasswordEncoder`；引 `spring-boot-starter-security` 则会被自动装上一整套过滤器链，所有接口默认要登录，和自研拦截器打架。
2. 查重必须在 `encode` 之前：哈希每次都不同，不能拿哈希去比对"用户名是否重复"。
3. 库里 `password` 是 `varchar(100)`，BCrypt 固定 60 字符，够用；若列宽小于 60 会被截断，登录永远失败。
4. 存量明文密码不迁移，`encode` 之后的 `matches` 一定失败——不是代码错，是数据旧。

### L6-2 断点验证与自测题

| 停在哪 | 看什么 | 看到什么证明走对了 |
|---|---|---|
| `UserServiceImpl.save` 第 49 行 | `userMapper.selectByUsername(...)` 的返回值 | 第一次注册是 `null` |
| 第 54 行前后 | `user.getPassword()` | 执行前 `123456`，执行后 `$2a$10$...` |
| `UserMapper.insert` 返回后 | `user.getId()` | 非 null（自增回填成功） |

1. 为什么查重要放在加密之前？
2. 同一个密码两次注册，哈希为什么不同？登录时怎么还能比对成功？
3. 为什么只引 `spring-security-crypto`，不引 `spring-boot-starter-security`？


---

## 十一、L6-3 登录签发 JWT

### 先加 role 列（本步前置）

```sql
ALTER TABLE db_user.user
ADD COLUMN role varchar(20) NOT NULL DEFAULT 'USER' COMMENT '角色：USER/ADMIN（L6 RBAC）';

UPDATE db_user.user SET role = 'ADMIN' WHERE username = 'zhangsan';
-- Rows affected: 1
```

- 为什么带默认值：`NOT NULL` 加在没有默认值的列上，已有 4 行无法填值；`DEFAULT 'USER'` 让存量行自动补上。
- `UserMapper` 两条 `select` 也补上了 `role` 列，否则实体里 `role` 永远是 `null`。

**命令入场**：`POST http://localhost:8081/auth/login`
body：`{"username":"zhangsan","password":"123456"}`

**调用链**

| 顺序 | 谁 | 在哪 | 干了什么 |
|---|---|---|---|
| 1 | Tomcat 线程 | — | 按路径找到 `AuthController.login` |
| 2 | 框架 | — | JSON → `LoginDTO` |
| 3 | `AuthController.login` | 第 44-47 行 | 调 `authService.login(dto)` |
| 4 | `AuthServiceImpl.login` | 第 37-40 行 | 参数校验 |
| 5 | `UserMapper.selectByUsername` | 第 22-23 行 | 按用户名查用户（含 password、role） |
| 6 | `AuthServiceImpl.login` | 第 44 行 | `passwordEncoder.matches` 比对 |
| 7 | `JwtIssuer.issue` | 第 29-42 行 | 组 header/payload、私钥签名、compact |
| 8 | `AuthServiceImpl.login` | 第 52-56 行 | 组装 `LoginVO` |
| 9 | `AuthController.login` | 第 46 行 | `Result.ok(vo)` |

**数据编号**

| 编号 | 数据 | 初始值 | 谁产生 |
|---|---|---|---|
| `[D1]` | 登录请求 | `LoginDTO{username=zhangsan, password=123456}` | 框架从 JSON 转换 |
| `[D2]` | 查询到的用户 | `User{id=1, password=$2a$10$bwSq..., role=ADMIN}` | MyBatis |
| `[D3]` | 比对结果 | `true` | `PasswordEncoder.matches` |
| `[D4]` | 过期时刻 | `now + 7200 秒` | `JwtIssuer` 算 |
| `[D5]` | token | `eyJkid...` 三段字符串 | `Jwts.builder().compact()` |
| `[D6]` | 响应体 | `{"code":0,"data":{"token":...,"tokenType":"Bearer","expiresIn":7200}}` | `Result.ok` |

**三个结局（总览）**

| 结局 | 分岔点 | 条件 | 浏览器 |
|---|---|---|---|
| ① 登录成功 | 第 44 行 | 用户存在且 `matches` 为 true | `code=0` + token |
| ② 用户不存在或密码错 | 第 44 行 | `user == null` 或 `matches` 为 false | `code=1005` |
| ③ 参数缺失 | 第 37 行 | username/password 为 null 或空白 | `code=1000` |

### 结局①逐步行进：登录成功

#### 第 1 步｜请求到达，JSON 变 DTO

- 【框架动作】Spring MVC 按 `@PostMapping("/login")`（第 44 行）匹配；`@RequestBody`（第 45 行）把 JSON 转成 `LoginDTO`。
- 【数据】`[D1]` 产生。

#### 第 2 步｜参数校验

```java
// 文件：mall-user/src/main/java/com/mall/user/service/impl/AuthServiceImpl.java
// 行号：37-40
if (dto.getUsername() == null || dto.getUsername().isBlank()
        || dto.getPassword() == null || dto.getPassword().isBlank()) {
    throw new BizException(ErrorCode.PARAM_ERROR);
}
```

- 【你写的】任一为空抛 1000。
- 【数据】`[D1]` 两个字段非空 → 继续。

#### 第 3 步｜按用户名查库

```java
// 行号：42
User user = userMapper.selectByUsername(dto.getUsername());
```

- 【框架动作】MyBatis 执行 `select id, username, password, phone, role, create_time from user where username = ?`。
- 【数据】MySQL 一行 → `[D2] = User{id=1, username=zhangsan, password="$2a$10$bwSq...", role="ADMIN"}`。

#### 第 4 步｜BCrypt 比对

```java
// 行号：43-47
// 用户不存在 和 密码错误 返回同一个 1005：不让攻击者试出"哪些用户名存在"
if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
    log.warn("登录失败, username={}", dto.getUsername());
    throw new BizException(ErrorCode.PASSWORD_ERROR);
}
```

- 【你写的】`user == null` 与 `matches` 两个条件，任一成立就 1005。
- 【框架动作】`BCryptPasswordEncoder.matches(明文, 哈希)`（框架：Spring Security Crypto）：从哈希里取出盐 → 用同样的盐和轮数算一遍 → 比较结果。**不是解密**，哈希不可逆。
- 【数据】`[D3] = true`（`123456` 与库里的哈希匹配）→ 条件 false → 继续。

#### 第 5 步｜组 header 和 payload

```java
// 文件：mall-user/src/main/java/com/mall/user/auth/JwtIssuer.java
// 行号：29-40
public String issue(User user) {
    Instant now = Instant.now();
    return Jwts.builder()
            // kid 写进 header，验签方据此在 JWKS 里挑选公钥
            .header().keyId(keyLoader.getKid()).and()
            // sub 存用户 id：验签后 sub 就是可信的 userId
            .subject(String.valueOf(user.getId()))
            .claim("username", user.getUsername())
            .claim("role", user.getRole())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(ttlSeconds)))
```

- 【你写的】`kid` 放 header；`sub` 放 userId（注意 `String.valueOf`，JWT 的 sub 是字符串）；`username`、`role` 放自定义 claim；`iat`/`exp` 是时间戳。
- 【数据】`[D4] = now + 7200`；payload = `{"sub":"1","username":"zhangsan","role":"ADMIN","iat":...,"exp":...}`。

#### 第 6 步｜私钥签名

```java
// 行号：41-42
            // RS256：私钥签名，公钥验签
            .signWith(keyLoader.getPrivateKey(), Jwts.SIG.RS256)
            .compact();
```

- 【框架动作】`signWith`（框架：jjwt）用 RSA 私钥对 `base64url(header) + "." + base64url(payload)` 做 SHA-256 摘要再 RSA 签名，拼成第三段。
- 【数据】`[D5]` = `header.payload.signature` 三段，签名段 342 个字符（2048 位 RSA）。

#### 第 7 步｜组装返回

```java
// 行号：52-56
LoginVO vo = new LoginVO();
vo.setToken(token);
vo.setTokenType("Bearer");
vo.setExpiresIn(jwtIssuer.getTtlSeconds());
```

- 【你写的】token 原样给前端；`Bearer` 是 HTTP 头的固定前缀（`Authorization: Bearer <token>`）。
- 【数据】`[D5]` → `[D6]`。

### 结局②逐步行进：用户不存在或密码错

第 1~3 步同结局①。

- 【数据】两种子情况：
  - 用户名不存在：`[D2] = null`；
  - 密码错：`[D2]` 有值，但 `matches("wrong", 哈希)` 返回 `false` → `[D3] = false`。
- 【你写的】第 46 行抛 `BizException(PASSWORD_ERROR)`；第 49 行签发不执行。
- 【数据】`[D4]`、`[D5]` 不产生；`[D6] = {"code":1005,"msg":"用户名或密码错误"}`。

> 两种子情况对外完全一样，这是故意的：如果"用户不存在"单独给 1001，攻击者就能批量试出哪些用户名注册过（用户名枚举）。

### 结局③逐步行进：参数缺失

- 【你写的】第 39 行抛 `PARAM_ERROR`，不查库、不比对、不签发。
- 【数据】`[D2]`~`[D5]` 都不产生；`[D6] = {"code":1000,"msg":"参数错误"}`。

### L6-3 实测

```text
POST /auth/login {"username":"zhangsan","password":"123456"}
→ code=0, expiresIn=7200
header : {"kid":"mall-l6-dev","alg":"RS256"}
payload: {"sub":"1","username":"zhangsan","role":"ADMIN","iat":1795346854,"exp":1795354054}
sigLen : 342

POST /auth/login {"username":"zhangsan","password":"wrong"}
→ {"code":1005,"data":null,"msg":"用户名或密码错误"}
```

### L6-3 坑

1. `sub` 是字符串：验签后要 `Long.valueOf(claims.getSubject())`，直接强转 `Long` 会 `ClassCastException`。
2. `iat`/`exp` 用 `Date`，用 `Instant` 直接塞会编译报错（jjwt 0.12 的 `expiration(Date)`）。
3. 用户不存在与密码错误必须同码，否则等于给攻击者一个用户名探测器。
4. JWT 是无状态的：服务端不存 session，所以**发出去的 token 在过期前无法单独作废**（要作废得引入黑名单/短 TTL + 刷新令牌，本项目不做）。
5. `role` 改了要重新登录才生效：token 里的 `role` 是签发那一刻的快照。

### L6-3 断点验证与自测题

| 停在哪 | 看什么 | 看到什么证明走对了 |
|---|---|---|
| `AuthServiceImpl.login` 第 42 行后 | `user.getPassword()`、`user.getRole()` | 以 `$2a$10$` 开头；`ADMIN` |
| 第 44 行 | `passwordEncoder.matches(...)` 的返回值 | `true` |
| `JwtIssuer.issue` 第 41 行 | `keyLoader.getPrivateKey().getAlgorithm()` | `RSA` |

1. BCrypt 的 `matches` 为什么不需要"解密"？
2. token 里为什么要放 `role`，而不是每次查库？
3. 用户改了角色，旧 token 还能通过角色校验吗？为什么？


---

## 十二、L6-4 拦截器统一验 token + ThreadLocal 上下文

**命令入场**：`GET http://localhost:8083/order/1`
请求头：`Authorization: Bearer eyJraWQiOiJtYWxsLWw2LWRldiIsImFsZyI6IlJTMjU2In0...`

**涉及零件（都在 `mall-common/src/main/java/com/mall/common/auth/`）**

| 名字 | 作用 |
|---|---|
| `JwtVerifier` | 按 `kid` 从 JWKS 取公钥、验签、把 claims 还原成 `LoginUser` |
| `JwtInterceptor` | `preHandle` 验 token 并 `UserContext.set`；`afterCompletion` 清 ThreadLocal |
| `UserContext` | `ThreadLocal<LoginUser>`，请求内任意层可读 |
| `@CurrentUser` + `CurrentUserArgumentResolver` | Controller 参数直接拿 userId |
| `JwtWebConfig`（本项目，`mall-order/.../config/`） | 把上面零件装到 order 服务，拦 `/order/**` |

**调用链**

| 顺序 | 谁 | 在哪 | 干了什么 |
|---|---|---|---|
| 1 | Tomcat 线程 | — | 请求进 `DispatcherServlet` |
| 2 | `JwtInterceptor.preHandle` | 第 29-38 行 | 读 `Authorization` 头，抠出 token |
| 3 | `JwtVerifier.verify` | 第 51-67 行 | 交给 jjwt 解析 |
| 4 | `JwtVerifier.resolveKey` | 第 69-79 行 | 按 `kid` 查缓存；没有就 `refreshKeys` |
| 5 | `JwtVerifier.refreshKeys` | 第 82-108 行 | HTTP 拉 `http://localhost:8081/auth/jwks`，n/e 还原公钥 |
| 6 | jjwt | — | 用公钥验签名、校验 `exp` |
| 7 | `JwtVerifier.verify` | 第 59-62 行 | claims → `LoginUser` |
| 8 | `JwtInterceptor.preHandle` | 第 36 行 | `UserContext.set(user)` |
| 9 | `OrderController.getById` | 第 25-28 行 | 原逻辑不变，正常查库返回 |
| 10 | `JwtInterceptor.afterCompletion` | 第 40-45 行 | `UserContext.clear()` |

**数据编号**

| 编号 | 数据 | 初始值 | 谁产生 |
|---|---|---|---|
| `[D1]` | Authorization 头 | `"Bearer eyJraWQiOi..."` | 客户端 |
| `[D2]` | token 字符串 | `eyJraWQiOi....eyJzdWIi....签名` | 第 38 行去前缀 |
| `[D3]` | kid | `"mall-l6-dev"` | jjwt 从 token header 解出 |
| `[D4]` | 公钥 | `RSAPublicKey` | 第一次请求时从 JWKS 拉取并缓存 |
| `[D5]` | Claims | `{sub=1, username=zhangsan, role=ADMIN, exp=...}` | jjwt 验签后返回 |
| `[D6]` | 当前用户 | `LoginUser{userId=1, username=zhangsan, role=ADMIN}` | `JwtVerifier` 组装 |
| `[D7]` | 响应 | `{"code":0,"data":{"id":1,...}}` | Controller + 框架 |

**四个结局（总览）**

| 结局 | 分岔点 | 条件 | 浏览器 |
|---|---|---|---|
| ① 验签通过 | `JwtVerifier.verify` 返回 | 签名对、未过期 | `code=0`（或业务码如 1003） |
| ② 没带 token | `JwtInterceptor` 第 35 行 | 头为空或不以 `Bearer ` 开头 | `code=4010` |
| ③ token 无效 | `JwtVerifier.verify` 第 59 行 | 签名错/已过期/`kid` 找不到 | `code=4011` |
| ④ JWKS 拉不到 | `refreshKeys` 第 104 行 | 用户服务没起、网络不通 | `code=4012` |

### 结局①逐步行进：验签通过

#### 第 1 步｜请求到达拦截器

```java
// 文件：mall-common/src/main/java/com/mall/common/auth/JwtInterceptor.java
// 行号：29-38
@Override
public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    String authorization = request.getHeader("Authorization");
    if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
        throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    String token = authorization.substring(BEARER_PREFIX.length()).trim();
    UserContext.set(jwtVerifier.verify(token));
    return true;
}
```

- 【你写的】取头 → 判前缀 → 截掉 `"Bearer "` → 验签 → 存上下文。
- 【框架动作】`HandlerInterceptor`（框架：Spring MVC）在 Controller 方法执行**之前**调用 `preHandle`；返回 `true` 才继续。
- 【数据】`[D1]` → `[D2]`；此时 `UserContext` 还是空的。

#### 第 2 步｜验签入口

```java
// 文件：mall-common/src/main/java/com/mall/common/auth/JwtVerifier.java
// 行号：52-58
public LoginUser verify(String token) {
    try {
        Claims claims = Jwts.parser()
                .keyLocator(header -> resolveKey(String.valueOf(header.get("kid"))))
                .build()
                .parseSignedClaims(token)
                .getPayload();
```

- 【你写的】`keyLocator` 是一个回调：jjwt 解析时会先读 header，把 header 交给这个 lambda。
- 【框架动作】jjwt（框架）拆三段 → 解 header → 调 lambda 要公钥 → 用公钥验第三段签名 → 检查 `exp`。
- 【数据】`[D3] = "mall-l6-dev"` 从 header 取出，传给 `resolveKey`。

#### 第 3 步｜第一次请求：拉 JWKS

```java
// 行号：82-100
private synchronized void refreshKeys() {
    try {
        HttpRequest request = HttpRequest.newBuilder(URI.create(jwksUri))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode keys = objectMapper.readTree(response.body()).path("keys");
        for (JsonNode jwk : keys) {
            BigInteger modulus = new BigInteger(1,
                    Base64.getUrlDecoder().decode(jwk.path("n").asText()));
            BigInteger exponent = new BigInteger(1,
                    Base64.getUrlDecoder().decode(jwk.path("e").asText()));
```

- 【你写的】JDK `HttpClient` 发 GET，Jackson 读 `keys` 数组，`n`/`e` 用 Base64URL 解码后拼公钥。
- 【框架动作】无（这里全是 JDK + Jackson）。
- 【数据】JWKS JSON → `[D4] = RSAPublicKey`，以 `kid` 为键放进 `keyCache`。
- 日志证据：`JWKS 已加载, kid=mall-l6-dev` 只出现**一次**（在第一次带 token 的请求时），后续请求走缓存。

#### 第 4 步｜claims 还原成 `LoginUser`

```java
// 行号：59-62
return new LoginUser(
        Long.valueOf(claims.getSubject()),
        claims.get("username", String.class),
        claims.get("role", String.class));
```

- 【你写的】`sub` 是字符串，必须 `Long.valueOf`；两个自定义 claim 按类型取。
- 【数据】`[D5]` → `[D6] = LoginUser{userId=1, username="zhangsan", role="ADMIN"}`。

#### 第 5 步｜存上下文

- 【你写的】`UserContext.set([D6])`。
- 【数据】`ThreadLocal` 里从 `null` 变成 `[D6]`。

#### 第 6 步｜Controller 正常执行

```java
// 文件：mall-order/src/main/java/com/mall/order/controller/OrderController.java
// 行号：25-28
@GetMapping("/{id}")
public Result<Order> getById(@PathVariable Long id) {
    return Result.ok(orderService.getById(id));
}
```

- 【你写的】L6-4 下这条命令的 Controller 还没用到 `UserContext`（L6-5 才改下单接口），但拦截器已经保证"只有登录用户能进来"。
- 【数据】`[D7]`；本例库里 id=1 存在，返回 `code=0`。

#### 第 7 步｜请求结束清 ThreadLocal

```java
// 行号：40-45
@Override
public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
        Object handler, Exception ex) {
    // 必须清：Tomcat 线程会被复用，不清就会把身份带给下一个请求
    UserContext.clear();
}
```

- 【框架动作】无论 Controller 成功还是抛异常，`afterCompletion` 都会执行。
- 【数据】`ThreadLocal` 从 `[D6]` → `null`。

### 结局②逐步行进：没带 token

- 【你写的】第 32 行条件成立 → 第 33 行抛 `BizException(UNAUTHORIZED)`。
- 【数据】`[D2]` 不产生；`UserContext` 全程为空。
- 【框架动作】异常从 `preHandle` 冒出，被 `GlobalExceptionHandler.handleBizException` 接住。
- 【数据】响应 `{"code":4010,"data":null,"msg":"未登录，请先登录"}`。

### 结局③逐步行进：token 无效

- 【框架动作】jjwt 验签失败（`SignatureException`）或过期（`ExpiredJwtException`），抛 `JwtException`；`kid` 找不到时 `resolveKey` 抛 `IllegalArgumentException`。
- 【你写的】第 63 行 `catch (JwtException | IllegalArgumentException e)` → 转成 4011。
- 【数据】`[D4]`~`[D6]` 不产生；响应 `{"code":4011,"msg":"登录凭证无效或已过期"}`。
- 实测两种都走到这里：改签名最后 4 位、以及 `abc.def.ghi` 这种乱码。

### 结局④逐步行进：拉不到 JWKS

- 【你写的】`refreshKeys` 里 `httpClient.send` 抛异常（用户服务没起/超时）→ 第 106 行转成 `BizException(AUTH_UNAVAILABLE)`。
- 【数据】`[D4]` 不产生；响应 `{"code":4012,"msg":"认证服务不可用，请稍后重试"}`。
- 注意：这个异常**不是** `JwtException`，不会被 `verify` 里的 catch 改写成 4011，所以能区分"凭证错"和"认证服务挂了"。

### L6-4 实测

```text
GET /order/1（无头）
→ {"code":4010,"data":null,"msg":"未登录，请先登录"}

GET /order/1（Bearer 有效 token）
→ {"code":0,"data":{"id":1,"amount":1.00,"num":1,"productId":1,"userId":1},"msg":"ok"}

GET /order/1（签名改 4 位）
→ {"code":4011,"data":null,"msg":"登录凭证无效或已过期"}

GET /order/1（abc.def.ghi）
→ {"code":4011,"data":null,"msg":"登录凭证无效或已过期"}

order 日志：
JWKS 已加载, kid=mall-l6-dev   ← 只有第一次带 token 的请求打印
```

### L6-4 坑

1. `JwtInterceptor` 不能加 `@Component`：它依赖 `JwtVerifier`，而 `JwtVerifier` 的 `jwks-uri` 各服务不同，交给各服务的 `@Configuration` 组装更清楚；否则 product 这种不用鉴权的服务也会被要求配该属性。
2. 拦截器里抛出的异常会被 `@RestControllerAdvice` 接住（`DispatcherServlet` 在 `preHandle` 外有统一异常出口），不需要自己 `response.getWriter()`。
3. `afterCompletion` 必须清 ThreadLocal；Tomcat 默认 200 个线程复用，漏清就是"下一个用户看到上一个用户的身份"。
4. JWKS 是**懒加载**：服务启动时不拉，第一次验签才拉；所以用户服务重启换了密钥，订单侧要等 `kid` 变了才会重新拉。
5. 公钥缓存后，用户服务短暂宕机不影响已缓存 `kid` 的验签——这是本地验签的收益，也是"无法即时吊销"的代价。
