# 项目能力清单

> LangChain4j + Spring Boot + 多 LLM provider 的脚手架/参考实现。
> 详细的设计取舍、prompt 演化与 ops 实践另见 `CLAUDE.md` / `PROMPT_JOURNEY.md` / `docs/`。

---

## 1. 工程基线

- Java 21、Spring Boot 3.3.5、Maven（含 `./mvnw` wrapper，无需本机装 Maven）
- LangChain4j 1.13.1（BOM 统一管理，部分子模块 pin 到 `1.13.1-beta23` / `1.13.1`）
- 243 个 Java 源文件，`./mvnw compile` 通过；约 43 个确定性单测类（纯逻辑、不连模型）
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

## 4. AI Service 形态（共 12 套，按职责拆分）

| AiService | 行为 | 带记忆 | 带 RAG | 带 Tool | 入口 |
| --- | --- | --- | --- | --- | --- |
| `Assistant` (`@AiService`) | 主对话 | ✅ | ✅ | ✅ | `/chat`, `/chat/stream`, `/chat/category` |
| `BareAssistant` | 不走 RAG 的轻量主对话（router 备选） | ✅ | ❌ | ✅ | 由 `/chat/auto` 路由触发 |
| `Extractor` | 一次性结构化抽取 → `Ticket` POJO | ❌ | ❌ | ❌ | `/extract/ticket` |
| `Answerer` + `Critic` | Reflexion 循环（生成 → 评分 → 改进） | ❌ | ❌ | ❌ | `/chat/reflexive`, `/chat/reflexive/stream` |
| `Planner` + `Worker` + `Synthesizer` (+ `Replanner`) | Multi-Agent DAG（可选 replan） | ❌ | ❌ | ❌ | `/chat/multi-agent`, `/chat/multi-agent/stream` |
| `AgentBrain` | 深度 Agent 单步 ReAct 决策（动作经 `AgentAction` 走循环，非原生 tool） | ❌ | 经 `rag_search` 动作 | 经 `AgentAction` | `/agent/run`, `/agent/run/async` |
| `SqlAssistant` | NL2SQL：调 `run_sql` 工具查只读库再解读（6 层护栏在 `NlToSqlService`） | ❌ | ❌ | `run_sql` | `/chat/sql` |
| `McpAssistant` | 工具来自外部 MCP server | ❌ | ❌ | MCP | `/chat/mcp` |
| `QueryClassifier` + `Judge` | LLM-as-router / LLM-as-judge | ❌ | ❌ | ❌ | `/chat/auto`, `/eval/run` 内部 |
| `GroundednessChecker` | RAG faithfulness 校验（temp=0，事后判答案是否被 source 支撑） | ❌ | ❌ | ❌ | `/chat` + `/chat/category` 内部（`app.rag.grounding.enabled=true`） |
| `ProfileExtractor` | 长期记忆：temp=0 从对话抽 durable 用户事实（偏好/属性/诉求） | ❌ | ❌ | ❌ | `/chat/memory` 后异步 observe |
| `GraphExtractor` + `LlmEntityLinker` | GraphRAG：temp=0 抽实体-关系三元组 / query 实体锚定 | ❌ | ❌ | ❌ | 入库建图 + `graph` 路检索内部 |

> 视觉理解走 `VisionModel`（`ai/vision`，自定义接口包装非 Bean 的 vision `ChatModel`，**不是 `@AiService`**，避开「多 ChatModel Bean」冲突）；意图分类 `FeishuIntent` / 客服大脑 `CustomerServiceBrain` 复用 `Assistant`。详见各模块 doc。

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
| `recursive`（默认） | `DocumentSplitters.recursive(max-size, overlap)` | 通用，按 `unit`(chars/tokens) 硬切 + overlap |
| `markdown-header` | 自实现 `MarkdownHeaderSplitter` | `## section` 切，每 chunk 是完整主题，给 segment 加 `section`/`index`/`breadcrumb` metadata + 极小 section 合并 |
| `parent-child` | 自实现 `ParentChildSplitter` | **small-to-big**：child 用 `max-size`/`overlap` 切小块去 embed（召回精准），parent 用 `app.rag.chunking.parent.*` 切大块；检索命中 child 后 `TaggedSourceContentInjector` 自动换成所属 parent 全文喂模型（上下文完整，多 child 命中同一 parent 去重）。parent 全文随 child 冗余存进 metadata（零新存储/重启安全/6 后端一致，代价 store 膨胀） |
| `semantic` | 自实现 `SemanticChunkingSplitter` | **按主题连续性切**：逐句 embed → 算相邻句 cosine 距离 → 距离超 `breakpoint-percentile` 分位的间隙处下刀（`app.rag.chunking.semantic.*`）。适合无标题结构的长文（纪要/访谈/论文正文），每块是语义自洽单元。代价：入库每句多一次 embed；embedding 故障自动降级 recursive |

> parent-child 关键设计：child 决定**召回精度**、parent 决定**上下文完整度**，把"切小召得准"和"切大上下文够"的两难拆开。`parent_id` 让同一 parent 的多个 child 共享同一 `[doc=file#parentId]` 引用，与 grounding（Layer 0 引用核对）/ citation 闭环对齐。`parent.strategy=markdown-header` 时 section 直接作 parent。确定性单测：`ParentChildSplitterTest`（5）+ `TaggedSourceContentInjectorTest`（3）。
>
> semantic 关键设计：复用已有 `EmbeddingModel`（零新依赖），`buffer-size` 把每句与邻居拼窗口再 embed 平滑噪声、`breakpoint-percentile`（默 95）控切点稀疏度、超 `max-size` 的语义块 fallback recursive、不足 `min-size` 的碎块并块。`RagIngestionService` 顺手改成"切一次→直接 embed/add"（不再走 `EmbeddingStoreIngestor` 的内部二次 split），避免 semantic 双倍 embedding 成本、与单上传路径口径一致。确定性单测：`SemanticChunkingSplitterTest`（7，桩 `EmbeddingModel` 按关键词给固定向量令距离可预测）。

### 7.3 检索增强

| 能力 | 配置 | 实现 |
| --- | --- | --- |
| 召回数 | `app.rag.top-k` (默认 5) | `EmbeddingStoreContentRetriever.maxResults` |
| 相似度阈值 | `app.rag.min-score` (默认 0.3) | 同上 minScore |
| **动态 metadata filter** | `CategoryContext` ThreadLocal + `dynamicFilter` | `/chat/category?category=xxx` |
| **Contextual Retrieval** | `app.rag.contextual.enabled` (默认关) | Anthropic 那套：入库时 `ChunkContextualizer`（temp=0，不注册 Bean）给每个 chunk 生成一句「安放回全文」的上下文前缀（消解代词/缩写、点明位置），`ContextualEnricher` 拼到 chunk 前再 embed → chunk 自洽、召回失败率显著降。与 chunking 策略正交、跟 hybrid(BM25)/rerank 叠加。软依赖接入两条入库链，关闭零回归。代价每 chunk 一次 LLM 调用（失败保留原文不崩） |
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
| **事实幻觉事后校验（grounding）** | `app.rag.grounding.enabled` (默认关) + `threshold` (默认 0.7) | `GroundingService`：Layer 0 引用 id 完整性核对（零 LLM）+ Layer 1 `GroundednessChecker` faithfulness（temp=0，RAGAS 拆断言）；命中追加 `⚠️ 可信度提示`（warn 模式，不改写）。挂在 `/chat` + `/chat/category`，流式不挂 |

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

## 12. 深度 Agent（开放式 plan→act→observe 循环）

- 区别于 Multi-Agent 的**固定 DAG**：模型每步自己决定下一步、用工具、观察、再决策，直到 `finish` 或预算耗尽——填「长程、轨迹由模型自定」的空白
- **显式 ReAct 循环**（结构化 `AgentDecision` 决策，**非**原生 function-calling）：为对每步完全控制 + 跨 provider 确定性可单测
- **循环控制**（核心价值）：硬预算 `max-steps` / 循环检测 `max-repeats`（连续重复同一动作）/ scratchpad 跨步工作记忆（`note` 沉淀 + 截断）/ 深度受限 `delegate` 子 Agent / 逐步 trace
- `stopReason` ∈ `DONE` / `MAX_STEPS` / `LOOP` / `ERROR`（brain 异常不崩 run）/ `CANCELLED`（异步 `Future.cancel(true)` interrupt → 每步开头侦测中断标志提前退出，顶层 finally 清标志防污染线程池）
- `AgentBrain` 程序化 `AiServices.builder` 构建、无 ChatMemory、走主 `ChatModel`——已挂 metrics + per-tenant token 预算 listener，**token 自动纳入配额**；不注册成 Bean（同 `Judge` 套路）
- **加动作 = 实现 `AgentAction` + `@Component`**（自动发现，无需改循环）。已接入真实能力动作：
  - `rag_search`：复用主 RAG 链 `vectorRetriever`（带租户 + category 过滤），返回带 `[doc=ID]` 引用的片段
  - `nl2sql_query`：deep-agent + nl2sql 双开时装配，透传 `NlToSqlService`（6 层 SQL 护栏 + 只读 + 租户谓词）
  - `mcp_call`：deep-agent + mcp 双开时装配，分派 `McpClient` 动态发现的整个工具集（目录进描述）
- **Browser-use**（`app.deep-agent.browser.enabled`，默认关）：Playwright 无头 Chromium 做成 6 个动作插进循环——`browser_open`（执行 JS 后读渲染文本/链接）/ `browser_click`（文本点击）/ `browser_click_xy`（坐标点击）/ `browser_type`（表单输入）/ `browser_screenshot`（整页截图存文件）/ `browser_see`（截图→`ai/vision` 视觉理解，browser + vision 双开时装配）；按线程懒加载、`AgentRunListener.onRunEnd` 关页面、Chromium 仅开启时下载
- `POST /agent/run`（同步）+ `/agent/run/async`（异步，复用 `async` 引擎投后台、轮询/SSE/webhook 取回）；**eval `type:"agent"`** 黄金集校验 stopReason/步数/答案
- 默认关（`app.deep-agent.*`），零新依赖（Playwright 仅 browser 开启时下载二进制）
- 38 个确定性单测（循环 11 + browser 11 + rag/nl2sql/mcp 动作 16），全用桩，不连模型/浏览器/DB/MCP

---

## 13. Reflexion（自反思）

- `Critic` 输出 **3 维评分**：`correctness` / `completeness` / `clarity`，每维 0.0–1.0 + 一句 `mainIssue`
- **加权聚合分** `Σ(weight_i × score_i) / Σ(weight_i)` 低于 `app.reflexion.threshold` 触发改进
- `weights.{correctness,completeness,clarity}` 默认 0.4/0.4/0.2，按场景调（压幻觉调高 correctness，C 端对话调高 clarity）
- `app.reflexion.max-attempts` 默认 2（首次生成不计）
- 改进环节把 3 维分 + `mainIssue` 一起喂给 `Answerer.improve`
- `Attempt` 记录每轮 4 个字段，调试可见

---

## 14. 业务落地与扩展模块（默认关，各有专属 doc）

> 这些是在框架能力之上落地的业务/生产化场景，**全部 `@ConditionalOnProperty` 默认关、零回归**，深度细节见各自 `docs/*.md`。

| 模块 | 一句话能力 | 开关 | 端点 | doc |
| --- | --- | --- | --- | --- |
| **NL2SQL / ChatBI** | 自然语言 → 只读 SELECT → 执行 → 解读；**6 层 SQL 护栏**（只读账号/语句白名单/表白名单/强制 LIMIT/超时/租户谓词）+ schema 注入 + 数字 grounding | `app.nl2sql.enabled` | `/chat/sql` | `nl2sql.md` |
| **企业知识库问答** | Milvus 持久化 + Apache Tika 解析 PDF/Office 上传 + `kb` profile，多租户隔离 + 重启持久化 + 版本覆盖 | `kb` profile | `/rag/documents` | `knowledge-base.md` |
| **工作流编排（Flowable）** | 退款审批 BPMN + 人工审批 + MySQL 持久化；生产硬化 #1–#10（超时驳回/幂等/补偿/历史清理/outbox+DLQ/claim 并发 409/PII purge…） | `app.workflow.enabled` | `/workflow/*` | `workflow-integration.md` |
| **渠道接入（飞书）** | 回调 + AES 解密验签 + 意图路由（退款→工作流/其余→对话）+ 审批卡片回推，5s ack + 异步回推 | `app.channel.feishu.enabled` | `/channel/feishu/event` | `workflow-integration.md` |
| **语音客服 Agent** | 音频 → ASR → 客服大脑（意图路由）→ TTS；JDK HttpClient 调 OpenAI 兼容 ASR/TTS，含 SSE 半流式分句 | `app.voice.enabled` | `/voice/chat`, `/voice/chat/stream`, `/voice/transcribe` | `voice-agent.md` |
| **多模态文档理解** | 图像入库增强 RAG + 视觉对话 + 扫描件 OCR；vision provider 与 chat/embedding 三向解耦，caption LRU 缓存 + 入库安全闸 | `app.vision.enabled` | `/chat/vision`, `/rag/documents`（图片） | `multimodal.md` |
| **GraphRAG（图谱增强）** | 入库抽三元组建图 + 检索 N 跳遍历作**第三路 retriever**（vector/keyword/graph）经 RRF 融合；JDBC 持久化 + LLM 实体链接 | `app.rag.graph.enabled` | 内部第三路召回 | `graphrag.md` |
| **长期记忆 / 用户画像** | 跨会话记住用户 durable 事实，chat 前召回注入、chat 后异步抽取更新；正交于会话内滑窗记忆 | `app.memory.profile.enabled` | `/chat/memory`, `/memory/profile` | `long-term-memory.md` |
| **A2A（Agent2Agent）Server** | JSON-RPC 单端点 + Agent Card 发现 + 三种调用（message/send 同步·message/stream SSE·pushNotificationConfig webhook 异步） | `app.a2a.enabled` | `/a2a`, `/.well-known/agent-card.json` | `a2a.md` |
| **生产化基线 #1–#8** | 多租户隔离 / 限流 / per-tenant token 配额 / 文档生命周期 / prompt injection / 审计日志 / 长任务异步化 / Webhook + SSE 推送 | 多个 flag | `/tasks/*`（异步） | `production-hardening.md` |

---

## 15. 安全 / Guardrails

- `PiiGuardrail` (`OutputGuardrail`)：检测邮箱、中国手机号、18 位身份证号；命中即 `reprompt` 让模型重写为 `[REDACTED]`，`maxRetries=2`
- `PromptInjectionGuardrail` (`InputGuardrail`)：12 条 bilingual 规则 + 可选 LLM 分类器；BLOCK/SANITIZE/AUDIT 三档
- 都挂在 `Assistant.chat()`；流式 `chatStream` 暂未挂（流式 guardrail 需缓冲整段，按需再加）
- **依赖注入靠自定义 SPI**：guardrail 是带参构造的 `@Component`，LC4j 默认反射无参实例化会抛 `NoSuchMethodException`。`config/SpringClassInstanceFactory`（注册 `ClassInstanceFactory` SPI + `SpringContextHolder`）让 LC4j 从 Spring 容器取 bean。**删了这层 guardrail 直接挂掉**，详见 CLAUDE.md 注意事项

---

## 16. 可观测性

| 能力 | 实现 | 暴露 |
| --- | --- | --- |
| LLM 调用日志 | `LoggingChatModelListener` | 每次一行 `model / duration_ms / tokens_in/out/total` |
| Micrometer 指标 | 自实现 `MetricsChatModelListener`（`langchain4j-micrometer` 还没发到 central） | `gen_ai.client.{requests,operation.duration,token.usage,errors}` |
| **切分质量指标** | `ChunkMetrics`（始终在线，每次入库打点，按 `strategy` tag） | `rag.chunk.size`（尺寸分布 `_count/_sum/_max`）/ `rag.chunk.{total,tiny,oversize}`（碎块·超大块比例）/ `rag.ingest.documents`。换策略/调 max-size 后切分质量可观测，阈值 `app.rag.metrics.{tiny,oversize}-chars` |
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

## 17. 评测 Harness（生产级）

### 黄金集
`src/main/resources/eval/eval-cases.json`，每条：`{id, question, type?, mustInclude[], mustNotInclude[], judgeHint?}`

当前 33 条：
- 23 条 `chat`：happy / adversarial / 工具 / 格式+语言 + 3 条依赖 ingest 的 RAG 引用/拒答（`rag-citation-*` / `rag-no-match`）
- 2 条 `grounded`：`grounding-supported-quiet`（充分支撑应静默）/ `grounding-abstain-quiet`（诚实弃答应静默）；需 `app.rag.grounding.enabled=true` + `app.eval.auto-ingest=true`
- 3 条 `extract`：CRITICAL / HIGH / LOW 优先级抽取
- 4 条 `multi-agent`：多维比较 (tasks=3) / trivial 不过拆 (tasks=1) / DAG (deps) / 不泄漏子任务结构
- 1 条 `reflexive`：清晰技术定义题，应一次过

### 端点
| 端点 | 说明 |
| --- | --- |
| `POST /eval/run?runs=N` | 跑黄金集，每 case 跑 N 次（默认 1） |
| `POST /eval/run-cases?runs=N` | body 传临时 `EvalCase[]` |

返回 `Summary{totalCases, runsPerCase, totalRuns, passedRuns, overallPassRate, averageScore, totalDurationMs, cases:[{caseId, runs, passedCount, passRate, avgScore, scoreStdev, attempts[]}]}`

### Type Dispatch（9 种 type 跨多 endpoint 统一评测）

> `set` 选黄金集：`default` / `sql` / `a2a` / `workflow` / `graph` / `agent`（各有独立 `eval-cases-<set>.json`，后五个需先开对应 profile）。

| `type` | 内部调用 | 喂给 Judge 的 answer 形式 |
| --- | --- | --- |
| `chat`（默认） | `Assistant.chat(...)` | 模型回复原文 |
| `grounded` | `GroundingService.applyToFreshAnswer(() -> Assistant.chat(...))` | 模型回复原文（命中闸门时末尾带 `⚠️ 可信度提示`） |
| `extract` | `Extractor.extractTicket(question)` | Ticket POJO 序列化的 JSON |
| `multi-agent` | `MultiAgentService.run(question)` | `tasks: N\n<子任务>\n---\n<finalAnswer>` |
| `reflexive` | `ReflexiveService.chatReflexive(question)` | `attempts: N, accepted: true\n---\n<finalAnswer>` |
| `agent` | `DeepAgentService.run(question)` | `stopReason: X\nsteps: N\n---\n<finalAnswer>`（需 `app.deep-agent.enabled`） |
| `sql` | `NlToSqlService.ask(question)` | `guardBlocked: B\nsql: ...\nrowCount: N\n---\n<解读>`（需 `app.nl2sql.enabled`） |
| `graph` | `Assistant.chat(...)`（同 chat dispatch） | 模型回复原文（mustInclude 查桥接实体，校验多跳）；需 `app.rag.graph.enabled` |
| `workflow` | `WorkflowService.start(...)` | `status: ...\npriority: ...\n---\n<reply>`（需 `app.workflow.enabled`） |
| `a2a` | `A2aService.dispatch("message/send", ...)` | 序列化的 JSON-RPC response（需 `app.a2a.enabled`） |

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

## 18. REST API 总览

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
| POST | `/agent/run` | 深度 Agent 开放式循环（需 `app.deep-agent.enabled`） |
| POST | `/agent/run/async` | 深度 Agent 异步版（投后台 + 轮询/SSE/webhook 取回） |
| POST | `/chat/memory` | 记忆增强对话（跨会话画像，需 `app.memory.profile.enabled`） |
| GET/DELETE | `/memory/profile` | 查看 / 清空当前用户长期记忆 |
| POST | `/chat/vision` | 视觉对话（multipart `image`，需 `app.vision.enabled`） |
| POST | `/chat/sql` | NL2SQL / ChatBI（需 `app.nl2sql.enabled`） |
| POST | `/extract/ticket` | 结构化输出（Ticket POJO） |
| POST | `/rag/ingest?category=` | 文档入库（可选分类标签） |
| POST/DELETE | `/rag/documents` | 文档生命周期：上传（Tika/图片视觉）+ 版本/删除（`kb` profile） |
| POST | `/voice/chat`, `/voice/chat/stream`, `/voice/transcribe` | 语音客服（需 `app.voice.enabled`） |
| POST/GET | `/workflow/*` | 退款审批工作流：start / tasks / claim / complete / instances / data（需 `app.workflow.enabled`） |
| POST | `/channel/feishu/event` | 飞书事件订阅 / 卡片回调（需 `app.channel.feishu.enabled`） |
| POST | `/a2a` | A2A JSON-RPC 单端点（需 `app.a2a.enabled`） |
| GET | `/.well-known/agent-card.json` | A2A 服务发现（免鉴权） |
| GET | `/tasks/{id}`, `/tasks/{id}/stream` | 异步任务轮询 / SSE（multi-agent / deep-agent async 取结果） |
| POST | `/eval/run?runs=N&set=` | 跑黄金集（`set` 选集） |
| POST | `/eval/run-cases?runs=N` | 跑临时集 |
| POST | `/eval/gate?set=`, `/eval/baseline?set=` | CI 门禁（回归返 422）/ 生成基线 |
| GET | `/health` | 简易健康 |
| GET | `/actuator/health` | Actuator（含 llm / embedding sub-indicator） |
| GET | `/actuator/health/readiness` | K8s readinessProbe 用 |
| GET | `/actuator/metrics/*` | Micrometer 指标 |
| GET | `/actuator/prometheus` | Prometheus scrape |

---

## 19. 关键配置开关

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
app.rag.chunking.strategy:      recursive | markdown-header | parent-child | semantic
app.rag.chunking.parent.strategy: recursive | markdown-header  # parent-child 专用
app.rag.chunking.parent.size:   1200                          # parent 大块目标大小
app.rag.chunking.semantic.breakpoint-percentile: 95           # semantic 专用：切点稀疏度（越高块越大）
app.rag.chunking.semantic.buffer-size: 1                      # 每句拼前后 N 句再 embed，平滑边界判断
app.rag.contextual.enabled:     false                        # Contextual Retrieval：每 chunk 加文档级上下文前缀再 embed
app.rag.contextual.max-doc-chars: 8000                       # 喂上下文生成器的文档截断上限

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

# RAG 进阶
app.rag.grounding.enabled:      false   # 事实幻觉事后校验（warn 模式）
app.rag.graph.enabled:          false   # GraphRAG 第三路召回

# 深度 Agent
app.deep-agent.enabled:         false
app.deep-agent.browser.enabled: false   # Browser-use（Playwright）

# 业务落地模块（默认全关，详见各 doc）
app.nl2sql.enabled:             false
app.workflow.enabled:           false
app.channel.feishu.enabled:     false
app.voice.enabled:              false
app.vision.enabled:             false
app.memory.profile.enabled:     false
app.a2a.enabled:                false

# 生产化基线（多租户 / 限流 / token 配额 / 审计 / 异步）
app.security.*  /  app.audit.*  /  app.async.*    # 见 docs/production-hardening.md

# 评测
app.eval.auto-ingest:           false
app.eval.concurrency:           4
```

---

## 20. 配套文档

| 文档 | 内容 |
| --- | --- |
| `CLAUDE.md` | 给 AI 协作者用的总览（技术栈 / 扩展点 / 注意事项 / 完整 doc 索引） |
| `CAPABILITIES.md` | 本文档：能力清单（参考/checklist） |
| `PROMPT_JOURNEY.md` | Prompt 工程 + eval harness + 生产化的完整演化日志 |
| **业务场景** | |
| `docs/scenarios.md` | 业务场景落地总览（知识库问答 / 智能客服：NL2SQL·工作流·渠道） |
| `docs/knowledge-base.md` | 企业知识库问答（Milvus + Tika + `kb` profile） |
| `docs/nl2sql.md` | NL2SQL / ChatBI（6 层 SQL 护栏 + schema 注入 + 数字 grounding） |
| `docs/workflow-integration.md` | 工作流编排（Flowable）+ 渠道接入（飞书）+ SSO |
| `docs/voice-agent.md` | 语音客服 Agent（ASR → 客服大脑 → TTS） |
| `docs/multimodal.md` | 多模态文档理解（图像入库 / 视觉对话 / 扫描件 OCR） |
| `docs/deep-agent.md` | 深度 Agent（开放式循环 + 真实能力动作 + Browser-use） |
| `docs/graphrag.md` | GraphRAG（三元组建图 + N 跳遍历第三路召回） |
| `docs/long-term-memory.md` | 长期记忆 / 用户画像（跨会话） |
| `docs/a2a.md` | A2A（Agent2Agent）Server 落地 |
| `docs/production-hardening.md` | 生产化基线 #1–#8（多租户 / 限流 / 配额 / 审计 / 异步 / 推送） |
| **运维 / 评估** | |
| `docs/observability.md` | Prometheus / Grafana / Health Check 接入 |
| `docs/grafana-dashboard.json` | 现成 7-panel dashboard |
| `docs/roadmap.md` | 待完善项 / ROI 分档 / 决策表 |
| `docs/recall-verification.md` | 召回验证与召回率计算 |
| `docs/qa.md` | 概念性问答记录（路由 / 决策权 / 设计取舍） |
| **面试速答稿** | |
| `docs/rag-interview-notes.md` / `token-control-interview.md` / `a2a-interview.md` | RAG / Token 控制 / A2A 速答稿 |

---

## 21. 项目结构

> 包级骨干视图（237 源文件不逐一展开；完整文件级拆解见各模块 `docs/*.md` 与 CLAUDE.md）。

```text
src/main/java/com/lrj/langchain4j/
├── LangChain4jApplication.java
├── ai/
│   ├── Assistant.java / CategoryChatService.java   主对话（@AiService + @Output/InputGuardrails）+ 动态 filter
│   ├── extract/  reflexion/  multiagent/  routing/ 结构化抽取 / 自反思 / Multi-Agent DAG(+replan) / LLM-as-router
│   ├── mcp/  grounding/  guardrail/  tools/         MCP 桥接 / 事实幻觉校验 / PII·注入守卫 / @Tool
│   ├── agent/                                       深度 Agent：循环 + AgentAction(actions/) + browser/(Browser-use)
│   └── vision/                                      多模态：VisionModel + VisionConfig + VisionContentGuard
├── config/                                          LlmConfig / EmbeddingModelConfig / EmbeddingStoreConfig / 各模块 @Config
├── controller/                                      Chat / Eval / Agent / Vision / Voice / Nl2Sql / Workflow / Memory / Document / Task / A2a / channel
├── rag/                                             检索增强 + hybrid/ graph/(GraphRAG) lifecycle/(文档生命周期) scoring/
├── memory/                                          SummarizingChatMemory + profile/(长期记忆/用户画像)
├── nl2sql/                                          NL2SQL：SqlAssistant + 6 层 SqlGuard + SchemaProvider + NumberGrounding
├── workflow/                                        Flowable BPMN：退款审批 + 人工审批 + outbox/DLQ + 硬化 #1–#10
├── channel/                                         CustomerServiceBrain + feishu/（渠道接入）
├── voice/                                           SpeechService + VoiceConversationService（ASR→脑→TTS）
├── a2a/                                             A2A Server：JSON-RPC + Agent Card + protocol/ 协议类型
├── security/  audit/  async/                        多租户/限流/token 配额 · 审计日志 · 长任务异步(sse/ webhook/)
├── eval/                                            评测 harness：EvaluationRunner + Judge + baseline gate
├── observability/                                   listener(logging/metrics) + health indicator + TraceIdFilter
└── store/                                           doris/（自实现 ANN）+ redis/（ChatMemoryStore）
```

---

## 22. 一句话定位

**一个从 demo 到生产可用的 LangChain4j 参考实现**：6 个 LLM provider 热切换、6 种向量库、3 种滑窗记忆、Hybrid + Rerank + Query Expansion + History-aware + GraphRAG 检索全套、Reflexion + Multi-Agent DAG + 深度 Agent（开放式循环 + Browser-use）+ LLM-as-router 多种 agent 形态、PII/注入 Guardrail + 事实幻觉校验、Prometheus + Grafana + Health Check、外置 prompt + 多 provider override、生产级评测 harness（9 种 type dispatch + multi-run + parallel + temp=0 Judge + baseline 门禁）。**业务落地层**再叠 NL2SQL/ChatBI、企业知识库、Flowable 工作流、飞书渠道、语音客服、多模态理解、长期记忆、A2A Server，以及多租户/限流/配额/审计/异步的生产化基线——**全部 `@ConditionalOnProperty` 默认关、零回归**。**零配置就是 Ollama + InMemory 的最小可跑形态**，要哪些上哪些。
