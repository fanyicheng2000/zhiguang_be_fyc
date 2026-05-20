# 单例模式与 Spring 单例 Bean 的关系

> 你问的完全正确——"设计模式里的单例"和"Spring Bean 的单例"确实有关系。本文从设计模式出发，解释单例是什么，它如何实现复用，以及和 Spring Bean 单例之间的联系与区别。

---

## 一、从问题出发：为什么要"单例复用"OSSClient

先看本项目当前的写法：

```java
// 当前写法：每次请求都新建 + 用完关闭
public String uploadAvatar(long userId, MultipartFile file) {
    OSS client = new OSSClientBuilder().build(endpoint, keyId, secret); // 每次新建
    try {
        client.putObject(...);
    } finally {
        client.shutdown(); // 每次关闭
    }
}
```

每次有用户上传头像，就执行一次 `new OSSClientBuilder().build(...)`，这会做什么？

```
new OSSClientBuilder().build(...) 内部：
  1. 分配内存，创建 OSSClient 对象
  2. 初始化 HTTP 连接池（预分配连接资源）
  3. 初始化重试策略、超时配置等
  4. 加载 TLS/SSL 证书配置（用于 HTTPS）

shutdown() 内部：
  1. 关闭 HTTP 连接池（释放所有连接）
  2. 释放内存

这些操作本身很快（几毫秒），但如果每秒有 1000 个上传请求：
  → 1000 次初始化 + 1000 次销毁
  → 大量重复的内存分配与释放
  → 频繁的 GC（垃圾回收）压力
```

**如果 OSSClient 对象只创建一次，一直用，就能避免这些重复代价。** 这就是"单例复用"的动机。

---

## 二、单例模式（Singleton Pattern）是什么

**单例模式**是设计模式中的一种，核心思想只有一句话：

> **一个类在整个程序运行期间，只允许存在一个实例。**

最经典的 Java 实现：

```java
public class OssClientHolder {

    // 静态变量持有唯一实例（JVM 级别只有一个）
    private static final OssClientHolder INSTANCE = new OssClientHolder();

    private final OSS client;

    // 构造方法私有化（外部无法 new，只能通过 getInstance() 获取）
    private OssClientHolder() {
        this.client = new OSSClientBuilder().build(endpoint, keyId, secret);
    }

    // 全局唯一的获取入口
    public static OssClientHolder getInstance() {
        return INSTANCE;
    }

    public OSS getClient() {
        return this.client;
    }
}
```

使用时：

```java
// 不管调用多少次，拿到的是同一个对象
OSS client1 = OssClientHolder.getInstance().getClient();
OSS client2 = OssClientHolder.getInstance().getClient();

client1 == client2  →  true（同一个对象，同一块内存地址）
```

**单例如何实现复用**：OSSClient 只在程序启动时创建一次，后续所有请求都共享这一个对象，不再反复 new/shutdown。

---

## 三、Spring Bean 的单例是同一个概念吗

**是的，Spring Bean 的默认 scope="singleton" 就是在 Spring 容器级别实现了单例模式。**

不过理解这句话需要先搞清楚两个层次：

### 3.1 设计模式的单例：JVM 级别

传统单例模式（上面的例子）保证的是：**整个 JVM 进程中只有一个实例**。

它通过 `private` 构造方法 + `static final` 变量来强制实现，外部根本没有机会创建第二个实例。

### 3.2 Spring Bean 的单例：Spring 容器级别

Spring 的 `@Component`、`@Service`、`@Bean` 等注解标注的类，**默认是单例的**：

```java
@Service  // scope 默认是 singleton
public class OssStorageService {
    // ...
}
```

Spring 启动时会创建一个 `OssStorageService` 实例，放进容器（一个大 Map）里。后续所有地方通过 `@Autowired` 注入这个 Bean，拿到的都是**同一个对象**。

```java
@RestController
public class ProfileController {
    @Autowired
    private OssStorageService ossStorageService;  // 拿到的是容器里唯一的那个实例
}

@RestController
public class AnotherController {
    @Autowired
    private OssStorageService ossStorageService;  // 同一个实例！
}
```

### 3.3 两者的关系和区别

| 维度 | 设计模式单例 | Spring Bean 单例 |
|------|------------|----------------|
| **保证范围** | 整个 JVM 进程 | Spring 容器内 |
| **实现机制** | private 构造 + static 变量 | Spring IoC 容器管理 |
| **能否绕开** | 几乎不能（反射可以，但很麻烦） | 可以在容器外 new 出第二个实例 |
| **本质** | 语言/JVM 级别的强制约束 | 框架级别的"只创建一次"约定 |
| **关系** | Spring Bean 单例是在框架层面**实现了**单例模式的思想 | — |

**一句话**：Spring Bean 单例不是用 `private` 构造方法强制的，而是由 Spring 容器保证"只创建一次、到处共享"——本质是单例模式思想的框架级实现。

---

## 四、用 Spring Bean 单例复用 OSSClient 的正确写法

了解了以上内容，就知道"生产环境通常用单例复用 OSSClient"在 Spring 项目里的标准做法：

```java
@Configuration
public class OssConfig {

    @Bean  // 这个 Bean 默认是单例的：Spring 容器里只有一个 OSS 实例
    public OSS ossClient(OssProperties props) {
        return new OSSClientBuilder().build(
            props.getEndpoint(),
            props.getAccessKeyId(),
            props.getAccessKeySecret()
        );
    }
}
```

然后在 Service 中注入使用：

```java
@Service
@RequiredArgsConstructor
public class OssStorageService {

    private final OssProperties props;
    private final OSS ossClient;  // 注入单例 OSSClient，不再每次 new

    public String uploadAvatar(long userId, MultipartFile file) {
        // 直接用注入的 client，不 new，不 shutdown
        PutObjectRequest request = new PutObjectRequest(
            props.getBucket(), objectKey, file.getInputStream()
        );
        ossClient.putObject(request);  // ← 复用同一个 client
        return publicUrl(objectKey);
    }
}
```

**效果**：

```
程序启动时：创建 1 次 OSSClient（初始化连接池）
后续每次请求：直接使用这 1 个 OSSClient
程序关闭时：销毁 1 次 OSSClient（释放连接池）

对比当前写法：
  1000 次请求 → 1000 次 new + 1000 次 shutdown（当前）
  1000 次请求 → 0 次 new + 0 次 shutdown（单例复用）
```

---

## 五、单例复用时需要注意线程安全

单例对象在多线程环境下会被**多个请求同时使用**，因此对象必须是**线程安全**的。

```
时刻 T1：用户 A 的请求调用 ossClient.putObject(...)
时刻 T1：用户 B 的请求同时调用 ossClient.putObject(...)
时刻 T1：用户 C 的请求也在调用 ossClient.putObject(...)

这三次调用用的是同一个 ossClient 对象！
如果 ossClient 内部有共享的可变状态（如一个全局计数器），
多个线程同时修改就会产生竞态条件（数据错误）。
```

阿里云 OSS SDK 的 `OSSClient` **是线程安全的**，内部使用了连接池，每次操作从池中取一个连接使用，用完归还，多个线程不会互相干扰。这是它可以被安全地做成单例的前提。

**并非所有 SDK 客户端都是线程安全的**，使用前要查阅官方文档确认。

---

## 六、总结

```
单例模式（设计模式）
  = 一个类在整个程序运行期间只存在一个实例
  = 通过 private 构造 + static 变量实现
  = 目的：避免重复创建/销毁开销，实现全局共享

Spring Bean 单例
  = Spring 容器对"只创建一次"这一思想的框架级实现
  = 你用 @Component/@Service/@Bean 声明的类，默认只创建一个实例
  = 所有 @Autowired 注入的地方拿到的是同一个对象

两者关系
  = Spring Bean 单例是单例模式在 Spring 框架层面的实现
  = 概念相同，实现机制不同（框架约定 vs 语言强制）

OSSClient 单例复用
  = 把 OSSClient 声明为 @Bean（Spring 单例）
  = 程序启动时初始化一次，所有请求共享同一个连接池
  = 前提：OSSClient 是线程安全的（官方保证）

