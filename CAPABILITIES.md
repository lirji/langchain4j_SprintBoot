# 项目能力清单

> LangChain4j + Spring Boot + 多 LLM provider 的脚手架/参考实现。
> 详细的设计取舍、prompt 演化与 ops 实践另见 `CLAUDE.md` / `PROMPT_JOURNEY.md` / `docs/`。

---

## 1. 工程基线

- Java 21、Spring Boot 3.3.5、Maven（含 `./mvnw` wrapper，无需本机装 Maven）
- LangChain4j 1.13.1（BOM 统一管理，部分子模块 pin 到 `1.13.1-beta23` / `1.13.1`）
- 61 个 Java 源文件，`./mvnw compile` 通过
- HTTP client 显式锁定为 JDK 实现（`LangChain4jApplication.main()` 里设系统属性，避免与 Spring RestClient SPI 冲突）

---

## 2. LLM 接入（多 provider，热切换）

`app.llm.provider`：`ollama | openai | anthropic | gemini | deepseek | vllm`

| provider | 用途 | API key 环境变量 | 默认模型 |
| --- | --- | --- | --- |
| `ollama`（默认） | 本地零成本 | — | `llama3.1` |
| `openai` | 云 GPT-4o 系列 | `OPENAI_API_KEY` | `gpt-4o-mini` |
| `anthropic` | Claude Haiku/Sonnet/Opus 4 系列 | `ANTHROPIC_API_KEY` | `claude-haiku-4-5` |
| `gemini` | Google AI Studio | `GOOGLE_AI_GEMINI_API_KEY` | `gemini-2.0-flash` |
| `deepseek` | OpenAI 兼容协议 | `DEEPSEEK_API_KEY` | `deepseek-chat`（可换 R1） |
| `vllm` | **生产推荐**（PagedAttention） | `VLLM_API_KEY`（默认 `EMPTY`） | `VLLM_MODEL`（必填） |

**关键设计**
- 所有 ChatModel / StreamingChatModel 在 `LlmConfig.java` 统一构建，**不走各家 spring starter**（避免自动装配冲突，也避免 Spring Boot 3.3.5 与 starter 内部依赖不匹配的 `NoClassDefFoundError`）。
- `ChatModelListener`（logging + metrics）通过构造器注入 `List<ChatModelListener>` 灌到每个 chat builder，所以 metrics 实际有打点。
- 每个 provider 独立配 `max-retries`（针对 429 / 5xx / 超时自动退避）。
- 已支持流式：`OllamaStreamingChatModel` / `OpenAiStreamingChatModel`（OpenAI 兼容协议含 DeepSeek/vLLM）/ `AnthropicStreamingChatModel` / `GoogleAiGeminiStreamingChatModel`。

---

## 3. Embedding 接入（独立 provider switch）

`app.embedding.provider`：`ollama | openai-compat`，**和 chat provider 完全解耦**。

| provider | 用途 | 默认模型 | 维度 |
| --- | --- | --- | --- |
| `ollama`（默认） | 本地 | `nomic-embed-text` | 768 |
| `openai-compat` | vLLM / TEI / 云 OpenAI | `BAAI/bge-m3` | 1024 |

**约束**：换 embedding = 换向量维度 = 必须 drop 重建持久化向量库；InMemory 无所谓。

---

## 4. AI Service 形态（共 7 套，按职责拆分）

| AiService | 行为 | 带记忆 | 带 RAG | 带 Tool | 入口 |
| --- | --- | --- | --- | --- | --- |
| `Assistant` (`@AiService`) | 主对话 | ✅ | ✅ | ✅ | `/chat`, `/chat/stream`, `/chat/category` |
| `BareAssistant` | 不走 RAG 的轻量主对话（router 备选） | ✅ | ❌ | ✅ | 由 `/chat/auto` 路由触发 |
| `Extractor` | 一次性结构化抽取 → `Ticket` POJO | ❌ | ❌ | ❌ | `/extract/ticket` |
| `Answerer` + `Critic` | Reflexion 循环（生成 → 评分 → 改进） | ❌ | ❌ | ❌ | `/chat/reflexive`, `/chat/reflexive/stream` |
| `Planner` + `Worker` + `Synthesizer` | Multi-Agent DAG | ❌ | ❌ | ❌ | `/chat/multi-agent`, `/chat/multi-agent/stream` |
| `McpAssistant` | 工具来自外部 MCP server | ❌ | ❌ | MCP | `/chat/mcp` |
| `QueryClassifier` + `Judge` | LLM-as-router / LLM-as-judge | ❌ | ❌ | ❌ | `/chat/auto`, `/eval/run` 内部 |

---

## 5. Prompt 工程（外置化 + 灰度）

`AssistantProperties`（`app.assistant.*`）+ `ResolvedAssistantStyle` Bean：

| key | 默认 | 作用 |
| --- | --- | --- |
| `language` | `中文` | 回答语言 |
| `tone` | `简洁，1–2 句话答完，必要时再展开` | 语气 |
| `citation-policy` | 3 种互斥情况分别处理（详见 yml） | RAG 引用规范 |
| `extra` | `""` | 灰度 / A-B 指令位 |
| `overrides.<provider>.{...}` | `{}` | 按 provider 部分覆盖 |

**改 prompt 不动 Java**：4 个 `{{var}}` 占位符在 `Assistant.SYSTEM_PROMPT` 里，启动时按当前 `app.llm.provider` 解析 → 注入。

---

## 6. Chat Memory

**存储后端** `app.memory.store`:
- `in-memory`（默认）— 重启丢
- `redis` — `RedisChatMemoryStore`，按 `chat:mem:<chatId>` 存 JSON + TTL

**滑窗策略** `app.memory.window-mode`:
- `messages`（默认）— `MessageWindowChatMemory`
- `tokens` — `TokenWindowChatMemory` + `OpenAiTokenCountEstimator` 近似计数
- `summary` — 自实现 `SummarizingChatMemory`：超阈值后 LLM 压缩旧消息为 `SystemMessage`

**按会话隔离**：`@MemoryId String chatId` 参数。

---

## 7. RAG

### 7.1 Embedding Store（6 种后端）`app.rag.store`

| 选项 | 实现 | 备注 |
| --- | --- | --- |
| `in-memory`（默认） | `InMemoryEmbeddingStore` | 重启丢 |
| `pgvector` | `PgVectorEmbeddingStore` | 原生 Hybrid（`search-mode=HYBRID` + `tsvector` + RRF） |
| `milvus` | `MilvusEmbeddingStore` | FLAT/IVF_FLAT/HNSW + 多 metric |
| `chroma` | `ChromaEmbeddingStore` | v1/v2 API |
| `qdrant` | `QdrantEmbeddingStore` | gRPC 6334 |
| `doris` | **自实现** `DorisEmbeddingStore` | JDBC + Doris ANN HNSW + filter SQL 翻译 |

### 7.2 Chunking 策略 `app.rag.chunking.strategy`

| 策略 | 实现 | 适用 |
| --- | --- | --- |
| `recursive`（默认） | `DocumentSplitters.recursive(max-chars, overlap)` | 通用，按字符切 |
| `markdown-header` | 自实现 `MarkdownHeaderSplitter` | `## section` 切，每 chunk 是完整主题，给 segment 加 `section` + `index` metadata |

### 7.3 检索增强

| 能力 | 配置 | 实现 |
| --- | --- | --- |
| 召回数 | `app.rag.top-k` (默认 5) | `EmbeddingStoreContentRetriever.maxResults` |
| 相似度阈值 | `app.rag.min-score` (默认 0.3) | 同上 minScore |
| **动态 metadata filter** | `CategoryContext` ThreadLocal + `dynamicFilter` | `/chat/category?category=xxx` |
| **Query Expansion** | `app.rag.query-expansion.enabled` + `n` | `ExpandingQueryTransformer`（1 query → N 变体，多路召回 + RRF） |
| **History-aware retrieval** | `app.rag.history-aware.enabled` | `CompressingQueryTransformer`（history → self-contained query） |
| **Transformer Chain** | 自动 | `ChainedQueryTransformer`（compress → expand 串行；LC4j 1.13 的 Augmentor 只接单个 transformer） |
| **Reranking 开关** | `app.rag.rerank.enabled` | `ReRankingContentAggregator` |
| Reranker：LLM-as-judge | `type=llm` | 自写 `OllamaLlmScoringModel`，零依赖但 N 次 LLM call |
| Reranker：Jina | `type=jina` + `JINA_API_KEY` | `JinaScoringModel`，云 API 多语言 |
| **通用 Hybrid** | `app.rag.hybrid.enabled` | `DefaultQueryRouter`(vector + keyword) + RRF |
| Hybrid 分词：simple | `tokenizer=simple` | 字符 + 标点切，零依赖 |
| Hybrid 分词：HanLP | `tokenizer=hanlp` | HanLP portable + 停用词，中文召回好 |
| PGVector 原生 Hybrid | `app.rag.pgvector.search-mode=HYBRID` | 向量 + `tsvector` 原生 RRF |
| **Citation 闭环** | 总是开 | `TaggedSourceContentInjector` 把片段包成 `<source id="文件名#片段号">`，配 `citation-policy` 让模型按 `[doc=ID]` 引用 |

### 7.4 文档加载

- `RagIngestionService` — `FileSystemDocumentLoader` + 切片 + 入库 + 同步 `DocumentMirror`（给 Hybrid 用）
- `POST /rag/ingest?category=xxx` — 给所有 segment 打 `metadata.category` 标签

### 7.5 Doris EmbeddingStore（自实现额外能力）

- 自动建表（`USING ANN` HNSW 索引）
- `add` / `addAll` / `search` / `remove`
- **Metadata filter 翻译**：`DorisFilterTranslator` 把 LC4j `Filter` 树翻成 `get_json_string(metadata,'$.k') = ?` SQL，JSON key 白名单防注入

---

## 8. 工具调用

| 来源 | 实现 | 接入方式 |
| --- | --- | --- |
| 内置 Java `@Tool` | `DateTimeTool`（当前时间、距某日天数）—— 长描述 + `@P` 参数注释，给小模型决策依据 | Spring `@Component` 自动发现 |
| 外部 MCP server | `McpClient`（stdio / streamable http） | `McpToolProvider` → `McpAssistant` |

---

## 9. 流式输出（SSE）

| 端点 | 流的事件 |
| --- | --- |
| `POST /chat/stream` | 逐 token `data:`，结束 `event: done` |
| `POST /chat/reflexive/stream` | 按阶段：`attempt-start` / `answer-token` / `critique` / `done` |
| `POST /chat/multi-agent/stream` | 按阶段：`plan` / `worker-result` / `synthesis-token` / `done`（Synthesizer 那 10-20s 一次性等变成 token-by-token） |

---

## 10. LLM-as-Router（智能路由）

`app.query-router.enabled=true` 启用 `/chat/auto`：

```
classify → RAG (Assistant) | TOOL (Assistant) | CHAT (BareAssistant, 跳过 RAG)
```

- `QueryClassifier` 用 LLM 给 `(query) → RouteKind`，多 1 次 LLM call
- `QueryRouterService` 按 `RouteDecision` 派发到 `Assistant` 或 `BareAssistant`
- 返回 `{decision, reply, classifyMs, answerMs}` 给观测
- 适用：embedding 走云 API（按 token 计费）+ 大量非 RAG 流量场景

---

## 11. Multi-Agent（DAG）

- `Planner` 拆 1–6 子任务，输出含 `dependsOn` 字段（内置 3 例 few-shot + 2 反例 + 1 DAG 例）
- **DAG 执行**：`MultiAgentService` 用 Kahn 拓扑排序分层，同层并行（`multiAgentExecutor` 4–8 线程），跨层等待；环 → 降级 flat 全并行 + log 警告
- `Worker.execute(task, upstream)` 接受上游输出（无依赖时传空串）
- `Synthesizer` **编织**最终答案：5 条 synthesis rules + 4 条 forbidden anti-patterns（禁止暴露 plan 结构）+ 1 个完整对比例
- 子线程通过 `MdcCopyingTaskDecorator` 继承 `traceId`，日志可串

---

## 12. Reflexion（自反思）

- `Critic` 输出 **3 维评分**：`correctness` / `completeness` / `clarity`，每维 0.0–1.0 + 一句 `mainIssue`
- **加权聚合分** `Σ(weight_i × score_i) / Σ(weight_i)` 低于 `app.reflexion.threshold` 触发改进
- `weights.{correctness,completeness,clarity}` 默认 0.4/0.4/0.2，按场景调（压幻觉调高 correctness，C 端对话调高 clarity）
- `app.reflexion.max-attempts` 默认 2（首次生成不计）
- 改进环节把 3 维分 + `mainIssue` 一起喂给 `Answerer.improve`
- `Attempt` 记录每轮 4 个字段，调试可见

---

## 13. 安全 / Guardrails

- `PiiGuardrail` (`OutputGuardrail`)：检测邮箱、中国手机号、18 位身份证号；命中即 `reprompt` 让模型重写为 `[REDACTED]`，`maxRetries=2`
- 挂在 `Assistant.chat()`；流式 `chatStream` 暂未挂（流式 guardrail 需缓冲整段，按需再加）
- 扩展位：`@InputGuardrails(...)` 同理可加输入侧

---

## 14. 可观测性

| 能力 | 实现 | 暴露 |
| --- | --- | --- |
| LLM 调用日志 | `LoggingChatModelListener` | 每次一行 `model / duration_ms / tokens_in/out/total` |
| Micrometer 指标 | 自实现 `MetricsChatModelListener`（`langchain4j-micrometer` 还没发到 central） | `gen_ai.client.{requests,operation.duration,token.usage,errors}` |
| Prometheus 抓取 | `micrometer-registry-prometheus` | `GET /actuator/prometheus` |
| Grafana dashboard | `docs/grafana-dashboard.json` | 7 panel（req rate / latency p50p95p99 / token spend / error rate by type / etc），导入即用 |
| 请求 TraceID | `TraceIdFilter` | MDC + `X-Trace-Id` 响应头，日志 pattern `[%X{traceId:-}]` |
| 多 Agent 子线程继承 traceId | `MdcCopyingTaskDecorator` | eval 子线程同享 |
| Actuator 端点 | `health / info / metrics / prometheus` | `GET /actuator/*` |

### Health Checks

- `LlmHealthIndicator` + `EmbeddingHealthIndicator` 自定义 Actuator indicator，对当前 provider 的 base-url 做 1s TCP 探测（不烧 token、不需 api-key 有效）
- 暴露在 `/actuator/health/llm`、`/actuator/health/embedding` 单独可查
- `management.endpoint.health.group.readiness.include=readinessState,llm,embedding` 挂进 K8s readinessProbe
- 需 `management.health.probes.enabled=true`（已默配）

---

## 15. 评测 Harness（生产级）

### 黄金集
`src/main/resources/eval/eval-cases.json`，每条：`{id, question, type?, mustInclude[], mustNotInclude[], judgeHint?}`

当前 26 条：
- 20 条 `chat`：8 happy / 7 adversarial / 3 工具 / 2 格式+语言
- 3 条 `extract`：CRITICAL / HIGH / LOW 优先级抽取
- 2 条 `multi-agent`：多维比较 (tasks=3) / trivial 不过拆 (tasks=1)
- 1 条 `reflexive`：清晰技术定义题，应一次过

### 端点
| 端点 | 说明 |
| --- | --- |
| `POST /eval/run?runs=N` | 跑黄金集，每 case 跑 N 次（默认 1） |
| `POST /eval/run-cases?runs=N` | body 传临时 `EvalCase[]` |

返回 `Summary{totalCases, runsPerCase, totalRuns, passedRuns, overallPassRate, averageScore, totalDurationMs, cases:[{caseId, runs, passedCount, passRate, avgScore, scoreStdev, attempts[]}]}`

### Type Dispatch（跨 4 个 endpoint 统一评测）

| `type` | 内部调用 | 喂给 Judge 的 answer 形式 |
| --- | --- | --- |
| `chat`（默认） | `Assistant.chat(...)` | 模型回复原文 |
| `extract` | `Extractor.extractTicket(question)` | Ticket POJO 序列化的 JSON |
| `multi-agent` | `MultiAgentService.run(question)` | `tasks: N\n<子任务>\n---\n<finalAnswer>` |
| `reflexive` | `ReflexiveService.chatReflexive(question)` | `attempts: N, accepted: true\n---\n<finalAnswer>` |

### Judge 噪声控制
1. **客观字段走规则匹配**：`coversAllRequiredFacts` / `violatesForbidden` 在 `EvaluationRunner` 里用 `answer.contains(...)` 算，**不让 Judge LLM 判**。Judge 只负责 `score` + `reasoning`
2. **Judge 用独立 temp=0 ChatModel**：`LlmConfig.buildJudgeChatModel(props)`；由 `EvalConfig` 直接构造，**未注册成 Spring Bean**（避免与主 ChatModel 冲突）
3. **注入 `today`**：`LocalDate.now()` 传 `@V("today")`，Judge 知道当前日期
4. **Judge 不重复审 MUST_***：系统提示明令禁止；`Default to 1.0 ... do not be stingy` 避免主观通胀扣分

### Multi-run（A/B 必备）
- `runs=3~5`：3 次能区分"真改进" vs "运气好"；看 `scoreStdev` 而不是单次分数
- `runs=1`：smoke test，不可靠
- case 内部 N 个 run 顺序（chatId 隔离），case 之间并行

### 并行执行
- 独立 `evalExecutor` 线程池（**不复用 multiAgentExecutor**，否则 multi-agent case 会死锁）
- `app.eval.concurrency`（默 4）控制池大小
- MDC `MdcCopyingTaskDecorator` 透传 traceId
- 实测 26 cases × 2 runs：concurrency=4 wall-clock **75s vs 顺序 188s（2.5×）**

### Auto-Ingest
- `app.eval.auto-ingest=true` 时 `/eval/run` 首次会自动 `/rag/ingest`，避免 RAG case 召回空假 fail
- 默认 `false`（不想被 eval 误改向量库）

### judgeHint 字段
仅用于 Judge 看 `(question, answer)` 无法自己推断"正确行为是什么"的 case（例：PII redaction 是合规非偷懒、cite-no-context 是设计意图非拒答）。**禁止用 judgeHint 直接喂答案**。

---

## 16. REST API 总览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/chat` | 主对话（RAG + 记忆 + 工具 + Guardrail） |
| POST | `/chat/stream` | SSE 流式输出 |
| POST | `/chat/category` | 按 category 过滤 RAG 检索 |
| POST | `/chat/auto` | LLM-as-router 智能路由 |
| POST | `/chat/reflexive` | Reflexion 自反思循环 |
| POST | `/chat/reflexive/stream` | 流式反思（按阶段 emit） |
| POST | `/chat/multi-agent` | Multi-Agent DAG |
| POST | `/chat/multi-agent/stream` | 流式 Multi-Agent |
| POST | `/chat/mcp` | MCP 工具驱动对话 |
| POST | `/extract/ticket` | 结构化输出（Ticket POJO） |
| POST | `/rag/ingest?category=` | 文档入库（可选分类标签） |
| POST | `/eval/run?runs=N` | 跑黄金集 |
| POST | `/eval/run-cases?runs=N` | 跑临时集 |
| GET | `/health` | 简易健康 |
| GET | `/actuator/health` | Actuator（含 llm / embedding sub-indicator） |
| GET | `/actuator/health/readiness` | K8s readinessProbe 用 |
| GET | `/actuator/metrics/*` | Micrometer 指标 |
| GET | `/actuator/prometheus` | Prometheus scrape |

---

## 17. 关键配置开关

```yaml
# LLM
app.llm.provider:               ollama | openai | anthropic | gemini | deepseek | vllm
app.llm.<provider>.{base-url,api-key,model-name,temperature,timeout,max-retries,log-*}

# Embedding（独立 switch）
app.embedding.provider:         ollama | openai-compat
app.embedding.<provider>.{...}

# Assistant prompt（外置 + 灰度）
app.assistant.{language, tone, citation-policy, extra}
app.assistant.overrides.<provider>.{...}    # 按 provider 部分覆盖

# 记忆
app.memory.store:               in-memory | redis
app.memory.window-mode:         messages | tokens | summary

# RAG 后端
app.rag.store:                  in-memory | pgvector | milvus | chroma | qdrant | doris
app.rag.top-k:                  5
app.rag.min-score:              0.3
app.rag.chunking.strategy:      recursive | markdown-header

# RAG 检索增强
app.rag.history-aware.enabled:  false
app.rag.query-expansion.enabled: false
app.rag.query-expansion.n:      3
app.rag.rerank.enabled:         false
app.rag.rerank.type:            llm | jina
app.rag.hybrid.enabled:         false
app.rag.hybrid.tokenizer:       simple | hanlp
app.rag.pgvector.search-mode:   VECTOR | HYBRID

# Reflexion
app.reflexion.threshold:        0.75
app.reflexion.max-attempts:     2
app.reflexion.weights.{correctness,completeness,clarity}: 0.4/0.4/0.2

# 路由
app.query-router.enabled:       false

# MCP
app.mcp.enabled:                false
app.mcp.transport:              stdio | http

# 评测
app.eval.auto-ingest:           false
app.eval.concurrency:           4
```

---

## 18. 配套文档

| 文档 | 内容 |
| --- | --- |
| `CLAUDE.md` | 本仓库给 AI 协作者用的总览（含技术栈/扩展点/注意事项） |
| `PROMPT_JOURNEY.md` | Prompt 工程 + eval harness + 生产化的完整演化日志 |
| `docs/roadmap.md` | 待完善项 / ROI 分档 / 决策表 |
| `docs/observability.md` | Prometheus / Grafana / Health Check 接入 |
| `docs/qa.md` | 概念性问答记录（路由 / 决策权 / 设计取舍） |
| `docs/grafana-dashboard.json` | 现成 7-panel dashboard |
| `CAPABILITIES.md` | 本文档：能力清单（参考/checklist） |

---

## 19. 项目结构

```text
src/main/java/com/lrj/langchain4j/
├── LangChain4jApplication.java
├── ai/
│   ├── Assistant.java                          @AiService 主对话（含 @OutputGuardrails）
│   ├── CategoryChatService.java                动态 filter 包装
│   ├── extract/{Extractor,Ticket}.java         结构化抽取
│   ├── guardrail/PiiGuardrail.java             PII 输出守卫
│   ├── mcp/McpAssistant.java                   MCP 工具桥接
│   ├── multiagent/{Plan,SubTask,Planner,Worker,Synthesizer,MultiAgentService}.java
│   ├── reflexion/{Answerer,Critic,Critique,ReflexiveService}.java
│   ├── routing/{BareAssistant,QueryClassifier,QueryRouterService,RouteDecision,RouteKind}.java
│   └── tools/DateTimeTool.java
├── config/
│   ├── LlmConfig.java                          6-provider chat/streaming 统一装配
│   ├── EmbeddingModelConfig.java               2-provider embedding 装配
│   ├── AssistantProperties / AssistantStyleConfig / ResolvedAssistantStyle.java
│   ├── LangChain4jConfig.java                  Retriever + Reranker + QueryTransformer + Augmentor
│   ├── ChatMemoryConfig.java
│   ├── EmbeddingStoreConfig.java               6 种 store 切换
│   ├── ExtractorConfig / ReflexionConfig / MultiAgentConfig / QueryRoutingConfig.java
│   ├── EvalConfig.java                         Judge ChatModel + evalExecutor
│   ├── McpConfig.java
│   └── ObservabilityConfig.java
├── controller/{ChatController,EvalController}.java
├── eval/{EvalCase,EvalResult,EvaluationRunner,Judge,Judgment}.java
├── memory/SummarizingChatMemory.java
├── observability/
│   ├── LoggingChatModelListener / MetricsChatModelListener.java
│   ├── LlmHealthIndicator / EmbeddingHealthIndicator.java
│   └── TraceIdFilter.java
├── rag/
│   ├── RagIngestionService.java
│   ├── CategoryContext.java
│   ├── MarkdownHeaderSplitter.java
│   ├── ChainedQueryTransformer.java
│   ├── TaggedSourceContentInjector.java
│   ├── hybrid/{DocumentMirror,KeywordContentRetriever,KeywordTokenizer,SimpleKeywordTokenizer,HanLpKeywordTokenizer}.java
│   └── scoring/OllamaLlmScoringModel.java
└── store/
    ├── doris/{DorisEmbeddingStore,DorisFilterTranslator}.java
    └── redis/RedisChatMemoryStore.java
```

---

## 20. 一句话定位

**一个从 demo 到生产可用的 LangChain4j 参考实现**：6 个 LLM provider 热切换、6 种向量库、3 种滑窗记忆、Hybrid + Rerank + Query Expansion + History-aware 检索全套、Reflexion + Multi-Agent DAG + LLM-as-router 三种 agent 形态、PII Guardrail、Prometheus + Grafana + Health Check、外置 prompt + 多 provider override、生产级评测 harness（type dispatch + multi-run + parallel + temp=0 Judge）。**零配置就是 Ollama + InMemory 的最小可跑形态**，要哪些上哪些。
