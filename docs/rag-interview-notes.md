# RAG 面试速答稿：Chunking / 混合召回 / RRF / 召回评估

> 面向「Chunking 怎么切？多路/混合召回怎么做？召回准确率怎么评估？」这类面试追问。
> 内容与本项目实现绑定（`rag/` 包 + eval harness），既能讲「怎么做」也能讲「为什么 / 踩过什么坑」。

---

## 1. Chunking 策略

### 1.1 本项目实现（`app.rag.chunking.strategy`）

| 策略 | 做法 | 取舍 |
| --- | --- | --- |
| `recursive`（默认） | `DocumentSplitters.recursive(max-size=300, overlap=50)`，按 unit 硬切 + overlap | 对任何文档可用、零假设；缺点是会切断完整语义单元 |
| `markdown-header` | 自实现 `MarkdownHeaderSplitter`，按 `(?m)(?=^##+ )` 切 section，每块一个完整主题；超长 section fallback 到 recursive | 语义内聚；给 segment 打 metadata（`section` 标题 + `index` 顺序号），引用 `[doc=file.md#3]` 指第 3 个 section 而非第 3 个块 |
| `parent-child` | 自实现 `ParentChildSplitter`，**small-to-big**：child 小块 embed（精准召回）、parent 大块喂 LLM（上下文完整）；命中 child 后 `TaggedSourceContentInjector` 按 metadata `parent_text` 换 parent 全文、多 child 命中同一 parent 按 `parent_id` 去重 | 解耦「检索粒度」与「喂 LLM 粒度」；parent 全文随 child 冗余存 metadata（零新存储/重启安全，代价 store 膨胀） |
| `semantic` | 自实现 `SemanticChunkingSplitter`，逐句 embed（拼 `buffer-size` 邻居）→ 相邻 cosine 距离 → 超 `breakpoint-percentile` 分位处切；超长块 fallback recursive | 主题内聚最好；复用主 `EmbeddingModel`，代价入库每句多一次 embed；后端故障降级 recursive 不崩 |

**正交叠加：Contextual Retrieval（`app.rag.contextual.enabled`，默认关）** —— 自实现 `ContextualEnricher` + `ChunkContextualizer`（temp=0），入库时给每个 chunk 加一句「安放回全文」的 LLM 上下文前缀再 embed，与上面任何 strategy 都能叠加，跟 hybrid(BM25)/rerank 组合是 Anthropic 原文标配。每块一次 LLM 调用、失败保留原文不崩、串行保 `TenantContext`。

**切分可观测：`ChunkMetrics`（始终在线）** —— 每次入库打 Micrometer 指标 `rag.chunk.{size,total,tiny,oversize}` + `rag.ingest.documents`（按 `strategy` tag），换策略/调 max-size 后切分质量（碎块/超大块比例、尺寸分布）可量化对照，不靠人肉看召回。详见 `docs/observability.md`。

**计量单位 `app.rag.chunking.unit`（chars | tokens）**：
- `chars`（默认）—— 按字符数，零依赖、稳定可控
- `tokens` —— 给 splitter 挂 `OpenAiTokenCountEstimator`（tiktoken），用 `DocumentSplitters.recursive(size, overlap, estimator)` 三参重载，`max-size`/`overlap` 单位变 token；markdown-header 的 section 阈值也按 token 计量
- 为什么要 token：LLM 的 context 和计费都按 token 算，字符≠token（英文 1 token≈4 char；中文 1 字≈1~2 token，按字符切会导致中英文 chunk 的 token 预算严重不均）
- 本地模型（Ollama/bge-m3）不暴露 tokenizer → 用 OpenAI 的 tiktoken 近似（偏差 ~10~15%，对 chunk 软目标可接受，与 `TokenWindowChatMemory` 同款思路）
- **铁律**：token 模式必须保证 `max-size + overlap ≤ embedding 模型 max input`，否则 chunk 尾部被静默截断
- 经验值：chunk **256~512 token** 是 embedding 友好甜区，overlap 取 chunk 的 10~15%

**核心原则：按语义/结构边界切，长度只是 fallback 兜底；计量优先按 token（贴齐 LLM context 与计费）。**

**实测支撑（面试讲这个最有说服力）**：文档把 5 个 chat provider 列在同一个 `## Section` 下。
- recursive(300)：section 被切断，召回只命中 2 个 provider
- markdown-header：整个 section 一个 chunk，5 个全召回

→ 结论：chunk 边界要对齐**语义边界**，不是字符数；overlap 只是缓解硬切跨界丢失。

### 1.2 生产常用的 chunking 谱系（从纯结构到纯语义）

1. **固定大小 + overlap** —— 最基础，稳定可控、零成本，overlap 一般 10~20%
2. **Recursive 递归分隔符** —— 按优先级 `["\n\n","\n","。"," ",""]` 逐级找自然边界，切不动才硬切（LangChain 默认主力）
3. **结构感知** —— 按文档固有结构：Markdown 按标题 / HTML 按 section / 代码按 AST（函数·类）/ PDF 按版面块
4. **Semantic 语义切分** ⭐ —— 逐句 embedding，相邻相似度骤降处即语义边界；主题内聚最好，但预处理慢且贵（LlamaIndex `SemanticSplitterNodeParser`）
5. **Parent-Child / 小切大召** ⭐ —— 小 chunk 检索（精准）、返回所属大 chunk 给 LLM（上下文完整）；解耦「检索粒度」与「喂 LLM 粒度」（LangChain `ParentDocumentRetriever`）
6. **命题切分（Proposition / Dense-X）** —— LLM 把文档改写成自包含原子事实，每条一个 chunk；召回质量高、成本最贵
7. **Contextual Retrieval** ⭐ —— Anthropic 2024 提出，给每个 chunk 用 LLM 生成「全文上下文前缀」再入库；实测检索失败率降 ~35%，配合 BM25 + rerank 降到 ~67%，性价比最高的增量优化

---

## 2. Chunking 选型决策表

### 表一：按文档类型选策略（实战入口）

| 文档类型 | 首选策略 | 为什么 | 备选/叠加 |
| --- | --- | --- | --- |
| Markdown / 技术文档 | 结构感知（按标题层级） | 标题天然是语义边界，每块一个完整主题 | 超长 section → fallback recursive |
| HTML / 网页 | 结构感知（section/标签） | 按 DOM 结构切 | 配合正文抽取去噪 |
| 源代码 | AST 切分（按函数/类） | 字符切会切断语法单元 | 函数太长按逻辑块切 |
| 纯文本长文 / 报告 | Recursive 递归分隔符 | 无固有结构，按分隔符找自然边界 | 召回差 → 上 semantic |
| 扫描件 / 复杂版面 PDF | 版面解析 + 结构切 | 先 layout 还原阅读顺序再切 | 表格单独成块 |
| FAQ / 问答对 | 一问一答成块 | 每个 QA 自包含 | — |
| 高价值知识库（预算充足） | Semantic / 命题切分 | 主题内聚最高、召回最好 | 成本高，按 ROI 上 |

### 表二：策略对比（原理 + 取舍 + 踩坑）

| 策略 | 切割依据 | 召回质量 | 成本 | 需 LLM/embedding | 适用 | 踩坑提示 |
| --- | --- | --- | --- | --- | --- | --- |
| 固定大小 + overlap | token/字符数 | ★★ | 零 | 否 | 兜底、要稳定可控 | overlap 太小跨界丢、太大冗余；按 token 切非字符（中英 token 密度差大）；别在句中断 |
| Recursive 递归分隔符 | 优先级分隔符列表 | ★★★ | 零 | 否 | 非结构化长文默认主力 | 分隔符要适配语言——中文要补 `。！？；`，否则退化成按字符硬切 |
| 结构感知(md/html/code) | 文档固有结构 | ★★★★ | 零 | 否 | 结构化文档（本项目用） | 超长 section 必须有 fallback；代码 AST 处理嵌套；HTML 先去导航/页脚噪声 |
| Semantic 语义切分 | 相邻句 embedding 相似度骤降 | ★★★★ | 中 | 需 embedding | 主题混杂长文 | 强依赖分句质量（中文难）；预处理慢且贵；相似度阈值要调 |
| Parent-Child / 小切大召 | 小块检索、大块喂 LLM | ★★★★ | 低 | 否 | 检索精准 + 上下文完整 | 管好父子 id 映射；父块别太大；多子块命中同一父块要去重 |
| 命题切分(Proposition) | LLM 拆原子事实 | ★★★★★ | 高 | 需 LLM | 高价值精排知识库 | 成本/延迟最高；LLM 改写可能漂移丢信息；指代消解失败产出无意义命题 |
| Contextual Retrieval | 每块加 LLM 上下文前缀 | ★★★★★ | 高 | 需 LLM | 性价比最高的增量优化 | 生成前缀要喂全文（用 prompt caching 压成本）；前缀别太长；按需决定检索后是否保留前缀 |

> ★ 为相对值，实际质量强依赖语料 + embedding 模型，最终以 eval 数据为准。

### 决策流（口述版）

```
结构化文档(md/代码/HTML)?
  ├─ 是 → 结构感知切分(按标题/AST)，超长块 fallback recursive
  └─ 否 → Recursive 递归分隔符
            └─ 召回质量不够 & 预算允许?
                 ├─ 主题混杂 → Semantic 语义切分
                 ├─ 上下文断裂 → Parent-Child(小切大召)
                 └─ 还要再榨 → Contextual Retrieval(LLM 加上下文前缀)
```

**三条第一性原则（收口金句）：**
1. 优先按语义/结构边界切，长度只是 fallback 兜底。
2. 检索粒度和喂 LLM 的粒度可以解耦（Parent-Child），不必同块两头用。
3. 切分策略必须和召回评估数据绑定迭代，不是选最炫的。

---

## 3. 多路召回 / 混合召回

本项目有两层「多路」，面试要分清。

### 3.1 第 1 层：Hybrid Retrieval（向量 + 关键词）

`app.rag.hybrid.enabled=true`：
- **两路并行**：向量检索（语义）+ `KeywordContentRetriever`（token-overlap 关键词）
- **路由**：`DefaultQueryRouter` 把同一 query 分发两路
- **融合**：`DefaultContentAggregator` 用 **RRF** 融合排序

为什么两路：向量解决「同义不同词」，关键词解决「专有名词/缩写/代码符号」——纯向量对精确 token 容易漏，关键词补洞。

**中文坑**：`tokenizer=simple` 默认按字+标点切，召回粗糙；`tokenizer=hanlp`（带词典+停用词）中文召回明显更好。

### 3.2 第 2 层：Query Expansion（查询扩展多路）

`app.rag.query-expansion.enabled=true`：
- LLM 把 1 个 query 扩成 n 个变体（同义改写 / 补上下文 / 拆子问题）
- 多路并行召回 → 同样 RRF 融合

### 3.3 召回 vs 精度：expansion 与 rerank 互补

- **Query Expansion 提召回率**：让相关 chunk 更可能被检索到（广撒网）
- **Rerank 提精度**：已召回候选里挑最相关（收口）
- 生产叠加：`fan-out 多路召回 → ReRanker 收口`
- 本项目 rerank 三档：`llm`（零依赖但慢）/ `jina`（云 API，多语言，快准）/ 还可选 Cohere、本地 ONNX

### 3.4 诚实的加分点

实测在小语料 + 中文 embedding（nomic-embed-text）下，query expansion 收益不显著（embedding 对同义改写已很包容）。真正显价值的是大语料、多语言、模糊 query。所以做成开关、默认关——**不堆技术，按数据决定。**

---

## 4. RRF（Reciprocal Rank Fusion，倒数排名融合）

### 解决的问题
多路召回各给一个排序，分数体系不同（cosine 0~1、BM25 0~50），无法直接相加。

### 核心思想：只看排名，不看分数
```
RRF_score(d) = Σ  1 / (k + rank_i(d))
              i∈各路
```
- `rank_i(d)`：文档 d 在第 i 路的名次（第 1 名 rank=1）
- `k`：平滑常数，通常 60（本项目 `rrf-k` 默认 60）
- 某路未出现的文档，该路贡献 0

### 例子（k=60）
- 文档 D：向量第 1、关键词第 3 → `1/61 + 1/63 = 0.0323`
- 文档 E：向量第 2、关键词缺席 → `1/62 + 0 = 0.0161`
- → D 排 E 前

### 为什么这么设计
1. **免归一化**：向量分与 BM25 量纲不同，强行归一化脆弱；只用排名天然统一
2. **k 控制头部权重**：k 越大头部差距越小（更平滑），越小越偏各路 top；60 是原论文经验值
3. **多路共同背书被奖励**：在多路都靠前的文档两个倒数相加自然冒头——正是 hybrid 想要的
4. **零训练、零参数（除 k）**：工程上几乎免费，成为 hybrid search 事实标准（ES / Weaviate / Milvus hybrid 都内置）

### 一句话总结
> RRF 把多路结果按「排名倒数」加权融合，只信排名不信分数，绕开不同检索器分数不可比的问题；`1/(k+rank)`，k 一般 60，多路共同靠前者得分最高。

---

## 5. 召回准确率 / 效果评估（eval harness）

### 5.1 整体架构
- 黄金集 `eval/eval-cases.json`，每条 `{id, question, mustInclude:[], mustNotInclude:[]}`
- 跑 `POST /eval/run?runs=N`，每 case 跑 N 次
- 两套判分：**规则匹配 + LLM-as-Judge**

### 5.2 关键设计：客观走规则，主观走 Judge
- `coversAllRequiredFacts` / `violatesForbidden` 用 `answer.contains(...)` **规则匹配**，不让 LLM 判（会瞎波动）
- Judge LLM 只负责 `score` 和 `reasoning`
- pass 条件：`coversAllRequiredFacts && !violatesForbidden && score >= 0.6`

### 5.3 压 Judge 噪声（保证评估本身稳定）
1. Judge 用独立 **temp=0** 的 ChatModel —— 同样 (Q,A) 多次评分给同一个 score
2. 客观事实规则匹配，不让 Judge 认字面
3. 给 Judge 注入 `today` —— 否则按训练 cutoff 推日期会判错时间类答案
4. Judge 不重复审 MUST_* —— 避免重复扣分

### 5.4 Multi-run 看方差
跑 `runs=3~5`，看 `scoreStdev`：σ≈0 才是真稳定，σ>0.1 说明答案不稳定。runs=1 只是 smoke test。

### 5.5 验证「换 chunking 配置后召回没掉」（含 token 模式）

eval-cases.json 里有一条专门的强召回 case `rag-recall-all-providers`：
- 靶点是 `project-faq.md` 把 **5 个 chat provider 列在同一个 `## 2.` section** 里——对 chunking 切分边界最敏感的内容
- 切分若把该 section 切碎，召回只命中 2~3 个 provider，5 个名字 `mustInclude` 任一缺失即 fail
- 用来回归「换了 chunking（尤其 `unit=tokens`）召回掉没掉」
- 跑法（同时设 unit + auto-ingest，用 env var 避开多参数逗号丢值的坑）：
  ```bash
  APP_RAG_CHUNKING_UNIT=tokens APP_EVAL_AUTO_INGEST=true mvn spring-boot:run
  # 另开终端
  curl -X POST 'localhost:8080/eval/run?runs=3'
  ```
- 对照组 `APP_RAG_CHUNKING_UNIT=chars` 同跑一遍，两者都应 passRate=1.0；token 模式掉了说明 `max-size` 设太小把 section 切碎了

### 5.6 针对「召回是否支撑答案」：grounding / faithfulness
- **Layer 0（零 LLM）**：答案里 `[doc=ID]` 引用的 id 必须在本轮真实检索集合里，否则判「编造引用」
- **Layer 1（faithfulness，RAGAS 风格）**：把答案拆成原子断言，逐条对照 `<source>` 判是否被支撑，`groundedScore = 被支撑数 / 总数`

### 5.7 eval 的真正价值（收尾金句）
> eval 不是为了拿满分，而是 prompt/模型/RAG 配置的**回归告警器**。每改一个变量（只改一个）就重跑，看分数漂没漂——漂下去说明改坏了。全 1.0 反而说明 case 不够锐利，该加对抗样本。这套 harness 实测钉出过两个真 bug：测试用例题面自相矛盾、citationPolicy 被误套用到所有问答。

---

## 附：召回评估的通用指标（补充，面试可能追问）

> 上面是本项目的工程化做法；如果面试官问「学术/通用怎么量化召回」，补这些：

- **Recall@k**：top-k 里召回到的相关文档数 / 全部相关文档数（召回率）
- **Precision@k**：top-k 里相关文档占比（精度）
- **MRR（Mean Reciprocal Rank）**：第一个相关文档排名倒数的均值，看「正确答案排多靠前」
- **NDCG@k**：带位置折扣的相关性增益，支持多档相关性（最常用的排序质量指标）
- **Hit Rate**：top-k 是否至少命中一个相关文档
- **RAGAS 套件**：context precision / context recall / faithfulness / answer relevancy —— 专为 RAG 设计，本项目 faithfulness 即来自此思路

前提是要有**带标注的黄金集**（query → 相关文档 id）。没有标注时，用 LLM-as-Judge（本项目做法）或 RAGAS 的无参考指标兜底。
