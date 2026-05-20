# Cookie 与 Domain 详解

> 起因：工作中遇到一个问题——`switch` 接口的 `Set-Cookie` 响应头设置的 `Domain` 是
> `pass.grocery.test.sankuai.com`，但页面的 URL 是
> `https://fanyicheng02-pvdsk-sl-pass.grocery.test.sankuai.com/lps/`，
> 结果 Cookie 设置失败了。
>
> 这个问题涉及到 Cookie 的 Domain 规则、域名的层级结构、以及浏览器的安全限制。

---

## 一、先搞清楚域名的层级结构

要理解 Cookie 的 Domain 规则，首先要搞懂域名是怎么分层的。

以工作中遇到的域名为例：

```
fanyicheng02-pvdsk-sl-pass.grocery.test.sankuai.com

从右往左读，层级逐渐变细：

com                                        ← 顶级域（TLD）
└── sankuai.com                            ← 一级域（注册域）
    └── test.sankuai.com                   ← 二级子域
        └── grocery.test.sankuai.com       ← 三级子域
            └── pass.grocery.test.sankuai.com          ← 四级子域
                └── fanyicheng02-pvdsk-sl-pass.grocery.test.sankuai.com  ← 五级子域（页面所在）
```

**重要概念：父域 vs 子域**

```
对于 fanyicheng02-pvdsk-sl-pass.grocery.test.sankuai.com 来说：

它的父域（从直接父域到根域）依次是：
  pass.grocery.test.sankuai.com          ← 直接父域？❌ 等等，稍后解释
  grocery.test.sankuai.com               ← 父域 ✅
  test.sankuai.com                       ← 父域 ✅
  sankuai.com                            ← 父域 ✅

注意：pass.grocery.test.sankuai.com 不是
fanyicheng02-pvdsk-sl-pass.grocery.test.sankuai.com 的父域！
```

**为什么？** 这里有一个新手最容易犯的认知错误，下面专门解释。

---

## 二、最容易犯的错误：把"名字里包含"当成"父子关系"

直觉上看，`pass.grocery.test.sankuai.com` 这个字符串包含在
`fanyicheng02-pvdsk-sl-pass.grocery.test.sankuai.com` 里，感觉它是父域。

**但这是错的！**

域名的父子关系不是字符串包含关系，而是**从右往左按"点"切割的层级关系**：

```
域名结构：[最左子域].[父域].[父域的父域]...

fanyicheng02-pvdsk-sl-pass.grocery.test.sankuai.com
│                          │
│                          └── 这个点右边的是它的父域：grocery.test.sankuai.com
│
└── 最左边这一段 fanyicheng02-pvdsk-sl-pass 是这个域名相对于父域的"自己的名字"

所以：
  fanyicheng02-pvdsk-sl-pass.grocery.test.sankuai.com 的父域
  = grocery.test.sankuai.com    ← 去掉最左边一段（fanyicheng02-pvdsk-sl-pass）后剩下的

pass.grocery.test.sankuai.com 只是"名字里恰好有 pass 这个词"的另一个域名，
和 fanyicheng02-pvdsk-sl-pass.grocery.test.sankuai.com 是平级的兄弟域！
```

用一个更直观的树形图来看：

```
grocery.test.sankuai.com
├── pass.grocery.test.sankuai.com                              ← 子域A
│   └── （pass.grocery... 的子域，和下面的B不相关）
│
├── fanyicheng02-pvdsk-sl-pass.grocery.test.sankuai.com       ← 子域B（页面所在）
│
├── other-service.grocery.test.sankuai.com                    ← 子域C
│
└── ...

子域A 和 子域B 是兄弟关系，不是父子关系！
```

**为什么会产生这个误解？**

因为 `fanyicheng02-pvdsk-sl-pass` 这个名字里带了 `pass` 这个词，看起来像是 `pass.grocery.test.sankuai.com` 的"子域"，但域名的结构是由"点"来决定的，名字里的字符串只是一个标识符，和层级无关。

---

## 三、Cookie 的 Domain 规则

### 3.1 Set-Cookie 的 Domain 属性是什么

当服务器返回响应时，可以通过 `Set-Cookie` 头告诉浏览器存一个 Cookie，并指定这个 Cookie 适用于哪个域：

```
Set-Cookie: token=abc123; Domain=grocery.test.sankuai.com; Path=/; HttpOnly
                          ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
                          这个 Cookie 适用于哪个域
```

**Domain 的含义**：指定哪些域名可以收到这个 Cookie。

如果 `Domain=grocery.test.sankuai.com`，那么浏览器在向以下地址发请求时，都会自动带上这个 Cookie：
- `grocery.test.sankuai.com` 本身
- `pass.grocery.test.sankuai.com`（子域）
- `fanyicheng02-pvdsk-sl-pass.grocery.test.sankuai.com`（子域）
- `any-other.grocery.test.sankuai.com`（子域）

---

### 3.2 浏览器的核心安全限制：Domain 只能设为当前页面的父域或本身

**规则**：`Set-Cookie` 里指定的 `Domain`，必须是**当前页面域名本身，或者它的父域（祖先域）**，不能是其他域。

```
当前页面：https://fanyicheng02-pvdsk-sl-pass.grocery.test.sankuai.com/lps/

合法的 Domain 值：
  fanyicheng02-pvdsk-sl-pass.grocery.test.sankuai.com  ← 本身 ✅
  grocery.test.sankuai.com                             ← 直接父域 ✅
  test.sankuai.com                                     ← 祖父域 ✅
  sankuai.com                                          ← 曾祖父域 ✅

非法的 Domain 值：
  pass.grocery.test.sankuai.com                        ← ❌ 兄弟域，不是父域！
  other.grocery.test.sankuai.com                       ← ❌ 另一个兄弟域
  evil.com                                             ← ❌ 完全不相关的域
```

---

### 3.3 为什么工作中的问题会报错？

回顾一下出错的情况：

```
页面 URL：   https://fanyicheng02-pvdsk-sl-pass.grocery.test.sankuai.com/lps/
Set-Cookie 里的 Domain：pass.grocery.test.sankuai.com

浏览器的检查过程：
  当前页面域名：fanyicheng02-pvdsk-sl-pass.grocery.test.sankuai.com
  想设置的 Domain：pass.grocery.test.sankuai.com

  检查：pass.grocery.test.sankuai.com 是
       fanyicheng02-pvdsk-sl-pass.grocery.test.sankuai.com 的父域吗？

  父域推导：去掉最左段 → grocery.test.sankuai.com（直接父域）
           再去掉一段  → test.sankuai.com
           再去掉一段  → sankuai.com

  结论：pass.grocery.test.sankuai.com 不在父域列表里！
        它是 grocery.test.sankuai.com 的一个子域，
        和 fanyicheng02-pvdsk-sl-pass.grocery.test.sankuai.com 是平级兄弟关系。

  ❌ 浏览器拒绝设置这个 Cookie，Set-Cookie 头被忽略。
```

这就是报错的根本原因：**服务端的配置把一个"兄弟域"误认为了"父域"**。

---

### 3.4 为什么有这个限制？安全原因

如果浏览器允许任意设置 Domain，会发生什么？

```
场景：A 网站试图设置其他网站的 Cookie

假设没有这个限制：
  evil.com 返回 Set-Cookie: session=fake; Domain=bank.com

  如果浏览器接受了这个 Cookie，
  以后用户访问 bank.com 时，浏览器会带上这个伪造的 session=fake！

  攻击者可以：
  - 强制用户登录攻击者控制的账号（会话固定攻击）
  - 覆盖受害者在 bank.com 上的正常 Cookie

有了这个限制：
  每个域只能设置自己或自己父域范围内的 Cookie，
  无法干涉其他不相关域的 Cookie。

  兄弟域之间也无法互相设置 Cookie——
  pass.grocery.test.sankuai.com 无法设置
  fanyicheng02-pvdsk-sl-pass.grocery.test.sankuai.com 的 Cookie，
  反之亦然。
```

---

## 四、Cookie 的完整作用域机制

Cookie 的作用范围由两个属性共同决定：**Domain** 和 **Path**。

### 4.1 Domain 控制"哪些域名"能收到这个 Cookie

```
Set-Cookie: token=abc; Domain=sankuai.com
→ sankuai.com 及其所有子域都能收到这个 Cookie

Set-Cookie: token=abc; Domain=grocery.test.sankuai.com
→ grocery.test.sankuai.com 及其子域能收到，
  test.sankuai.com 和 sankuai.com 本身收不到

Set-Cookie: token=abc（不写 Domain）
→ 默认等于当前页面的完整域名，不包括子域
  例：页面在 pass.grocery.test.sankuai.com
      Cookie 只属于 pass.grocery.test.sankuai.com 本身，
      子域收不到，父域也收不到
```

**一个关键的微妙差别**：

```
Set-Cookie: token=abc; Domain=pass.grocery.test.sankuai.com
               ↑ 显式写了 Domain

vs.

Set-Cookie: token=abc（不写 Domain）
               ↑ 没有 Domain 属性

效果不同！
  显式写了 Domain：Cookie 会被发送到该域及其所有子域
  不写 Domain：Cookie 只属于当前域，不包括子域
```

### 4.2 Path 控制"哪些路径"能收到这个 Cookie

```
Set-Cookie: token=abc; Domain=sankuai.com; Path=/lps/
→ 只有访问路径以 /lps/ 开头的请求才会带这个 Cookie

Set-Cookie: token=abc; Domain=sankuai.com; Path=/
→ 所有路径都带这个 Cookie（最常见）
```

### 4.3 完整的 Cookie 匹配条件

浏览器决定"要不要在某个请求里带某个 Cookie"，需要同时满足：

```
条件1：请求的域名 与 Cookie 的 Domain 匹配
       （请求域名 == Cookie Domain，或者请求域名是 Cookie Domain 的子域）

条件2：请求的路径 与 Cookie 的 Path 匹配
       （请求路径以 Cookie Path 开头）

条件3：协议匹配（如果 Cookie 有 Secure 属性，则只在 HTTPS 请求中携带）

条件4：没过期（没有超过 Expires/Max-Age）

全部满足 → 携带这个 Cookie
```

---

## 五、跨子域共享 Cookie 的正确做法

### 5.1 问题场景

假设前端部署在 `zhiguang.cn`，API 在 `api.zhiguang.cn`，用 Cookie 做认证，Cookie 怎么跨子域传递？

```
页面：       https://zhiguang.cn
API 服务器：  https://api.zhiguang.cn

问题：
  用户登录时，api.zhiguang.cn 设置了 Cookie
  下次前端访问 api.zhiguang.cn 时，Cookie 能带过去吗？

默认情况（Domain = api.zhiguang.cn）：
  Cookie 只属于 api.zhiguang.cn，不会被发送到 zhiguang.cn
  但访问 api.zhiguang.cn 时会带上 ✅（因为域名完全匹配）

  等等，前端和 API 分属不同子域，Cookie 是同一个域名下的事，这里不是跨子域问题。
  真正的跨子域问题是下面这种：
```

**真正的跨子域场景**：

```
服务A：  auth.sankuai.com    （统一认证服务）
服务B：  grocery.sankuai.com （业务服务）
服务C：  hotel.sankuai.com   （另一个业务服务）

需求：用户在 auth.sankuai.com 登录，
      登录状态（Cookie）需要在 grocery 和 hotel 两个子域都生效
```

### 5.2 解决方案：设置 Domain 为公共父域

```
auth.sankuai.com 登录成功后，返回：
Set-Cookie: session=xxx; Domain=sankuai.com; Path=/; HttpOnly; Secure

关键：Domain=sankuai.com（公共父域）

效果：
  这个 Cookie 会在以下所有域的请求中自动携带：
  - sankuai.com
  - auth.sankuai.com      ✅
  - grocery.sankuai.com   ✅
  - hotel.sankuai.com     ✅
  - any.other.sankuai.com ✅
```

**对应到工作中遇到的场景**：

```
如果 switch 接口想让 Cookie 在
fanyicheng02-pvdsk-sl-pass.grocery.test.sankuai.com 下生效，
正确的 Domain 设置应该是：

  Domain=grocery.test.sankuai.com   ← Cookie 在整个 grocery 子域下生效 ✅
  或
  Domain=fanyicheng02-pvdsk-sl-pass.grocery.test.sankuai.com  ← 只在当前域生效 ✅
  或
  Domain=test.sankuai.com           ← 范围更大 ✅（视业务需要）

  而不是：
  Domain=pass.grocery.test.sankuai.com  ← ❌ 兄弟域，浏览器拒绝
```

---

## 六、与本项目的关联：为什么本项目选择 JWT 而非 Cookie？

了解了 Cookie 的 Domain 规则，再来看本项目的设计选择就更清晰了。

### 跨域场景下 Cookie 的困境

```
本项目场景（假设）：
  前端：https://zhiguang.cn
  API：  https://api.zhiguang.cn

如果用 Cookie 做认证：
  api.zhiguang.cn 设置 Cookie
  Set-Cookie: session=xxx; Domain=zhiguang.cn; Path=/

  问题1：需要配置 Domain=zhiguang.cn（父域），Cookie 才能跨子域传递
  问题2：前端发 AJAX 请求到 api.zhiguang.cn，需要设置 credentials: 'include'
  问题3：服务器的 CORS 配置里必须把 allowCredentials 设为 true
  问题4：allowCredentials=true 时，allowedOrigins 不能是 *，必须是白名单
  问题5：如果前端和 API 完全不同域（不共享父域），Cookie 完全无法跨域传递

  一环扣一环，稍有不慎就是配置错误，
  而且每增加一个前端域名，就要同步修改 CORS 白名单。
```

### JWT 的跨域优势

```
本项目选择的方案：
  用户登录 → 服务器返回 JWT（在响应 Body 里，不是 Set-Cookie）
  前端把 JWT 存到 localStorage
  每次请求，前端手动加 Authorization: Bearer <token> 头

好处：
  ✅ 不依赖 Cookie，不受 Domain 规则限制
  ✅ 不需要 credentials: 'include'，CORS 配置简单
  ✅ allowedOrigins 可以设 *（因为没有 Cookie 风险）
  ✅ 前端在任何域下都能用同一套逻辑访问 API
  ✅ 天然支持多端（App、小程序等没有 Cookie 的环境）

代价：
  ❌ JWT 存在 localStorage，有 XSS 风险（Cookie 可以 HttpOnly 防 XSS 偷取）
  ❌ 需要前端代码主动管理 Token 的存储、刷新、过期
```

---

## 七、Cookie 的其他重要属性

顺带梳理一下 Cookie 的全部安全相关属性，方便对比理解：

```
Set-Cookie: name=value; Domain=xxx; Path=/; Expires=...; Max-Age=...;
            Secure; HttpOnly; SameSite=Strict|Lax|None

─────────────────────────────────────────────────────────────────
属性          含义
─────────────────────────────────────────────────────────────────
Domain        Cookie 适用的域名范围（本文重点）

Path          Cookie 适用的路径范围
              Path=/api → 只有 /api 开头的请求才带

Expires       过期时间（绝对时间）
              不设则是"会话 Cookie"，浏览器关闭即消失

Max-Age       过期时间（相对秒数）
              Max-Age=86400 → 1天后过期

Secure        只在 HTTPS 连接中发送
              防止 Cookie 在 HTTP 中被明文传输窃取

HttpOnly      禁止 JavaScript 访问（document.cookie 读不到）
              防止 XSS 攻击偷 Cookie
              （本项目用 localStorage 存 JWT，没有这个保护）

SameSite      控制跨站请求是否携带 Cookie（防 CSRF 的现代方案）
              Strict：只有同站请求才带（最严格，连从 Google 点链接跳转都不带）
              Lax：顶级导航（点链接跳转）可以带，跨站的 AJAX/表单 不带（推荐默认值）
              None：所有情况都带（需要同时设置 Secure）
─────────────────────────────────────────────────────────────────
```

---

## 八、一句话总结

> Cookie 的 `Domain` 属性决定了哪些域名能收到这个 Cookie。
> 浏览器的安全规则是：**服务器只能设置当前页面域名本身或其父域（祖先域）的 Cookie**，不能设置兄弟域的 Cookie。
>
> 工作中遇到的问题根源：`fanyicheng02-pvdsk-sl-pass.grocery.test.sankuai.com` 的直接父域是
> `grocery.test.sankuai.com`，而 `pass.grocery.test.sankuai.com` 是它的**兄弟域**（名字里带了 pass 只是巧合），
> 所以浏览器拒绝了这个 `Set-Cookie`。
>
> 正确做法：将 `Domain` 改为 `grocery.test.sankuai.com`（公共父域）。

