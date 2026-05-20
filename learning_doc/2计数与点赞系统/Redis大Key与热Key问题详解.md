# Redis 大 Key 与热 Key 问题详解

> 这是两个在高并发系统中最常见、也最容易被忽视的 Redis 性能陷阱。本文从底层原理出发，解释"为什么会慢"，而不只是告诉你"会慢"。

---

## 一、大 Key 问题

### 1.1 什么叫"大 Key"

大 Key 不是指 key 的名字很长，而是指 **key 对应的 value 占用了大量内存**。

常见的大 Key 形式：

| 类型 | 大 Key 的样子 | 阈值参考 |
|------|-------------|---------|
| String | value 是一个几 MB 的 JSON/二进制 | > 10KB |
| Hash | 有几十万个 field | > 5000 个 field |
| List | 有几十万个元素 | > 5000 个元素 |
| Set | 有几十万个 member | > 5000 个 member |
| ZSet | 有几十万个 member | > 5000 个 member |
| Bitmap | offset 很大导致按最大偏移分配内存 | > 10KB |

---

### 1.2 Redis 的内存模型：为什么大 Key 会慢

要理解大 Key 为什么慢，需要先理解 Redis 的内存管理机制。

#### Redis 是单线程处理命令的

Redis 的命令执行（6.0 以前）由**单个主线程**串行处理：

```
客户端请求 → [epoll 事件循环] → 读取命令 → 执行命令 → 写回响应 → 下一个命令
                                               ↑
                              这里是单线程，一次只执行一个命令
```

这意味着：**一个命令执行时间越长，后面排队的所有命令等待时间就越长。**

#### 大 Key 操作的时间复杂度

对大 Key 的常见操作，时间复杂度都是 **O(N)**：

```
HGETALL  → 遍历所有 field，O(N)
SMEMBERS → 遍历所有 member，O(N)
LRANGE   → 遍历范围内元素，O(N)
DEL      → 释放所有元素的内存，O(N)   ← 最容易被忽视
KEYS     → 全局扫描，O(N)            ← 我们代码里存在的问题！
```

**当 N = 100 万时，这些命令可能需要执行几十到几百毫秒。** 在这段时间内，所有其他命令都被阻塞，对用户来说就是"服务卡顿"甚至"超时"。

#### 内存分配：jemalloc 的 slab 机制

Redis 使用 `jemalloc` 作为内存分配器。jemalloc 把内存按 **size class（大小类）** 分成不同的 slab（内存池），比如 8 字节、16 字节、32 字节……直到几 MB 级别。

**关键规律：分配的内存大小会向上取整到最近的 size class。**

```
你要存 100 字节  → jemalloc 实际分配 128 字节（向上取整）
你要存 500 字节  → jemalloc 实际分配 512 字节
你要存 1.5 MB   → jemalloc 实际分配 2 MB

内存浪费比例有时高达 33%（如存 97 字节 → 分配 128 字节）
```

当一个大 Key 的 value 达到几 MB，jemalloc 需要在大 slab 中分配连续内存块。如果内存碎片化严重，分配本身就会耗时，甚至触发内存整理（compaction）。

#### Bitmap 的特殊内存分配机制

上一篇文档中提到的分片位图问题，在这里有更底层的解释：

```
Redis 的 String 类型（位图底层是 String）在 SETBIT 时，
会按照"最大 offset / 8 + 1"字节来分配内存。

示例：
  SETBIT mykey 1000000 1
  → Redis 需要分配至少 1000000 / 8 = 125000 字节 ≈ 122KB
  → 即使你只设置了 1 个 bit，也要占用 122KB

这就是分片的必要性：每个分片最大 32768 bit = 4KB，
防止单个 bit 操作触发几百 KB 的内存分配。
```

---

### 1.3 大 Key 的三类具体危害

#### 危害一：读写阻塞（最常见）

```
场景：某篇爆款文章的点赞记录存在单个 Hash 里，有 200 万个 field

操作：HGETALL like:knowpost:hot_article

耗时：Redis 需要遍历 200 万个 field，序列化成响应，
      假设每个 field 处理 100ns，200 万 × 100ns = 200ms

结果：这 200ms 内，其他所有 Redis 命令全部排队等待。
      对用户来说，所有接口突然"慢了 200ms"
```

#### 危害二：删除阻塞（最容易被忽视）

很多人只注意到读写大 Key 会慢，却忘了**删除大 Key 更危险**。

```
DEL big_key

Redis 在执行 DEL 时，需要：
  1. 找到 key 对应的数据结构
  2. 逐个释放每个 field/element 的内存
  3. 将内存归还给 jemalloc 的 slab

如果 Hash 有 100 万个 field，DEL 需要做 100 万次内存释放操作
→ 可能阻塞几百毫秒甚至更长
```

**Redis 4.0 引入了 `UNLINK` 命令**来解决这个问题：

```
UNLINK big_key

UNLINK 会先把 key 从 keyspace（哈希表）中摘除（这步很快，O(1)），
然后把实际的内存释放工作交给后台异步线程处理。

用户视角：UNLINK 立即返回，不阻塞
实际释放：在后台线程中慢慢完成
```

#### 危害三：网络带宽打满

```
场景：大 Key 的 value 是 5MB 的序列化 JSON

每次读取：传输 5MB 数据
  → 1000 个并发请求 → 5GB/s 网络流量
  → 服务器网卡带宽通常是 1Gbps（千兆网）
  → 直接打满网卡，所有服务响应变慢

这和 Redis 自身性能无关，是纯网络层瓶颈
```

---

### 1.4 大 Key 的排查方法

```bash
# 方法一：redis-cli 扫描（推荐，非阻塞）
redis-cli --bigkeys

# 方法二：SCAN 命令（游标迭代，不阻塞）
SCAN 0 COUNT 100
→ 游标迭代，每次只扫描部分 key，不阻塞主线程

# 方法三：OBJECT ENCODING / OBJECT FREQ
OBJECT ENCODING mykey  → 查看编码方式（ziplist/hashtable 等）
DEBUG OBJECT mykey     → 查看 value 序列化长度
```

**为什么要用 SCAN 而不是 KEYS**：

```
KEYS pattern：O(N) 全局扫描，N=总 key 数量
              单次执行，阻塞主线程直到扫描完成
              生产环境禁止使用！

SCAN cursor COUNT 100：游标分批迭代
              每次只处理约 COUNT 个 key
              多次调用，游标返回 0 时完成
              不阻塞主线程，对业务无影响
```

---

### 1.5 大 Key 的解决方案

#### 方案一：拆分（最直接）

```
Hash 大 Key 拆分示例：

原来：like:knowpost:123456  → 200 万个 field
拆分：like:knowpost:123456:shard:{userId % 100}  → 100 个分片，每片 2 万个 field

读取时：并行读取 100 个分片 key，最终合并
写入时：先计算 shard，再写对应的分片 key
```

这本质上就是本项目**分片位图**的思路——把一个可能很大的 key 按维度拆成多个较小的 key。

#### 方案二：压缩

```
String 大 Value 压缩：
  原始 JSON：5MB
  GZIP 压缩：200KB（压缩比约 25:1）

代价：每次读写都要序列化/反序列化，CPU 消耗增加
适合：数据本身有高重复率的 JSON/文本
```

#### 方案三：冷热分离

```
访问模式通常是：
  热数据（最近 7 天）  → 高频访问，放 Redis
  温数据（7~30 天）   → 中频访问，放 Redis + 压缩
  冷数据（30 天以上）  → 低频访问，迁移到 MySQL/对象存储

定期清理 Redis 中的冷数据，防止内存无限膨胀
```

---

## 二、热 Key 问题

### 2.1 什么叫"热 Key"

热 Key 是指**被极高频率访问的 key**，导致大量请求集中打到 Redis 的同一个 key 上。

```
正常情况（访问均匀分布）：
  key1 → 100 QPS
  key2 → 150 QPS
  key3 → 80 QPS
  ...（均匀分布在所有 key 上）

热 Key 情况（流量集中）：
  like:knowpost:爆款文章 → 50,000 QPS  ← 热 Key，流量集中
  other keys             → 合计 5,000 QPS
```

### 2.2 热 Key 与大 Key 的本质区别

| 维度 | 大 Key | 热 Key |
|------|--------|--------|
| **问题根源** | 单次操作耗时长 | 单位时间内操作次数太多 |
| **是否必然重叠** | 不一定 | 不一定 |
| **可能同时出现** | 热文章的点赞 Hash 既大又热 | 是的 |
| **主要危害** | 阻塞主线程 | CPU/网络/内存带宽被单个 key 打满 |

---

### 2.3 热 Key 为什么会造成问题：从 Redis 架构说起

#### Redis 的单线程命令处理

前面说了，Redis 主线程是单线程的。对于热 Key：

```
50,000 QPS 访问同一个 key 意味着：
  每秒 50,000 次命令执行
  每次命令平均 10μs
  → 单 key 消耗：50,000 × 10μs = 500ms 的 CPU 时间

而主线程每秒能处理的命令总数约 10万~100万（取决于命令复杂度）
→ 热 Key 独占了主线程 50%~500% 的处理能力
→ 其他 key 的命令都被挤占，响应时间上升
```

#### Redis Cluster 场景下更严重

在 Redis Cluster（集群模式）中，每个 key 通过 CRC16 哈希映射到固定的 slot，每个 slot 由固定的节点负责：

```
Redis Cluster 数据分布：
  Node 1：slot 0~5460
  Node 2：slot 5461~10922
  Node 3：slot 10923~16383

热 Key "like:knowpost:爆款文章" 的 slot：
  CRC16("like:knowpost:爆款文章") % 16384 = 假设 slot 7890
  → 所有 50,000 QPS 全打到 Node 2

结果：
  Node 2：CPU 100%，OOM，响应超时
  Node 1、3：轻松愉快，资源闲置

集群扩容毫无帮助——因为热 Key 永远只在一个节点上！
```

**这就是热 Key 在集群场景下特别棘手的原因**：你加再多节点，热 Key 依然只打一个节点，无法通过水平扩展解决。

---

### 2.4 热 Key 的具体危害

#### 危害一：CPU 单点瓶颈

```
正常 Redis 节点：CPU 使用率 20%，处理来自各 key 的均匀请求
热 Key 场景：单节点 CPU 跑满 100%
            → 命令队列积压
            → 响应时间从 1ms 升至 100ms+
            → 客户端连接池耗尽
            → 服务端返回 "too many connections" 错误
```

#### 危害二：带宽打满（热 Key + 大 Value）

```
如果热 Key 的 value 还比较大（比如 1KB 的 JSON）：
  50,000 QPS × 1KB = 50MB/s 的网络流量
  → 百兆网卡：立即打满，丢包
  → 千兆网卡：消耗 50% 带宽，其他服务也受影响
```

#### 危害三：Redis 集群节点雪崩

```
热 Key 节点 CPU 跑满
→ 心跳包处理延迟
→ 其他节点误判该节点宕机
→ 触发选举，该节点的 Slave 晋升为 Master
→ 旧 Master 心跳恢复，触发脑裂检测
→ 集群进入不稳定状态，部分 slot 短暂不可用
→ 业务大规模报错
```

---

### 2.5 热 Key 的解决方案

#### 方案一：本地缓存（最有效）

```
思路：在应用层（JVM 内存）缓存热 Key 的数据，
      大部分请求直接从本地缓存读取，不打到 Redis

实现：
  // 使用 Caffeine 本地缓存
  Cache<String, Long> localCache = Caffeine.newBuilder()
      .maximumSize(1000)           // 最多缓存 1000 个 key
      .expireAfterWrite(5, SECONDS) // 5 秒后过期（允许短暂不一致）
      .build();

  public Long getLikeCount(String entityId) {
      return localCache.get(entityId, id -> {
          // 本地缓存未命中时，才查 Redis
          return redis.get("cnt:knowpost:" + id);
      });
  }

效果：假设 5 秒内同一 key 被访问 10,000 次
      → 只有第 1 次打到 Redis，后续 9,999 次命中本地缓存
      → Redis 压力降低 99.99%
```

**代价**：本地缓存有过期时间，存在短暂数据不一致（5 秒内看到旧数据）。对"点赞数"这类允许短暂误差的场景完全可以接受。

#### 方案二：Key 打散（热 Key 复制）

```
思路：把热 Key 复制成多份，每次读取随机选一份，
      将流量分散到多个 key（进而分散到多个 Redis 节点）

实现：
  写入时（更新点赞数）：
    for (int i = 0; i < REPLICA_COUNT; i++) {
        redis.set("cnt:knowpost:hot:replica:" + i, value);
    }

  读取时：
    int replica = ThreadLocalRandom.current().nextInt(REPLICA_COUNT);
    redis.get("cnt:knowpost:hot:replica:" + replica);

  REPLICA_COUNT = 10（复制 10 份）
  → 原来 50,000 QPS 打一个 key
  → 现在 50,000 QPS 均匀打 10 个 key，每个 5,000 QPS
  → 10 个 key 分散在不同节点，CPU 压力均摊
```

**代价**：写入时要更新 N 份，写放大 N 倍。读取时可能读到不同副本的短暂不一致。

#### 方案三：读写分离（对读热 Key）

```
Redis 主从架构：
  Master  →  Slave 1
          →  Slave 2
          →  Slave 3

写操作（点赞/取消点赞）→ Master
读操作（查点赞数）→ 随机选一个 Slave

读 QPS 被分散到多个 Slave 节点，
Master 只承受写压力（通常远低于读压力）
```

**代价**：主从复制存在毫秒级延迟，Slave 数据可能短暂落后于 Master。

#### 方案四：熔断降级

```
思路：当检测到某 key 的 QPS 超过阈值时，
      暂时停止访问 Redis，直接返回降级数据（如 0 或缓存的最后已知值）

实现（Sentinel/Resilience4j）：
  RateLimiter limiter = RateLimiter.of("like-count", config);

  return limiter.executeSupplier(() -> redis.get(key))
      .recover(Exception.class, ex -> fallbackValue); // 降级返回

适用场景：
  - 秒杀/抢购等短暂极高峰
  - 点赞数允许展示"约 xxx 人点赞"而非精确数字
```

---

### 2.6 本项目中的热 Key 分析

回到计数系统，热 Key 的风险点在哪？

```
最可能的热 Key：
  bm:like:knowpost:{爆款文章ID}:{chunk}   // 位图分片（写热）
  cnt:knowpost:{爆款文章ID}              // SDS 计数（读热）

写热场景（爆款文章发布后涌入大量点赞）：
  → Lua 脚本执行 GETBIT + SETBIT
  → 并发写同一个位图分片
  → 但 Lua 脚本是单线程串行的，并发写实际上是排队写
  → Redis 主线程 CPU 被同一 key 的写操作占满

读热场景（Feed 流展示爆款文章点赞数）：
  → 大量并发读 cnt:knowpost:{爆款文章ID}
  → SDS 只有 8 字节，每次 GET 极快
  → 但 QPS 足够高时依然形成热点
```

**本项目的应对**：Kafka 聚合写（多次写压缩为批量更新）部分缓解了写热 Key 问题，但没有针对读热 Key 的本地缓存机制，是一个可以优化的点。

---

## 三、大 Key vs 热 Key 核心总结

```
                  单次操作慢          频繁操作
                       ↓                  ↓
大 Key：         阻塞主线程           —
                  （O(N) 操作）

热 Key：           —             CPU / 带宽 / 节点被单点打满
                              （集群无法水平扩展解决）

两者叠加：       最危险的情况
  同一个 key 既大（HGETALL 很慢）又热（高频访问）
  → 每次慢操作 × 高频触发 = 长时间持续阻塞
```

| | 大 Key | 热 Key |
|---|--------|--------|
| **根本原因** | 数据设计不合理，单 key 存了太多数据 | 业务流量分布不均，少数 key 承受大部分请求 |
| **检测方式** | `redis-cli --bigkeys`、`DEBUG OBJECT` | `redis-cli --hotkeys`、监控 QPS |
| **解决方向** | 拆分数据 | 分散流量 |
| **核心手段** | 分片、压缩、冷热分离 | 本地缓存、Key 复制、读写分离 |
| **Redis 版本优化** | 4.0+ `UNLINK` 异步删除 | 6.0+ 多线程 I/O（但命令执行仍单线程） |

