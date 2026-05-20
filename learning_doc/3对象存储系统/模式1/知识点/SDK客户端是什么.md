# SDK 客户端是什么

> "SDK" 和 "客户端" 这两个词经常一起出现，却很少有人解释清楚。本文从问题出发，一步步拆解这两个概念的本质。

---

## 一、从一个具体问题出发

假设你想在 Java 代码里把一张图片上传到阿里云 OSS。

阿里云 OSS 本质上是一个 HTTP 服务，它对外暴露了一组接口，比如上传文件的接口是：

```
PUT /{bucket}/{objectKey}
Host: {bucket}.oss-cn-beijing.aliyuncs.com
Date: Wed, 20 May 2026 10:00:00 GMT
Authorization: OSS {accessKeyId}:{signature}
Content-Type: image/jpeg
Content-Length: 153600

[文件二进制内容]
```

**如果没有 SDK，你需要自己做所有这些事**：

```java
// 没有 SDK，手动实现上传（伪代码）

// 第一步：计算签名
String dateStr = formatDate(new Date());
String stringToSign = "PUT\n\nimage/jpeg\n" + dateStr + "\n/"
                     + bucket + "/" + objectKey;
String signature = base64(hmacSha1(accessKeySecret, stringToSign));
String authHeader = "OSS " + accessKeyId + ":" + signature;

// 第二步：构造 HTTP 连接
URL url = new URL("https://" + bucket + ".oss-cn-beijing.aliyuncs.com/" + objectKey);
HttpURLConnection conn = (HttpURLConnection) url.openConnection();
conn.setRequestMethod("PUT");
conn.setDoOutput(true);
conn.setRequestProperty("Date", dateStr);
conn.setRequestProperty("Authorization", authHeader);
conn.setRequestProperty("Content-Type", "image/jpeg");
conn.setRequestProperty("Content-Length", String.valueOf(fileBytes.length));

// 第三步：写入文件数据
OutputStream out = conn.getOutputStream();
out.write(fileBytes);
out.flush();

// 第四步：读取响应，判断是否成功
int status = conn.getResponseCode();
if (status != 200) {
    // 解析 OSS 返回的 XML 错误信息
    String error = readResponse(conn.getErrorStream());
    throw new RuntimeException("上传失败: " + error);
}
```

这大约需要写 50~100 行代码，而且：
- 签名算法细节极易出错
- 需要处理各种错误码和重试逻辑
- 需要自己管理连接超时、连接池
- 每次调用 OSS 的不同接口（删除、复制、列举……）都要重复这套流程

---

## 二、SDK 是什么：帮你写好了这 100 行代码

**SDK（Software Development Kit，软件开发工具包）** 就是服务提供商（阿里云）事先把上面那 100 行代码写好，打包成一个 jar 包，让你直接用。

```
没有 SDK（你自己写）：
  你的代码
    → 手动构造 HTTP 请求
    → 手动计算签名
    → 手动处理错误/重试
    → OSS HTTP 接口

有了 SDK（SDK 帮你写）：
  你的代码
    → ossClient.putObject(bucket, key, inputStream)  ← 一行
        ↓（SDK 内部）
      构造 HTTP 请求 + 计算签名 + 处理错误/重试
        ↓
      OSS HTTP 接口
```

本项目引入的 OSS SDK：

```xml
<!-- pom.xml -->
<dependency>
    <groupId>com.aliyun.oss</groupId>
    <artifactId>aliyun-sdk-oss</artifactId>
    <version>3.x.x</version>
</dependency>
```

这个 jar 包里就包含了 `OSSClient`、`PutObjectRequest`、`GeneratePresignedUrlRequest` 等类，都是阿里云工程师写好的。

---

## 三、"客户端"是什么意思

在网络通信中，"客户端"和"服务端"是一对相对的概念：

```
服务端（Server）：持续运行，等待请求
客户端（Client）：主动发起请求，用完即走

例子：
  浏览器（客户端）  ←→  网站服务器（服务端）
  手机 App（客户端）←→  后端 API（服务端）
  你的后端（客户端）←→  阿里云 OSS（服务端）  ← 这里！
```

**你的后端相对于 OSS 来说，就是一个客户端。**

`OSSClient` 这个名字里的 "Client" 就是这个意思：它是一个代表"客户端"角色的对象，专门负责向 OSS 服务端发起请求。

---

## 四、SDK 客户端 = 封装了网络通信的本地对象

把前两节合起来：

```
SDK 客户端 = SDK 提供的、封装了与远程服务通信细节的本地 Java 对象

"本地对象"：在你的 JVM 进程里，像普通对象一样 new 出来、调用方法
"封装了通信细节"：内部帮你处理 HTTP/TCP 连接、序列化/反序列化、签名、重试等
"远程服务"：实际的执行发生在另一台机器（阿里云的数据中心）上
```

调用一个 SDK 客户端的方法，**表面上看和调用本地方法一样**，但背后触发了一次网络请求：

```java
// 看起来：像调用本地方法
ossClient.putObject(bucket, key, inputStream);

// 实际上：
// 1. 序列化参数（构造 HTTP 请求头和请求体）
// 2. 建立 TCP 连接（到 oss-cn-beijing.aliyuncs.com:443）
// 3. 发送 HTTPS PUT 请求
// 4. 等待远程服务器处理（文件写入 OSS 磁盘阵列）
// 5. 接收响应，反序列化（解析 HTTP 状态码和响应体）
// 6. 返回结果（或抛出异常）
```

---

## 五、SDK 客户端在各种场景下的形式

SDK 客户端这个模式在 Java 开发中随处可见：

| 你用的类 | 它是谁的 SDK 客户端 | 封装的通信协议 |
|---------|-----------------|--------------|
| `OSSClient` | 阿里云 OSS | HTTPS/REST |
| `RedisTemplate` / `Jedis` | Redis 服务器 | Redis 自定义 TCP 协议 (RESP) |
| `KafkaProducer` | Kafka Broker | Kafka 自定义 TCP 协议 |
| `RestTemplate` / `OkHttpClient` | 任意 HTTP 服务 | HTTP/HTTPS |
| `MysqlDataSource` / JDBC | MySQL 数据库 | MySQL 自定义 TCP 协议 |
| `AmazonS3` | AWS S3 | HTTPS/REST |
| `WxPayClient` | 微信支付 | HTTPS/REST |

你每天写的 `redisTemplate.opsForValue().set("key", "value")` 就是在用 Redis 的 SDK 客户端，它背后也发起了一次网络请求到 Redis 服务器。

---

## 六、创建客户端时发生了什么

```java
OSS client = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
```

创建客户端 **不等于** 发起网络请求。这一步做的是：

```
初始化阶段（在 JVM 内存里）：
  1. 存储 endpoint / accessKeyId / accessKeySecret（供后续请求使用）
  2. 初始化 HTTP 连接池（预分配连接资源，但此时不建立真实 TCP 连接）
  3. 配置超时参数（连接超时 / 读取超时）
  4. 初始化重试策略（失败后重试几次、间隔多久）
```

只有当你调用 `putObject()`、`getObject()` 等方法时，才会真正发起网络请求。

---

## 七、为什么是 `new OSSClientBuilder().build()` 而不是直接 `new OSSClient()`

这是 **Builder 模式（建造者模式）**：

```java
// 不用 Builder，假设 OSSClient 有很多可选参数：
new OSSClient(endpoint, keyId, secret, null, null, 5000, 30000, true, false, ...);
// → 一堆 null 和数字，完全不知道哪个参数是什么意思

// 用 Builder：
OSSClient client = new OSSClientBuilder()
    .endpoint(endpoint)
    .accessKey(keyId, secret)
    .connectTimeout(5000)
    .socketTimeout(30000)
    .build();
// → 每个参数都有明确的名字，可读性强，可以只设置需要的参数
```

本项目用的是简化版（只传三个必须参数），其他参数使用默认值：

```java
new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
```

---

## 八、总结

```
SDK（Software Development Kit）
  = 服务提供商写好的、帮你封装了所有底层细节的代码库

SDK 客户端（SDK Client）
  = SDK 里表示"客户端"角色的核心类
  = 一个本地 Java 对象
  = 调用它的方法 → 实际触发对远程服务的网络请求

OSSClient 具体做了什么：
  帮你省去了：计算签名 + 构造 HTTP 请求 + 管理连接 + 处理错误重试
  让你只需要：ossClient.putObject(bucket, key, stream) 一行代码
```

**一句话**：SDK 客户端就是"别人帮你写好的远程服务调用封装"，让你像调用本地函数一样使用远程服务，而不用关心网络通信的任何细节。

