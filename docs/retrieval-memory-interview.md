# 检索与记忆面试速答稿（Embedding / Memory / RAG / 召回率）

> 配套：`docs/rag-interview-notes.md`（RAG 选型速答）、`docs/recall-verification.md`（召回率算法详解）、`docs/long-term-memory.md`、`docs/token-control-interview.md`。
> 本稿按**面试提问方式**组织，每题：**速答（可直接背）→ 展开/追问 → 代码锚点**。四大块：Embedding / Memory / RAG 检索链 / 召回率。

---

## 一、Embedding

### Q1. 你项目的 embedding 怎么接的？和 chat 模型什么关系？

**速答**：完全解耦。`app.embedding.provider` 独立开关（`ollama` = nomic-embed-text 768 维 / `openai-compat` = bge-m3 1024 维，生产用 vLLM/TEI 私有化跑），跟 `app.llm.provider`（chat）互不影响——换 chat 模型不动已入库的向量。

**为什么解耦**：chat 和 embedding 是两类模型、两类成本、两种迭代节奏。生产上常见「chat 用云 API、embedding 用自建 bge-m3」的混搭，解耦才换得动。

**代码锚点**：`EmbeddingModelConfig`（按 provider 装配 `EmbeddingModel`）、`app.embedding.*`。

### Q2. 换 embedding 模型要注意什么？（高频，考对向量库的理解）

**速答**：**换 embedding = 换向量维度 = 必须重建持久化向量库**。nomic(768) → bge-m3(1024) 三步：① drop 已有的 PGVector 表 / Milvus collection / Chroma collection；② 重启让 starter 按新维度建表；③ 重新 `POST /rag/ingest` 入库。维度由 `EmbeddingModel.dimension()` 自动取，不硬编码。

**为什么**：向量库的字段维度是建表时定死的，新旧维度不一致会直接报错或算出无意义的相似度。InMemory store 重启即丢，无所谓。

**追问「为什么中文选 bge-m3」**：中文/多语言语义分辨力强、可私有化（数据不出网）、开源零成本；关键不是 768 vs 1024 这个数字，是**模型在你领域语料上的语义分辨力**，得实测。

**代码锚点**：`EmbeddingStoreConfig`、`app.rag.store`（6 种向量库条件化）。

---

## 二、Memory

### Q3. 你项目的「记忆」有几种？（考能不能讲出层次）

**速答**：两套**完全正交**的记忆。
- **A 会话内滑窗（ChatMemory）**：会话内的短期工作记忆，超窗即忘。
- **B 跨会话长期记忆（用户画像）**：跨会话记住用户的持久事实（偏好/属性/反复诉求）。

两者叠加才像「真记得住用户」——A 管这轮对话的上下文，B 管「这个用户是谁」。

**代码锚点**：`ChatMemoryConfig`（A）、`memory/profile`（B）。

### Q4. 会话内滑窗有哪几种策略？summary 那种怎么实现的？

**速答**：`app.memory.window-mode` 三选一：
- `messages`：保留最近 N 条（`MessageWindowChatMemory`，最简单）。
- `tokens`：按 token 预算保留（`TokenWindowChatMemory` + `OpenAiTokenCountEstimator`）。**Ollama 没自带 tokenizer，用 OpenAI 估算偏差 10–15%**（诚实承认这个偏差是加分点）。
- `summary`：自实现 `SummarizingChatMemory`，超窗就把旧消息 **LLM 压成一条摘要 SystemMessage**。

**summary 的工程细节（这块最能体现后端功力，重点讲）**：
- **异步压缩**：`add()` 只追加立即返回，压缩投后台线程池——不阻塞用户请求（压缩要额外一次 LLM 调用）。
- **single-flight**：同一会话正在压缩时不重复触发。
- **per-id 锁**：串行化 read-modify-write，防并发丢更新。
- **temp=0 摘要器**：同样历史每次压出同一摘要，否则记忆漂移。
- **膨胀上限**：`max-summary-chars` 截断，防多轮累积越滚越大。
- **失败不丢消息**：压缩失败保留原消息、下次再压。

**持久化**：`app.memory.store` = `in-memory`（重启丢）/ `redis`（`chat:mem:<chatId>` + TTL，`RedisChatMemoryStore`）。

**追问「per-id 锁的局限」**：只在单 JVM 有效，**多副本部署要上 Redis 分布式锁**（诚实说边界，别装）。

**代码锚点**：`memory/SummarizingChatMemory`、`store/redis/RedisChatMemoryStore`、`app.memory.*`。

### Q5. 长期记忆怎么做的？和滑窗什么区别？

**速答**：`memory/profile` 包。chat 前 `recall` 该用户的 durable 事实注入上下文，chat 后**异步** `observe`（`ProfileExtractor` temp=0 抽取更新画像）。`UserProfileStore` 按 `(tenant, user)` 隔离 + 去重 + 容量淘汰。

**和滑窗的区别**：滑窗是**会话内**短期记忆（超窗即忘、无选择），长期记忆是**跨会话**持久画像（选择性提炼「值得长期记住的事实」）。正交，可叠加。

**为什么异步 observe**：抽取画像是额外一次 LLM 调用，放同步链路会拖慢每次对话；异步在后台更新，不影响响应。

**代码锚点**：`memory/profile/{ProfileExtractor, UserProfileService, UserProfileStore}`、端点 `/chat/memory`、`docs/long-term-memory.md`。

---

## 三、RAG 检索链

### Q6. 你的 RAG 检索链有哪些环节？（考深度，不能只说"检索+拼接"）

**速答**：多路召回 → 精排 → 引用闭环的完整链路，每个旋钮可配：

| 环节 | 实现 | 作用 |
| --- | --- | --- |
| 切分 | 5 种 chunking（recursive / markdown-header / parent-child / semantic）+ char/token 计量 | 决定召回粒度 |
| 多路召回 | 向量 + BM25 关键词（`KeywordContentRetriever`），`DefaultQueryRouter` 路由、**RRF 融合**；中文可挂 HanLP 分词 | 补向量对精确词的盲区 |
| 查询改写 | history-aware（多轮代词消解）+ query-expansion（1→N 变体），自动 `compress→expand` 串链 | 提召回 |
| 精排 | rerank（LLM / Jina / Cohere），`candidate-size` 先放大再收口 | 提精度 |
| 图谱增强 | GraphRAG 作**第三路** retriever，补多跳关系/实体聚合盲区 | 补语义盲区 |
| 引用+校验 | `TaggedSourceContentInjector` 把片段包成 `<source id=...>`，grounding 事后核对是否编造引用 | 可信度闭环 |

**代码锚点**：`LangChain4jConfig`（retriever/augmentor 装配）、`rag/hybrid`、`rag/graph`、`rag/scoring`、`rag/TaggedSourceContentInjector`。

### Q7. 为什么要向量 + BM25 混合？RRF 是什么？

**速答**：向量擅长**语义近似**，但对**精确关键词 / 型号 / 罕见专名**弱（会被语义相近但不对的内容干扰）；BM25 精确词匹配强。两路互补，用 **RRF（Reciprocal Rank Fusion）**融合——按各路结果的**排名倒数**加权求和，不依赖不同检索器分数量纲可比，鲁棒。

**追问「都开 expansion/rerank 不烧钱吗」**：每条 query 多几次 LLM 调用，**对小 corpus 收益有限**（项目实测 nomic 对同义改写已很包容）；大 corpus / 多语言 / 模糊 query 才显价值——按场景开，不无脑全开。

**代码锚点**：`rag/hybrid/KeywordContentRetriever`、`DefaultContentAggregator`（RRF）、`app.rag.hybrid.*`。

### Q8. 有没有被测试逼出来的参数？（考真实工程经验）

**速答**：`app.rag.min-score`（cosine 相似度阈值）默认 **0.3**。一开始想当然设 0.6，结果**中文 query + nomic-embed-text 召回骤降**——eval 把这个钉出来了，才调到 0.3。这是「参数是被测试逼出来的，不是拍脑袋定的」的真实例子。

**代码锚点**：`app.rag.min-score`、eval case（RAG 相关）。

---

## 四、召回率（最考深度，两套答案别答错）

### Q9. 你的 RAG 召回率怎么算？（面试官最爱在这挖坑）

**速答（先分清两套）**：
- **本项目 eval 实际算的是 `passRate`，不是经典召回率**。`passRate = 通过 run 数 / 总 run 数`，单 run pass 条件 = `mustInclude 全命中(规则匹配) && !mustNotInclude && Judge分(temp=0)≥0.6`。这是**端到端答案质量**的代理，把检索 + 生成耦在一起。
- **经典 IR 召回率是 `Recall@k`** = `top-k 里相关文档数 / 该 query 全部相关文档数`（例：标注 4 个相关、top-5 命中 3 → 0.75），**前提是有带标注的黄金集**（query → 相关 chunk id）。

**本项目的现实选择**：没维护标注集，用 `passRate` 代理。但加了一条**召回探针 case** `rag-recall-all-providers`：靶点是「5 个 provider 列在同一 section」——对 chunking 边界**最敏感**的内容。切碎了 → 命中 chunk 只含 2~3 个 → 答案必漏 provider 名 → case fail。**用 pass/fail 间接回归召回完整性**，专门守 token 模式切分后不退化。

**代码锚点**：`eval/EvaluationRunner`、`resources/eval/eval-cases.json`（`rag-recall-all-providers`）、`docs/recall-verification.md`。

### Q10. passRate 把检索和生成耦在一起，怎么定位是哪层的问题？

**速答**：看**本轮检索到的 context**。case fail 时把检索到的 `<source>` 片段打出来（项目 `RetrievedSourcesContext` 这个 ThreadLocal 正好存了检索到的 id），分两种：
- 相关 chunk **根本没进 context** → **召回层**问题（chunking 切碎 / embedding 不行 / top-k 太小）。
- 相关 chunk **在 context 里但答案还漏/错** → **生成层**问题（prompt / 模型没利用上）。

`rag-recall-all-providers` 故意把靶点设成「召回不全必然答不全」，让它的 fail **几乎只可能是召回层**，把变量钉死。

**代码锚点**：`rag/RetrievedSourcesContext`。

### Q11. 没有标注集，怎么低成本造一个？

**速答**：让 LLM 反向造——遍历每个 chunk，让 LLM 基于这段生成 1~2 个「只有这段能回答的问题」，`(问题 → 该 chunk id)` 就是一对弱标注；再人工抽检 10~20% 校正。几百条一天能造出来（RAGAS 的 testset generator 就是这套思路）。**注意去重 + 过滤太泛的问题**（答案能在多个 chunk 找到的丢掉，否则相关性标注不干净）。

### Q12. chunk 切大一点不就全召回了？

**速答**：不行，召回率 vs 精度的 trade-off。chunk 越大，一个向量要表示的语义越杂 → 向量被「稀释」→ query 与它的 cosine 相似度反而下降，**精度掉**，还烧 context、带噪声。甜区 256~512 token。真要又全又准，正解是 **小 chunk 检索 + parent-child 召回大块**（检索粒度与喂 LLM 粒度解耦），不是无脑切大。

### Q13. 召回率和 faithfulness 一样吗？

**速答**：**正交**。召回率管「该召回的有没有召回到」；faithfulness（项目 `GroundednessChecker`，RAGAS 风格把答案拆原子断言逐条对照 `<source>`，`groundedScore = 被支撑数 / 总数`）管「答案有没有扣着检索内容说、有没有乱编」。一个测检索完整性，一个测生成忠实度。

**代码锚点**：`ai/grounding/GroundednessChecker`、`ai/grounding/GroundingService`。

---

## 一句话收口（面试用）

> 我项目的 eval 算的是 **case 通过率 passRate**（`mustInclude` 规则匹配 + temp=0 Judge 打分），是端到端质量代理。我用 `rag-recall-all-providers` 这条 case 把靶点设成对 chunking 边界最敏感的内容，让它的 fail 几乎只来自召回层，间接回归召回完整性。要严格的检索层召回率那是 **Recall@k**，需要 query→相关 chunk 的标注集——我目前没维护，可以用 RAGAS 的 `context_recall` 做无参考评估，或让 LLM 反向造弱标注集补上。Embedding 我做了 chat/embedding/向量库三向解耦，换 embedding 记得重建向量库；Memory 分会话内滑窗（含异步压缩的 summary 记忆）和跨会话长期画像两套正交机制。
