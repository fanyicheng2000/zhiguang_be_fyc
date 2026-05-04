# 知文发布与 Feed 流系统详解

> 知文（KnowPost）是平台的核心内容单元。发布采用**渐进式流程**（草稿 → 内容上传 → 元数据 → 发布），支持图文、视频等多媒体内容存储于 OSS，并接入 AI 摘要。Feed 流采用**三级缓存架构**（Caffeine → Redis 片段缓存 → MySQL），配合 SingleFlight 单飞锁防缓存击穿，热点探测自动延长 TTL 抗雪崩。

---

## 一、模块结构

```
knowpost/
├── api/
│   ├── KnowPostController.java         # REST 接口（发布/Feed/详情/操作）
│   ├── KnowPostFeedController.java     # Feed 流专用接口
│   └── dto/
│       ├── FeedItemResponse.java       # Feed 条目响应
│       ├── FeedPageResponse.java       # 分页 Feed 响应
│       ├── KnowPostDetailResponse.java # 知文详情响应
│       └── ...
├── id/
│   └── SnowflakeIdGenerator.java       # Snowflake 分布式 ID 生成器
├── model/
│   ├── KnowPost.java                   # 领域实体（Builder模式）
│   ├── KnowPostFeedRow.java            # Feed 查询结果映射
│   └── KnowPostDetailRow.java          # 详情查询结果映射
├── mapper/
│   └── KnowPostMapper.java             # MyBatis 数据访问接口
├── listener/
│   └── CounterEventListener.java       # 监听点赞/收藏事件 → 缓存失效
└── service/
    ├── KnowPostService.java            # 知文 CRUD 服务接口
    ├── KnowPostFeedService.java        # Feed 流服务接口
    └── impl/
        ├── KnowPostServiceImpl.java    # 知文 CRUD 实现
        └── KnowPostFeedServiceImpl.java # Feed 流实现（三级缓存）
```

---

## 二、知文发布（渐进式发布流程）

### 2.1 整体流程

```
第1步：POST /api/v1/knowposts/draft
       → createDraft → INSERT know_posts (status=draft)
       → 返回 postId（Snowflake ID）

第2步：GET /api/v1/knowposts/{id}/upload-url
       → OssStorageService.generatePresignedPutUrl(objectKey, contentType, 600)
       → 返回预签名 PUT URL（有效期10分钟）

       前端直接 PUT 文件到 OSS（不经过后端），节省带宽

第3步：POST /api/v1/knowposts/{id}/content
       confirmContent(creatorId, id, objectKey, etag, size, sha256)
       → 写入 contentObjectKey/contentEtag/contentUrl
       → 缓存双删（delete before & after）
       → 触发 RAG 预索引（草稿阶段可能被跳过）

第4步：POST /api/v1/knowposts/{id}/metadata
       updateMetadata(creatorId, id, title, tags, imgUrls, visible, isTop, description)
       → 写入元数据
       → 缓存双删
       → 写入 Outbox 事件（KnowPostMetadataUpdated → 触发搜索索引更新）

第5步：POST /api/v1/knowposts/{id}/publish
       publish(creatorId, id)
       → UPDATE status='published', publish_time=NOW()
       → userCounterService.incrementPosts(creatorId, +1)
       → 写入 Outbox 事件（KnowPostPublished → 触发搜索索引写入）
       → 触发 RAG 预索引（减少首次问答冷启动延迟）
```

### 2.2 Snowflake ID 生成器

```java
// SnowflakeIdGenerator
// 格式：[时间戳 41bit][数据中心 5bit][机器 5bit][序列号 12bit]
// 每毫秒最多生成 4096 个不重复 ID
// 优点：趋势递增（有利于 B-Tree 索引性能）、全局唯一、不需要数据库自增
```

### 2.3 缓存双删策略（Double Delete）

```java
// updateMetadata 示例
void updateMetadata(...) {
    invalidateCache(id);      // 第1次删：防止旧缓存被其他线程读走
    // ... 执行数据库更新 ...
    invalidateCache(id);      // 第2次删：确保更新后的脏缓存也被清除
}

void invalidateCache(long id) {
    redis.delete("knowpost:detail:" + id + ":v1");  // Redis 删除
    knowPostDetailCache.invalidate(pageKey);          // Caffeine 失效
}
```

**为什么需要双删**：更新数据库前后各删一次，防止"更新数据库后、缓存被另一个并发请求重新写入旧值"的竞态窗口。

---

## 三、知文详情读取（两级缓存 + SingleFlight）

### 3.1 完整流程

```
GET /api/v1/knowposts/detail/{id}
  │
  ▼
KnowPostServiceImpl.getDetail(id, currentUserIdNullable)
  │
  ├─ L1: Caffeine 本地缓存（knowPostDetailCache）
  │       命中 → recordHotKeyAndExtendTtl + enrich（叠加用户状态）→ 返回
  │
  ├─ L2: Redis 缓存
  │       key = knowpost:detail:{id}:v1
  │       命中（非"NULL"）→ 反序列化 → L1写回 → enrich → 返回
  │       命中"NULL" → 抛 BusinessException（内容不存在）
  │
  ├─ 缓存未命中 → 进入 SingleFlight 模式
  │       computeIfAbsent(pageKey, k -> new Object())
  │       synchronized(lock) {
  │         双重检查（Double Check）：再读一次 Redis
  │         仍未命中 → 查数据库（DB）
  │       }
  │
  ├─ DB 查询
  │   ├─ row == null || status == "deleted"
  │   │   → SET pageKey "NULL" TTL=30+random(31)s  # 防穿透
  │   │   → 抛异常
  │   │
  │   ├─ 权限校验：
  │   │   is_public = (status=published AND visible=public)
  │   │   is_owner  = currentUserId == creatorId
  │   │   !is_public && !is_owner → 抛"无权限查看"异常
  │   │
  │   └─ 组装 KnowPostDetailResponse
  │       → 写入 Redis（TTL = max(hotKeyTtl, 60+random(30)s)）
  │       → 写入 Caffeine L1
  │       → singleFlight.remove(pageKey)
  │       → enrich（叠加用户 liked/faved 状态）→ 返回
  │
  └─ enrich：覆盖用户维度状态（不写入公共缓存）
      liked = counterService.isLiked("knowpost", id, userId)
      faved = counterService.isFaved("knowpost", id, userId)
```

### 3.2 缓存污染问题与解决

**问题**：liked/faved 是用户个性化数据，如果写入公共缓存，A 用户的点赞状态会被 B 用户看到。

**解决**：
1. 存入缓存的对象中 `liked/faved` 字段为 `null`
2. 每次从缓存读取后，调用 `enrich()` 方法实时查询当前用户的 liked/faved
3. 返回给客户端的对象中包含正确的个人状态，但缓存层永远不存个人状态

### 3.3 热点探测（HotKeyDetector）与 TTL 自动延长

```java
// 每次缓存命中都记录访问
hotKey.record("knowpost:" + id);

// 根据热度计算目标 TTL
int target = hotKey.ttlForPublic(60, "knowpost:" + id);
// 热度级别1：60-90s
// 热度级别2：120-180s
// 热度级别3：300-600s

// 如果当前 TTL < 目标 TTL，则延长
if (redis.getExpire(key) < target) {
    redis.expire(key, Duration.ofSeconds(target));
}
```

**同时延长**：
1. 详情页缓存（`knowpost:detail:{id}`）
2. Feed 流内容片段缓存（`feed:item:{id}`）

这样热点内容在首页 Feed 流中也不会轻易过期，两个缓存共用同一热度统计，避免重复计算。

---

## 四、Feed 流（三级缓存架构）

### 4.1 公共首页 Feed（getPublicFeed）

```
GET /api/v1/knowposts/feed?page=1&size=20
  │
  ▼
KnowPostFeedServiceImpl.getPublicFeed(page, size, currentUserIdNullable)
  │
  ├─ L1: Caffeine（feedPublicCache）
  │       key = feed:public:{size}:{page}:v1
  │       TTL由 Caffeine 配置（如30秒）
  │       命中 → 记录热度 + enrich（叠加用户状态）→ 返回
  │
  ├─ L2: Redis 片段缓存（组装模式）
  │   assembleFromCache(idsKey, hasMoreKey, page, size, uid)
  │     ├─ idsKey = feed:public:ids:{size}:{hourSlot}:{page}
  │     │    按小时分片：hourSlot = currentTimeMs / 3600000
  │     │    → LIST：[id1, id2, ..., idN]（页内内容 ID 列表）
  │     │
  │     ├─ 批量 MGET feed:item:{id}（内容元数据片段缓存）
  │     ├─ 批量 getCounts（点赞/收藏计数）
  │     └─ 组装 FeedPageResponse（liked/faved 实时计算不写缓存）
  │
  ├─ 片段缓存未命中 → SingleFlight（按 idsKey 为航班号）
  │       computeIfAbsent(idsKey, k -> new Object())
  │       synchronized(lock) {
  │         再查一次片段缓存（Double Check）
  │         仍未命中 → 查 DB
  │       }
  │
  └─ DB 回源
      mapper.listFeedPublic(size+1, offset)  # 多查1条以判断 hasMore
      写片段缓存（writeCaches）：
        ├─ SET idsKey → LIST of IDs，TTL = 60+random(30)s
        ├─ SET hasMoreKey → "0"/"1"，TTL = 10~20s
        └─ SET feed:item:{id} → JSON，TTL同 idsKey
      写 L1 Caffeine
      enrich（叠加用户状态）→ 返回
```

### 4.2 片段缓存（Segmented Cache）

**为什么不缓存整页**：
- 不同用户访问同一页内容时，liked/faved 不同
- 如果缓存整页（含用户状态），则每个用户各一份缓存，内存消耗与用户数成正比

**三段分离策略**：
```
片段1：feed:public:ids:{size}:{hourSlot}:{page}  → 纯 ID 列表（无个人信息）
片段2：feed:item:{id}                           → 内容元数据（无个人信息）
片段3：cnt:{entityType}:{id}（SDS）              → 计数（无个人信息）
个人状态：实时查询，不缓存
```

### 4.3 按小时分片的 idsKey

```java
long hourSlot = System.currentTimeMillis() / 3600000L;
String idsKey = "feed:public:ids:" + size + ":" + hourSlot + ":" + page;
```

**目的**：每小时自然切换一次 idsKey，避免 Feed 列表长期使用同一 key 导致的内容"僵化"（新发布内容无法进入缓存）。同时，旧 hour 的 key 会随 TTL 过期自动清理。

### 4.4 随机抖动（Jitter）防雪崩

```java
int baseTtl = 60;
int jitter = ThreadLocalRandom.current().nextInt(30);
Duration frTtl = Duration.ofSeconds(baseTtl + jitter);  // 60~89秒
```

如果所有 key 同时设置相同 TTL，会在同一时刻全部过期，导致 Redis 击穿风暴（雪崩）。加入随机抖动，使过期时间分散在 60~90 秒范围内，把集中的过期请求分散到 30 秒内，大幅降低单时刻 DB 压力。

### 4.5 "我的发布"列表（getMyPublished）

```
Caffeine（feedMineCache）→ Redis 整页缓存（用户维度）→ DB
  │
  ├─ key = feed:mine:{userId}:{size}:{page}
  ├─ TTL 更短（30+random(20)s），因为个人列表变化频率较高
  └─ 缓存内容含 liked/faved（用户自己的列表，无污染风险）
```

---

## 五、内容状态机

```
草稿（draft）
    │
    │ publish()
    ▼
已发布（published）    ←── 可见性：public/followers/school/private/unlisted
    │
    │ delete()
    ▼
已删除（deleted）      ← 软删除，数据库记录保留
```

### 5.1 可见性规则

| visible 值 | 可见范围 |
|-----------|--------|
| `public` | 所有人 |
| `followers` | 粉丝（暂留接口，服务层按需校验） |
| `school` | 同校用户（暂留接口） |
| `private` | 仅作者本人 |
| `unlisted` | 知道链接的人 |

---

## 六、与其他系统的集成

### 6.1 OSS 集成（对象存储）

```
知文正文（Markdown）→ OSS Object Key = {folder}/post/{id}/content.md
封面图片         → OSS Object Key = {folder}/post/{id}/images/{n}.jpg
预签名 PUT URL   → 有效期 600 秒，前端直传，后端不承担流量
```

### 6.2 AI 摘要生成

```
POST /api/v1/knowposts/{id}/description/generate
  → LLM Service 读取知文正文（从 OSS URL 拉取）
  → 调用 DeepSeek 模型生成摘要（< 200 字）
  → 写回 know_posts.description 字段
```

### 6.3 搜索索引集成（Outbox 事件驱动）

```
知文发布/更新/删除
    │ outboxMapper.insert(KnowPostPublished/Metadata/Deleted)
    ▼
Canal → Kafka
    ▼
CanalOutboxConsumerSearch（search 模块消费）
    ├─ KnowPostPublished  → ES upsert（写入全量文档）
    ├─ KnowPostMetadataUpdated → ES partial update（更新 title/tags 等）
    └─ KnowPostDeleted    → ES delete（删除索引文档）
```

### 6.4 RAG 索引集成

```
confirmContent / publish
    │
    └─ ragIndexService.ensureIndexed(postId)
        ├─ 指纹检查（SHA256/ETag）
        ├─ 无变化 → 跳过
        └─ 有变化/首次 → 重建向量索引（chunkMarkdown → VectorStore.add）
```

---

## 七、数据库核心字段

```sql
CREATE TABLE know_posts (
    id              BIGINT PRIMARY KEY,         -- Snowflake ID
    creator_id      BIGINT NOT NULL,            -- 作者 ID（外键 users）
    title           VARCHAR(256),               -- 标题
    description     TEXT,                       -- 摘要/描述（AI 生成）
    content_url     VARCHAR(1024),              -- 正文 OSS URL
    content_object_key VARCHAR(512),            -- OSS Object Key
    content_etag    VARCHAR(128),               -- OSS ETag（指纹）
    content_sha256  VARCHAR(64),                -- 内容 SHA256（指纹）
    content_size    BIGINT,                     -- 正文大小（字节）
    img_urls        JSON,                       -- 图片 URL 列表
    tags            JSON,                       -- 标签列表（字符串数组）
    tag_id          BIGINT,                     -- 主标签 ID（暂留）
    status          VARCHAR(32) DEFAULT 'draft',-- draft/published/deleted
    visible         VARCHAR(32) DEFAULT 'public',-- 可见性
    is_top          TINYINT(1) DEFAULT 0,       -- 是否置顶
    type            VARCHAR(32) DEFAULT 'image_text',
    publish_time    TIMESTAMP,
    create_time     TIMESTAMP,
    update_time     TIMESTAMP
)
```

---

## 八、Redis Key 汇总

| Key 格式 | 类型 | 说明 |
|----------|------|------|
| `knowpost:detail:{id}:v1` | String（JSON） | 知文详情缓存 |
| `feed:public:{size}:{page}:v1` | Caffeine key | 公共 Feed 本地缓存 |
| `feed:public:ids:{size}:{hourSlot}:{page}` | List | Feed 页内容 ID 列表（片段1） |
| `feed:public:ids:...hasMore` | String | 是否有下一页（软缓存） |
| `feed:item:{id}` | String（JSON） | 内容元数据片段（片段2） |
| `feed:public:pages` | Set | 已缓存页面 key 集合（反向索引） |
| `feed:public:index:{id}:{hour}` | Set | 内容→页面反向索引 |
| `feed:mine:{userId}:{size}:{page}` | String（JSON） | 用户个人发布列表缓存 |

---

## 九、设计亮点与学习要点

1. **渐进式发布**：将发布过程拆分为多步，每步独立可重试，前端体验流畅（草稿自动保存）
2. **预签名直传**：后端只负责生成签名 URL，内容直传 OSS，省去了后端转发大文件的带宽成本（尤其是视频）
3. **三级缓存架构**：Caffeine（毫秒级本地）→ Redis 片段缓存（毫秒级远程）→ MySQL（百毫秒级DB），按热度递进命中
4. **片段缓存分离用户状态**：公共缓存（IDs/内容片段/计数）与个人状态（liked/faved）完全隔离，避免缓存污染
5. **SingleFlight（单飞锁）**：同一 Key 只允许一个请求回源，其余等待后共享结果，防止缓存击穿时的"惊群效应"
6. **空值缓存**：内容不存在时写入 "NULL" 值，防止缓存穿透（恶意大量查询不存在 ID 打爆 DB）
7. **按小时分片的 idsKey**：自然淘汰旧内容，新发布内容能进入 Feed，避免 Feed 内容"僵化"
8. **热点探测 + TTL 延长**：热点内容自动延长缓存时间，冷内容按时过期，智能区分对待
9. **随机抖动（Jitter）**：防止大批量 key 同时过期引发的缓存雪崩
10. **缓存双删**：数据库更新前后各删一次缓存，缩小脏读时间窗口

