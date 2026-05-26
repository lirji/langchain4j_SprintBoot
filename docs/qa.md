# Q&A

项目里反复被问到的概念性问题，按时间倒序记录。新问题加到顶部。

每条格式：

- **Q**: 原问题
- **背景**: 为什么问这个 / 误解从哪来
- **A**: 直接答案 + 详细展开 + 取舍

---

## Q4. 跨 provider 的 prompt 差异怎么处理？

> 问于 2026-05-26

### 背景

不同 chat provider 对 prompt 的偏好不一样：

- DeepSeek-V3：中文强，但 system prompt 太长会忽略后半段
- Claude Haiku：偏好 XML 标签（`<fact>...</fact>` 之类）
- Gemini Flash：tool-calling 触发不积极，工具描述要更"诱导"
- Ollama 小模型：需要更明确的指令 + few-shot 兜底
- vLLM：看跑哪个 base model

最初版本 `AssistantProperties` 一份默认值用到所有 provider —— 显然不是最优。Round h 加了 per-provider override 机制。

### A

`AssistantProperties` 保留默认字段，新增 `overrides: Map<String, Override>`。启动时按 `app.llm.provider` 解析成 `ResolvedAssistantStyle` Bean，调用方注入 Bean。Override 里任何字段为 null 就 fallback 到默认 —— 部分覆盖，不需要复制整套 default。

#### yml 配置示例

```yaml
app:
  assistant:
    language: "中文"
    tone: "简洁，1–2 句话答完，必要时再展开"
    citation-policy: |-
      引用与来源处理...
    extra: ""
    overrides:
      anthropic:
        # Claude 偏好 XML 标签结构化
        tone: "简洁，1–2 句；分组事实时用 <fact>...</fact> XML 标签"
      gemini:
        # Gemini 触发工具不积极
        extra: "如果有可用工具能直接给答案，立刻调用；不要先猜再决定"
      ollama:
        # Ollama 小模型，每句独立
        tone: "简洁，每句独立成段，避免长复合句；最多 3 句"
      deepseek:
        # DeepSeek 中文强，可以更口语
        tone: "口语化，像跟同事讲技术，必要时用类比；2–3 句话"
```

#### 实测效果

同样问题「什么是 Spring DI？」，DeepSeek 用默认 vs override：

- **默认 tone**：「Spring DI 是 Spring 框架的核心机制，它让对象之间的依赖关系由容器在运行时自动注入...」（2 个正式长句）
- **override 口语 tone**：「**简单说就是**对象不再自己 new 依赖，而是由 Spring 容器在运行时把需要的依赖注入进去。**比如**你有个 Service 需要用到 Dao...」（3 句话 + 代码举例）

#### 设计决策

- **启动时一次性解析 → ResolvedAssistantStyle Bean**：相比每次调用动态查 overrides Map 简单。Provider 切换 = 重启（项目里 provider 本身就是启动期定的）
- **部分覆盖，不是整套替换**：只列要改的字段，其他沿用默认。避免 "改个 tone 还要复制 citationPolicy 一长串"
- **null = fallback，空串 = 真清空**：想真不要 citationPolicy 就传 `citation-policy: ""`；不写或写 `null` 表示沿用上面的默认
- **AssistantProperties 不再被业务调用方用**：4 个调用方（ChatController / CategoryChatService / EvaluationRunner / QueryRouterService）都改成注入 `ResolvedAssistantStyle`。这把"配置长什么样"和"运行时实际用哪份"解耦

#### 什么时候该开 overrides？

**默认不动**。生产里只有 1 个 provider 时完全没必要。要开的信号：

- 真在多个 provider 间路由（少见 —— 大部分项目就一个）
- 某个 provider 上 eval 分数明显偏低，怀疑是 prompt 不匹配
- 切到 Claude 后想用 XML 标签格式，切到 Gemini 想加 tool 诱导

最常用：**临时对照实验**。生产跑 DeepSeek，本地测 Anthropic，加 anthropic override，看是不是 prompt 锁太死。

参考代码：`config/AssistantProperties.java`（默认 + Override 内部类 + resolve 方法）、`config/ResolvedAssistantStyle.java`、`config/AssistantStyleConfig.java`（@Bean 解析）。

---

## Q3. Multi-agent 的 DAG 怎么用？跟 flat 并行比有什么差别？

> 问于 2026-05-26

### 背景

`/chat/multi-agent` 早期是纯并行 fan-out（所有 sub-task 同时跑）。round-h 后加了 DAG 支持，`SubTask` 多出 `dependsOn: List<String>` 字段。**什么场景适合开 DAG**？滥用 DAG 会丧失并行价值。

### A

**默认仍是 flat 并行**（`dependsOn=[]`），只有当一个 sub-task 的指令**字面引用**另一个 sub-task 的输出时才用 DAG。

#### 判断标准

写出 sub-task 的 description 时，如果你必须用类似的措辞才能让 Worker 理解任务：

- 「基于 t1 列出的 X，挑出..」
- 「使用 t1 的结果，进一步...」
- 「根据 t1 给的 3 个候选，选最好的并...」

→ 这就是真依赖，加 `dependsOn: ["t1"]`。

否则别加。即便是逻辑上有顺序关系的任务（比如「先查事实再总结」），如果"总结"完全可以由 `Synthesizer` 合成阶段统一处理 —— 那就让 sub-tasks 平级并行，`Synthesizer` 收尾。**合成是 Synthesizer 的事，不是 Planner 的事**。

#### 实测对比

**Flat case**（HTTP/1.1 vs HTTP/2 三维比较）：

```text
plan.tasks:
  t1 dependsOn=[]: 对比 HTTP/1.1 与 HTTP/2 在连接复用方面的差异
  t2 dependsOn=[]: 对比 HTTP/1.1 与 HTTP/2 在头部压缩方面的差异
  t3 dependsOn=[]: 对比 HTTP/1.1 与 HTTP/2 在多路复用方面的差异

agent-1 / agent-3 / agent-4 同一秒齐开 → 5-7s 内全部完成（并行）
```

**DAG case**（先列 Java 21 特性再聚焦最影响并发的一个）：

```text
plan.tasks:
  t1 dependsOn=[]: 列出 Java 21 引入的 3 个最重要的语言层面新特性
  t2 dependsOn=[t1]: 基于 t1 列出的 3 个特性，挑出对并发编程影响最大的那一个

agent-1 跑 t1（~3s）→ t1 完成后 agent-2 才开始跑 t2（接收 t1 输出作为 upstream context，~9s）
t2 选了 Virtual Threads，详细展开了设计动机 + 代码示例
```

DAG case 里 t2 **不能在不知道 t1 输出**的情况下开始（"挑出"暗示需要选项），所以必须等。

#### 实现要点

- **Kahn 拓扑排序**：按入度分层，同层并行，跨层等待。`MultiAgentService.topologicalLevels()`
- **环检测**：拓扑序中途无法推进 → 有环 → **降级 flat 全并行**（丢掉所有 deps）+ log 警告。不抛异常，因为业务流量可能瞬时 plan 出 bug，丢部分能力比整个失败好
- **上游 id 清洗**：dependsOn 引用不存在的 id 时 log 警告并丢弃，剩下的有效依赖照常执行
- **Worker 不感知 DAG**：只接收 `(task, upstream)` 两参数，upstream 拼好的 string 传过去，Worker 当成普通上下文消化

#### 滥用 DAG 的代价

如果 Planner 把每个 task 都串成链：

```text
t1 → t2 → t3 → t4
```

那就退化成单线程顺序执行，比纯 Synthesizer 合成还慢，**完全失去 multi-agent 价值**。Planner prompt 里专门有一条反例钉这种情况：

> For "对比 X 在 a, b, c 三方面" do NOT chain as:
> `t1: 对比 a`、`t2 [deps: t1]: 对比 b`、`t3 [deps: t2]: 对比 c`。
> Aspects are INDEPENDENT — keep them parallel, no deps.

参考代码：`ai/multiagent/SubTask.java`、`Planner.java`（DAG 教学 + 反例）、`Worker.java`（upstream 参数）、`MultiAgentService.java`（拓扑排序）。

---

## Q2. Query routing (`/chat/auto`) 什么时候值得开？

> 问于 2026-05-26（紧跟 Q1 的后续）

### 背景

Q1 提到"Query routing"是未来可加的 LLM 决策路由，后来真接了。但开它要付出额外的 classifier LLM call 成本，所以不是默认开 —— 什么场景值得权衡这次额外调用？

### A

**默认关。开 `app.query-router.enabled=true` 之前先算账**：classifier 多 1 次 LLM call（500-1500ms + token 成本），换来跳过 RAG 链路（embedding + vector search + 拼检索结果到 prompt + 主模型生成更长 prompt）。

#### 开启 ROI 矩阵

| 场景 | classifier 成本 | RAG 节省 | 净收益 |
| --- | --- | --- | --- |
| 本地 Ollama embedding + 主模型也 Ollama | 1 次本地 LLM call | nomic embed ~50ms + in-memory search <10ms | **亏**（classifier 比 RAG 还贵） |
| 云 embedding（OpenAI / vLLM）+ 主对话用小模型 | 1 次小模型 call | 1 次 embedding API call + 主 prompt 短一截 | **接近持平** |
| 云 embedding + 主对话用大模型 + 大量非 RAG 流量 | 1 次小模型 call（少量 token） | embedding 钱 + 主大模型 prompt 大幅压缩 | **赚** |
| 流量混合不均（80% 都是闲聊，20% 才要 RAG） | 全量加 classify | 80% 流量跳掉 RAG | **赚** |

#### 实测耗时（DeepSeek 跑 3 类 query 各 1 次）

| query 类型 | classifyMs | answerMs | 备注 |
| --- | --- | --- | --- |
| TOOL（现在几点） | 1126 | 1826 | answer 含 tool round-trip |
| RAG（按文档作答） | 729 | 1212 | answer 含 embedding 检索 |
| CHAT（解释概念） | 918 | 1194 | 跳过 RAG |

**总耗时差不多**（~1900-2950ms）—— 在本配置下 classifier 没省到时间。**这正常**，因为：

- DeepSeek API 响应快（700-1200ms/次），classifier 一次几乎等于一次主对话
- 本机 Ollama embedding 也快，RAG 没多大开销

#### 真要省钱时怎么做

- **降级 classifier**：classifier 不必跟主对话同模型 / 同 provider。生产里专门起个 `Qwen2.5-3B-Instruct` / `Llama-3.2-3B-Instruct` 跑 classifier，~200ms 一次，token 量也小
- **缓存 classification**：同一 chatId 短时间内连续问相同主题，路由结果应该一致 → 在 controller 加 5min LRU
- **裁掉 CHAT 路径**：如果业务里几乎所有 query 都该走 RAG，直接关 query-router；如果几乎所有都是 CHAT，直接关 RAG 入库

#### 设计上的几个决策

- **TOOL 和 CHAT 共享 `BareAssistant`**：它跳过 RAG 但保留 tools，所以 TOOL case 走它没问题。区分两档主要是给运维看 metrics 用，不影响路由代码
- **Classifier 用独立 ChatModel + temperature=0**：跟 Judge 同思路，同一 query 多次分类应该给同一答案，否则 routing 会随机分流到不同后端，eval 没法稳定比对
- **`@ConditionalOnProperty` 默认关**：整套 Bean 不构造，关掉时 `/chat/auto` 返回 503 错误，不影响其他 endpoint
- **同一 chatMemoryProvider**：Assistant 和 BareAssistant 共享 chatId 历史，同一会话在两个变种间切换不丢上下文

参考代码：`ai/routing/QueryClassifier.java`、`ai/routing/BareAssistant.java`、`ai/routing/QueryRouterService.java`、`config/QueryRoutingConfig.java`。

---

## Q1. 动态路由是交给 LLM 执行的吗？

> 问于 2026-05-26

### 背景

项目里有很多 "选择" 看上去像决策：用哪个 chat provider、用哪个工具、RAG 召回哪条、multi-agent 拆几个任务等。容易让人以为这些都是 LLM 在自主决定。

### A

**绝大部分路由是代码/配置决定的，LLM 只在 3 个具体地方"决策"** —— 项目刻意把"模型智能"和"基础设施编排"分开。

#### 完整路由地图

| 路由决策 | 谁决策 | 在哪 |
| --- | --- | --- |
| HTTP path → Controller | **Spring MVC** | `ChatController` / `EvalController` 等 |
| Chat provider（ollama/openai/...） | **配置**（启动时定） | `app.llm.provider` → `LlmConfig.switch` |
| Embedding provider | **配置**（启动时定） | `app.embedding.provider` → `EmbeddingModelConfig.switch` |
| 向量库（pgvector/milvus/...） | **配置** | `app.rag.store` → `EmbeddingStoreConfig.@ConditionalOnProperty` |
| Memory store / window mode | **配置** | `app.memory.store` / `window-mode` → `ChatMemoryConfig` |
| 是否调 `@Tool` / 调哪个 | 🤖 **LLM** | tool calling 协议：模型看 `@Tool` 描述自己决定 |
| RAG 多路检索（vector + keyword）| **代码** | `DefaultQueryRouter`，固定 fan-out 两路 |
| RAG re-rank | 🤖 **LLM**（或云 reranker API） | `OllamaLlmScoringModel` / `JinaScoringModel` |
| RAG 类别 filter | **代码**（请求参数） | `?category=xxx` → ThreadLocal → `dynamicFilter` |
| Multi-agent 拆任务 | 🤖 **LLM** | `Planner.@AiService`（结构化输出 `Plan`） |
| Multi-agent 子任务分发 | **代码**（fan-out） | `MultiAgentService` → `CompletableFuture.supplyAsync(executor)` |
| Multi-agent 合成 | 🤖 **LLM** | `Synthesizer.@AiService` |
| Reflexion 是否再迭代 | **代码** + LLM 评分 | `ReflexiveService` `while (agg < threshold)`，agg 来自 `Critic` LLM 评分 |
| Guardrail 是否触发 reprompt | **代码**（regex） | `PiiGuardrail` 正则匹配 PII |
| Eval case → endpoint type | **代码**（switch on `c.type()`）| `EvaluationRunner.invokeByType()` |
| 并发线程分发 | **Java executor** | `multiAgentExecutor` / `evalExecutor` |
| K8s readiness routing | **K8s + Spring Actuator** | `/actuator/health/readiness` |
| **Provider fallback**（A 挂切 B） | ❌ 没做 | 路由层重构，挂在"未做完的"清单 |

#### LLM 决策的 3 个位置（详细）

##### 1. Tool calling（每条 chat 都可能）

- 输入：user message + 所有 `@Tool` 描述
- LLM 决定：要不要调工具 / 调哪一个 / 参数是什么
- 这是项目里 LLM 最频繁的"决策"位
- 代码层完全被动 —— LangChain4j 框架接收 LLM 的 function-call 响应，反射调对应 `@Component` 方法，把结果塞回去再让 LLM 继续

##### 2. Multi-agent Planner（`/chat/multi-agent` 每次）

- 输入：原问题 + Planner system prompt（含 3 例 few-shot + 反例）
- LLM 决定：拆几个子任务 / 每个子任务描述
- 决定后是**死板的 fan-out** —— `MultiAgentService` 不二次决策，所有 sub-task 平等丢线程池
- 没有 DAG 依赖，没有动态加任务

##### 3. RAG re-ranker（当 `app.rag.rerank.enabled=true`）

- 输入：原 query + 一组候选 chunk
- LLM 决定：每个 chunk 跟 query 的相关性打 0-1 分
- 项目里有两种 `ScoringModel` 实现：`OllamaLlmScoringModel`（本地 LLM 当 reranker）和 `JinaScoringModel`（云 API，技术上不是 LLM 是专用 reranker 模型，接口相同）
- 拿到分数后 `ReRankingContentAggregator` 按分排序截 top-k —— 这步又是代码

#### 设计取舍

为什么大部分路由**不**交给 LLM：

- **可预测性**：provider / store / memory mode 这类一旦决定全局生效，没人想要 LLM 半夜决定"我今天想用 PGVector"
- **成本**：每个路由决策都让 LLM 投票 = 每次请求多 N 个 LLM call，token 烧不起
- **可调试**：代码路由可以打日志、能复现；LLM 决策有不确定性，难重现
- **延迟**：tool calling 已经多一轮 round trip，多 agent 决策套娃就秒级响应没了

LLM 做决策的 3 个位置都有**共同特点**：

- 决策本身需要"理解语义"（哪个工具适合这问题 / 怎么拆 / chunk 跟 query 多相关）—— 这是 LLM 强项，代码做不了
- 决策频率低或可控（一次 chat 0-1 个工具决策、一次 multi-agent 1 个拆解决策）
- 决策结果有结构化输出兜底（tool schema / `Plan` record / `0-1` 浮点数），不会失控

#### 未来可能加的 LLM 决策路由

- **DAG planner**：让 Planner 输出带依赖的任务图，工作流按拓扑序执行（现在是无依赖 fan-out）
- **Provider fallback router**：通常用代码（规则：主 provider 错误率 >5% / 1min → 切备）—— LLM 决策反而过度
- **Query routing in RAG**：根据 query 类型决定走 RAG / 走 tool / 走纯 chat。这个适合 LLM 做一次轻量分类（`@AiService classify(query): RouteKind`），结构化输出 enum 即可

参考代码：`config/LlmConfig.java`、`config/EmbeddingModelConfig.java`、`ai/multiagent/MultiAgentService.java`、`ai/reflexion/ReflexiveService.java`、`rag/scoring/OllamaLlmScoringModel.java`。

---

<!-- 后续问题在此之上插入，保持时间倒序 -->
