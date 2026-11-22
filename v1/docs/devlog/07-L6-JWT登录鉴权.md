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
| L6-2 | 注册走 BCrypt；用户名唯一；存量密码迁移 | 未开始 |
| L6-3 | `role` 列；登录签发 RS256 token | 未开始 |
| L6-4 | `mall-common` 鉴权组件 + order 注册拦截器 | 未开始 |
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

## 七、实测（L6-1）

```text
GET http://localhost:8081/auth/jwks
HTTP 200
{"keys":[{"kty":"RSA","use":"sig","alg":"RS256","kid":"mall-l6-dev",
          "n":"lZhf9SouRSCrTl6MzvhH2BbTnlI2ZNRGtfhubSxc_lQv...",
          "e":"AQAB"}]}
```

另外用一次性脚本（`v1/target/JwkCheck.java`，生成后已删）校验过：把 `n`/`e` 还原成公钥，与 `jwt-public.pem` 解析出的 `RSAPublicKey` 比对 —— `modulus equal: true`、`exponent equal: true`，证明公布出去的确实是同一把钥匙。

## 八、坑

1. PEM 头尾标记和换行必须去掉再 Base64 解码，直接整体解码会报 `Illegal base64 character`。
2. 私钥是 PKCS#8（`BEGIN PRIVATE KEY`）、公钥是 X.509（`BEGIN PUBLIC KEY`），`KeySpec` 用错会抛 `InvalidKeySpecException`。
3. JWK 的 `n`/`e` 用 **Base64URL 无填充**，不是标准 Base64；写成标准 Base64 时 `+` `/` 会让 URL 解析出问题。
4. `BigInteger.toByteArray()` 可能带符号字节 `0x00`，不去掉的话模数会变成 257 字节，验签方拼出的公钥不对。
5. **安全提醒**：`jwt-private.pem` 提交进仓库等于公开私钥，仅限本地学习；生产必须放密钥管理服务，仓库里只留公钥。

## 九、断点验证

| 停在哪 | 看什么 | 看到什么证明走对了 |
|---|---|---|
| `JwtKeyLoader` 构造器第 37 行 | `privateKeyResource`、`privateKey.getAlgorithm()` | 路径是 `classpath:keys/jwt-private.pem`，算法 `RSA` |
| `AuthController.jwks` 第 35 行 | `key.getModulus().bitLength()` | `2048` |
| 第 44 行前 | `jwk` | 六个键齐全，`e = "AQAB"` |

## 十、自测题（不附答案）

1. 为什么私钥只放 `mall-user`，而公钥要公布成 `/auth/jwks`？
2. `kid` 在什么场景下才会真正用到第二把钥匙？
3. `n` 为什么必须先去掉 `0x00` 再 Base64URL？
