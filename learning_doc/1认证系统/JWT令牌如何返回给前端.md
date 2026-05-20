# JWT 令牌如何返回给前端

> **问题**：后端签发了 JWT 令牌对之后，是怎么把它给到前端的？哪段代码做了这件事？

---

## 一、答案先行：就是普通的 JSON HTTP 响应体

没有任何魔法。JWT 字符串就是作为**普通 JSON 字段**，放在 HTTP 响应体里返回的，和返回一个用户昵称字符串在技术上没有任何区别。

前端收到响应后，负责把令牌存起来（通常存 `localStorage` 或内存），后续每次请求时自己把 Access Token 塞进 `Authorization: Bearer <token>` 请求头。

---

## 二、完整的代码调用链

从令牌签发到返回前端，涉及 4 个类，按调用顺序逐一看：

```
前端 POST /api/v1/auth/login
        │
        ▼
① AuthController.login()        ← 接收 HTTP 请求，返回 HTTP 响应
        │  调用
        ▼
② AuthService.login()           ← 核心业务逻辑（验密、签发、审计）
        │  调用
        ▼
③ JwtService.issueTokenPair()   ← 真正生成 JWT 字符串
        │  返回
        ▼
④ TokenPair                     ← 内部传输对象，持有令牌字符串
        │  经 mapToken() 转换
        ▼
⑤ TokenResponse                 ← 面向前端的 DTO，被 Jackson 序列化成 JSON
        │  包装进
        ▼
⑥ AuthResponse                  ← 最终的 HTTP 响应体
```

---

## 三、逐层代码追踪

### 第①步：Controller 接收请求并返回响应

```java
// AuthController.java
@PostMapping("/login")
public AuthResponse login(@Valid @RequestBody LoginRequest request,
                          HttpServletRequest httpRequest) {
    return authService.login(request, resolveClient(httpRequest));
    //     ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
    //     直接 return，Spring MVC 会把返回值序列化为 JSON 响应体
}
```

关键点：Controller 方法标注了 `@RestController`（等价于 `@Controller + @ResponseBody`），这意味着返回值**不是视图名**，而是**直接被 Jackson 序列化为 JSON** 写入 HTTP 响应体。

### 第②步：AuthService 执行业务逻辑，组装响应

```java
// AuthService.java
public AuthResponse login(LoginRequest request, ClientInfo clientInfo) {
    // ...（标识校验、密码/验证码校验，省略）...

    // 1. 签发令牌对
    TokenPair tokenPair = jwtService.issueTokenPair(user);

    // 2. 把 Refresh Token 的 jti 存入 Redis 白名单
    storeRefreshToken(user.getId(), tokenPair);

    // 3. 记录审计日志
    loginLogService.record(user.getId(), identifier, channel,
                           clientInfo.ip(), clientInfo.userAgent(), "SUCCESS");

    // 4. 组装响应：用户信息 + 令牌信息，一起返回
    return new AuthResponse(mapUser(user), mapToken(tokenPair));
    //                      ^^^^^^^^^^^    ^^^^^^^^^^^^^^^^
    //                      用户信息 DTO   令牌 DTO
}
```

`mapToken()` 是一个简单的转换方法，把内部的 `TokenPair` 转为面向前端的 `TokenResponse`：

```java
// AuthService.java
private TokenResponse mapToken(TokenPair tokenPair) {
    return new TokenResponse(
        tokenPair.accessToken(),          // JWT 字符串
        tokenPair.accessTokenExpiresAt(), // 过期时间
        tokenPair.refreshToken(),         // JWT 字符串
        tokenPair.refreshTokenExpiresAt() // 过期时间
    );
}
```

### 第③步：JwtService 生成令牌字符串

```java
// JwtService.java
public TokenPair issueTokenPair(User user) {
    String refreshTokenId = UUID.randomUUID().toString(); // Refresh Token 的唯一 ID（jti）
    Instant issuedAt = Instant.now(clock);
    Instant accessExpiresAt  = issuedAt.plus(properties.getJwt().getAccessTokenTtl());  // 15分钟后
    Instant refreshExpiresAt = issuedAt.plus(properties.getJwt().getRefreshTokenTtl()); // 7天后

    // 生成 Access Token 字符串
    String accessToken  = encodeToken(user, issuedAt, accessExpiresAt,
                                      "access", UUID.randomUUID().toString());

    // 生成 Refresh Token 字符串
    String refreshToken = encodeRefreshToken(user, issuedAt, refreshExpiresAt, refreshTokenId);

    // 打包成 TokenPair 返回
    return new TokenPair(accessToken, accessExpiresAt,
                         refreshToken, refreshExpiresAt, refreshTokenId);
}
```

`encodeToken()` 内部用 `NimbusJwtEncoder` 把 Claims 对象序列化并用 RSA 私钥签名，最终得到 `eyJ...` 格式的字符串。

### 第④步：TokenPair —— 内部传输对象

```java
// TokenPair.java（Java record）
public record TokenPair(
    String accessToken,           // "eyJhbGciOiJSUzI1NiIs..." Access Token 字符串
    Instant accessTokenExpiresAt, // 过期时刻
    String refreshToken,          // "eyJhbGciOiJSUzI1NiIs..." Refresh Token 字符串
    Instant refreshTokenExpiresAt,// 过期时刻
    String refreshTokenId         // UUID，仅用于白名单存储，不发给前端
) {}
```

注意 `refreshTokenId` 字段：它是 Refresh Token 里 `jti` 声明的值，用于在 Redis 里建白名单。这个字段在 `mapToken()` 时被**丢掉了**，不会出现在给前端的响应里（前端不需要它，它只是后端内部的键）。

### 第⑤步：TokenResponse —— 面向前端的 DTO

```java
// TokenResponse.java（Java record）
public record TokenResponse(
    String accessToken,           // "eyJhbGciOiJSUzI1NiIs..."
    Instant accessTokenExpiresAt, // 2026-05-16T10:15:00Z
    String refreshToken,          // "eyJhbGciOiJSUzI1NiIs..."
    Instant refreshTokenExpiresAt // 2026-05-23T10:00:00Z
) {}
```

### 第⑥步：AuthResponse —— 最终响应体

```java
// AuthResponse.java（Java record）
public record AuthResponse(
    AuthUserResponse user,   // 用户信息
    TokenResponse token      // 令牌信息
) {}
```

`AuthUserResponse` 包含用户 ID、昵称、头像、手机号等信息，方便前端登录成功后直接展示用户信息，不用再发一次 `/me` 请求。

---

## 四、最终 HTTP 响应长什么样

前端收到的是一个标准 `200 OK` 响应，`Content-Type: application/json`，响应体如下：

```json
{
  "user": {
    "id": 10001,
    "nickname": "知光用户a3f8b2c1",
    "avatar": "https://static.zhiguang.cn/default-avatar.png",
    "phone": "138****8888",
    "zhId": null,
    "birthday": null,
    "school": null,
    "bio": null,
    "gender": null,
    "tagJson": "[]"
  },
  "token": {
    "accessToken": "eyJhbGciOiJSUzI1NiIsImtpZCI6InpoaWd1YW5nLWtleSJ9.eyJpc3MiOiJ6aGlndWFuZyIsInN1YiI6IjEwMDAxIiwidWlkIjoxMDAwMSwidG9rZW5fdHlwZSI6ImFjY2VzcyIsIm5pY2tuYW1lIjoi55+l5YWJ55So5oi3YTNmOGIyYzEiLCJqdGkiOiJ1dWlkLTEiLCJpYXQiOjE3NDY0MjQ0MDAsImV4cCI6MTc0NjQyNTMwMH0.签名",
    "accessTokenExpiresAt": "2026-05-16T10:15:00Z",
    "refreshToken": "eyJhbGciOiJSUzI1NiIsImtpZCI6InpoaWd1YW5nLWtleSJ9.eyJpc3MiOiJ6aGlndWFuZyIsInN1YiI6IjEwMDAxIiwidWlkIjoxMDAwMSwidG9rZW5fdHlwZSI6InJlZnJlc2giLCJqdGkiOiJ1dWlkLTIiLCJpYXQiOjE3NDY0MjQ0MDAsImV4cCI6MTc0NzAyOTIwMH0.签名",
    "accessTokenExpiresAt": "2026-05-23T10:00:00Z"
  }
}
```

**Jackson 自动完成了序列化**：Spring Boot 默认集成了 Jackson，`@RestController` 标注的 Controller 会自动用 Jackson 把返回的 Java 对象转成 JSON 字符串写入响应体，没有任何手动 `response.getWriter().write(json)` 这样的代码。

---

## 五、为什么不用 Cookie / Set-Cookie？

你可能想到：很多系统用 `Set-Cookie` 响应头来传递 Session ID，为什么这里不用？

本项目用 JSON Body 传递 JWT，有意为之：

| 方案 | 优点 | 缺点 |
|---|---|---|
| `Set-Cookie` + `HttpOnly` | 自动携带，防 XSS（JS 无法读取） | 有 CSRF 风险；前端无法控制携带时机 |
| **响应体 JSON（本项目）** | 前端完全控制；天然跨域友好；适合 App/小程序 | JS 可以读取，需防 XSS |

本项目的 `SecurityConfig` 也配置了：
- `csrf(AbstractHttpConfigurer::disable)`：关闭了 CSRF 保护（因为不用 Cookie）
- `SessionCreationPolicy.STATELESS`：完全无状态，服务端不保存 Session

---

## 六、刷新令牌接口的响应格式（稍有不同）

`POST /api/v1/auth/token/refresh` 只返回 `TokenResponse`，不返回用户信息（刷新时已知道是谁了）：

```java
// AuthController.java
@PostMapping("/token/refresh")
public TokenResponse refresh(@Valid @RequestBody TokenRefreshRequest request) {
    return authService.refresh(request);
    // 直接返回 TokenResponse，不包 AuthResponse
}
```

对应响应体：

```json
{
  "accessToken": "eyJ...",
  "accessTokenExpiresAt": "2026-05-16T10:30:00Z",
  "refreshToken": "eyJ...",
  "refreshTokenExpiresAt": "2026-05-23T10:00:00Z"
}
```

---

## 七、一句话总结

> JWT 令牌就是两个普通字符串字段，被装进 `TokenResponse` DTO，再被 Jackson 自动序列化成 JSON，通过 HTTP `200 OK` 的响应体传给前端。没有 Cookie，没有 Header，就是最普通的 JSON Body。

