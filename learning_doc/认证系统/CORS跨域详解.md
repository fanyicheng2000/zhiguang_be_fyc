# CORS 跨域详解

> **你的问题**：CORS 跨域到底是什么东西？CorsFilter 具体做了什么？我对这个概念一直有很大的疑惑。

---

## 一、从"为什么需要 CORS"开始理解

### 先搞清楚一个前置问题：谁在发请求？

你的疑问是："发请求不是浏览器的事吗，和网页有什么关系？"

这里需要区分两种完全不同的情况：

**情况A：你在浏览器地址栏输入一个 URL，然后回车**

这是**你（用户）** 在操纵浏览器发请求。浏览器作为你的代理去取回网页，没有任何限制。

**情况B：网页加载完之后，网页里的 JavaScript 代码自动发出请求**

这是**网页里的代码** 在驱动浏览器发请求。这两种情况的主体完全不同——前者是"你控制浏览器"，后者是"代码控制浏览器"。

```
情况A（你控制）：
  你 → 地址栏输入 bank.com → 回车 → 浏览器发请求 → 返回网页内容 ✅
  （无任何限制，你想访问哪就访问哪）

情况B（代码控制）：
  你打开了 evil.com 这个网站
  evil.com 的 JS 代码悄悄执行：
    fetch('https://bank.com/api/transfer?to=攻击者&amount=10000')
  这是"evil.com 的代码"在驱动你的浏览器去请求 bank.com ！
```

**同源策略限制的就是情况B**：网页里的 JavaScript 代码发出的跨域请求。

对你在地址栏输入 URL 访问网站，同源策略没有任何限制。

---

### 为什么要限制情况B？一个具体的攻击场景

假设你登录了网银 `bank.com`，银行在你的浏览器里存了一个登录 Cookie（证明你已经登录了）。这个 Cookie 和你的 `bank.com` 绑定，浏览器每次访问 `bank.com` 时都会自动带上它。

然后，你点开了一条微信消息里的链接，打开了恶意网站 `evil.com`。

```
evil.com 的页面加载后，它的 JS 代码悄悄执行：

fetch('https://bank.com/api/transfer', {
    method: 'POST',
    body: JSON.stringify({ to: '攻击者账号', amount: 10000 }),
    credentials: 'include'  // 告诉浏览器：发请求时带上 bank.com 的 Cookie
});
```

**关键点**：这段代码是在**你的浏览器**里运行的，所以：
- 浏览器认为这是来自你电脑的请求
- 请求携带了你的 bank.com 登录 Cookie
- 银行服务器收到请求，验证 Cookie 有效，执行了转账

你什么都不知道，钱就没了。这就是 CSRF 攻击（跨站请求伪造）。

---

### 同源策略如何阻止这个攻击

浏览器内置了同源策略：**当网页里的 JS 代码发出请求时，如果请求的目标和当前网页不"同源"，浏览器会拦截对响应的读取**。

"同源"的定义是：**协议 + 域名 + 端口** 三者完全一致。

```
当前网页：https://evil.com
发出请求：https://bank.com/api/transfer

协议：都是 https  ✅
域名：evil.com vs bank.com  ❌ 不同！

→ 跨域！浏览器介入：这个请求由 evil.com 的代码发出，
  但目标是 bank.com，不同源，我要管一管。
```

有了同源策略，上面的攻击会被这样阻断：

```
evil.com 的 JS 发出请求 → 浏览器检查：跨域请求
                         → 浏览器询问 bank.com：你允许 evil.com 访问吗？
                         → bank.com 没有声明允许 evil.com
                         → 浏览器拦截响应，evil.com 的 JS 代码收不到任何数据
                         → 攻击失败
```

**一个重要细节**：浏览器拦截的是"JS 代码读取响应"，而不是"请求本身"。实际上请求已经发到 bank.com 服务器了，服务器也处理了。但浏览器不把响应内容交给 evil.com 的 JS。

这对于 GET 请求（读数据）来说已经足够了——攻击者读不到数据。
但对 POST 请求（写操作，如转账）来说，光拦截响应不够——请求已经执行了！

这就是为什么 Cookie + POST 请求的场景还需要 CSRF Token 等额外防护。

---

### 同源策略的完整示意

```
https://zhiguang.cn/index.html 里的 JS 代码向以下地址发请求：

https://zhiguang.cn/api/login         ← 同源（协议、域名、端口都一样）✅ 无限制
http://zhiguang.cn/api/login          ← 不同源（协议不同：https vs http）❌ 受限
https://api.zhiguang.cn/login         ← 不同源（子域名不同）❌ 受限
https://zhiguang.cn:8080/api/login    ← 不同源（端口不同：默认443 vs 8080）❌ 受限
https://other.com/api/login           ← 不同源（域名完全不同）❌ 受限
```

注意：这些限制只针对**网页 JS 代码发出的请求**。
- 用 curl、Postman 发请求：没有同源策略，任意访问
- 在地址栏输入 URL：没有同源策略，任意访问
- 服务器之间互相调用 API：没有同源策略，任意访问
- 只有**浏览器里运行的 JS 代码**才受到同源策略约束

---

### 同源策略保护的核心本质

同源策略保护的不是服务器，而是**你的登录状态不被其他网站的代码利用**。

```
没有同源策略的世界：
  任何网站的 JS 代码都能用你的身份（Cookie）偷偷操作其他网站
  你打开 evil.com → evil.com 的代码用你的身份登录 bank.com → 转账 → 完成

有了同源策略的世界：
  evil.com 的代码发出的跨域请求，bank.com 的响应被浏览器拦截
  evil.com 的代码读不到响应，也无法确认操作是否成功
  （配合 CSRF Token，连发出写操作请求本身也能被拦截）
```

---

## 二、CORS：被允许的跨源访问

### CORS 是什么

**CORS（Cross-Origin Resource Sharing，跨源资源共享）** 是一种机制，让服务器声明"我允许哪些来源的网站访问我"，从而有选择地放宽同源策略的限制。

本质上，CORS 是浏览器和服务器之间的一个协商过程：

```
浏览器对前端 JS 说："你想跨域访问 api.zhiguang.cn？
                       你得等我先问问它同不同意。"

浏览器问服务器：     "我是 zhiguang.cn 的前端，你允许我访问吗？"

服务器回答：         "允许。"  （通过响应头声明）

浏览器才让 JS 收到响应。
```

如果服务器没有声明允许，浏览器就拦截响应，前端代码收不到任何数据（但请求已经到达服务器了！）。

---

## 三、两种 CORS 请求类型

浏览器并不是对所有跨域请求都一视同仁，而是把它们分成了两类，处理方式完全不同。

---

### 类型一：简单请求（Simple Request）

**什么样的请求算"简单请求"？**

同时满足以下两个条件，才算简单请求：
1. HTTP 方法是 `GET`、`HEAD`、`POST` 之一
2. 请求头里**没有**自定义字段（比如 `Authorization`），`Content-Type` 只能是 `text/plain`、`multipart/form-data`、`application/x-www-form-urlencoded` 三种之一

**为什么叫"简单"？** 因为这类请求是早期 HTML 表单本来就能发出的——浏览器在没有 JS 的时候就能发 GET/POST 表单，这些请求被认为是"低风险"的，不需要额外确认。

**简单请求的流程：**

```
① 前端 JS 发请求（例：从 zhiguang.cn 请求 api.zhiguang.cn 的数据）

   浏览器直接发出请求，但自动带上 Origin 头：
   ─────────────────────────────────────────
   GET /api/knowposts/feed HTTP/1.1
   Host: api.zhiguang.cn
   Origin: https://zhiguang.cn        ← 浏览器自动加的，告诉服务器"我从哪来"
   ─────────────────────────────────────────

② 服务器收到请求，正常处理，在响应里加上 CORS 响应头：
   ─────────────────────────────────────────
   HTTP/1.1 200 OK
   Access-Control-Allow-Origin: *     ← 声明：我允许所有来源访问
   Content-Type: application/json
   { "posts": [...] }
   ─────────────────────────────────────────

③ 浏览器收到响应，检查 Access-Control-Allow-Origin：
   有这个头，且值包含当前 Origin（或是 *）→ 放行，JS 可以读到数据 ✅
   没有这个头                              → 拦截！JS 报错：CORS Error ❌
```

**注意**：简单请求是"先打后问"——请求已经发出去了，服务器也处理了，只是浏览器最后决定要不要把响应给 JS 看。

---

### 类型二：预检请求（Preflight Request）

**什么时候会触发预检？**

只要满足以下任意一个条件，就会触发预检：
- HTTP 方法是 `PUT`、`DELETE`、`PATCH` 等
- 请求头里包含自定义字段，比如 `Authorization: Bearer xxx`（本项目大量使用！）
- `Content-Type` 是 `application/json`（本项目所有 POST 接口都是！）

**所以：本项目几乎所有接口都会触发预检请求。**

**为什么这类请求需要预检？**

简单说：因为这类请求"破坏力更大"。
- `DELETE /api/user/123` 会删除数据，如果不提前确认，请求一发出去数据就没了
- 带 `Authorization` 头意味着携带了身份凭证，需要服务器明确表示"我信任你"

预检相当于在真正做事之前先打个招呼，确认服务器同意，再行动。

**预检流程（以前端调用登录接口为例）：**

```
前端代码：
fetch('https://api.zhiguang.cn/api/v1/auth/login', {
    method: 'POST',
    headers: {
        'Content-Type': 'application/json'   ← 触发预检！
    },
    body: JSON.stringify({ ... })
})

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
第一步：浏览器"偷偷"自动发一个 OPTIONS 预检请求
（注意：这一步是浏览器自己发的，前端代码没有写这个，你甚至感知不到）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
OPTIONS /api/v1/auth/login HTTP/1.1
Host: api.zhiguang.cn
Origin: https://zhiguang.cn
Access-Control-Request-Method: POST              ← "我打算用 POST 方法"
Access-Control-Request-Headers: Content-Type     ← "我打算带这些请求头"

含义：浏览器在询问服务器："我是 zhiguang.cn 的前端，
      我打算发一个带 Content-Type 头的 POST 请求，你允许吗？"

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
第二步：服务器（CorsFilter）回答这个"询问"
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
HTTP/1.1 200 OK
Access-Control-Allow-Origin: *
Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS
Access-Control-Allow-Headers: Authorization, Content-Type, X-Requested-With
Access-Control-Max-Age: 1800        ← 这个预检结果缓存 30 分钟，期间不用重复预检

含义：服务器回答说："可以，你用 POST 带 Content-Type 头来访问我，我允许。
      而且这个结论你记住半小时，半小时内你不用再来问我。"

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
第三步：浏览器得到许可，这才发出真正的 POST 请求
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
POST /api/v1/auth/login HTTP/1.1
Host: api.zhiguang.cn
Origin: https://zhiguang.cn
Content-Type: application/json
{ "identifier": "...", "password": "..." }

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
第四步：服务器处理登录业务，返回响应
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
HTTP/1.1 200 OK
Access-Control-Allow-Origin: *      ← 再次告诉浏览器允许访问
{ "user": {...}, "tokens": {...} }
```

总结对比：

```
简单请求（低风险）：直接发 → 服务器处理 → 浏览器决定要不要给 JS 看响应
预检请求（高风险）：先问  → 服务器批准 → 再发真正的请求 → 浏览器决定要不要给 JS 看响应
```

---

## 四、本项目的 CORS 配置解析

上面搞清楚了 CORS 的运作机制，现在来看本项目里具体是怎么配的，以及每行代码的作用。

### 配置代码

```java
// SecurityConfig.java
@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(List.of("*"));
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
    configuration.setAllowCredentials(false);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
}
```

这个 Bean 的作用：告诉 `CorsFilter`，当它收到跨域请求时，按照这份配置来决定"允不允许"。

---

### 逐行解析

**① `setAllowedOrigins(List.of("*"))` — 允许哪些网站来访问**

`Origin` 就是"来访者的网站地址"。这里设置 `"*"` 表示任何网站都可以访问本后端。

```
具体场景：
  前端地址是 https://zhiguang.cn，它调用 https://api.zhiguang.cn/...
  请求里带 Origin: https://zhiguang.cn

  服务器检查：allowedOrigins 是 *，zhiguang.cn 匹配 → 允许
  响应里加：  Access-Control-Allow-Origin: *

  如果 allowedOrigins 设的是 ["https://zhiguang.cn"]（白名单）：
    zhiguang.cn 来 → 允许
    evil.com 来    → 拒绝（不在白名单），不加 CORS 响应头，浏览器拦截
```

代码注释里写了 `TODO replace with product whitelist`，说明开发阶段为了方便设了 `*`，上线前应该改成具体的前端域名，这样只有官方前端能调用 API，第三方网站无法跨域调用。

---

**② `setAllowedMethods(...)` — 允许哪些 HTTP 方法**

```
这行声明的意思是：
  前端来一个预检请求，问"我能用 DELETE 方法吗？" → 能，在列表里
  前端来一个预检请求，问"我能用 PATCH 方法吗？" → 不能，不在列表里，预检失败

OPTIONS 必须包含在里面！
原因：预检请求本身就是 OPTIONS 方法发出的，
      如果不允许 OPTIONS，那预检请求自己都会被拒绝，
      导致所有需要预检的接口全部失败。
```

---

**③ `setAllowedHeaders(...)` — 允许前端在请求里携带哪些自定义请求头**

```
Authorization:     允许前端携带 "Authorization: Bearer eyJxxx..." 这个头
                   → 这是 JWT 认证的核心！不加这个，所有需要登录的接口都会在预检阶段被拒

Content-Type:      允许前端发 JSON（Content-Type: application/json）
                   → 如果不配，带 JSON 的请求会被拒绝

X-Requested-With:  一个老式约定，jQuery 等 AJAX 库会自动添加这个头，
                   标识这是一个 AJAX 请求而不是普通浏览器跳转
```

假设这里漏写了 `Authorization`，会发生什么：

```
前端发预检：
  Access-Control-Request-Headers: Authorization, Content-Type

CorsFilter 检查：
  Authorization 不在 allowedHeaders 里 → 预检失败，返回 403

前端报错：
  CORS Error: Request header 'Authorization' is not allowed by header policy

  → 前端永远拿不到登录后的数据，即使用户已经登录了
```

---

**④ `setAllowCredentials(false)` — 是否允许携带 Cookie**

这里有一个浏览器的强制规定，很多人会踩坑：

```
规定：如果 allowCredentials = true，那么 allowedOrigins 就不能是 "*"，
      必须是具体的域名列表，否则浏览器直接报错拒绝。

原因：
  如果同时允许"所有来源"又允许"携带 Cookie"，
  那任何网站都能带着你的 Cookie 来调接口，
  同源策略就完全形同虚设了，太危险了。
  浏览器直接在规范层面禁止了这种组合。

本项目的选择：
  认证用 JWT（存在 localStorage），不用 Cookie
  所以 allowCredentials = false，allowedOrigins 可以安全地设 *
```

---

**⑤ `source.registerCorsConfiguration("/**", configuration)` — 对哪些路径生效**

`"/**"` 是一个路径通配符，表示"所有路径"。

这行的意思是：上面这套 CORS 规则，对本服务的所有 URL 都生效。

如果你想对不同路径设不同的 CORS 规则，也可以这样写：

```java
// 公开 API 允许所有来源
source.registerCorsConfiguration("/api/public/**", publicConfig);
// 管理接口只允许内网
source.registerCorsConfiguration("/api/admin/**", adminConfig);
```

---

## 五、CorsFilter 的具体执行步骤

现在把前面所有内容串起来，看 `CorsFilter` 面对一个真实的"前端调用需要登录的接口"时，完整的执行过程。

**场景：已登录用户在 `zhiguang.cn` 上请求 `api.zhiguang.cn` 的知识贴列表**

前端代码大概是这样的：

```javascript
fetch('https://api.zhiguang.cn/api/v1/knowposts', {
    method: 'GET',
    headers: {
        'Authorization': 'Bearer eyJhbGciOiJSUzI1NiJ9...'  // 登录时拿到的 JWT
    }
})
```

因为有 `Authorization` 自定义头，这会触发预检。

---

### 第一轮：浏览器自动发送 OPTIONS 预检

```
━━━━━━━━━━ 浏览器 → 服务器 ━━━━━━━━━━

OPTIONS /api/v1/knowposts HTTP/1.1
Host: api.zhiguang.cn
Origin: https://zhiguang.cn
Access-Control-Request-Method: GET
Access-Control-Request-Headers: Authorization

━━━━━━━━━━ CorsFilter 收到后，逐步检查 ━━━━━━━━━━

步骤1：这是 OPTIONS 请求，且带有 Access-Control-Request-Method
       → 识别为"预检请求"，走预检处理逻辑

步骤2：读取 Origin: https://zhiguang.cn
       检查 allowedOrigins = ["*"]
       → * 通配，zhiguang.cn 通过 ✅

步骤3：读取 Access-Control-Request-Method: GET
       检查 allowedMethods = ["GET", "POST", "PUT", "DELETE", "OPTIONS"]
       → GET 在列表里，通过 ✅

步骤4：读取 Access-Control-Request-Headers: Authorization
       检查 allowedHeaders = ["Authorization", "Content-Type", "X-Requested-With"]
       → Authorization 在列表里，通过 ✅

步骤5：三项全部通过，构造预检响应：

━━━━━━━━━━ 服务器 → 浏览器 ━━━━━━━━━━

HTTP/1.1 200 OK
Access-Control-Allow-Origin: *
Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS
Access-Control-Allow-Headers: Authorization, Content-Type, X-Requested-With
Access-Control-Max-Age: 1800

注意：到这一步，后面的所有过滤器（Spring Security 的 JWT 验证等）
     都没有被触发！CorsFilter 处理完预检直接返回，不往下走。
     真正的业务逻辑一行都没执行。
```

---

### 第二轮：浏览器确认许可，发送真正的 GET 请求

```
━━━━━━━━━━ 浏览器 → 服务器 ━━━━━━━━━━

GET /api/v1/knowposts HTTP/1.1
Host: api.zhiguang.cn
Origin: https://zhiguang.cn
Authorization: Bearer eyJhbGciOiJSUzI1NiJ9...

━━━━━━━━━━ CorsFilter 收到后 ━━━━━━━━━━

这次是真正的 GET 请求（不是 OPTIONS），走普通跨域处理逻辑：

步骤1：读取 Origin: https://zhiguang.cn，检查 allowedOrigins
       → 通过 ✅

步骤2：在响应头里预先标记要加的 CORS 头：
       Access-Control-Allow-Origin: *
       （还没有响应体，这只是一个标记，等业务处理完再一起返回）

步骤3：放行！将请求传递给后续过滤器链
       → BearerTokenAuthenticationFilter（解析 JWT，验证身份）
       → AuthorizationFilter（检查权限）
       → DispatcherServlet → Controller（执行业务逻辑）

━━━━━━━━━━ 业务处理完毕，服务器 → 浏览器 ━━━━━━━━━━

HTTP/1.1 200 OK
Access-Control-Allow-Origin: *          ← CorsFilter 加的
Content-Type: application/json
{ "posts": [...] }

━━━━━━━━━━ 浏览器收到响应 ━━━━━━━━━━

检查 Access-Control-Allow-Origin: * → 有！且值包含当前 Origin
→ 放行，JS 代码可以读取响应数据 ✅
```

---

### 如果 Origin 不在允许列表里（模拟攻击场景）

```
假设攻击者网站 evil.com 的 JS 发出请求：

━━━━━━━━━━ 浏览器（被 evil.com 操控） → 服务器 ━━━━━━━━━━

OPTIONS /api/v1/knowposts HTTP/1.1
Origin: https://evil.com               ← 来自 evil.com
Access-Control-Request-Method: GET
Access-Control-Request-Headers: Authorization

━━━━━━━━━━ CorsFilter 检查 ━━━━━━━━━━

等等——本项目 allowedOrigins = ["*"]，所以 evil.com 也会通过！

这正是代码注释里说要在生产环境替换白名单的原因：
如果 allowedOrigins 改为 ["https://zhiguang.cn"]：
  evil.com 来的预检 → Origin 不在白名单 → 预检失败 → 后续真实请求也无法被 JS 读取

所以当前开发阶段 allowedOrigins = * 是有安全风险的，
只是开发环境下为了方便调试才这么设置。
```

---

## 六、一个常见误解：CORS 保护的是谁？

很多人以为 CORS 是为了保护服务器，实际上不是。

**CORS 保护的是用户**，而不是服务器。

```
重要事实：
  CORS 校验发生在"浏览器"里，而不是服务器里。
  服务器实际上收到了请求、处理了请求、返回了响应。
  是浏览器决定"要不要把响应给前端 JS 看"。

如果攻击者用 curl 或 Postman 发请求（不经过浏览器），CORS 完全没有作用！
```

CORS 的保护目标是：**防止恶意网站的 JavaScript 代码在用户不知情的情况下利用用户的身份（Cookie）向其他域发请求，并读取响应**。

---

## 七、为什么本项目不怕 CSRF？

你可能想到：同源策略主要防 CSRF，本项目关掉了 CSRF 保护（`csrf(AbstractHttpConfigurer::disable)`），是否有风险？

```java
// SecurityConfig.java
.csrf(AbstractHttpConfigurer::disable)  // 关闭 CSRF
```

**关闭 CSRF 是安全的，因为本项目不用 Cookie**。

CSRF 攻击的前提是：浏览器会自动携带目标域的 Cookie。
本项目的认证凭证（JWT）存在 `localStorage` 里，需要前端 JavaScript 主动读取并添加到 `Authorization` 头——**浏览器不会自动携带，恶意网站的 JS 也无法读取（受同源策略限制）**。

所以：
- 用 Cookie 认证 → 需要 CSRF 保护
- 用 localStorage + Authorization 头认证 → 不需要 CSRF 保护，但要防 XSS

---

## 八、一句话总结

> CORS 是浏览器的同源策略 + 服务器声明"谁可以访问我"的协商机制。`CorsFilter` 的工作是：检查请求的 `Origin` 是否在允许列表内，是则在响应里加 `Access-Control-Allow-Origin` 头让浏览器放行，否则不加该头让浏览器拦截——保护的是用户不被恶意网站利用，而不是保护服务器本身。

