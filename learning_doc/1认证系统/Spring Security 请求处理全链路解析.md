# Spring Security 请求处理全链路解析

> **目标**：彻底搞清楚一个 HTTP 请求到达后端之后，Spring Security 这个"黑盒"是如何一步步把它解析成"已登录用户"或拒绝它的。本文以本项目的实际代码为依据，不讲虚的。

---

## 一、首先纠正一个概念：它不是"网关层"

你说的"网关层"在微服务架构里通常是指独立部署的 API Gateway（如 Spring Cloud Gateway），负责路由、限流、全局鉴权。

而本项目是**单体 Spring Boot 应用**，没有独立网关。你感受到的那个"黑盒"是 **Spring Security 的 Servlet 过滤器链（Filter Chain）**，它运行在 **同一个 JVM 进程内**，是 Tomcat Servlet 容器里的一组过滤器。

理解这一点很重要：请求根本没有离开当前进程，Spring Security 只是在请求到达 Controller 之前，在 Servlet 过滤器这一层拦截并处理它。

---

## 二、整体架构：从 TCP 到 Controller 的完整路径

```
客户端 HTTP 请求
      │
      ▼
[Tomcat / Embedded Servlet Container]
      │  接收 TCP 连接，解析 HTTP 报文，封装为 HttpServletRequest
      │
      ▼
[Servlet FilterChain（javax/jakarta 标准）]
      │  Spring Security 在这里插入了一个特殊过滤器：
      │  ──────────────────────────────────────────
      │  DelegatingFilterProxy
      │    └─► FilterChainProxy（Spring Security 的总代理）
      │          └─► SecurityFilterChain（本项目配置的那条链）
      │                ├─ CorsFilter               ← 处理 CORS 跨域
      │                ├─ SecurityContextPersistenceFilter  ← 初始化安全上下文
      │                ├─ ...（其他内置过滤器）
      │                ├─ BearerTokenAuthenticationFilter  ← ★核心：解析 JWT
      │                ├─ ExceptionTranslationFilter  ← 捕获鉴权异常，返回 401/403
      │                └─ AuthorizationFilter         ← ★核心：校验路径权限
      │
      ▼
[DispatcherServlet（Spring MVC）]
      │  根据 URL 路由到对应的 Controller 方法
      │
      ▼
[Controller 方法执行]
      │  可以通过 @AuthenticationPrincipal Jwt jwt 直接拿到已解析的令牌
      ▼
[返回响应]
```

---

## 三、Spring Security 如何被"激活"：DelegatingFilterProxy 的作用

### 3.1 标准 Servlet Filter 的注册

Tomcat 处理请求时，会按顺序执行所有注册的 `javax.servlet.Filter`。Spring Boot 在启动时，会向 Tomcat 注册一个叫做 **`DelegatingFilterProxy`** 的特殊过滤器。

**`DelegatingFilterProxy` 的职责**：它本身是一个标准 Servlet Filter，但它不做任何安全逻辑。它的唯一工作是：**懒加载 Spring 容器中名为 `springSecurityFilterChain` 的 Bean，并把请求委托给它**。

```
Tomcat 调用 DelegatingFilterProxy.doFilter(request, response, chain)
    │
    └─► 去 Spring ApplicationContext 中找 Bean "springSecurityFilterChain"
          │
          └─► 这个 Bean 就是 FilterChainProxy
```

这个设计是为了解耦：Tomcat 不需要知道 Spring 的 Bean，`DelegatingFilterProxy` 作为桥接者连通两个世界。

### 3.2 FilterChainProxy：Spring Security 的真正入口

`FilterChainProxy` 持有一个 `SecurityFilterChain` 列表（本项目只配置了一条）。当请求来了，它：

1. 遍历所有 `SecurityFilterChain`，找到第一个 `matches(request)` 返回 `true` 的链；
2. 把请求在那条链的所有过滤器中依次传递。

本项目的 `SecurityConfig.securityFilterChain()` 方法返回的 `SecurityFilterChain` 就是这个链：

```java
// SecurityConfig.java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .csrf(AbstractHttpConfigurer::disable)
        .cors(Customizer.withDefaults())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/v1/auth/login", ...).permitAll()
            .anyRequest().authenticated()
        )
        .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));
    return http.build();
}
```

调用 `http.build()` 时，Spring Security 内部根据你的配置，自动组装出具体的过滤器列表并封装成 `SecurityFilterChain` 返回。

---

## 四、过滤器链详解：逐个过滤器的职责

下面按实际执行顺序列出关键过滤器（Spring Security 6.x 默认约有 16 个）：

### 4.1 SecurityContextHolderFilter（第1位附近）

**职责**：为每个请求提供一个干净的 `SecurityContext` 容器（线程局部变量 `ThreadLocal`），请求结束后清理。

```
┌─ SecurityContextHolder（ThreadLocal<SecurityContext>）
│    └─ SecurityContext
│         └─ Authentication（初始为 null，认证成功后被赋值）
└─ 请求结束后自动清空，防止线程复用时泄漏
```

**为什么要这个？** `SecurityContextHolder` 是一个全局静态持有者，后续所有组件（包括你的 Controller）都通过 `SecurityContextHolder.getContext().getAuthentication()` 获取当前用户信息。这个过滤器保证每个请求开始时都有空的上下文。

**无状态模式（本项目的配置）**：由于配置了 `SessionCreationPolicy.STATELESS`，Spring Security **不会把 `SecurityContext` 存到 `HttpSession`**，也不会从 Session 中读取。每个请求都必须自己携带凭证（JWT）并重新认证。

### 4.2 CorsFilter（CORS 跨域处理）

**触发时机**：每个请求都会经过，无论是否携带 JWT。

**职责**：检查请求的 `Origin` 头，与 `corsConfigurationSource()` Bean 里定义的规则比对：

```java
// SecurityConfig.java
@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(List.of("*"));
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
    configuration.setAllowCredentials(false);
    // ...
}
```

- 若是 **预检请求（OPTIONS）**：直接返回 200，不继续往后传；
- 若是正常请求且跨域合法：在响应里加上 `Access-Control-Allow-Origin` 等头，然后继续；
- 若跨域不合法：直接返回 403。

### 4.3 BearerTokenAuthenticationFilter（★最关键的过滤器）

这个过滤器是 `.oauth2ResourceServer(oauth -> oauth.jwt(...))` 这行配置自动注册进来的，是整个 JWT 认证的核心。

**职责**：从请求头中提取 Bearer Token，并驱动完整的 JWT 认证流程。

**详细步骤如下**：

#### 步骤 1：提取 Token 字符串

过滤器内部有一个 `BearerTokenExtractor`，专门从 HTTP 请求中提取令牌：

```
客户端发送请求头：
Authorization: Bearer eyJhbGciOiJSUzI1NiIsImtpZCI6InpoaWd1YW5nLWtleSJ9...

BearerTokenExtractor 执行：
  1. 读取 "Authorization" 请求头
  2. 检查是否以 "Bearer " 开头（大小写不敏感）
  3. 截取 "Bearer " 之后的部分，得到 JWT 字符串
  4. 创建 BearerTokenAuthenticationToken（一个未认证的认证对象）
```

如果请求**没有 `Authorization` 头**，或者不是 `Bearer` 格式：
- 不抛出异常，直接放行，`SecurityContext` 中的 `Authentication` 保持 `null`；
- 后续由 `AuthorizationFilter` 决定是否需要认证（如果路径是 `permitAll` 则通过，否则 401）。

#### 步骤 2：触发 AuthenticationManager 认证

提取到 Token 字符串后，`BearerTokenAuthenticationFilter` 调用 `AuthenticationManager.authenticate(token)`，把这个未认证的 Token 交给认证管理器处理。

#### 步骤 3：JwtAuthenticationProvider 解码并校验 JWT

`AuthenticationManager` 会找到能处理 `BearerTokenAuthenticationToken` 的 `AuthenticationProvider`，这里是 **`JwtAuthenticationProvider`**（由 `.oauth2ResourceServer(oauth -> oauth.jwt(...))` 自动注册）。

`JwtAuthenticationProvider` 的处理流程：

```
JwtAuthenticationProvider.authenticate(authentication)
      │
      ▼
   1. 从 BearerTokenAuthenticationToken 取出 JWT 字符串
      │
      ▼
   2. 调用 JwtDecoder.decode(tokenValue) ─────────────────────────────────
      │                                                                   │
      │  本项目的 JwtDecoder 在 AuthConfiguration 里定义：               │
      │  NimbusJwtDecoder.withPublicKey(rsaPublicKey).build()            │
      │                                                                   │
      │  NimbusJwtDecoder 内部做了 5 件事：                              │
      │  ① 将 JWT 字符串按 "." 分割成 Header、Payload、Signature        │
      │  ② Base64Url 解码 Header，读取 alg（应为 RS256）和 kid          │
      │  ③ 用公钥（RSAPublicKey）验证 Signature 的数字签名               │
      │     - 如果签名无效 → 抛出 JwtException（令牌被篡改）            │
      │  ④ Base64Url 解码 Payload，解析所有 Claims（声明）              │
      │  ⑤ 校验标准声明：                                               │
      │     - exp（过期时间）：当前时间 > exp → 抛出 JwtException        │
      │     - nbf（不早于时间，如有）：当前时间 < nbf → 抛出异常         │
      │  ⑥ 返回 Jwt 对象（包含所有 Claims）                              │
      │                                                                   │
      ▼                                                                   │
   3. 将 Jwt 对象转换为 JwtAuthenticationToken（已认证状态）             │
      │   - principal（主体） = Jwt 对象本身                              │
      │   - authorities（权限）= 从 Claims 的 "scope"/"scp" 字段提取     │
      │     本项目没有设置 scope，所以 authorities 为空集合              │
      │   - authenticated = true                                          │
      │                                                                   │
      ▼                                                                   │
   4. 返回 JwtAuthenticationToken ──────────────────────────────────────┘
```

#### 步骤 4：存入 SecurityContextHolder

认证成功后，`BearerTokenAuthenticationFilter` 把 `JwtAuthenticationToken` 存入 `SecurityContextHolder`：

```java
SecurityContextHolder.getContext().setAuthentication(jwtAuthenticationToken);
```

**此时整个线程的安全上下文已经建立**。后续任何代码（Controller、Service、AOP）都可以通过 `SecurityContextHolder.getContext().getAuthentication()` 拿到当前用户信息。

#### 步骤 5：如果 JWT 校验失败

`NimbusJwtDecoder.decode()` 抛出 `JwtException` 时，`BearerTokenAuthenticationFilter` 会捕获它并调用 `authenticationFailureHandler`，默认行为是：

```
直接返回 401 Unauthorized，响应体：
{
  "error": "invalid_token",
  "error_description": "..."  // 具体失败原因
}
```

请求**不会继续往后传**，Controller 根本不会收到。

### 4.4 ExceptionTranslationFilter

**职责**：捕获后续过滤器（主要是 `AuthorizationFilter`）抛出的两类异常，并转换为对应的 HTTP 响应：

| 异常类型 | 含义 | 默认处理 |
|---|---|---|
| `AuthenticationException` | 未认证（没有 Token 或 Token 无效） | 调用 `AuthenticationEntryPoint`，默认返回 **401** |
| `AccessDeniedException` | 已认证但权限不足 | 调用 `AccessDeniedHandler`，默认返回 **403** |

在资源服务器模式下，`AuthenticationEntryPoint` 被替换为 `BearerTokenAuthenticationEntryPoint`，会在 401 响应里带上 `WWW-Authenticate: Bearer realm="..."` 头。

### 4.5 AuthorizationFilter（★权限校验）

**职责**：对照 `SecurityConfig` 里配置的 `authorizeHttpRequests` 规则，决定当前请求能不能通过。

本项目的规则（按顺序匹配，第一个匹配到的规则生效）：

```java
.authorizeHttpRequests(auth -> auth
    // 规则1：健康检查 - 直接放行
    .requestMatchers("/actuator/health", "/actuator/info").permitAll()
    // 规则2：公开 Feed - 直接放行
    .requestMatchers("/api/v1/knowposts/feed").permitAll()
    // 规则3：知文详情 GET - 直接放行
    .requestMatchers(HttpMethod.GET, "/api/v1/knowposts/detail/*").permitAll()
    // 规则4：RAG 问答 GET - 直接放行
    .requestMatchers(HttpMethod.GET, "/api/v1/knowposts/*/qa/stream").permitAll()
    // 规则5：认证相关接口 - 直接放行
    .requestMatchers("/api/v1/auth/send-code", "/api/v1/auth/register", ...).permitAll()
    // 规则6：其他所有请求 - 必须已认证
    .anyRequest().authenticated()
)
```

`AuthorizationFilter` 的逻辑：

```
当前请求 URL + HTTP Method
      │
      ▼
遍历规则列表，找到第一个匹配的规则
      │
      ├─ 规则是 permitAll() → 直接放行，不管 Authentication 是否为 null
      │
      └─ 规则是 authenticated() → 检查 SecurityContextHolder 里的 Authentication
              │
              ├─ Authentication != null 且 isAuthenticated() == true
              │     └─ 放行，请求继续到 DispatcherServlet
              │
              └─ Authentication == null 或 isAuthenticated() == false
                    └─ 抛出 AuthenticationException
                         └─ ExceptionTranslationFilter 捕获，返回 401
```

---

## 五、Controller 层如何获取用户信息

经过上述过滤器链后，请求到达 Controller。此时有两种方式获取已认证的用户：

### 方式一：@AuthenticationPrincipal Jwt（本项目使用的方式）

```java
// AuthController.java
@GetMapping("/me")
public AuthUserResponse me(@AuthenticationPrincipal Jwt jwt) {
    long userId = jwtService.extractUserId(jwt);
    return authService.me(userId);
}
```

`@AuthenticationPrincipal` 是 Spring Security 提供的参数注解，Spring MVC 在调用 Controller 方法时，会从 `SecurityContextHolder.getContext().getAuthentication()` 取出 `JwtAuthenticationToken`，然后取其 `getPrincipal()` 字段（就是 `Jwt` 对象）注入进来。

你不需要手动写任何代码从 Header 解析 JWT，Spring Security 已经帮你做好了，注入进来的 `Jwt` 对象已经是解码且校验通过的。

### 方式二：SecurityContextHolder.getContext().getAuthentication()

任何地方（包括 Service 层）都可以：

```java
Authentication auth = SecurityContextHolder.getContext().getAuthentication();
if (auth instanceof JwtAuthenticationToken jwtToken) {
    Jwt jwt = (Jwt) jwtToken.getPrincipal();
    // 从 jwt 里取 claims
}
```

### JwtService 如何从 Jwt 提取信息

```java
// JwtService.java - 令牌签发时写入的自定义声明
JwtClaimsSet claims = JwtClaimsSet.builder()
    .issuer("zhiguang")          // 标准 iss 声明
    .subject(String.valueOf(user.getId()))  // 标准 sub 声明（用户ID字符串）
    .id(tokenId)                 // 标准 jti 声明（令牌唯一ID）
    .expiresAt(accessExpiresAt)  // 标准 exp 声明
    .claim("token_type", "access")  // 自定义：令牌类型
    .claim("uid", user.getId())     // 自定义：用户ID（long类型）
    .claim("nickname", user.getNickname())  // 自定义：昵称
    .build();
```

`extractUserId()` 从 `"uid"` 这个自定义声明里取用户 ID（用 long 而不是 sub 里的字符串，省去解析步骤）：

```java
public long extractUserId(Jwt jwt) {
    Object claim = jwt.getClaims().get("uid");
    if (claim instanceof Number number) {
        return number.longValue();
    }
    // ...
}
```

---

## 六、一个请求的完整生命周期：两种场景

### 场景A：受保护接口 + 携带合法 JWT（如获取用户信息 GET /api/v1/auth/me）

```
客户端发送：
GET /api/v1/auth/me
Authorization: Bearer eyJhbGciOiJSUzI1NiIsImtpZCI6InpoaWd1YW5nLWtleSJ9...

─── Tomcat 接收，封装 HttpServletRequest ────────────────────────

─── DelegatingFilterProxy 委托给 FilterChainProxy ──────────────

─── SecurityFilterChain 按顺序执行：

[1] SecurityContextHolderFilter
    → 在 ThreadLocal 中创建空的 SecurityContext

[2] CorsFilter
    → 无 Origin 头（或同域），跳过 CORS 处理，继续

[3] BearerTokenAuthenticationFilter
    → 读取 Authorization 头，提取 JWT 字符串
    → BearerTokenAuthenticationToken(tokenValue="eyJ...")
    → 调用 AuthenticationManager.authenticate()
    → JwtAuthenticationProvider.authenticate()
        → NimbusJwtDecoder.decode("eyJ...")
            ① 解析 Header: {"alg":"RS256","kid":"zhiguang-key"}
            ② 用 RSAPublicKey 验证签名 → 验证通过
            ③ 解析 Payload: {"iss":"zhiguang","sub":"10001","uid":10001,
                              "token_type":"access","exp":1746...,"jti":"uuid..."}
            ④ 检查 exp > 当前时间 → 未过期
            → 返回 Jwt 对象
        → 构建 JwtAuthenticationToken(principal=Jwt, authenticated=true)
    → 返回 JwtAuthenticationToken
    → SecurityContextHolder.getContext().setAuthentication(jwtAuthenticationToken)

[4] ... 其他过滤器 ...

[5] ExceptionTranslationFilter
    → 无异常，继续

[6] AuthorizationFilter
    → 匹配规则 "anyRequest().authenticated()"
    → 检查 Authentication: JwtAuthenticationToken, isAuthenticated()=true
    → 放行 ✓

─── DispatcherServlet 路由到 AuthController.me() ────────────────

[Controller] AuthController.me(@AuthenticationPrincipal Jwt jwt)
    → Spring MVC 从 SecurityContextHolder 取出 JwtAuthenticationToken
    → getPrincipal() 得到 Jwt 对象，注入进来
    → jwtService.extractUserId(jwt) → 返回 10001L
    → authService.me(10001L) → 返回 AuthUserResponse

─── 返回 200 响应 ────────────────────────────────────────────────
```

### 场景B：受保护接口 + 无 JWT（未登录访问）

```
客户端发送：
GET /api/v1/auth/me
（无 Authorization 头）

[1] SecurityContextHolderFilter → 创建空 SecurityContext
[2] CorsFilter → 通过
[3] BearerTokenAuthenticationFilter
    → 读取 Authorization 头 → 不存在 / 没有 Bearer 前缀
    → 不做任何处理，直接 chain.doFilter() 继续
    → SecurityContext 中 Authentication 仍为 null
[4] ExceptionTranslationFilter → 注册异常捕获，继续
[5] AuthorizationFilter
    → 匹配规则 "anyRequest().authenticated()"
    → 检查 Authentication == null
    → 抛出 AuthenticationException
[6] ExceptionTranslationFilter 捕获 AuthenticationException
    → 调用 BearerTokenAuthenticationEntryPoint
    → 返回 HTTP 401 Unauthorized
       WWW-Authenticate: Bearer realm="..."

─── 请求结束，Controller 根本未被调用 ─────────────────────────
```

### 场景C：公开接口（如登录 POST /api/v1/auth/login）

```
客户端发送：
POST /api/v1/auth/login
Content-Type: application/json
{"identifier": "user@example.com", "password": "..."}

[3] BearerTokenAuthenticationFilter
    → 无 Authorization 头，直接跳过，Authentication 保持 null
[5] AuthorizationFilter
    → 匹配规则 ".requestMatchers("/api/v1/auth/login").permitAll()"
    → permitAll = 不检查 Authentication，直接放行 ✓

─── Controller 执行登录逻辑，自己做密码校验 ─────────────────────
```

---

## 七、JWT 本身的结构：Base64Url 编码的三段式

理解 Spring Security 在做什么，需要先搞清楚 JWT 的格式。一个 JWT 字符串长这样：

```
eyJhbGciOiJSUzI1NiIsImtpZCI6InpoaWd1YW5nLWtleSJ9   ← Header（Base64Url 编码）
.
eyJpc3MiOiJ6aGlndWFuZyIsInN1YiI6IjEwMDAxIiwidWlkIjoxMDAwMSwidG9rZW5fdHlwZSI6ImFjY2VzcyIsIm5pY2tuYW1lIjoidGVzdCIsImp0aSI6InV1aWQiLCJpYXQiOjE3NDY0MjQ0MDAsImV4cCI6MTc0NjQyNTMwMH0   ← Payload（Base64Url 编码）
.
SFlXuQ3...（数字签名，二进制后 Base64Url 编码）   ← Signature
```

解码后：

```json
// Header
{
  "alg": "RS256",       // 签名算法：RSA + SHA-256
  "kid": "zhiguang-key" // 密钥标识，对应 AuthConfiguration 里的 keyId
}

// Payload（Claims）
{
  "iss": "zhiguang",        // 签发者
  "sub": "10001",           // 主题（用户ID字符串）
  "uid": 10001,             // 自定义：用户ID（long）
  "token_type": "access",   // 自定义：令牌类型
  "nickname": "test",       // 自定义：用户昵称
  "jti": "uuid-...",        // JWT ID（令牌唯一标识）
  "iat": 1746424400,        // 签发时间（Unix 秒）
  "exp": 1746425300         // 过期时间（Unix 秒，15分钟后）
}
```

**为什么 Payload 可以被任何人 Base64 解码看到？** 因为 JWT 是**签名**，不是**加密**。任何人都能看到里面的内容，但是**只有持有私钥的签发者才能生成有效的签名**。公钥用来验证签名真实性，但无法伪造签名。所以不要把密码、余额等敏感信息放进 JWT。

---

## 八、RS256 签名的数学原理（简化版）

本项目使用 RSA 非对称加密算法 + SHA-256 哈希，而不是对称的 HMAC-SHA256：

```
签发（私钥操作，在本项目后端）：
  1. 将 Header.Payload 的 Base64Url 编码拼接
  2. 用 SHA-256 计算哈希值
  3. 用 RSA 私钥对哈希值加密 → 得到签名字节
  4. 将签名 Base64Url 编码，拼到末尾

验证（公钥操作，在 Spring Security 里）：
  1. 取出 Signature，Base64Url 解码
  2. 用 RSA 公钥对签名解密 → 得到原始哈希值
  3. 重新计算 Header.Payload 的 SHA-256 哈希
  4. 比较两个哈希值是否一致
  5. 一致 → 签名有效，令牌未被篡改
```

公钥在 `AuthConfiguration` 里从 PEM 文件读取：

```java
// AuthConfiguration.java
@Bean
public JwtDecoder jwtDecoder() {
    RSAPublicKey publicKey = PemUtils.readPublicKey(jwtProps.getPublicKey());
    return NimbusJwtDecoder.withPublicKey(publicKey).build();
}
```

PEM 文件读取（`PemUtils.java`）：
- 读取 `-----BEGIN PUBLIC KEY-----` ... `-----END PUBLIC KEY-----` 之间的内容
- 去掉换行，Base64 解码，得到 X.509 DER 格式的公钥字节
- 用 `KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes))` 构建 `RSAPublicKey` 对象

---

## 九、Refresh Token 为什么不走 Spring Security 验证

你可能注意到刷新令牌的接口是 `permitAll()`：

```java
.requestMatchers("/api/v1/auth/token/refresh").permitAll()
```

这是**刻意的设计**。刷新令牌校验走的是**业务逻辑层**，而不是 Spring Security 的自动过滤器：

```java
// AuthService.refresh() 的逻辑
public TokenResponse refresh(TokenRefreshRequest request) {
    // 1. 用 JwtDecoder 手动解码（验签+过期校验）
    Jwt jwt = jwtService.decode(request.refreshToken());
    // 2. 业务校验：确认是 refresh 类型，不是 access 类型
    if (!"refresh".equals(jwtService.extractTokenType(jwt))) {
        throw new IllegalArgumentException("Not a refresh token");
    }
    // 3. 白名单校验（查 Redis）：防止令牌复用攻击
    long userId = jwtService.extractUserId(jwt);
    String tokenId = jwtService.extractTokenId(jwt);
    if (!refreshTokenStore.isTokenValid(userId, tokenId)) {
        throw new IllegalArgumentException("Refresh token revoked");
    }
    // 4. 撤销旧令牌，签发新令牌对
    refreshTokenStore.revokeToken(userId, tokenId);
    // ...签发新的 TokenPair
}
```

**为什么要白名单？** 因为 JWT 本身是无状态的：只要签名有效且未过期，Spring Security 就会认它合法。如果 Refresh Token 被盗，攻击者可以一直刷新。通过在 Redis 里维护白名单，可以随时撤销令牌（登出、改密码时调用 `revokeAll(userId)`）。

Access Token 没有白名单，因为它只有 15 分钟有效期，代价可接受。Refresh Token 有 7 天有效期，必须有撤销机制。

---

## 十、总结：Spring Security 做了什么，没做什么

| 步骤 | Spring Security 做的 | 你的代码做的 |
|---|---|---|
| 从 `Authorization` 头提取 JWT 字符串 | ✅ `BearerTokenAuthenticationFilter` | ❌ |
| 验证 JWT 数字签名 | ✅ `NimbusJwtDecoder`（用公钥） | ❌ |
| 检查 JWT 是否过期（exp 字段） | ✅ `NimbusJwtDecoder` | ❌ |
| 解析 JWT Payload 为 Claims 对象 | ✅ `NimbusJwtDecoder` | ❌ |
| 存入 SecurityContextHolder | ✅ `BearerTokenAuthenticationFilter` | ❌ |
| 校验请求路径是否需要认证 | ✅ `AuthorizationFilter` | ❌（只负责配置规则） |
| 向 Controller 注入 Jwt 对象 | ✅ `@AuthenticationPrincipal` 解析器 | ❌ |
| 校验 Refresh Token 白名单 | ❌ | ✅ `AuthService.refresh()` |
| 校验令牌类型（access/refresh） | ❌ | ✅ `AuthService.refresh()` |
| 签发新 JWT | ❌ | ✅ `JwtService.issueTokenPair()` |
| 密码校验 | ❌ | ✅ `AuthService.login()` |
| 验证码校验 | ❌ | ✅ `AuthService.login()`/`register()` |

