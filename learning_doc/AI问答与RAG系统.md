# AI 问答与 RAG 系统详解

> 基于 Spring AI + DeepSeek 大模型，为每篇知文构建专属 RAG（检索增强生成）知识库，实现用户针对单篇内容的智能问答。通过合理分块、SHA256/ETag 指纹检测、预索引机制显著提升问答准确性与响应速度。

---

## 一、模块结构

```
llm/
├── rag/
│   ├── RagIndexService.java         # RAG 索引构建（分块/向量写入/幂等重建）
│   └── RagQueryService.java         # RAG 问答查询（检索→Prompt→流式生成）
├── service/
│   ├── KnowPostDescriptionService.java  # AI 摘要生成服务（接口）
│   └── impl/
│       └── KnowPostDescriptionServiceImpl.java  # 调用 DeepSeek 生成摘要
└── LlmConfig.java                   # ChatClient Bean 配置
```

---

## 二、RAG 系统整体流程

```
                    用户发起问答请求
                    GET /api/v1/knowposts/{id}/qa/stream?question=xxx
                           │
                           ▼
              ┌─────────────────────────┐
              │   RagQueryService       │
              │   streamAnswerFlux()    │
              └─────────────┬───────────┘
                             │
                   ┌─────────▼────────┐
                   │ 1. 索引保障        │
                   │ ensureIndexed()   │
                   └─────────┬─────────┘
                             │
               ┌─────────────▼──────────────┐
               │    RagIndexService          │
               │  reindexSinglePost(postId)  │
               └─────────────┬──────────────┘
                             │
          ┌──────────────────▼──────────────────────┐
          │  指纹检查（SHA256/ETag）                  │
          │  已索引 && 指纹未变 → 跳过（return 0）    │
          │  未索引 || 指纹变化 → 执行重建             │
          └──────────────────┬──────────────────────┘
                             │ 需要重建
              ┌──────────────▼────────────────┐
              │  a. 拉取正文（HTTP GET OSS URL）│
              │  b. Markdown 切片              │
              │  c. delete-by-query 删旧切片   │
              │  d. VectorStore.add 写入新切片 │
              └──────────────────────────────-┘
                             │
                   ┌─────────▼────────┐
                   │ 2. 向量检索        │
                   │ searchContexts() │
                   └─────────┬─────────┘
                             │
          ┌──────────────────▼──────────────────────┐
          │  宽召回：topK × 3（至少20条）             │
          │  语义相似检索 VectorStore.similaritySearch│
          │  服务端过滤：metadata.postId == postId    │
          │  取前 topK 条（默认5条）                  │
          └──────────────────┬──────────────────────┘
                             │
                   ┌─────────▼────────┐
                   │ 3. Prompt 构造   │
                   └─────────┬────────┘
                             │
          system: "你是中文知识助手。只能依据提供的知文上下文回答；
                   无法确定的请说明不确定。"
          user:   "问题：{question}\n\n上下文如下：\n{context}\n\n
                   请基于以上上下文作答。"
                             │
                   ┌─────────▼────────┐
                   │ 4. 流式生成（SSE）│
                   │ DeepSeek Chat    │
                   └─────────┬────────┘
                             │
                   ┌─────────▼────────────┐
                   │ 5. Flux<String> 返回  │
                   │ Content-Type:         │
                   │ text/event-stream     │
                   └──────────────────────┘
```

---

## 三、RAG 索引构建详解

### 3.1 Markdown 切片策略

**第一步：按 Markdown 标题切段**

```java
// 遇到 '#' 开头的标题行作为分段边界
for (String line : lines) {
    if (line.startsWith("#") && !buf.isEmpty()) {
        paras.add(buf.toString());  // 保存上一段
        buf.setLength(0);
    }
    buf.append(line).append('\n');
}
```

**第二步：固定长度切片（每片 ≤ 800 字符，100 字符重叠）**

```
段落 A（1500字符）→ 切片为：
  chunk[0] = A[0:800]    (字符 0~799)
  chunk[1] = A[700:1500]  (字符 700~1499，与前一片重叠100字符)
```

**重叠（Overlap）的作用**：
- 防止语义跨片断裂（如"因此...，所以..."被切断到两片中）
- 检索时两片都可能被召回，保留语义连续性
- 100 字符约为 1~2 句话的长度

### 3.2 Document 元数据

每个切片对应一个 `Document`，包含以下元数据：

```json
{
  "postId": "123456",
  "chunkId": "123456#2",
  "position": 2,
  "contentEtag": "abc-etag",
  "contentSha256": "def456...",
  "contentUrl": "https://oss.xxx/content.md",
  "title": "知文标题"
}
```

**postId 元数据的关键作用**：
- 检索时按 `metadata.postId` 过滤，确保问答结果只来自当前知文，防止跨文章内容污染

### 3.3 幂等重建（delete-by-query）

```java
// 先删除旧切片
es.deleteByQuery(d -> d
    .index(esProps.getIndex())
    .query(q -> q.term(t -> t
        .field("metadata.postId")
        .value(v -> v.stringValue(String.valueOf(postId))))));

// 再批量写入新切片
vectorStore.add(docs);
```

**幂等性**：无论调用多少次 `reindexSinglePost`，最终结果一致——每篇知文在 ES 中只有最新版本的切片。

### 3.4 指纹检测（跳过重建）

```java
private boolean isUpToDate(long postId, String currentSha, String currentEtag) {
    // 1. 查 ES 中任意一条该 postId 的已索引文档
    //    获取其 metadata.contentSha256 和 metadata.contentEtag
    //
    // 2. 优先比较 SHA256（更精确）
    //    其次比较 ETag（OSS 对象标识）
    //
    // 3. 一致 → return true（无需重建）
    //    不一致或不存在 → return false（需要重建）
}
```

**指纹检测的意义**：
- 用户每次问答都会调用 `ensureIndexed`，如果每次都重建索引，性能很差
- 通过指纹判断内容是否变化，未变化时直接跳过重建（`return 0`）
- 只有知文内容更新后（新上传文件，OSS ETag 变化），才真正重建

---

## 四、RAG 问答查询详解

### 4.1 宽召回策略

```java
int fetchK = Math.max(topK * 3, 20);
// topK=5 → fetchK=max(15, 20)=20
// topK=10 → fetchK=max(30, 20)=30

List<Document> docs = vectorStore.similaritySearch(
    SearchRequest.builder()
        .query(question)     // 语义相似查询（向量距离）
        .topK(fetchK)        // 先取20条
        .build()
);
```

**为什么先宽召回再过滤**：
- ES 向量搜索时，按 `metadata.postId` 做 pre-filter 可能会影响向量索引（HNSW 图）的召回质量
- 先宽召回（取更多条），然后在 Java 代码中按 postId 过滤，最终取 topK 条
- 确保在向量空间中找到足够相关的切片

### 4.2 Prompt 工程

```
System Prompt（角色设定）：
"你是中文知识助手。只能依据提供的知文上下文回答；无法确定的请说明不确定。"

User Prompt（问题+上下文）：
"问题：{question}

上下文如下（可能不完整）：
{chunk1}

---

{chunk2}

---

{chunk3}

请基于以上上下文作答。"
```

**设计要点**：
- **低温度（0.2）**：减少随机性，使回答更稳定、更准确（减少"幻觉"）
- **仅依据上下文**：系统提示明确限定只能依据提供内容作答，防止模型用训练知识瞎编
- **明确不确定性**：要求模型在信息不足时如实说明，而非猜测

### 4.3 流式输出（SSE）

```java
return chatClient
    .prompt()
    .system(system)
    .user(user)
    .options(DeepSeekChatOptions.builder()
        .model("deepseek-chat")
        .temperature(0.2)
        .maxTokens(maxTokens)
        .build())
    .stream()       // 流式模式
    .content();     // 返回 Flux<String>
```

**Spring WebFlux + SSE**：
- `stream()` 模式下，DeepSeek 会边生成边推送 token
- `Flux<String>` 通过 Spring WebFlux 以 `text/event-stream` 格式推送给前端
- 前端 EventSource 接收，实现打字机效果，提升用户体验

---

## 五、AI 摘要生成（KnowPostDescriptionService）

### 5.1 功能

知文发布后，用户可一键生成 AI 摘要：

```
POST /api/v1/knowposts/{id}/description/generate
  │
  ▼
1. 从 OSS 拉取知文正文（Markdown）
2. 构造 Prompt：
   "请为以下知识文章生成一段不超过200字的中文摘要，
    要求：简洁、准确、体现核心知识点。\n\n{content}"
3. 调用 DeepSeek（非流式，等待完整响应）
4. 更新 know_posts.description 字段
5. 返回摘要文本
```

### 5.2 与 RAG 的区别

| 特性 | AI 摘要 | RAG 问答 |
|------|---------|--------|
| 触发方式 | 用户主动触发（一次性） | 用户提问时实时触发 |
| 模型调用 | 非流式（blocking） | 流式（SSE） |
| 向量库 | 不使用 | 使用（检索上下文） |
| 结果存储 | 写入 DB | 不存储 |
| 应用场景 | 生成简介/摘要 | 智能问答 |

---

## 六、LlmConfig（Spring AI 配置）

```java
@Configuration
public class LlmConfig {
    @Bean
    public ChatClient chatClient(@Qualifier("deepSeekChatModel") ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
```

Spring AI 通过 `spring.ai.deepseek.*` 配置（API Key、Base URL 等）自动注册 `deepSeekChatModel` Bean，`LlmConfig` 以此构建 `ChatClient`。

**向量存储**：`spring.ai.vectorstore.elasticsearch.*` 配置 ES 向量库，Spring AI 自动注册 `VectorStore` Bean，直接注入使用。

---

## 七、预索引机制（减少冷启动延迟）

**问题**：用户首次对某知文提问时，如果尚未建立索引，需要：
1. 从 OSS 拉取全文
2. 分块（可能上百个切片）
3. 批量向量化（调用 Embedding 模型）
4. 写入 ES

这个过程可能耗时数秒，导致首次问答响应很慢。

**解决**：在知文发布时就提前触发索引建立：

```java
// KnowPostServiceImpl.publish()
void publish(...) {
    // ... 业务逻辑 ...
    ragIndexService.ensureIndexed(id);  // 发布时预索引
}

// KnowPostServiceImpl.confirmContent()
void confirmContent(...) {
    // ... 写入 content URL ...
    ragIndexService.ensureIndexed(id);  // 内容确认后预索引
}
```

这样用户发布后几乎立即可以进行 AI 问答，首次问答时 `ensureIndexed` 检测到指纹一致直接跳过，不再重建。

---

## 八、技术组件对应关系

| 功能 | 技术组件 |
|------|---------|
| 向量化（Embedding） | DeepSeek Embedding / ES 内置向量化 |
| 向量存储与检索 | Elasticsearch VectorStore（Spring AI 抽象） |
| 大语言模型 | DeepSeek Chat API |
| 流式输出 | Spring AI `ChatClient.stream()` + Spring WebFlux Flux |
| ES 客户端 | `co.elastic.clients.ElasticsearchClient`（直接使用，指纹检测/删除操作） |
| Spring AI 抽象 | `VectorStore`, `ChatClient`, `Document`, `SearchRequest` |

---

## 九、设计亮点与学习要点

1. **RAG 核心思想**：大模型的"幻觉"问题（凭训练知识瞎编）通过 RAG 得到缓解——先检索相关上下文，再让模型基于上下文回答，而非凭记忆
2. **切片重叠（Overlap）**：保证语义在切片边界处的连续性，提升检索质量，这是 RAG 中非常重要的工程细节
3. **宽召回再过滤**：向量检索不做硬过滤，先宽召回后 Java 层过滤，保证相关切片不被漏掉
4. **指纹幂等**：SHA256/ETag 双保险，内容不变则不重建，保证每次问答都触发检查但性能不受影响
5. **delete-by-query 幂等 upsert**：先删再写，无论调用几次结果一致，解决 ES 索引重复写入问题
6. **预索引**：发布时就建立索引，而非等到用户第一次提问时才建立，消除首次问答的冷启动延迟
7. **低温度**：RAG 问答场景强调准确性，低温度（0.2）让模型输出更稳定、减少胡编
8. **流式 SSE**：大模型生成速度慢，流式输出让用户能立即看到第一个 token，大幅提升感知响应速度

