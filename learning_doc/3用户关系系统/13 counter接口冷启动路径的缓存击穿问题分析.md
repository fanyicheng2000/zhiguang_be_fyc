# counter 接口冷启动路径的缓存击穿问题分析

## 一、什么是缓存击穿

缓存击穿指的是：一个热点 key 在缓存中**不存在或过期**，导致大量并发请求**同时穿透缓存，直接打到数据库**，造成 DB 压力骤增。

与之容易混淆的两个概念：

| 概念 | 触发条件 | 特征 |
|---|---|---|
| **缓存击穿** | 单个热点 key 失效 | 大量请求打同一个 key |
| **缓存穿透** | 查询一个根本不存在的数据 | 每次都打 DB，缓存无法生效 |
| **缓存雪崩** | 大量 key 同时过期 | DB 压力大面积爆发 |

本文讨论的是 `counter` 接口中典型的**缓存击穿**场景。

---

## 二、问题定位：冷启动路径缺少互斥保护

`counter` 接口在读取 SDS 时，如果 key 不存在或结构异常，会直接调用重建：

```
// RelationController.java（冷启动路径）
if (raw == null || raw.length < 20) {
    try {
        userCounterService.rebuildAllCounters(userId);   // ← 无任何互斥保护
    } catch (Exception ignored) {}
    // 重建后二次读取 ...
}
```

`rebuildAllCounters` 的代价很高，内部要依次执行：

```
1. SELECT COUNT(*) FROM following WHERE from_user_id=? AND rel_status=1
2. SELECT COUNT(*) FROM follower  WHERE to_user_id=?   AND rel_status=1
3. SELECT post_id FROM know_post  WHERE author_id=?
4. Pipeline 批量读取每篇帖子的 like/fav 计数（Redis）
5. SET ucnt:{userId} <20字节> 写回 Redis
```

---

## 三、并发场景下的问题

假设某个大V用户（userId=10001）的 SDS key 因 Redis 重启而全部失效，此时 1000 个访问者同时打开他的主页：

```
t=0ms  1000个请求同时 GET ucnt:10001  → 全部返回 null
t=0ms  1000个请求同时进入冷启动分支
t=0ms  1000个请求同时调用 rebuildAllCounters(10001)
           ↓
       1000次 SELECT COUNT(*)  → MySQL 被打穿
       1000次 listMyPublishedIds → MySQL 被打穿
       1000次 Pipeline GET     → Redis 瞬间大量请求
       1000次 SET ucnt:10001   → Redis 写入（结果相同，幂等）
```

**数据结果不会出错**——因为 `rebuildAllCounters` 最终写入的是 `SET`（无条件覆盖），N 次并发写入的值来自同一份 DB 事实，是相同的。**但 DB 承受了 N 倍查询压力**，对于帖子多的大V用户，`listMyPublishedIds` + Pipeline 的代价尤其高。

---

## 四、对比：采样校验路径已正确处理

`counter` 接口中的**采样校验路径**已经通过 `setIfAbsent` 正确解决了这个问题：

```
// 采样校验路径（正确）
String chkKey = "ucnt:chk:" + userId;
Boolean doCheck = redis.opsForValue()
    .setIfAbsent(chkKey, "1", Duration.ofSeconds(300));

if (Boolean.TRUE.equals(doCheck)) {
    // 只有抢到锁的那一个请求执行 DB COUNT
    dbFollowings = relationMapper.countFollowingActive(userId);
    ...
}
// 其余请求 doCheck=false，直接读 SDS 返回
```

`setIfAbsent` 是原子操作（底层是 Redis `SET NX`），在高并发下**只有一个请求能返回 true**，其余全部返回 false，天然实现了互斥。

---

## 五、修复方案：互斥重建（Mutex Rebuild）

在冷启动路径同样引入 `setIfAbsent` 作为互斥锁：

```
if (raw == null || raw.length < 20) {
    String rebuildLock = "ucnt:rebuild:" + userId;
    Boolean got = redis.opsForValue()
                       .setIfAbsent(rebuildLock, "1", Duration.ofSeconds(5));

    if (Boolean.TRUE.equals(got)) {
        // 只有抢到锁的请求负责重建
        try {
            userCounterService.rebuildAllCounters(userId);
        } catch (Exception ignored) {
        } finally {
            redis.delete(rebuildLock);  // 重建完立即释放，不必等满 5s
        }
        // 重建后二次读取
        raw = redis.execute(...);
    }

    // 没抢到锁的请求直接降级返回零
    if (raw == null || raw.length < 20) {
        m.put("followings", 0L);
        m.put("followers",  0L);
        m.put("posts",      0L);
        m.put("likedPosts", 0L);
        m.put("favedPosts", 0L);
        return m;
    }
}
```

**修复后的并发行为：**

```
1000个请求同时 GET ucnt:10001 → 全部 null

setIfAbsent("ucnt:rebuild:10001", "1", 5s)
  → 请求 #1：返回 true  → 执行重建（DB COUNT × 2，写 Redis）
  → 其余 999 个：返回 false → 降级返回零值

重建完毕（约几十ms）
  → 此后新来的请求读到 SDS，走正常路径，返回真实数据
```

**权衡说明：**
- 降级的 999 个请求这次返回的计数是 0，不是精确值
- 但计数是**非核心展示字段**（不影响关注/发帖等操作），偶发一次返回 0 对用户体验影响可接受
- DB 压力从 1000 次重建降为 1 次，是合理的权衡

---

## 六、两条路径的保护对比（修复后）

| 路径 | 触发条件 | 互斥手段 | TTL | 其余请求的处理 |
|---|---|---|---|---|
| 冷启动路径 | SDS 不存在或结构损坏 | `setIfAbsent("ucnt:rebuild:{userId}", 5s)` | 5s（重建完主动 delete） | 降级返回零 |
| 采样校验路径 | 300s 内首次读取触发校验 | `setIfAbsent("ucnt:chk:{userId}", 300s)` | 300s（自然过期） | 直接读 SDS 返回 |

两条路径互斥手段相同（都是 Redis `SET NX`），差异在于：
- 冷启动锁是**短暂锁**（几十ms重建完后主动删除），降低后续请求的等待时间
- 采样校验锁是**节流锁**（300s），目的是限制 DB 校验频率

