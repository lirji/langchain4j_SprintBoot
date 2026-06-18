# GraphRAG（图谱增强检索）落地设计

> 状态：**G1–G4 全部落地**，默认关、零新依赖、23 个确定性单测（`GraphRagTest` / `GraphIngestorTest` /
> `LlmEntityLinkerTest`）+ 3 条多跳黄金集（`eval-cases-graph.json`）。照本项目一贯方式：默认关
> （`@ConditionalOnProperty`）、in-memory 起步、持久化作为 opt-in。
> 关联：RAG 装配链 → `config/LangChain4jConfig.java`；现有混合召回范式 → `rag/hybrid/*`；
> 召回评估 → `docs/recall-verification.md`；导航 → `CLAUDE.md`。

> **落地与本设计的关键偏差（记录）**：
> - **G3 持久化改 MySQL 边表（`JdbcGraphStore`，`store=jdbc`）而非 Neo4j**：零新依赖
>   （`mysql-connector-j` 已在）、复用 workflow/Doris 同款 JDBC 基建、本机可达可验证，跟项目当初
>   「Doris 自实现 JDBC 而非等官方模块」一个判断。Neo4j 分支仍可后补。
> - **文档生命周期同步提前到 G1**：`DocumentService.delete`/re-upload 按 `displayName#` 前缀同步删图边
>   （`GraphStore.removeBySourcePrefix`，JDBC 走 `WHERE source_id LIKE ?`），避免 re-upload 重复建边。
> - **G4 实体消歧用轻量别名表**（`aliases` 配置：surface→canonical，入库时套用）而非 embedding 聚类——
>   确定性、可测、零成本；embedding 自动消歧仍留作后续。
> - **CI baseline（`baseline-graph.json`）是保守种子**（Ollama 未起没法 derive），起模型后
>   `POST /eval/baseline?set=graph&runs=3` 重生成。

---

## 1. 做什么 / 解决什么

**GraphRAG = 把语料先抽成「实体–关系」知识图谱，检索时从 query 命中的实体出发做 N 跳遍历，
把图上连通的事实喂给 LLM。** 它补的是现有向量/关键词召回的一个**结构性盲区**：

- **多跳关系问题**：「A 和 C 是什么关系？」——而事实是 `A —属于→ B`、`B —隶属→ C`，
  A 和 C 从不出现在同一个 chunk 里。向量召回按语义相似度找单 chunk，**跨 chunk 的关系链它桥不起来**。
- **实体聚合问题**：「张三经手过哪些订单？」——信息散在 10 个 chunk，向量 top-k 只捞到最相似的 3 个。
- **"关系/路径"类问法**：组织架构、产品依赖、供应链、合同条款交叉引用、人物关系。

向量 RAG 擅长「单点事实就近召回」，GraphRAG 擅长「跨片段的关系/路径/聚合」。两者**正交互补**，
所以本设计是**并联第三路召回**，不替换现有任何一路。

**不该用的场景**（跟本项目其它 docs 一样先把边界划清）：

- 小语料 + 单点事实查询 —— 向量已经够，建图的抽取成本（N 次 LLM）纯浪费。
- 非实体密集型文本（散文、闲聊、纯叙述）—— 抽不出有意义的三元组。
- 一句话能答的 FAQ —— GraphRAG 的收益在「多跳」，没有跳就没收益。

---

## 2. 为什么是「轻量 GraphRAG」而不是微软那套

微软 GraphRAG（community detection + 层次化摘要 + map-reduce global search）很重，要全量 LLM 扫语料
建社区摘要，跑一次几十刀。本项目是脚手架，对齐它一贯的「轻量自实现 + 零新依赖起步」取舍
（参考 hybrid keyword 召回不引 Lucene、Doris store 自己写而不等官方模块）：

| 维度 | 微软 GraphRAG（Global） | 本设计（Local / 实体中心） |
| --- | --- | --- |
| 建图 | 全语料 LLM 抽实体+关系+社区摘要 | 仅 chunk 级三元组抽取（已有 chunk 复用） |
| 检索 | 社区摘要 map-reduce 全图扫描 | query 实体链接 → 1–2 跳邻域遍历 |
| 答的问题 | 「这批文档的主要主题是什么」 | 「X 和 Y 什么关系 / X 关联了哪些事实」 |
| 成本 | 建图贵、查询也贵 | 建图一次性、查询零/一次 LLM |
| 依赖 | 通常上图数据库 | 默认 in-memory，可升级 Neo4j |

**Global GraphRAG（社区摘要、主题归纳）本设计明确不做**，归到第 11 节「故意不做」——
它是研究性 > 工程性，且和本项目已有的 multi-agent/summary 能力重叠。

---

## 3. 架构：怎么并联进现有装配链

现有检索枢纽是 `LangChain4jConfig.retrievalAugmentor`：`DefaultQueryRouter` 把 query 扇出到
多路 `ContentRetriever`，`DefaultContentAggregator`（或 rerank aggregator）用 **RRF** 融合。
GraphRAG 就是给 router 再挂一路 `GraphContentRetriever`：

```
                    ┌─ vectorRetriever（EmbeddingStore，已有）
   query ─ Router ──┼─ keywordRetriever（DocumentMirror，已有，hybrid 开时）
                    └─ graphRetriever （GraphStore，本设计新增，graph 开时）
                              │
                       ContentAggregator（RRF / rerank，已有，零改动）
                              │
                    TaggedSourceContentInjector（已有，<source id=...> 闭环）
                              │
                          Assistant
```

**关键点：GraphRAG 召回的 `Content` 仍带 `file_name#chunk` 来源 metadata**，所以现有的
`[doc=ID]` 引用契约、`RetrievedSourcesContext`、grounding Layer 0 引用核对**全部白嫖、零改动**
（见第 8 节 provenance）。这是「并联而非另起炉灶」的核心收益。

入库侧对称地加一个抽取钩子：

```
  文档 → Splitter（已有）→ chunks ─┬─ Embedding → EmbeddingStore（已有）
                                    ├─ DocumentMirror.add（已有，hybrid 用）
                                    └─ GraphExtractor → GraphStore.add（本设计新增）
```

---

## 4. 关键文件（已落地）

新增包 `rag/graph/`，跟 `rag/hybrid/` 对称：

| 文件 | 职责 |
| --- | --- |
| `rag/graph/Triple.java` | record `(String subject, String relation, String object, String sourceId, String tenantId, String category)` —— 一条带来源/租户的三元组 |
| `rag/graph/GraphExtractor.java` | `@AiService`，structured output：chunk 文本 → `List<Triple>`。走 **temp=0 判官模型**（确定性抽取，复用 `LlmConfig.buildJudgeChatModel`，不注册 ChatModel Bean，跟 `Critic`/`Judge`/`SummarizingChatMemory` 同思路） |
| `rag/graph/GraphStore.java` | 接口：`add(List<Triple>)` / `neighbors(Set<String> entities, int hops, tenant, category)` / `entities()` / `remove(sourceId)` / `clear(tenant)` |
| `rag/graph/InMemoryGraphStore.java` | 默认实现：邻接表（`Map<entity, List<Triple>>`），租户/类别隔离，零依赖。**重启即丢**（跟 `InMemoryEmbeddingStore`/`DocumentMirror` 一致），仅本地/演示 |
| `rag/graph/GraphContentRetriever.java` | `implements ContentRetriever`：query 实体链接 → `GraphStore.neighbors` 遍历 → 三元组序列化成 `Content`，租户/类别过滤（**完全照搬 `KeywordContentRetriever` 的隔离套路**） |
| `rag/graph/EntityLinker.java` | query → 种子实体集。两档：`token`（query 分词后跟图里实体名做匹配，零 LLM）/ `llm`（小抽取调用，更准） |
| `config/GraphRagConfig.java` | `@ConditionalOnProperty(app.rag.graph.enabled)` 装配上面这些 Bean；router 注入见下 |
| `config/GraphRagProperties.java` | `app.rag.graph.*` 绑定 |

**改动现有文件（小）**：

- `config/LangChain4jConfig.retrievalAugmentor` —— `@Autowired(required=false) GraphContentRetriever`，
  非空就加进 `DefaultQueryRouter` 的 retriever 列表（跟现在 `keywordRetriever` 那行同款写法）。
- `rag/RagIngestionService` + `rag/lifecycle/DocumentService`（per-tenant 上传路径）——
  入库后多调一步 `graphExtractor` + `graphStore.add`，包在 `app.rag.graph.enabled` 开关里
  （用 `ObjectProvider<GraphIngestor>` 软依赖，关闭时为空、零开销，跟 nl2sql/a2a 服务软注入同套路）。

---

## 5. 配置 `app.rag.graph.*`

```yaml
app:
  rag:
    graph:
      enabled: false            # 默认关，行为与历史完全一致
      store: in-memory          # in-memory（默认，零依赖）| neo4j（升级路径，见第 7 节）
      max-hops: 1               # 遍历跳数；1 够大多数关系问题，2 召回更全但噪声/成本涨
      max-triples: 30           # 单次召回三元组上限，挡 context 爆炸（高连通实体会扇出很多边）
      entity-linking: token     # token（零 LLM，query 分词匹配实体名）| llm（小抽取，更准）
      extract:
        max-triples-per-chunk: 12   # 单 chunk 抽取上限，挡模型把每个名词都连成边
        # 抽取走 temp=0 判官模型，模型名复用 app.llm.<provider> 当前配置
      neo4j:                    # store=neo4j 时才用
        uri: bolt://localhost:7687
        username: neo4j
        password: ${NEO4J_PASSWORD:}
```

设计原则同项目惯例：**默认关 = 历史行为零变化**；每个 key 带「调高/调低各自付什么代价」的注释。
`max-hops` / `max-triples` 是两个最该调的旋钮（召回完整性 vs context 成本/噪声的 trade-off）。

---

## 6. 入库与检索两条链路

### 6.1 入库：chunk → 三元组

复用**已经切好的 chunk**（不重新切），每个 chunk 喂 `GraphExtractor`：

```java
// GraphExtractor @SystemMessage 要点（structured output 约束 + few-shot）
// - 只抽文中明确陈述的关系，禁止推断/补全世界知识（防幻觉边）
// - relation 用动词短语归一（"隶属于"/"负责"/"依赖"），不要每条都造新词
// - subject/object 用文中出现的实体表面形式，实体消歧留给 store 层
// - 3 例 few-shot：典型(组织隶属) / 边界(一句话多关系) / 反例(别把形容词连成边)
record Triple(String subject, String relation, String object,
              String sourceId, String tenantId, String category) {}
```

每条 `Triple` 带上 `sourceId = file_name#chunkIndex`（从 chunk metadata 取，跟
`TaggedSourceContentInjector` 同一个 id 口径）→ 这是第 8 节引用闭环的命脉。

**成本**：建图 = chunk 数 × 1 次 temp=0 LLM 抽取，**一次性、可后台批跑**。
大语料建议异步入库（项目已有 `async` 引擎可复用）。

### 6.2 检索：query → 种子实体 → N 跳邻域 → Content

```java
public List<Content> retrieve(Query query) {
    String tenant = TenantContext.current().tenantId();   // 照搬 KeywordContentRetriever
    String category = CategoryContext.get();
    Set<String> seeds = entityLinker.link(query.text());  // token 或 llm
    if (seeds.isEmpty()) return List.of();                // 没命中实体 → 这一路空手，交给向量路

    List<Triple> sub = graphStore.neighbors(seeds, maxHops, tenant, category);
    // 截断到 max-triples，按「离种子近 + 出现频次」排序
    String block = serialize(sub);   // "张三 —负责→ 订单#1001 [doc=orders.md#3]\n..."
    return List.of(Content.from(TextSegment.from(block, provenanceMetadata(sub))));
}
```

序列化把三元组拼成**人类可读的关系陈述行**（不是 JSON），每行尾带 `[doc=...]`，让 LLM 既能用关系
又能引用来源。`neighbors` 内部强制 `tenantId` 过滤——**多租户隔离在 store 层兜底，跟向量路的
`tenantScopedFilter` 对称**，不能只靠检索后过滤。

---

## 7. 存储选型决策

| 选项 | 何时用 | 代价 | 状态 |
| --- | --- | --- | --- |
| **`in-memory`（默认）** | 本地开发 / 演示 / 中小语料 | 重启即丢、单 JVM。零新依赖 | ✅ `InMemoryGraphStore` |
| **`jdbc`（MySQL 边表）** | 需持久化 / 重启不丢 / 已有 MySQL | 每跳一条 `IN(...)` SQL（走归一列索引）；超大图换 Neo4j | ✅ `JdbcGraphStore` |
| `neo4j` | 真上量 / 复杂图查询（最短路径、社区） | 引 driver + 多一个组件 | ⏳ 接口已留，按信号补 |

**决策**：默认 `in-memory`（照搬 `DocumentMirror` 范式，脚手架最诚实选择）；持久化落 **`jdbc`（MySQL 边表）**
而非 Neo4j——零新依赖、复用 workflow/Doris 同款 JDBC 基建、本机可达可验证。`GraphStore` 抽成接口，
两个实现 `@ConditionalOnProperty(store=…)` 分支化，跟向量库 `EmbeddingStoreConfig` 同手法。

`JdbcGraphStore` 建 `graph_triple` 表（subject/relation/object + 归一列 + source_id/tenant_id/category +
三个 KEY 索引），遍历 = 每跳一条 `WHERE tenant=? AND (subject_norm IN(..) OR object_norm IN(..))`，
租户隔离写进每条 SQL。已接 `kb` profile（`store=jdbc` + `async=true`，跟 Milvus/Redis 一起持久）。

---

## 8. 引用闭环 + grounding（白嫖的关键）

这是本设计相比"另写一套 GraphRAG"最大的工程收益：

- GraphRAG 召回的 `Content` 走的还是 `TaggedSourceContentInjector` → 包成
  `<source id="orders.md#3">张三 负责 订单#1001</source>`，模型看得到 id。
- `app.assistant.citation-policy` 让模型按 `[doc=ID]` 引用 —— **格式契约零改动复用**。
- grounding **Layer 0**（`GroundingService.fabricatedCitations`）核对答案引用的 id ∈ 本轮检索集 ——
  只要 `GraphContentRetriever` 把命中的 `sourceId` 写进 `RetrievedSourcesContext`，编造引用检测白嫖。
- grounding **Layer 1**（faithfulness 拆断言）—— 三元组序列化成 `<source>` 后照常逐断言核对。

**前提**：每条 `Triple` 必须忠实带住它被抽取自哪个 chunk 的 `sourceId`。这条是硬约束，
抽取时丢了来源就等于 GraphRAG 的答案不可溯源、grounding 失效。

---

## 9. Eval：多跳黄金集

照搬 `sql`/`a2a`/`workflow` 的范式，加 `type: "graph"` + 独立黄金集
`resources/eval/eval-cases-graph.json`，`/eval/run?set=graph`：

| type | 调用 | 喂 Judge 的形式 | 用途 |
| --- | --- | --- | --- |
| `graph` | `Assistant.chat(...)`（graph 路开） | 模型回复原文 | 校验多跳关系/聚合问题答全 |

`EvaluationRunner.invokeByType()` 加一支（graph 其实就是 `chat` 在 graph 开启下跑，
可直接复用 `chat` dispatch，独立 set 只为隔离前置）。`GraphRagService` 经 `ObjectProvider` 软注入。

**黄金集要钉的是"向量召回干不了、图能干"的 case**（否则证明不了 GraphRAG 的价值）：

```jsonc
// 故意构造跨 chunk 的关系链：A→B 在 chunk1，B→C 在 chunk2，无单 chunk 同时含 A 和 C
{ "id": "graph-multihop-relation", "type": "graph",
  "question": "张三和『华东大区』是什么关系？",
  "mustInclude": ["李四", "隶属"],   // 期望答出 张三→李四(直属)→华东大区 的链
  "mustNotInclude": ["无法确定", "资料里没有"],
  "judgeHint": "正确行为是顺着 张三-上级-部门 的两跳关系链作答；单 chunk 向量召回会答不全" },

{ "id": "graph-entity-aggregation", "type": "graph",
  "question": "李四经手过哪些订单？",
  "mustInclude": ["订单"],
  "judgeHint": "信息散在多个 chunk，期望图聚合出该实体的全部关联订单" }
```

**对照实验**（证明收益的标准动作，跟 recall-verification.md 一脉相承）：
同一套 graph 黄金集，`app.rag.graph.enabled=false` 跑一遍拿 baseline passRate，
`=true` 再跑一遍 —— 多跳 case 的 passRate 漂上去 = GraphRAG 真有用；没漂 = 这语料不该上图。

**CI baseline（`baseline-graph.json`）当前是保守种子，不是实测**：因为生成时本机 Ollama 未起、
没法 derive，先落了一个低门槛起步线（`minOverallPassRate=0.50` / `minAverageScore=0.50`、无 per-case floor），
只保证「能接 `/eval/gate?set=graph`、不误报 CASE 缺席」。**起一个支持结构化输出的模型后务必重生成**：
`POST /eval/baseline?set=graph&runs=3`（建议 runs≥3），把返回 JSON 覆盖 `src/main/resources/eval/baseline-graph.json`
再提交——届时才有真实的 per-case 合格线。这跟默认集 `baseline.json` 当初「保守起步线、建议真实环境重生成」同处置。

---

## 10. 怎么跑（设计目标）

```bash
# 1) 准备实体密集 + 跨片段关系的语料（组织架构 / 订单关系等）放 ./documents
# 2) 开 graph + 入库（建图）。需 tool-calling/结构化输出能力的模型
APP_RAG_GRAPH_ENABLED=true mvn spring-boot:run
curl -X POST localhost:8080/rag/ingest          # 入库时同步抽三元组建图

# 3) 多跳提问
curl -X POST 'localhost:8080/chat?chatId=u1' -H 'Content-Type: application/json' \
  -d '{"message":"张三和华东大区是什么关系？"}'

# 4) 对照 eval（证明收益）
APP_RAG_GRAPH_ENABLED=true APP_EVAL_AUTO_INGEST=true mvn spring-boot:run
curl -X POST 'localhost:8080/eval/run?set=graph&runs=3'   # vs enabled=false 的 baseline
```

> 多参数覆盖按 CLAUDE.md 的坑用 env var，别堆逗号。

---

## 11. 成本 / 坑 / 故意不做

**成本**：

- 建图 = chunk 数 × 1 次 temp=0 抽取（一次性，大语料走 `async` 后台批跑）。
- 查询侧 `entity-linking=token` 零 LLM；`=llm` 多 1 次小抽取。
- 遍历是内存图操作，`max-triples` 截断挡住高连通实体的 context 爆炸。

**坑（提前记，照本项目"踩坑"传统）**：

1. **实体消歧（entity resolution）是 GraphRAG 真正的难点**：「张三」「张三经理」「Mr. Zhang」是不是同一个
   实体？v1 **不做消歧**，按表面形式存（坦白这个局限），靠抽取 prompt 约束「用文中表面形式」减轻。
   真要做归一是独立工程（embedding 聚类 / 别名表），归到第二阶段。
2. **抽取质量 = 图质量上限**：模型乱抽边 → 图里全噪声 → 召回更差。few-shot 必须含**反例**
   （别把形容词/修饰当关系），且 `max-triples-per-chunk` 兜底。
3. **多跳放大噪声**：`max-hops=2` 召回更全但会把弱相关实体也拉进来，context 变脏反而拉低答案质量。
   默认 `1`，调 2 前先跑 eval 看 passRate 涨没涨。
4. **租户隔离必须在 store 层**：`neighbors()` 遍历时就过滤 `tenantId`，不能遍历完再过滤
   （否则跨租户的边会被遍历到，泄漏路径信息）。跟向量路的 `tenantScopedFilter` 对称。
5. **provenance 不能丢**：三元组抽取时若丢了 `sourceId`，整条引用 + grounding 闭环失效（第 8 节）。

**故意不做（决策记录，照 roadmap E 节风格）**：

| 项 | 为什么不做 |
| --- | --- |
| Global GraphRAG（社区检测 + 层次摘要 + map-reduce） | 研究性 > 工程性，建图/查询都贵；和已有 multi-agent/summary 能力重叠 |
| 实体消歧 / 别名归一（v1） | 独立工程，先按表面形式存能跑，按信号补 |
| 图可视化前端 | 跟 roadmap「Trace store / Web UI 不做」同理，脚手架不是产品 |
| 自动 schema 约束（限定 relation 类型集） | 开放抽取够演示；做受限 schema 是领域定制，留给具体业务 |

---

## 12. 分阶段交付

| 阶段 | 内容 | 验收 | 状态 |
| --- | --- | --- | --- |
| **G1 最小闭环** | `Triple` + `InMemoryGraphStore` + `GraphExtractor` + `GraphContentRetriever`（token 链接，1 跳）+ router 接线 + `app.rag.graph.*` 开关 + 生命周期同步 | 关闭时行为零变化；确定性单测 | ✅ 已落地 |
| **G2 可证收益** | `eval-cases-graph.json`（3 条多跳/聚合/防编造）+ `type:"graph"` dispatch + `GoldenSetsTest` 校验 + `baseline-graph.json` 种子 | graph on/off 的 passRate 对照（见第 9 节方法） | ✅ 已落地 |
| **G3 持久化** | `JdbcGraphStore`（`store=jdbc`，MySQL 边表）+ `async` 后台建图（`graphExecutor`）+ 接 `kb` profile | 重启图仍在；大语料建图不阻塞入库（`GraphIngestorTest` 验 async 投递） | ✅ 已落地（MySQL 代 Neo4j） |
| **G4 进阶** | `entity-linking=llm`（`LlmEntityLinker` + `QueryEntityExtractor`）+ 受限 schema（`relation-types` 白名单）+ 轻量实体消歧（`aliases` 别名表） | 别名归并 / schema 过滤 / llm 锚定均有确定性单测 | ✅ 已落地 |

G1–G4 全部落地。仍留作后续（按信号）：Neo4j 分支、embedding 自动实体消歧、Global GraphRAG（社区摘要，见第 11 节「故意不做」）。

---

## 关联文档

- RAG 装配链 / 召回融合 → `config/LangChain4jConfig.java`、`docs/rag-interview-notes.md`
- 现有第二路召回范式（照抄对象）→ `rag/hybrid/KeywordContentRetriever.java`
- 召回评估 / 对照实验方法 → `docs/recall-verification.md`
- 持久化 profile（G3 接入点）→ `docs/knowledge-base.md`、`application-kb.yml`
- 待完善项 / ROI 决策 → `docs/roadmap.md`
