# 双重检查锁（DCL）详解

> DCL（Double-Checked Locking，双重检查锁）是一种在"懒加载"和"线程安全"之间取得平衡的并发编程模式。本文先从 Spring 单例 Bean 的 DCL 实现讲起，再梳理 DCL 在其他场景中的应用。

---

## 一、为什么需要 DCL

先回顾一下问题背景：

```
需求：某个对象只能创建一次（单例），且希望延迟到真正需要时才创建（懒加载）

方案一：不加锁
  if (instance == null) {
      instance = new Singleton();  // 多线程下可能创建多个实例
  }
  → 线程不安全

方案二：整个方法加 synchronized
  public synchronized Object getInstance() {
      if (instance == null) {
          instance = new Singleton();
      }
      return instance;
  }
  → 线程安全，但每次调用都要竞争锁，即使实例已经存在
  → 高并发下性能很差

目标：实例已存在时，不加锁直接返回（快）；实例不存在时，加锁安全创建（准）
```

DCL 就是为了同时满足这两个目标而设计的。

---

## 二、DCL 的标准写法

```java
public class Singleton {

    // ⚠️ volatile 是必须的，原因见下文
    private static volatile Singleton instance = null;

    private Singleton() {}

    public static Singleton getInstance() {
        // 第一次检查（无锁，性能好）
        if (instance == null) {
            // 只有 instance 为 null 时才进入同步块
            synchronized (Singleton.class) {
                // 第二次检查（有锁，保证安全）
                if (instance == null) {
                    instance = new Singleton();
                }
            }
        }
        return instance;
    }
}
```

### 为什么要检查两次

```
场景：线程 A 和线程 B 同时发现 instance == null，都通过了第一次检查

没有第二次检查：
  线程 A 拿到锁，创建实例，释放锁
  线程 B 拿到锁，再次创建实例（因为没有第二次检查）← 单例被破坏！

有了第二次检查：
  线程 A 拿到锁，第二次检查 null → 创建实例，释放锁
  线程 B 拿到锁，第二次检查不为 null → 直接退出，不再创建
  → 单例得到保证
```

### 为什么 volatile 是必须的

```
new Singleton() 在 JVM 层面分三步：
  步骤 1：在堆上分配内存（地址为 0x1234）
  步骤 2：调用构造方法，初始化对象字段
  步骤 3：把 0x1234 赋值给 instance 变量

JVM/CPU 的指令重排序可能把顺序变为：
  步骤 1 → 步骤 3 → 步骤 2
  即：先把地址赋给 instance，再初始化对象

如果没有 volatile，线程 B 做第一次检查时：
  instance 不为 null（步骤 3 已执行，地址已赋值）
  但对象还没初始化完（步骤 2 还没执行）
  线程 B 直接返回 instance，拿到一个"半初始化"的对象
  使用时 NullPointerException 或数据错误！

volatile 的作用：
  1. 禁止指令重排序，保证 步骤1→2→3 严格按顺序执行
  2. 保证内存可见性，一个线程对 instance 的写入，其他线程立刻可见
```

---

## 三、Spring 单例 Bean 中的 DCL

Spring 在创建 Bean 时使用了 DCL，核心代码在 `DefaultSingletonBeanRegistry` 中。

### Spring 的三级缓存 + DCL

Spring 用三个 Map（三级缓存）来管理 Bean，其中第一级就是完全初始化好的单例：

```java
// DefaultSingletonBeanRegistry（简化版）
public class DefaultSingletonBeanRegistry {

    // 一级缓存：完全初始化好的单例 Bean
    private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>(256);

    // 二级缓存：早期曝光的 Bean（已实例化但未完成依赖注入，用于解决循环依赖）
    private final Map<String, Object> earlySingletonObjects = new ConcurrentHashMap<>(16);

    // 三级缓存：Bean 工厂（用于产生代理对象等）
    private final Map<String, ObjectFactory<?>> singletonFactories = new HashMap<>(16);

    public Object getSingleton(String beanName) {
        // 第一次检查（无锁，直接查一级缓存）
        Object singletonObject = singletonObjects.get(beanName);

        if (singletonObject == null) {
            // 进入同步块
            synchronized (this.singletonObjects) {
                // 第二次检查（有锁，再查一遍）
                singletonObject = singletonObjects.get(beanName);

                if (singletonObject == null) {
                    // 查二级缓存
                    singletonObject = earlySingletonObjects.get(beanName);
                    if (singletonObject == null) {
                        // 查三级缓存（通过工厂创建）
                        ObjectFactory<?> factory = singletonFactories.get(beanName);
                        if (factory != null) {
                            singletonObject = factory.getObject();
                            earlySingletonObjects.put(beanName, singletonObject);
                            singletonFactories.remove(beanName);
                        }
                    }
                }
            }
        }
        return singletonObject;
    }
}
```

### Spring DCL 的特点

```
与经典 DCL 的异同：

相同点：
  - 两次检查，第一次无锁，第二次有锁
  - 避免了每次 getBean() 都进入同步块的开销

不同点：
  - Spring 用 ConcurrentHashMap 代替 volatile 变量
    → ConcurrentHashMap 本身保证读操作的可见性（内部用 volatile 实现）
    → 所以不需要额外的 volatile 修饰
  - Spring 的"创建"逻辑更复杂：还有三级缓存、循环依赖处理等
  - 锁对象是 singletonObjects 这个 Map 本身，而不是类
```

### 为什么 Spring 用 ConcurrentHashMap 而不是普通 HashMap

```
ConcurrentHashMap 的 get() 操作：
  底层数组元素用 volatile 修饰
  → 一个线程 put() 后，另一个线程 get() 能立刻看到最新值
  → 天然保证内存可见性，效果等价于 volatile

因此：第一次检查（无锁 get）也是安全的，不会读到"旧值"
```

---

## 四、除了 Spring，哪里还会用到 DCL

### 4.1 懒汉式单例（最经典场景）

即本文第二节讲的标准写法，不再赘述。

### 4.2 缓存的懒加载（Cache 初始化）

```java
public class ConfigCache {

    private static volatile Map<String, String> cache = null;

    public static Map<String, String> getCache() {
        if (cache == null) {
            synchronized (ConfigCache.class) {
                if (cache == null) {
                    // 从数据库或配置文件加载，耗时操作
                    cache = loadFromDatabase();
                }
            }
        }
        return cache;
    }
}
```

**场景**：某个配置缓存很重（比如要查数据库），不想程序启动时就加载，而是等第一次需要时才加载，且保证多线程下只加载一次。

### 4.3 连接池的懒初始化

```java
public class ConnectionPool {

    private static volatile ConnectionPool pool = null;
    private final List<Connection> connections;

    private ConnectionPool() {
        // 创建连接池很耗时
        this.connections = initConnections(10);
    }

    public static ConnectionPool getInstance() {
        if (pool == null) {                     // 第一次检查
            synchronized (ConnectionPool.class) {
                if (pool == null) {             // 第二次检查
                    pool = new ConnectionPool();
                }
            }
        }
        return pool;
    }
}
```

### 4.4 HashMap 扩容时的并发控制（JDK 源码）

JDK 8 的 `ConcurrentHashMap` 在扩容时，多个线程协作迁移数据，也用到了类似 DCL 的思想：

```java
// ConcurrentHashMap.transfer() 简化逻辑
if (nextTable == null) {            // 第一次检查（无锁）
    Node<K,V>[] nt = new Node[newCapacity];
    if (NEXT_TABLE.compareAndSet(this, null, nt)) {  // CAS 代替 synchronized
        nextTable = nt;
    }
}
// 只有一个线程会成功初始化 nextTable，其他线程看到不为 null 就直接参与迁移
```

这里用 **CAS（Compare-And-Swap）** 代替了 `synchronized`，是 DCL 思想的变体：先检查，再用原子操作保证只执行一次。

### 4.5 MyBatis 的 SqlSessionFactory 初始化

在一些 MyBatis 手动配置场景（非 Spring 托管）中，会这样写：

```java
public class MyBatisUtil {

    private static volatile SqlSessionFactory factory = null;

    public static SqlSessionFactory getFactory() {
        if (factory == null) {
            synchronized (MyBatisUtil.class) {
                if (factory == null) {
                    // 读取配置文件，构建 SqlSessionFactory，很耗时
                    InputStream is = Resources.getResourceAsStream("mybatis-config.xml");
                    factory = new SqlSessionFactoryBuilder().build(is);
                }
            }
        }
        return factory;
    }
}
```

### 4.6 Java 标准库中的 DCL

JDK 源码里也有大量 DCL 的影子：

**`java.lang.Class.getAnnotation()`**：反射获取注解时，注解信息会懒加载并缓存，用到了 DCL。

**`FutureTask` 的结果获取**：

```java
// FutureTask 等待结果（简化）
public V get() {
    if (state <= COMPLETING) {    // 第一次检查（无锁）
        awaitDone();
    }
    return report(state);
}
```

**`ThreadLocal.ThreadLocalMap` 的创建**：线程第一次使用 ThreadLocal 时才创建对应的 Map。

### 4.7 框架/中间件中的 DCL

| 框架/场景 | 使用 DCL 的地方 | 目的 |
|---------|--------------|------|
| **Spring** | `DefaultSingletonBeanRegistry.getSingleton()` | 保证单例 Bean 只创建一次 |
| **Dubbo** | 服务代理对象的懒初始化 | 首次调用时才建立连接 |
| **Netty** | `HashedWheelTimer` 的启动 | 保证定时器只启动一次 |
| **Log4j** | Logger 实例的创建 | 按需创建，避免预热开销 |
| **Guava Cache** | `LoadingCache` 的数据加载 | Cache Miss 时只有一个线程去加载 |

---

## 五、什么时候用 DCL，什么时候不用

### 适合用 DCL 的场景

```
✅ 对象创建开销大（连接池、数据库连接、复杂配置加载）
✅ 对象不一定每次都会用到（懒加载价值高）
✅ 对象需要在多线程环境下只创建一次
✅ 读操作远多于写操作（DCL 的优化主要体现在"已创建后的读"）
```

### 不适合用 DCL 的场景

```
❌ 对象创建很轻量（直接饿汉式更简单）
❌ 单线程环境（没有并发，普通 if-null-create 即可）
❌ 对象需要频繁更新（DCL 只适合"写一次，读多次"）
❌ 有更好的替代方案（如静态内部类 Holder 模式，更简洁）
```

### DCL 的替代方案

| 替代方案 | 原理 | 适用场景 |
|---------|------|---------|
| **静态内部类（Holder）** | 利用 JVM 类初始化锁 | 纯单例，代码更简洁 |
| **饿汉式** | 类加载时就创建 | 对象轻量，不在乎启动时间 |
| **CAS（AtomicReference）** | 无锁原子操作 | 超高并发，要求无锁 |
| **枚举单例** | JVM 保证枚举实例唯一 | 防反射/序列化破坏单例 |

**枚举单例**（最安全的写法，但不支持懒加载）：

```java
public enum EnumSingleton {
    INSTANCE;

    public void doSomething() { /* ... */ }
}

// 使用
EnumSingleton.INSTANCE.doSomething();
```

---

## 六、总结

```
DCL 的本质：
  用"两次检查 + 一把锁"解决懒加载场景下的线程安全问题：
  - 第一次检查（无锁）：实例已存在时，直接快速返回，避免锁竞争
  - synchronized 块：保证同一时刻只有一个线程进行初始化
  - 第二次检查（有锁）：防止多个线程同时通过第一次检查后重复初始化

使用 DCL 的三个要点：
  1. volatile 修饰目标变量（防止指令重排序导致半初始化对象被返回）
  2. 两次 null 检查（缺一不可）
  3. 锁住的是类或共享的 Monitor 对象

DCL 的使用场景：
  单例模式 → Spring Bean 管理 → 缓存懒加载 → 连接池初始化 → JDK/框架源码
  核心场景特征：对象创建代价大 + 懒加载 + 只初始化一次

