# CLAUDE.md

这份文档给在本仓库工作的 Claude Code 使用，描述项目的技术栈、结构、约定与常用命令。

## 配套文档

- `PROMPT_JOURNEY.md`（项目根目录）— prompt 工程 + eval harness + 生产化的完整演化日志，从 demo 到生产可用
- `CAPABILITIES.md`（项目根目录）— 项目能力清单：工程基线 + 已落地能力一览，面向"这个仓库能做什么"的速览（设计取舍/演化看 `CLAUDE.md` / `PROMPT_JOURNEY.md` / `docs/`）
- `docs/scenarios.md` — **业务场景落地总览**：把已落地/规划中的业务场景汇总在一处（① 企业知识库问答 ✅ / ② 智能客服：NL2SQL ✅、工作流编排 🚧、渠道 🚧），含各场景状态、核心端点、关键文件、怎么跑，是场景层的导航入口
- `docs/roadmap.md` — 待完善项 / 按 ROI 分档 / "触发信号 → 该做什么"决策表
- `docs/production-hardening.md` — **业务化基线 #1–#8 完整落地记录**：多租户隔离 / 限流 / token 配额 / 文档生命周期 / prompt injection / 审计日志 / 长任务异步化 / Webhook + SSE 推送。包含设计要点、关键文件、yml 配置、验证脚本、关键设计决定
- `docs/workflow-integration.md` — **业务落地接入设计（规划/实施中）**：客服场景的工作流编排（Flowable BPMN 7.1.0 + 人工审批 + 状态持久化）/ SSO·OAuth（暂缓）/ 渠道接入（飞书样板）。含阶段决策、Flowable 三个坑、退款审批样板流程、待确认 TODO
- `docs/nl2sql.md` — **NL2SQL / ChatBI（Milestone 2.A + 2.B 已落地并验证）**：自然语言→SQL→只读执行→解读的受控链路。核心是 6 层 SQL 安全护栏（L1 只读账号 / L2 语句白名单 / L3 表白名单 / L4 强制 LIMIT / L5 超时 / L6 租户谓词）+ Schema 注入（含中文枚举 distinct 值）+ few-shot。`POST /chat/sql`，MySQL demo 库，`app.nl2sql.*` 默认关，需 tool-calling 模型。2.B 已补：**数字 grounding**（`NumberGrounding` 确定性核对答案数字 ∈ rows，warn 模式）+ **自修环轮数上限**（`max-tool-calls`）+ **eval `type:"sql"`** 黄金集
- `docs/knowledge-base.md` — **企业知识库问答系统落地**：Milvus 持久化 + Apache Tika 解析 PDF/Office 上传 + `kb` profile（`application-kb.yml`）+ 端到端验证（多租户隔离 / 重启持久化 / 版本覆盖）
- `docs/observability.md` — Prometheus / Grafana / Health Check 接入说明
- `docs/recall-verification.md` — **召回验证与召回率计算**：`rag-recall-all-providers` 强召回 case（靶点「5 provider 列在同一 section」，回归 chunking 切分后召回完整性，含 token 模式跑法）+ 厘清本项目 `passRate`（规则匹配 + Judge）vs 经典 `Recall@k`（需标注黄金集）的区别与各自算法
- `docs/rag-interview-notes.md` — **RAG 面试速答稿**：Chunking 选型决策表（含 chars/tokens 计量单位）/ 多路·混合召回 / RRF / 召回评估，按「怎么做 + 为什么 + 踩坑」组织
- `docs/retrieval-memory-interview.md` — **检索与记忆面试速答稿**：13 题按面试提问方式组织（Embedding 三向解耦/换模型重建库 · Memory 会话内滑窗[messages/tokens/summary 异步压缩]与跨会话长期画像两套正交 · RAG 检索链[多路召回 RRF/精排/图谱/引用校验]与被测试逼出的 min-score · 召回率 passRate vs Recall@k/召回探针/定位哪层/faithfulness 正交），每题「速答→追问→代码锚点」
- `docs/graphrag.md` — **GraphRAG（图谱增强检索）落地（G1–G4 全落地）**：`rag/graph` 包，`app.rag.graph.enabled`（默认关），零新依赖。补向量召回的「多跳关系 / 实体聚合」盲区——入库时 `GraphExtractor`（temp=0）抽实体-关系三元组建图，检索时 `GraphContentRetriever` 从 query 命中实体做 N 跳遍历，作为**第三路 retriever** 并联进 router（vector/keyword/graph）由 RRF 融合。每条三元组带 `sourceId`（`file#chunk`）→ `[doc=ID]` 引用 + grounding Layer 0 白嫖。**G3**：`JdbcGraphStore`（`store=jdbc` MySQL 边表持久化，代 Neo4j）+ `async` 后台建图 + 接 `kb` profile + `removeBySourcePrefix` 生命周期同步。**G4**：`entity-linking=llm`（`LlmEntityLinker` 抽 query 实体再锚定）+ 受限 schema（`extract.relation-types` 白名单）+ 轻量实体消歧（`aliases` 别名表）。23 个确定性单测 + `eval-cases-graph.json`（3 条）+ `baseline-graph.json` 种子（起模型后 `/eval/baseline?set=graph` 重生成）
- `docs/token-control-interview.md` — **Token 控制面试速答稿**：8 题按 AI Agent 岗提问方式组织（ChatMemory 三种滑窗 / tokenizer 估算偏差 / per-tenant 日 token 预算三组件闭环 / 多副本 Redis 演进 / multi-agent fan-out 成本 + 租户归属 / 输出侧短板 / token 指标 / 降本 roadmap），每题含代码锚点
- `docs/a2a-interview.md` — **A2A 协议面试速答稿**：三种调用方式（message/send 同步 · message/stream SSE 流式 · pushNotificationConfig Webhook 异步推）/ 三种传输 binding（JSON-RPC/gRPC/REST）/ Agent Card 发现 / Task 状态机（含 input-required）/ A2A vs MCP 对比 / 本项目落地答法
- `docs/long-term-memory.md` — **长期记忆 / 用户画像落地（v1）**：`memory/profile` 包，`app.memory.profile.enabled`（默认关），零新依赖。补「跨会话记住用户」能力，正交于会话内滑窗记忆（ChatMemory）。chat 前 `recall` 该用户 durable 事实（偏好/属性/反复诉求）注入上下文，chat 后 `observe` 异步用 `ProfileExtractor`（temp=0）抽取更新。`UserProfileStore` 按 `(tenant,user)` 隔离 + 去重 + 容量淘汰；`UserProfileChatService` 包装 `Assistant.chat`（同 `CategoryChatService` 范式）。端点 `POST /chat/memory`、`GET/DELETE /memory/profile`。10 个确定性单测。Redis 持久化 / embedding 消歧 / update-forget 按信号补
- `docs/voice-agent.md` — **语音客服 Agent 落地（v1 turn-based）**：`voice` 包 + 共享 `channel/CustomerServiceBrain`，`app.voice.enabled`（默认关），零新依赖（JDK `HttpClient`）。把「智能客服」从飞书文本延伸到语音——音频 → ASR → 客服大脑（意图路由：退款/投诉→工作流，其余→RAG 对话）→ TTS → 音频。`SpeechService` 抽象 + `OpenAiSpeechService`（OpenAI 兼容：`/audio/transcriptions` multipart + `/audio/speech`，base-url 可指云 OpenAI/Azure/本地 whisper+tts）。`POST /voice/chat`（multipart 音频）。TTS 前剥 `[doc=]` 引用标记 + 空转写兜底。复用多租户/配额/审计/工作流全链；7 个确定性单测。实时全双工/电话 IVR = 未来项
- `docs/multimodal.md` — **多模态文档理解落地（分三段）**：`ai/vision` 包 + `app.vision.*`（默认关），零新依赖。把「文档理解」从纯文本延伸到图像——① **图像入库增强 RAG**：`POST /rag/documents` 上传图片 → 视觉模型「描述 + OCR 转写」→ 文本 → 走现有 chunk/embed/检索/引用全链；② **视觉对话** `POST /chat/vision`（multipart `image`+`message`）看图直接作答、单轮不入库；③ **扫描件 OCR**（图片格式，caption 指令内含逐字转写，与描述同一次调用）。`app.vision.provider` 与 chat/embedding **三向解耦**（openai-compat: gpt-4o/qwen2.5-vl；ollama: llava/qwen2.5-vl/llama3.2-vision）。关键设计：视觉 `ChatModel` 由 `VisionConfig` 直接构造、**不注册成 Bean**（避开 `@AiService` 只能有一个 ChatModel 的约束，同 `buildJudgeChatModel` 套路），对外只暴露 `VisionModel` 接口；`MultimodalDocumentExtractor` 软依赖 vision（关闭时上传文本零回归、上传图片清晰 400）。**生产硬化 A/B/C**：A 视觉 `ChatModel` 灌入 `ChatModelListener`（纳入 Prometheus 指标 + per-tenant token 预算，堵绕过配额的口子）；B `DefaultVisionModel` 按图 SHA-256 去重的有界 LRU caption 缓存（`app.vision.caption-cache-size`）；C `VisionContentGuard` 入库前安全闸——注入指令阻断（复用 `PromptInjectionDetector`）+ PII 脱敏（`PiiDetector.redact`）+ 审计，补「图像 caption 绕过 `@AiService` guardrail」缺口。13 个确定性单测。PDF 扫描件逐页渲染 = 未来项
- `docs/deep-agent.md` — **深度 Agent（开放式 plan→act→observe 循环）落地**：`ai/agent` 包 + `app.deep-agent.*`（默认关），零新依赖。区别于 `multiagent` 的固定 DAG——模型自己决定下一步、用工具、观察、再决策，直到 `finish` 或预算耗尽。显式 ReAct 循环（结构化 `AgentDecision` 决策 + 可插拔 `AgentAction`，**非**原生 function-calling，为的是对每步完全控制 + 跨 provider 确定性可测）：**三维预算**（步数 `max-steps` / 墙钟 `max-wall-clock-ms` / 近似 token `max-tokens`，后两者默认关，任一超限即停）/ **滑窗循环检测**（`loop-window` 内同 (动作,入参) 达 `max-repeats`，抓 A→B→A→B 震荡而非仅连续重复）/ scratchpad 跨步工作记忆（`note` 沉淀，溢出按 bullet 行压缩，`scratchpad-summary=true` 时 LLM 摘要旧结论、否则丢弃最旧整条）/ **brain 单步重试**（`brain-max-retries`，兜结构化输出解析失败/provider 抖动，全失败才 ERROR）/ 深度受限的 `delegate` 子 Agent 派生 / 逐步 trace；`stopReason` ∈ DONE/MAX_STEPS/TIMEOUT/BUDGET/LOOP/ERROR/CANCELLED（异步 `Future.cancel(true)` interrupt → 每步开头侦测中断标志提前退出，顶层 finally 清标志防污染线程池）。这套「预算不只步数、卡死不只连续、溢出不盲砍」正是 Loop Engineering 把 demo 循环升级为生产循环的核心。`AgentBrain` 程序化 `AiServices.builder` 构建、无 ChatMemory、走主 `ChatModel`（token 自动纳入配额）。加动作 = 实现 `AgentAction` + `@Component`（自动发现，无需改循环），是 Browser-use/Computer-use 的地基。`POST /agent/run`（同步）+ `/agent/run/async`（**异步**，复用 `async` 引擎投后台、轮询/SSE/webhook 取回）。**eval `type:"agent"`** 黄金集（`set=agent`，校验 stopReason/步数/答案）已接。**真实能力动作**已接入（验证「动作只是往循环里插的工具」）：`rag_search`（复用主 RAG 链 `vectorRetriever`、带 `[doc=ID]` 引用、租户隔离）/ `nl2sql_query`（deep-agent+nl2sql 双开时装配、透传 `NlToSqlService` 全护栏）/ `mcp_call`（deep-agent+mcp 双开、分派 `McpClient` 动态工具集、目录进描述）。**Browser-use** 已落地（`app.deep-agent.browser.enabled`，默认关）：Playwright 无头 Chromium 做成 `browser_open`/`browser_click`（文本点击）/`browser_click_xy`（坐标点击）/`browser_type`（表单输入）/`browser_screenshot`（整页截图存文件）/`browser_see`（截图→`ai/vision` 视觉理解，browser+vision 双开时装配）插进循环，按线程懒加载、`AgentRunListener.onRunEnd` 关页面、Chromium 仅开启时下载。**Code Interpreter** 已落地（`app.deep-agent.code-exec.enabled`，默认关）：`actions/CodeExecAction`（`code_exec`）用 JDK `jdk.jshell` local 引擎跑模型写的 Java 源码、护栏尽力而为（源码 denylist + 墙钟超时 + 输出截断）、`run()` 绝不抛异常，详见 `docs/code-exec.md`。54 个确定性单测（循环 18 + browser 11 + rag/nl2sql/mcp 动作 16 + code_exec 9）。**Computer-use 桌面沙箱（方向 C：Docker 桌面 + 系统级截图/点击）+ code_exec 真沙箱（外部受限进程/容器）= 剩余未来项**
- `docs/a2a.md` — **A2A（Agent2Agent）Server 落地**：`a2a` 包，`app.a2a.enabled`（默认关），零新依赖。三种调用方式（`message/send` chat 同步 / `message/stream` chat SSE 流式 / multi-agent 异步 Task + `pushNotificationConfig` webhook 回推）+ `tasks/get|cancel`。复用 `async` Task 引擎 + 安全/多租户/配额链；A2A push 与现有 `WebhookDispatcher` 双通道隔离。含协议↔内部模型映射、关键文件、怎么跑
- `docs/workflow-patterns.md` — **Agentic Workflow 模式全覆盖**：把 Anthropic《Building Effective Agents》的 5 种 workflow（Prompt Chaining `ai/chaining` `/chat/chain` 顺序链+步间 gate / Routing `ai/routing` / Parallelization-Sectioning `multiagent` DAG / Parallelization-Voting `ai/voting` `/chat/vote` 同任务并行取共识 / Orchestrator-Workers `multiagent` / Evaluator-Optimizer `reflexion`）+ agent（`deep-agent`）逐一映射到本项目代码，含各自开关/端点/怎么跑。**注意**：这里的 "workflow" 是 LLM 编排模式，跟 `workflow` 包（Flowable BPMN 业务流程）不是一回事
- `docs/semantic-cache.md` — **语义响应缓存落地**：`cache/semantic` 包，`app.cache.semantic.enabled`（默认关），零新依赖。embed query → 按租户桶找 cosine ≥ threshold（默 0.95）的历史问答，命中即 0 LLM token 短路、miss 跑模型后回填。有界 LRU（每租户 `max-entries`）+ TTL 过期 + `cache.semantic{result=hit|miss}` 指标。`/chat` 经 `ObjectProvider<SemanticCache>` 软依赖接入。7 个确定性单测
- `docs/otel-tracing.md` — **OpenTelemetry GenAI 分布式追踪落地**：`observability/otel` 包，`app.observability.otel.enabled`（默认关）。补 Micrometer 指标之外的 span 级链路——`OtelChatModelListener`（`@Component` 自动入 `LlmConfig` 的 `List<ChatModelListener>`）每次 chat 发一棵 `gen_ai.*` CLIENT span（模型/token/租户属性）。OTLP HTTP exporter（不引 grpc，避开 Milvus grpc 1.59.1 冲突），关闭时 no-op Tracer 兜底零开销。3 个确定性单测（`InMemorySpanExporter`）
- `docs/model-cascade.md` — **Model Cascade / 成本路由落地**：`ai/cascade` 包，`app.llm.cascade.enabled`（默认关），零新依赖。便宜模型先答 → `ConfidenceGate` 确定性启发式（拒答/不确定标记 + 过短 + 可选 temp=0 自评）判低置信 → 才升级强模型。`CascadeChatModel implements ChatModel` 但**不注册成 Bean**（避开 `@AiService` 单 ChatModel 约束，构造在 `CascadeService` 内部），底层两模型灌 `ChatModelListener` 走配额。`POST /chat/cascade`，指标 `llm.cascade{served=cheap|strong}`。13 个确定性单测
- `docs/mcp-server.md` — **MCP Server（反向）落地**：`mcpserver` 包，`app.mcp.server.enabled`（默认关），零新依赖。与 `ai/mcp`（client，把外部工具桥进来）方向相反——把本 app 能力暴露给外部 MCP 客户端（Claude Desktop / Cursor）调入。`POST /mcp/server` JSON-RPC 2.0 over HTTP（`initialize` / `tools/list` / `tools/call`）暴露 `current_datetime` / `rag_search` / `nl2sql_query`（后者双开软依赖），手写协议 record 仿 `a2a/protocol`，需 `X-Api-Key` 走租户隔离。9 个确定性单测
- `docs/code-exec.md` — **Code Interpreter 动作落地**：`ai/agent/actions/CodeExecAction`（`code_exec`），`app.deep-agent.code-exec.enabled` + deep-agent 双开才装配，零新依赖（JDK `jdk.jshell` local 引擎）。模型写 Java 源码 → JShell 沙箱执行 → 回填 stdout/表达式值进 ReAct 循环。护栏尽力而为（源码长度上限 + 危险 API denylist + 墙钟超时 + 输出截断），`run()` 绝不抛异常。非真沙箱（同 JVM，无 SecurityManager），强隔离=未来项。9 个确定性单测
- `docs/multimodal-embedding.md` — **原生多模态（CLIP）embedding 落地**：`rag/multimodal` 包，`app.rag.multimodal-embedding.*`（默认关），零新依赖（JDK HttpClient）。区别于 `ai/vision` 的 caption→text 路径——图片直接 embed 进 CLIP/jina-clip 跨模态向量空间，存进现有 `EmbeddingStore`（`type=image`/`file_name`/`tenantId` metadata），`POST /rag/image` 入库 + `POST /rag/image-search` 文本搜图。与 chat/文本 embedding 三向解耦、不注册 EmbeddingModel Bean。检索强制 AND `type=image` filter 做维度安全 + 租户隔离。5 个确定性单测
- `docs/cost-attribution.md` — **Per-tenant USD 成本归因落地**：`cost` 包，`app.cost.enabled`（默认关），零新依赖。把 token 用量按 model 单价翻成 **USD**（`CostProperties` 定价表 USD/1M tokens + 最长前缀匹配）、per-tenant 日累加（`CostTracker` 同构 `TokenBudgetTracker`）+ Micrometer `gen_ai.client.cost.usd`。核心是 `CostCalculator` 纯函数**把 Anthropic cache 输入拆三档**（regularInput = input−cacheRead−cacheWrite，分别乘 1×/0.1×/1.25×）。`CostChatModelListener` 走 `List<ChatModelListener>` 自动接入（不改 LlmConfig），`GET /actuator/cost` 出快照。`CostTracker` 抽接口 + `app.cost.store=in-memory|redis`（redis 用 `INCRBYFLOAT` 多副本汇总同一份账，复用 `RedisDailyCounters`）。补 `token-budget`「不分模型贵贱」的短板，给 cascade/cache/prompt-caching 降本叙事收口一个 $ 口径。13 个确定性单测（`CostCalculatorTest` 7 + `InMemoryCostTrackerTest` 6）
- `docs/distributed-state.md` — **Redis-backed 分布式状态（token 预算样板）落地**：`security` 包，`app.token-budget.store=in-memory|redis`（默认 in-memory），复用已在的 `spring-boot-starter-data-redis`，零新依赖。挑最有代表性、且**多 pod 下是真 correctness bug**（进程内计数各算各的 → 配额被放大到副本数倍）的 token 日预算落一个 Redis 后端，把项目里一排「限单 JVM，多副本需 Redis」注释从承诺变成现实。`TokenBudgetTracker` 抽成接口（消费方零改动）+ `InMemoryTokenBudgetTracker`（现逻辑 + Clock seam）/ `RedisTokenBudgetTracker`（key `<prefix><date>:<tenantId>` 内嵌日期 → 跨日自动过期免清理；`consume` 走 Lua `INCRBY`+`PEXPIREAT` 原子累加；`snapshotAll` 靠 `SCAN`；Redis 抖动不拖垮主链路）。`SecurityConfig` 按 `store` 条件装配两个互斥 Bean（照抄 `app.memory.store` 范式）。共享纯函数范式抽成 `security/RedisDailyCounters`（key 布局/租户解析/次日午夜过期），**已顺手复用到第二处 `CostTracker`**（`app.cost.store=redis`，`INCRBYFLOAT`）验证可复制。19 个确定性单测（`RedisDailyCountersTest` 7 + `InMemoryTokenBudgetTrackerTest` 7 + `InMemoryCostTrackerTest` 6，全不连 Redis）。剩 `RateLimiterRegistry`/`TaskStore` 等按同范式补
- `docs/retrieval-eval.md` — **检索质量评测（Recall@k/Precision@k/MRR/Hit@k）落地**：`eval/retrieval` 包，零新依赖。补 `eval-cases.json` passRate（规则+Judge，混检索+生成两层）没覆盖的**纯召回层**——**不经 LLM**，只跑主链 `vectorRetriever` 量相关文档召回没。`RetrievalMetrics` 纯函数算四指标，`RetrievalEvaluator` 跑 `retrieve(Query.from(q))` → 片段 id 用 `TaggedSourceContentInjector.inferId`。id 匹配**文件级为主**（对 chunk 切分漂移鲁棒）。`POST /eval/retrieval?set=&ingest=`，黄金集 `retrieval-cases.json`（8 条靶 `documents/`）。调 chunking/embedding/rerank 后重跑能把召回变化跟生成变化拆开归因。9 个确定性单测（`RetrievalMetricsTest`）
- `docs/qa.md` — 概念性问答记录（路由 / 决策权 / 设计取舍等），按时间倒序
- `docs/grafana-dashboard.json` — 现成的 7 panel dashboard JSON

## 项目概览

LangChain4j + Spring Boot + Ollama 脚手架，演示四大能力：

- Chat + 多轮记忆（`ChatMemoryProvider` + `@MemoryId`，按 chatId 隔离会话）
- RAG（`EmbeddingStoreContentRetriever` + 可切换的 EmbeddingStore）
- Tools / Function Calling（`@Tool` 注解的 Spring Bean，自动被 `@AiService` 发现）
- AI Services（声明式接口 `Assistant`，Spring 自动装配模型/记忆/检索/工具）

并附带：

- 流式响应（`TokenStream` + Spring MVC `SseEmitter`）
- 向量库可切换（默认 in-memory；可切到 PGVector）

## 技术栈

- Java 21
- Spring Boot 3.3.5（spring-boot-starter-parent）
- LangChain4j 1.13.1（通过 `langchain4j-bom` 统一版本）
- Maven

关键依赖：

- `langchain4j-spring-boot-starter`（`@AiService` 注解装配）
- `langchain4j-ollama-spring-boot-starter`（只用来自动装配 `EmbeddingModel`；ChatModel/StreamingChatModel 由 `LlmConfig` 统一接管以支持多 provider 切换）
- `langchain4j-open-ai` / `langchain4j-anthropic` / `langchain4j-google-ai-gemini`（OpenAI / DeepSeek / Claude / Gemini 的 ChatModel 实现，由 `LlmConfig` 直接构建，不走各自的 spring starter，避免自动装配冲突）
- `langchain4j-easy-rag`（开箱即用的文档加载/切分）
- `langchain4j-pgvector` / `langchain4j-milvus` / `langchain4j-chroma` / `langchain4j-qdrant`（可选向量库）
- `mysql-connector-j`（Doris 走 MySQL 协议，给自定义 `DorisEmbeddingStore` 用）

## 目录结构

```text
src/main/
├── resources/application.yml
└── java/com/lrj/langchain4j/
    ├── LangChain4jApplication.java       启动类
    ├── config/
    │   ├── LangChain4jConfig.java        ContentRetriever（direct / candidate）+ ScoringModel + RetrievalAugmentor + TaggedSourceContentInjector
    │   ├── LlmConfig.java                按 app.llm.provider 装配 ChatModel/StreamingChatModel（ollama/openai/anthropic/gemini/deepseek/vllm）
    │   ├── EmbeddingModelConfig.java     按 app.embedding.provider 装配 EmbeddingModel（ollama/openai-compat），跟 chat provider 解耦
    │   ├── ChatMemoryConfig.java         ChatMemoryStore（InMemory / Redis）+ ChatMemoryProvider
    │   └── EmbeddingStoreConfig.java     条件化 InMemory / PGVector / Milvus / Chroma / Qdrant / Doris
    ├── store/
    │   ├── doris/
    │   │   ├── DorisEmbeddingStore.java       自定义 EmbeddingStore（Doris ANN）
    │   │   └── DorisFilterTranslator.java     LC4j Filter → Doris JSON WHERE 子句
    │   └── redis/RedisChatMemoryStore.java    持久化 ChatMemoryStore
    ├── ai/
    │   ├── Assistant.java                 @AiService 接口（chat + chatStream）
    │   ├── CategoryChatService.java       动态 filter 包装（设 ThreadLocal 再调 Assistant.chat）
    │   ├── tools/DateTimeTool.java        @Tool 示例（Spring @Component）
    │   ├── extract/
    │   │   ├── Extractor.java             一次性结构化抽取接口（不走自动 RAG/记忆）
    │   │   └── Ticket.java                被抽取的 record（@Description 元数据 → JSON Schema）
    │   ├── reflexion/
    │   │   ├── Answerer.java              answer/improve 两个方法（生成 + 基于反馈改进）
    │   │   ├── Critic.java                critique(question, answer) → Critique JSON
    │   │   ├── Critique.java              结构化评分 { score, feedback }
    │   │   └── ReflexiveService.java      generate → critique →（低分则）improve 的循环
    │   ├── multiagent/
    │   │   ├── Plan.java / SubTask.java   结构化任务计划
    │   │   ├── Planner.java               拆任务（输出 Plan JSON）
    │   │   ├── Worker.java                执行单任务（并行）
    │   │   ├── Synthesizer.java           汇总专家答案
    │   │   └── MultiAgentService.java     plan → 并行 worker → synthesize
    │   └── guardrail/PiiGuardrail.java    检测 email/手机/身份证 → reprompt 重写
    ├── rag/
    │   ├── RagIngestionService.java                从 ./documents 加载并入库（可选 category 标签）
    │   ├── CategoryContext.java                    per-request 的 RAG 类别 ThreadLocal
    │   ├── TaggedSourceContentInjector.java        自定义 ContentInjector：检索片段包成 <source id="filename#N">，与 citationPolicy 闭环
    │   └── scoring/OllamaLlmScoringModel.java      LLM-as-reranker（ScoringModel 实现）
    └── controller/ChatController.java     /chat, /chat/stream, /rag/ingest, /health
documents/                                 RAG 文档目录（.txt/.md/.pdf 等）
```

> 上面的树是骨干视图。仓库已扩出若干新包，未逐一展开：`ai/routing`（LLM-as-router classifier）、`ai/mcp`（MCP 桥接 AiService）、`ai/grounding`（事实幻觉事后校验）、`memory`（SummarizingChatMemory 等滑窗实现）、`memory/profile`（长期记忆/用户画像 v1：`ProfileExtractor` temp=0 抽 durable 事实 + `InMemoryUserProfileStore` 按 (tenant,user) 隔离/去重/容量淘汰 + `UserProfileService` recall/异步 observe + `UserProfileChatService` 召回注入包装 + `MemoryController` `/chat/memory`·`/memory/profile`，默认关，详见 `docs/long-term-memory.md`）、`rag/hybrid`（KeywordContentRetriever + DocumentMirror）、`rag/graph`（GraphRAG G1–G4：`GraphExtractor` 三元组抽取 + `InMemoryGraphStore`/`JdbcGraphStore`（MySQL 持久化）邻接图 + `TokenEntityLinker`/`LlmEntityLinker` 实体链接 + `GraphContentRetriever` N 跳遍历第三路召回 + `GraphIngestor` async 建图/别名消歧/受限 schema，默认关，详见 `docs/graphrag.md`）、`rag/lifecycle`（文档生命周期）、`security`（多租户 / prompt injection / 限流 / token 配额）、`audit`（审计日志）、`async`（长任务异步化 + `async/sse` + `async/webhook` 推送）、`eval`（评测 harness）、`observability`（listener / TraceIdFilter）、`nl2sql`（NL2SQL/ChatBI：6 层 SQL 安全护栏 + 只读执行，默认关）、`workflow`（Flowable BPMN 工作流编排：退款审批样板 + 人工审批 + MySQL 持久化。上生产硬化 #1–#10 全落地：`ApprovalTimeoutSweeper` 超时自动驳回（#1）+ `dedupeId`/businessKey 幂等（#2）+ `ServiceTaskDelegates.withRetry` LLM 失败降级补偿/事务边界（#3）+ `WorkflowHistoryCleaner` 历史表按保留期清理/history=audit（#4）+ `WorkflowReplyStore` reply 出流程变量落 `WF_REPLY` 业务表（#5）+ 版本分布日志（#6）+ claim/unclaim + 并发审批 409（#7）+ `WorkflowOutbox`/`WorkflowOutboxDispatcher` 终态回推持久 outbox + DLQ（#8）+ `WorkflowMetrics` 接 Micrometer（#9）+ `purge` PII 合规删除（#10），默认关）、`channel/feishu`（飞书渠道 Milestone 1.B：`FeishuController` 回调 + `FeishuCrypto` AES 解密/验签 + `FeishuIntent` 意图分类 + `FeishuClient` 出站/token 缓存 + `FeishuChannelService` 意图路由[退款→工作流/其余→对话] + `FeishuReplyListener` 监听 `WorkflowTerminalEvent` 回推，5s ack + 异步回推 + 审批卡片闭环，默认关）、`voice`（语音客服 Agent v1：`SpeechService` 抽象 + `OpenAiSpeechService`（ASR `/audio/transcriptions` + TTS `/audio/speech`，JDK HttpClient）+ `VoiceConversationService` 编排 ASR→脑→TTS + `controller/VoiceController` `/voice/chat`，复用 `channel/CustomerServiceBrain`（渠道无关客服大脑：意图分类→工作流/对话），默认关，详见 `docs/voice-agent.md`）、`ai/vision`（多模态文档理解：`VisionModel` 抽象 + `DefaultVisionModel`（base64 + `UserMessage.from(ImageContent,TextContent)` → `chat()`）+ `VisionConfig`（`app.vision.enabled` 条件化，按 provider 建视觉 `ChatModel` 但**不注册成 Bean**，灌入 `ChatModelListener` 接指标/配额）+ `VisionProperties` + `VisionContentGuard`（入库前注入阻断 + PII 脱敏）+ caption SHA-256 LRU 缓存，配 `rag/lifecycle/MultimodalDocumentExtractor`（图片→视觉描述/OCR→安全闸、其余→Tika）+ `controller/VisionController`（`/chat/vision`），与 chat/embedding 三向解耦，默认关，详见 `docs/multimodal.md`）、`ai/agent`（深度 Agent：开放式 plan→act→observe 循环。`AgentBrain`（结构化 `AgentDecision` 单步决策 AiService，无记忆，程序化构建走主 `ChatModel`）+ `DeepAgentService`（循环本体：`max-steps` 硬预算 / `max-repeats` 循环检测 / scratchpad 跨步记忆 / 深度受限 `delegate` 子 Agent / 逐步 trace / stopReason）+ `AgentAction` 可插拔动作接口（`@Component` 自动发现，`actions/CurrentTimeAction` 示例 + `actions/RagSearchAction`（`rag_search` 复用 `vectorRetriever`、带 `[doc=ID]` 引用、租户隔离）+ `actions/Nl2SqlAction`（`nl2sql_query` 仅 deep-agent+nl2sql 双开时装配、透传受控 `NlToSqlService` 全护栏）+ `actions/McpToolAction`（`mcp_call` 仅 deep-agent+mcp 双开时装配、分派 `McpClient` 动态工具集、目录进描述））+ `config/DeepAgentConfig`（`app.deep-agent.enabled` 条件化）+ `controller/AgentController`（`/agent/run` 同步 + `/agent/run/async` 投 `async` 引擎，`Future.cancel(true)` 触发循环 `CANCELLED` 取消感知）+ `AgentRunListener`（顶层 run 收尾钩子）+ `ai/agent/browser`（Browser-use：`BrowserSession` 接口 + `PlaywrightBrowserSession` 按线程懒加载无头 Chromium + `browser_open`/`browser_click`/`browser_click_xy`（坐标点击）/`browser_type`（表单输入）/`browser_screenshot`（整页截图存文件、不回传 base64）/`browser_see`（截图→`ai/vision` 视觉理解，双开 browser+vision 时装配）动作，`app.deep-agent.browser.enabled` 默认关）+ eval `type:"agent"`，默认关，详见 `docs/deep-agent.md`）、`a2a`（A2A Server Milestone：`A2aController` JSON-RPC 单端点 `/a2a` + `/.well-known/agent-card.json` 发现 + `A2aService` 分派[chat 同步/multi-agent 异步] + `A2aStreamService` SSE 流式 + `A2aMapper` 状态翻译 + `A2aPushNotifier`/`A2aPushNotificationStore` webhook 回推 + `a2a/protocol/*` 手写 record 协议类型，复用 `async`/安全/配额，零新依赖，默认关）、`cache/semantic`（语义响应缓存：embed query 找 cosine≥阈值历史问答命中 0 token 短路，租户桶 LRU+TTL，`/chat` 软依赖接入，默认关，详见 `docs/semantic-cache.md`）、`observability/otel`（OpenTelemetry GenAI 追踪：`OtelChatModelListener` 每次 chat 发 `gen_ai.*` span，OTLP HTTP 不引 grpc，no-op Tracer 兜底，默认关，详见 `docs/otel-tracing.md`）、`ai/cascade`（Model Cascade/成本路由：`ConfidenceGate` 判低置信才升级强模型，`CascadeChatModel` 不注册 Bean，`/chat/cascade`，默认关，详见 `docs/model-cascade.md`）、`mcpserver`（MCP Server 反向暴露：`/mcp/server` JSON-RPC 把 datetime/rag_search/nl2sql_query 暴露给外部 MCP 客户端，默认关，详见 `docs/mcp-server.md`）、`rag/multimodal`（原生 CLIP embedding：图片直接进跨模态向量空间、文本↔图片互检索，`/rag/image`·`/rag/image-search`，不注册 EmbeddingModel Bean，默认关，详见 `docs/multimodal-embedding.md`）、`ai/agent/actions/CodeExecAction`（`code_exec` JShell 沙箱执行模型写的 Java 源码，deep-agent+code-exec 双开装配，详见 `docs/code-exec.md`）。详见对应 `docs/*.md`。

## 构建 / 测试 / 打包

```bash
# 编译
mvn compile

# 跑全部单测（src/test/java，纯 JVM，不需要起 Ollama）
mvn test

# 跑单个测试类
mvn test -Dtest=MarkdownHeaderSplitterTest

# 跑单个测试方法
mvn test -Dtest=MarkdownHeaderSplitterTest#simpleMarkdown_oneSegmentPerSection

# 打可执行 jar（target/*.jar，含 spring-boot repackage）
mvn package

# 跳过测试打包
mvn package -DskipTests
```

现有单测（JUnit 5，spring-boot-starter-test，**全是纯逻辑单测、不拉起 Spring context、不连模型**）：

- `config/AssistantPropertiesTest` — provider override 解析成 `ResolvedAssistantStyle` 的 fallback 逻辑
- `rag/MarkdownHeaderSplitterTest` — markdown-header chunking 切分行为（含 `#`-only 自适应 / breadcrumb / 极小 section 合并）
- `eval/CaseAggregateTest` — multi-run 聚合（passRate / avgScore / σ）算法
- `eval/BaselineGateTest` — baseline CI 门禁纯逻辑（全局/per-case 门槛 / 缺席 case / 容差 / 基线生成）
- `eval/GoldenSetsTest` — 黄金集 JSON 结构合法性（4 个集 + baseline 解析，挡 JSON 笔误）
- `ai/multiagent/MultiAgentServiceTest` — DAG 拓扑排序 / 环检测降级
- `ai/grounding/GroundingServiceTest` — Layer 0 编造引用检测 / 弃答跳过
- `ai/guardrail/StreamGuardTest` — 流式后处理：PII 命中 + 澄清式提问识别（input-required）
- `ai/tools/DateTimeToolTest` — 工具坏入参返回可纠错文本而非抛异常
- `memory/SummarizingChatMemoryTest` — 摘要记忆：同步/异步压缩 / 失败不丢消息 / clear
- `nl2sql/SqlGuardTest` — NL2SQL 安全护栏（18 case）：注入拦截 / 只读 / 表白名单 / 强制 LIMIT / 租户谓词
- `nl2sql/NumberGroundingTest` — NL2SQL 数字 grounding：受支撑来源 / 序数·年份豁免 / 千分位归一
- `rag/graph/GraphRagTest` — GraphRAG（14 case）：图遍历（1 跳/2 跳/客体侧/桥接）/ 租户·类别隔离 / token 实体链接 / 检索 provenance（sourceId 重建）+ 分组 + maxTriples 截断 + `removeBySourcePrefix`
- `rag/graph/GraphIngestorTest` — 建图钩子（5 case）：别名规范化 / 受限 schema 过滤 / async 投后台 / 空三元组跳过
- `rag/graph/LlmEntityLinkerTest` — LLM 实体链接（4 case）：提及锚定真实实体 / 幻觉提及不当种子 / 抽取失败降级
- `channel/CustomerServiceBrainTest` — 客服大脑（5 case）：CHAT 路由 / 工作流关闭降级对话 / 工作流播报文案（挂起转人工 / 自动受理 reply）
- `voice/VoiceConversationServiceTest` — 语音编排（3 case）：ASR→脑→TTS 全链 / 空转写跳过大脑兜底 / TTS 前剥引用标记 + base64
- `voice/SentenceChunkerTest` — SSE 半流式分句器（5 case）：句末标点切句 / min-chars 阈值不切过短句 / flush 残余 / 引用标记不误切 / 单 token 多句
- `memory/profile/InMemoryUserProfileStoreTest` — 长期记忆存储（5 case）：去重 / 容量淘汰 / 租户·用户隔离 / 空文本跳过 / 清空
- `memory/profile/UserProfileServiceTest` — 画像服务（5 case）：召回格式 + recall-limit 截最近 / 观察抽取入库 / 空抽取 no-op / 抽取异常被吞
- `ai/agent/DeepAgentServiceTest` — 深度 Agent 循环：max-steps 硬预算 / max-repeats 循环检测 / scratchpad / stopReason 判定
- `ai/agent/browser/BrowserActionsTest` — Browser-use 动作（`browser_open`/`browser_click`）插入循环的行为
- `ai/agent/actions/RagSearchActionTest` / `Nl2SqlActionTest` / `McpToolActionTest` — 深度 Agent 真实能力动作：`rag_search` 引用格式·截断·空命中·异常降级 / `nl2sql_query` 结果格式·护栏拦截·异常降级 / `mcp_call` 目录进描述·JSON 分派·缺字段·坏 JSON·工具错误·异常降级（桩 retriever / 桩 SqlAssistant 写 `SqlExecutionContext` / 桩 `McpClient`，不连模型/DB/MCP server）
- `ai/vision/DefaultVisionModelTest` — 视觉模型：base64 编码 + caption SHA-256 LRU 缓存命中
- `a2a/A2aMapperTest` / `a2a/A2aDispatchTest` — A2A 协议↔内部模型映射 / dispatch + skill 路由
- `workflow/WorkflowServiceTest` / `WorkflowOutboxTest` / `ServiceTaskDelegatesTest` / `ApprovalTimeoutSweeperTest` / `WorkflowHistoryCleanerTest` — 工作流：抽单·优先级 / outbox+DLQ / withRetry 降级补偿 / 超时驳回 / 历史清理（纯逻辑，不连 Flowable 引擎）
- `rag/lifecycle/DocumentTextExtractorTest` / `InMemoryDocumentRegistryTest` / `MultimodalDocumentExtractorTest` — 文档生命周期：Tika 文本抽取 / 注册表去重·版本 / 多模态分流（图片→视觉、其余→Tika）
- `channel/feishu/FeishuCryptoTest` / `FeishuIntentTest` — 飞书 AES 解密·验签 / 意图分类
- `cache/semantic/SemanticCacheTest` — 语义缓存（7 case）：cosine≥阈值命中 / 阈值下未命中 / 租户隔离 / LRU·TTL 淘汰（桩 EmbeddingModel）
- `observability/otel/OtelChatModelListenerTest` — OTel 追踪（3 case）：`gen_ai.*` span 属性 / 错误 span / no-op Tracer 路径（`InMemorySpanExporter`）
- `ai/cascade/ConfidenceGateTest` / `CascadeChatModelTest` — 模型级联（13 case）：启发式判低置信（拒答/不确定/过短）/ 自评分支 / cheap↔strong 升级 + 指标
- `mcpserver/McpServerServiceTest` — MCP Server（9 case）：`tools/list` 形状 / `tools/call` 分派 / 工具名冲突 / 错误映射（桩工具）
- `ai/agent/actions/CodeExecActionTest` — Code Interpreter（9 case）：算术求值 / 输出截断 / 墙钟超时 / 编译错误回文本 / 危险 API 拦截 / 禁用路径（真跑 JShell、不联网）
- `rag/multimodal/MultimodalEmbeddingTest` — 原生多模态 embedding（5 case）：图片字节→向量维度 / `type=image` metadata / text→image 检索（桩 HTTP）

> 这份清单是骨干视图；`find src/test -name "*Test.java"` 取当前全集（52 个）。新增功能模块通常会带确定性单测，列表可能滞后于实际。

没有 lint / formatter / 覆盖率插件配置（pom 里只有 `spring-boot-maven-plugin`）。**LLM 行为回归靠 eval harness（见末尾「评测 Harness」节），不是 JUnit** —— JUnit 只覆盖确定性的纯逻辑，凡是要连模型的断言都走 `/eval/run`。

## 运行

前置条件：

1. 本机起 Ollama，并拉模型：

   ```bash
   ollama pull llama3.1
   ollama pull nomic-embed-text
   ```

2. （如需 PGVector）起一个 pgvector 容器：

   ```bash
   docker run -d --name pgvector -p 5432:5432 \
     -e POSTGRES_PASSWORD=postgres pgvector/pgvector:pg16
   ```

启动应用：

```bash
mvn spring-boot:run
```

切换 LLM Provider（`app.llm.provider` 可选 `ollama | openai | anthropic | gemini | deepseek`，默认 `ollama`）：

| provider | 依赖的环境变量 | 默认模型 | 备注 |
| --- | --- | --- | --- |
| `ollama`（默认） | 无 | `llama3.1` | 本地，零成本；需先 `ollama pull <model>` |
| `openai` | `OPENAI_API_KEY` | `gpt-4o-mini` | 走官方端点；如需代理填 `app.llm.openai.base-url` |
| `anthropic` | `ANTHROPIC_API_KEY` | `claude-haiku-4-5` | 可改 `claude-sonnet-4-6` / `claude-opus-4-7`；prompt caching 默认开（`app.llm.anthropic.cache-system-messages` / `cache-tools`）—— 长 system prompt + 工具 schema 命中缓存后输入 token 按 cache-read 计费（约 1 折），`MetricsChatModelListener` 打 `cache_read`/`cache_write` tag 量化命中率 |
| `gemini` | `GOOGLE_AI_GEMINI_API_KEY` | `gemini-2.0-flash` | Google AI Studio key |
| `deepseek` | `DEEPSEEK_API_KEY` | `deepseek-chat` | OpenAI 兼容协议，已预设 `base-url=https://api.deepseek.com/v1`；可换 `deepseek-reasoner`(R1) |
| `vllm` | `VLLM_MODEL`（必填）/ `VLLM_API_KEY`（vLLM 默认不校验，默 `EMPTY`） | 留空，必须显式设 | **生产推荐**。OpenAI 兼容协议，默认 K8s service DNS（`http://vllm-chat.default.svc.cluster.local:8000/v1`）。复用 `OpenAiChatModel`，零新依赖 |

```bash
# OpenAI
OPENAI_API_KEY=sk-... mvn spring-boot:run -Dspring-boot.run.arguments=--app.llm.provider=openai

# Claude
ANTHROPIC_API_KEY=sk-ant-... mvn spring-boot:run -Dspring-boot.run.arguments=--app.llm.provider=anthropic

# Gemini
GOOGLE_AI_GEMINI_API_KEY=... mvn spring-boot:run -Dspring-boot.run.arguments=--app.llm.provider=gemini

# DeepSeek
DEEPSEEK_API_KEY=sk-... mvn spring-boot:run -Dspring-boot.run.arguments=--app.llm.provider=deepseek
```

注意：

- **多参数覆盖优先用环境变量，别堆逗号**：`-Dspring-boot.run.arguments=--a=x,--b=y` 实测有过"只有第一个参数生效、第二个被静默丢弃"的情况（NL2SQL 落地时 `--app.nl2sql.enabled=true,--app.llm.ollama.model-name=qwen3:14b` 第二个没生效，排查良久）。要同时覆盖多个 key 时改用 env var（relaxed binding 稳）：`APP_NL2SQL_ENABLED=true APP_LLM_OLLAMA_MODEL_NAME=qwen3:14b mvn spring-boot:run`。单参数用 `-Dspring-boot.run.arguments` 没问题。
- **EmbeddingModel 由独立开关装配**（`app.embedding.provider`），跟 chat provider 完全解耦 —— 见下面 "切换 Embedding Provider" 节。切换 chat 不影响 RAG 已入库的向量。
- `application.yml` 里**不要**再添加 `langchain4j.ollama.chat-model` / `langchain4j.<provider>.chat-model` 块，否则 LangChain4j starter 会和 `LlmConfig` 各创建一个 `ChatModel` Bean → 启动冲突。所有 chat/streaming 配置都走 `app.llm.<provider>.*`。
- Tool calling 在 Gemini / DeepSeek-V3 上行为略有差异；`@AiService` 接口代码无需改。

切换 Embedding Provider（`app.embedding.provider` 可选 `ollama | openai-compat`，默认 `ollama`）：

| provider | 用途 | 配置块 |
| --- | --- | --- |
| `ollama`（默认） | 本地开发 / 小规模 | `app.embedding.ollama.{base-url, model-name, timeout}`；默认 `nomic-embed-text`（768 维） |
| `openai-compat` | **生产推荐**：vLLM 跑 embed / TEI / 云 OpenAI | `app.embedding.openai-compat.{base-url, api-key, model-name, timeout}`；推荐 `BAAI/bge-m3`（1024 维多语言） |

**重要：换 embedding = 换向量维度 = 必须重建持久化向量库**。`nomic-embed-text`(768) → `bge-m3`(1024) 切换前要：

1. drop 已有的 PGVector 表 / Milvus 集合 / Chroma collection / Doris 表
2. 重启应用，让 starter 按新维度建表
3. 重新 `POST /rag/ingest` 入库

InMemoryEmbeddingStore 重启即丢，无所谓。

vLLM 生产示例（同集群 chat + embedding 两个 deployment）：

```bash
# chat
kubectl run vllm-chat --image=vllm/vllm-openai:latest -- \
  --model meta-llama/Llama-3.1-8B-Instruct --port 8000
# embedding
kubectl run vllm-embed --image=vllm/vllm-openai:latest -- \
  --model BAAI/bge-m3 --task embed --port 8000

# 应用 yml 设
# app.llm.provider=vllm
# app.llm.vllm.base-url=http://vllm-chat.default.svc.cluster.local:8000/v1
# app.llm.vllm.model-name=meta-llama/Llama-3.1-8B-Instruct
# app.embedding.provider=openai-compat
# app.embedding.openai-compat.base-url=http://vllm-embed.default.svc.cluster.local:8000/v1
# app.embedding.openai-compat.model-name=BAAI/bge-m3
```

切换向量库（`app.rag.store` 可选 `in-memory | pgvector | milvus | chroma | qdrant | doris`）：

```bash
# in-memory（默认）
mvn spring-boot:run

# pgvector — docker run -d -p 5432:5432 -e POSTGRES_PASSWORD=postgres pgvector/pgvector:pg16
mvn spring-boot:run -Dspring-boot.run.arguments=--app.rag.store=pgvector

# milvus — docker run -d -p 19530:19530 milvusdb/milvus:v2.4.10 milvus run standalone
mvn spring-boot:run -Dspring-boot.run.arguments=--app.rag.store=milvus

# chroma — docker run -d -p 8000:8000 chromadb/chroma
mvn spring-boot:run -Dspring-boot.run.arguments=--app.rag.store=chroma

# qdrant — docker run -d -p 6333:6333 -p 6334:6334 qdrant/qdrant
mvn spring-boot:run -Dspring-boot.run.arguments=--app.rag.store=qdrant

# doris — 需 Doris 3.0+，建库 demo，启动后会自动建表（含 HNSW ANN 索引）
mvn spring-boot:run -Dspring-boot.run.arguments=--app.rag.store=doris
```

各家配置块在 `application.yml` 的 `app.rag.{pgvector|milvus|doris}` 下；维度由 `EmbeddingModel.dimension()` 自动取，换 embedding 模型时只需保证已有表/集合的维度一致（否则先 drop 重建）。

## REST 接口

| 方法 | 路径            | 说明 |
| ---- | --------------- | ---- |
| POST | `/chat`         | 普通对话；query 参数 `chatId` 区分会话，body `{"message":"..."}` |
| POST | `/chat/stream`  | SSE 流式响应，逐 token 推送，结束时 `event: done` |
| POST | `/chat/category`| RAG 检索只限定到 `category=xxx` 的文档；query `chatId` + `category`，body 同 `/chat` |
| POST | `/rag/ingest`   | 把 `./documents` 下文档分片入向量库；可选 query `category=xxx` 给所有文档打标签 |
| POST | `/extract/ticket` | 结构化输出示例：从 `{"text":"..."}` 提取 `Ticket(title, priority, category, summary, nextSteps)` POJO |
| POST | `/chat/reflexive` | Reflexion 自反思：body `{"message":"..."}`，返回最终答案 + 每轮 critique 的 trace |
| POST | `/chat/reflexive/stream` | SSE 流式反思：按阶段 emit `attempt-start` / `answer-token` / `critique` / `done` |
| POST | `/chat/multi-agent` | Planner 拆 → 多 Worker 并行 → Synthesizer 汇总；返回 plan + 每个 worker 输出 + final |
| POST | `/chat/multi-agent/stream` | SSE 流式 multi-agent：按阶段 emit `plan` / `worker-result` / `synthesis-token` / `done`。**Synthesizer 那 10-20s 一次性等变成 token-by-token 立刻看** |
| POST | `/chat/mcp` | 由 MCP server 的工具驱动的对话（需 `app.mcp.enabled=true`） |
| POST | `/chat/auto` | LLM-as-router：classifier 分类成 RAG/TOOL/CHAT 分别走 Assistant 或 BareAssistant（需 `app.query-router.enabled=true`）；返回 `{decision, reply, classifyMs, answerMs}` |
| POST | `/chat/chain` | Prompt Chaining（Anthropic workflow 模式，需 `app.chaining.enabled=true`）：body `{"input":"..."}` → 按 `app.chaining.steps` 预定义顺序链逐步处理、步间确定性 gate 短路，返回 `{input, steps[], finalOutput, completed}`。见 `docs/workflow-patterns.md` |
| POST | `/chat/vote` | Voting（Anthropic Parallelization/Voting，需 `app.voting.enabled=true`）：body `{"question":"...","n":5}` → 同一问题并行跑 N 次 + 聚合（majority 多数表决 / synthesis 聚合器收口），返回 `{question, votes[], strategy, decision, agreement, confident}`。见 `docs/workflow-patterns.md` |
| POST | `/chat/cascade` | Model Cascade / 成本路由（需 `app.llm.cascade.enabled=true`）：body `{"message":"..."}` → 便宜模型先答、低置信才升级强模型。返回 `{question, answer, served, cheapConfident}`。走 `/chat` 同款鉴权链。见 `docs/model-cascade.md` |
| POST | `/chat/sql` | NL2SQL / ChatBI：自然语言 → 只读 SELECT → 执行 → 解读（需 `app.nl2sql.enabled=true`）；返回 `{question, sql, rowCount, rows, answer, guardBlocked}`。需 tool-calling 模型。见 `docs/nl2sql.md` |
| POST | `/workflow/refund/start` | 退款审批工作流：抽工单 → 高优先级人工审批 → 通过/驳回 → 答复（需 `app.workflow.enabled=true` + MySQL）；body 可选 `dedupeId`（按 `tenant:chatId:dedupeId` 幂等去重）/ `webhookUrl`（终态经 outbox 可靠回推）；返回 `{instanceId, status, reply, taskId, priority, deduplicated}`。见 `docs/workflow-integration.md` |
| GET  | `/workflow/tasks` | 本租户待审任务列表（需 `SCOPE_approve`），含 `assignee` |
| POST | `/workflow/tasks/{taskId}/claim` `/unclaim` | 认领 / 取消认领任务（需 `SCOPE_approve`）；已被他人领 → 409 |
| POST | `/workflow/tasks/{taskId}/complete` | 完成审批 body `{approved, comment}`（需 `SCOPE_approve`）→ 同步跑 resolve/reject → 返回 `{reply}`；并发双重审批 → 409 |
| GET  | `/workflow/instances/{instanceId}` | 工作流实例状态 + reply |
| DELETE | `/workflow/data?chatId=` | PII 合规删除：清本租户该 chatId 的运行/历史实例 + reply + outbox（需 `SCOPE_approve`） |
| POST | `/channel/feishu/event` | 飞书事件订阅 / 卡片回调入口（需 `app.channel.feishu.enabled=true`）：URL 握手回 challenge + 消息事件（意图路由：退款/投诉→工作流，其余→对话）+ 审批卡片回调。安全链放行（飞书自带验签，不带 X-Api-Key）。见 `docs/workflow-integration.md`「渠道（飞书）」 |
| POST | `/voice/chat` | 语音客服（需 `app.voice.enabled=true`）：multipart `audio` + 可选 `chatId` → 音频经 ASR → 客服大脑（退款/投诉→工作流，其余→对话）→ TTS。返回 `{transcript, reply, route, audioContentType, audioBase64}`。走 `/chat` 同款鉴权链。见 `docs/voice-agent.md` |
| POST | `/voice/chat/stream` | 语音 SSE 半流式（需 `app.voice.enabled=true`）：multipart `audio` → 整段 ASR → 流式生成 → 分句 TTS。SSE 先 `transcript` 事件，再逐句 `audio-chunk`（`{text,audioContentType,audioBase64}`），末 `done`。边生成边播；只走对话。见 `docs/voice-agent.md` |
| POST | `/voice/transcribe` | 仅 ASR（调试）：multipart `audio` → `{transcript}`（需 `app.voice.enabled=true`） |
| POST | `/chat/vision` | 视觉对话（需 `app.vision.enabled=true`）：multipart `image` + 可选 `message` → 看图直接作答、单轮不入库。返回 `{reply}`。走 `/chat` 同款鉴权链。见 `docs/multimodal.md` |
| POST | `/rag/image` | 原生多模态入库（需 `app.rag.multimodal-embedding.enabled=true`）：multipart `image` → CLIP/jina-clip 直接 embed 进跨模态向量空间入库（metadata `type=image`）。走 `/chat` 同款鉴权链。见 `docs/multimodal-embedding.md` |
| POST | `/rag/image-search` | 文本搜图（需 `app.rag.multimodal-embedding.enabled=true`）：body `{"query":"红色跑车","topK":5}` → text→image 跨模态检索。见 `docs/multimodal-embedding.md` |
| POST | `/agent/run` | 深度 Agent（需 `app.deep-agent.enabled=true`）：body `{"goal":"..."}` → 开放式 plan→act→observe 循环，返回 `{goal, steps[], finalAnswer, stopReason, depth}`。走 `/chat` 同款鉴权链。见 `docs/deep-agent.md` |
| POST | `/agent/run/async` | 深度 Agent 异步版（需 `app.deep-agent.enabled=true`）：body `{"goal":"...","webhookUrl"?}` → 立即返回 `AsyncTask`，循环投后台。结果走 `GET /tasks/{id}` 轮询 / `/tasks/{id}/stream` SSE / webhook 回推（复用 `async` 引擎）。长目标用 |
| POST | `/chat/memory` | 记忆增强对话（需 `app.memory.profile.enabled=true`）：chat 前召回该用户跨会话长期记忆注入、chat 后异步更新画像。query `chatId` + body `{message}` → `{reply}`。见 `docs/long-term-memory.md` |
| GET/DELETE | `/memory/profile` | 查看 / 清空（PII 合规）当前用户的长期记忆（需 `app.memory.profile.enabled=true`） |
| POST | `/a2a` | A2A（Agent2Agent）JSON-RPC 2.0 单端点（需 `app.a2a.enabled=true`）：`message/send`（chat 同步 / multi-agent 异步建 Task）、`message/stream`（chat SSE 流式）、`tasks/get`·`tasks/cancel`、`tasks/pushNotificationConfig/set`·`/get`。需 `X-Api-Key`。见 `docs/a2a.md` |
| GET  | `/.well-known/agent-card.json` | A2A 服务发现：Agent Card（skills / endpoint / capabilities / securitySchemes）。安全链放行，免鉴权 |
| POST | `/mcp/server` | MCP Server（反向，需 `app.mcp.server.enabled=true`）：JSON-RPC 2.0 over HTTP 把本 app 能力暴露给外部 MCP 客户端（Claude Desktop / Cursor）——`initialize` / `tools/list` / `tools/call`，工具含 `current_datetime` / `rag_search` / `nl2sql_query`（后者双开软依赖）。需 `X-Api-Key` 走租户隔离。见 `docs/mcp-server.md` |
| POST | `/eval/run?runs=N&set=default` | 跑黄金集，每 case 跑 N 次（默认 1）；`set` 选集：`default`(`eval-cases.json`) / `sql` / `a2a` / `workflow` / `graph` / `agent`（`sql`/`a2a`/`workflow`/`agent` 需先开对应 profile，`graph` 需 `app.rag.graph.enabled`+`auto-ingest`）；返回 per-case avg/σ/passRate + 整体 |
| POST | `/eval/retrieval?set=default&ingest=false` | **检索质量评测（不经 LLM）**：跑黄金集每个 query 的向量召回，算 Recall@k/Precision@k/MRR/Hit@k；`ingest=true` 先入库一次。跟 `/eval/run` 的 passRate 互补——这条只量检索器。见 `docs/retrieval-eval.md` |
| POST | `/eval/run-cases?runs=N` | body 传 `EvalCase[]` 跑临时集（N 同上） |
| POST | `/eval/gate?runs=N&set=default` | CI 门禁：跑指定集 → 对照 `resources/eval/baseline[-set].json`；有回归返 **HTTP 422** + regressions 明细，无回归 200。配 `scripts/eval-gate.sh` |
| POST | `/eval/baseline?runs=N&set=default&slack=0.1` | 从一次实测 run 生成基线（观测值−slack），返回 `Baseline` JSON 供落盘提交（首次建基线 / 重置合格线用，建议 runs≥3） |
| GET  | `/actuator/health` | Spring Boot Actuator |
| GET  | `/actuator/metrics/gen_ai.client.token.usage` | LLM token 用量（Micrometer） |
| GET  | `/actuator/prometheus` | Prometheus scrape 端点 |
| GET  | `/actuator/cost` | per-tenant 当日累计 USD 成本快照（需 `app.cost.enabled=true`）；配 `/actuator/tokenbudget`（token 用量）看烧了多少钱。见 `docs/cost-attribution.md` |
| GET  | `/health`       | 健康检查 |

示例：

```bash
# 触发工具调用
curl -X POST 'localhost:8080/chat?chatId=u1' \
  -H 'Content-Type: application/json' \
  -d '{"message":"现在几点？时区 Asia/Shanghai"}'

# 流式
curl -N -X POST 'localhost:8080/chat/stream?chatId=u1' \
  -H 'Content-Type: application/json' \
  -d '{"message":"用三句话介绍 LangChain4j"}'

# RAG 入库 → 提问
curl -X POST localhost:8080/rag/ingest
curl -X POST 'localhost:8080/chat?chatId=u1' \
  -H 'Content-Type: application/json' \
  -d '{"message":"根据文档回答 ..."}'

# 动态 filter：先按 category 入库，再按 category 提问
curl -X POST 'localhost:8080/rag/ingest?category=manual'
curl -X POST 'localhost:8080/chat/category?chatId=u1&category=manual' \
  -H 'Content-Type: application/json' \
  -d '{"message":"使用手册里有讲到 X 吗？"}'
```

## 约定与扩展点

- **加新工具**：在 `ai/tools/` 下新建 `@Component` 类，方法标 `@Tool("自然语言描述")`。无需改 `Assistant`，自动被发现。**重要**：`@Tool` 描述就是模型决定"何时调用"的唯一依据，要写清楚：用途 / 使用时机 / 不该用的时机 / 参数语义（参考 `DateTimeTool.java`）。参数也建议加 `@P("描述")`。短描述（"Returns current time"）在小模型上经常导致漏调。
- **加新 AI 行为**：在 `Assistant` 接口中加方法即可，可用 `@SystemMessage` / `@UserMessage` / `@MemoryId`；返回类型可以是 `String`、`TokenStream`、自定义结构化 POJO（Structured Output）。
- **换 LLM 提供方**：改 `app.llm.provider` 即可（`ollama|openai|anthropic|gemini|deepseek`），相应的 API key 走环境变量。要接新家（Mistral / Bedrock / 通义 / 智谱 等），在 `LlmConfig` 里加一个 `case` 分支 + 一个 `*Props` 内部类，再在 `application.yml` 加 `app.llm.<新 provider>.*` 配置块；`@AiService` / `Assistant` 代码无需改。
- **换向量库**：在 `EmbeddingStoreConfig` 加新的 `@ConditionalOnProperty` 分支即可（Chroma / Redis / Qdrant / Weaviate 等都有对应 `langchain4j-*` 模块）。Doris 没有官方模块，参考 `DorisEmbeddingStore` 的写法（JDBC + Doris ANN 函数）。
- **embedding 维度**：PGVector 的 `dimension` 从 `EmbeddingModel.dimension()` 自动取，换 embedding 模型时不要改代码，但要注意已有表的维度匹配（或重建表）。

## 注意事项

- `InMemoryEmbeddingStore` 重启即丢，仅适合本地开发。
- `ChatMemoryProvider` 当前是进程内存，多实例部署需要换成持久化实现（如基于 Redis 的 `ChatMemoryStore`）。
- 默认 `MessageWindowChatMemory.withMaxMessages(20)`，长会话需考虑 token 预算或换成 `TokenWindowChatMemory`。
- `DorisEmbeddingStore` 是社区/自实现版本，已支持 add / search / remove **和 metadata filter**（`DorisFilterTranslator` 把 `IsEqualTo` / `IsIn` / `And` / `Or` / `Not` 等翻译成 `get_json_string(metadata,'$.key') = ?` 形式的 SQL；JSON key 用 `[A-Za-z0-9_.-]+` 白名单校验防注入）。`addAll` 已改为**单连接 + 单条多行 INSERT**批量入库（不再每行一条 INSERT/一个新连接）。生产更大规模仍可上 Stream Load + 连接池。
- **HTTP client 冲突**：classpath 里同时有 `langchain4j-http-client-spring-restclient` 和 `langchain4j-http-client-jdk` 两个 SPI 实现，LangChain4j 会抛 `Conflict: multiple HTTP clients found`。`LangChain4jApplication.main()` 里用 `System.setProperty("langchain4j.http.clientBuilderFactory", "dev.langchain4j.http.client.jdk.JdkHttpClientBuilderFactory")` 显式锁定 JDK 实现。要换回 Spring RestClient 改这一行即可，不要删。
- **Ollama starter 与 Spring Boot 3.3.5 不兼容**：`langchain4j-ollama-spring-boot-starter` 1.13.x 的 `OllamaEmbeddingModel` 自动装配引用了 Spring Boot 3.4+ 才有的 `org.springframework.boot.http.client.ClientHttpRequestFactorySettings`，3.3.5 下会 `NoClassDefFoundError`。因此 yml 里**不要**配 `langchain4j.ollama.embedding-model.*`（也不要配 `chat-model` / `streaming-chat-model`），所有 Ollama Bean 都在 `LlmConfig` 里手动 `OllamaChatModel.builder()` / `OllamaEmbeddingModel.builder()` 构建。升级 Spring Boot 到 3.4+ 后可以考虑回到 starter 自动装配，但目前自管比较省事。
- **Guardrail 必须靠自定义 SPI 才能注入依赖**（**别删 `SpringClassInstanceFactory`**）：LangChain4j（1.13.x）实例化 `@InputGuardrails(X.class)` / `@OutputGuardrails(Y.class)` 引用的类时，走 `ClassInstanceLoader` → 默认**反射调无参构造**，spring-boot-starter **并不会** `getBean()`。本项目的 guardrail（`PromptInjectionGuardrail` 需 `PromptInjectionDetector`、`PiiGuardrail` 需 `AuditLogger`）只有带参构造，默认路径会抛 `NoSuchMethodException` 把整条 `Assistant.chat` 打挂（连带 `/chat`、`/chat/category` 和 eval 全废）。解法：`config/SpringClassInstanceFactory` 实现 `dev.langchain4j.spi.classloading.ClassInstanceFactory` SPI，注册在 `resources/META-INF/services/dev.langchain4j.spi.classloading.ClassInstanceFactory`，优先从 Spring 容器取 bean、取不到再回退反射；context 通过 `SpringContextHolder`（`ApplicationContextAware` 静态持有）拿。这一层对所有 LC4j class 实例化生效，对非 bean 类无回归。

## Prompt 工程

主 `Assistant` 的 system prompt 拆成 5 段（# Role / # Language & Style / # Tool Use / # Citation / # Safety）+ 1 段灰度位（# Extra），定义在 `ai/Assistant.java` 的 `SYSTEM_PROMPT` 常量里。其中 4 个段落（语言、语气、引用策略、灰度指令）用 `{{var}}` 占位，由 `AssistantProperties`（`app.assistant.*`）提供默认值，`ChatController` 每次调用时透传 —— **改 prompt 不用动 Java**。

`app.assistant.*` 配置：

| key | 默认 | 作用 |
| --- | --- | --- |
| `language` | `中文` | 回答语言 |
| `tone` | `简洁，1–2 句话答完，必要时再展开` | 语气与详尽度 |
| `citation-policy` | `用 [doc=文件名#片段号] 标注，没检索到就说"资料里没提到"` | RAG 引用规范 |
| `extra` | `""` | 灰度/A-B 试新指令的位置，例如 `"本轮请用 markdown 列表组织答案"` |
| `overrides.<provider>.{language,tone,citationPolicy,extra}` | 空 map | 按 provider 部分覆盖；null/缺失字段 fallback 到默认。启动时按 `app.llm.provider` 解析成 `ResolvedAssistantStyle` Bean |

实际调用：

```bash
# 改 yml 后重启即生效
curl -X POST 'localhost:8080/chat?chatId=u1' -H 'Content-Type: application/json' \
  -d '{"message":"用三句话介绍 LangChain4j"}'

# 临时 A/B：启动时覆盖 extra
mvn spring-boot:run -Dspring-boot.run.arguments=\
  '--app.assistant.extra=本轮请只回答关键事实，禁止使用形容词'
```

调 prompt 的工程化流程：

1. 在 `src/main/resources/eval/eval-cases.json` 准备覆盖典型场景的黄金集（happy + edge + 拒答 + 工具调用 + PII）
2. 改前先 `curl -X POST localhost:8080/eval/run` 拿 baseline `passRate / averageScore`
3. **每次只动一个变量**（`tone` / `citation-policy` / `extra` 之一，或换 provider）
4. 重跑 eval → 看分数 → 跌了就回滚

跨 provider 注意：DeepSeek 中文强但忽略长 system，Claude 偏好 XML 标签（`<context>...</context>`），Gemini tool-calling 触发不积极，Ollama 小模型要更明确的指令。**这种差异已通过 `app.assistant.overrides.<provider>.*` 支持** —— 启动时按当前 `app.llm.provider` 解析出 `ResolvedAssistantStyle`，所有调用方注入这个 Bean（不再直接用 `AssistantProperties`）。换 provider = 重启。

其他 AiService 的 prompt 现状：

- `Critic`：3 维评分 + 锚点 + mainIssue 契约（见下 Reflexion 节）
- `Planner`、`Extractor`：已内置 3 例 few-shot + 反例，锚定常见失败（over-decompose / priority 通胀 / 拆错维度）。要扩例子直接改 `@SystemMessage` 字符串即可
- `Synthesizer` / `Judge`：仍是硬编码简短 prompt，需要细化时按上面套路改

**Few-shot 经验**：少而精比多而泛好。3 个例子覆盖（典型 / 边界 / 反例）的"打地基"模式比堆 10 个例子稳。例子要展示**判断**（priority 该选哪个、什么时候不拆任务），不要只展示**格式**——格式已经被 `@Description` + Structured Output 约束住了。

## 记忆与 Reranking

**ChatMemoryStore** `app.memory.store`:

- `in-memory`（默认）— `InMemoryChatMemoryStore`，重启即丢
- `redis` — `RedisChatMemoryStore`，按 `chat:mem:<chatId>` 存 JSON、带 TTL；需要 Redis 可用，配置在 `spring.data.redis.*`

**ChatMemory 滑窗** `app.memory.window-mode`:

- `messages`（默认）— `MessageWindowChatMemory`，保留最近 `max-messages` 条
- `tokens` — `TokenWindowChatMemory`，保留最近 `max-tokens` 个 token；用 `OpenAiTokenCountEstimator(tokenizer-model)` 近似计数（Ollama 没自带 tokenizer，OpenAI 估算偏差通常 10–15%）
- `summary` — 自定义 `SummarizingChatMemory`：超过 `max-messages` 时，把旧消息（保留最近 `summary.keep-recent` 条之外的）用 LLM 压缩成单条 `SystemMessage`；每次压缩一次额外 LLM 调用。摘要器走 **temp=0 专用模型**（`buildJudgeChatModel`，压缩是确定性任务，避免每次压出不同摘要导致记忆漂移）。压缩**默认异步**（`app.memory.summary.async`，默 true）：`add()` 只追加立即返回、压缩投后台 daemon 线程池，不阻塞请求；快照锁内取、LLM 调用锁外、合并回锁内，期间新 add 的尾部消息保留，单飞防堆叠，失败不丢消息。`add()` 用 per-id 锁串行化 RMW 防并发丢更新（限单 JVM，多副本 + Redis 需 Redis 层锁）。触发除消息条数外还支持 **token 预算**（`app.memory.summary.max-tokens`>0 时估算 token 超阈也压，治少量超大消息）；摘要有**膨胀上限** `app.memory.summary.max-summary-chars`（默 2000，截断兜底防多轮累积越滚越大）

**Reranking** `app.rag.rerank.enabled` + `type`:

- `enabled=false`（默认）— 向量检索直接取 `app.rag.top-k` 条返回
- `enabled=true, type=llm`（默认 type）— `OllamaLlmScoringModel`：用 ChatModel 给每对 (query, doc) 打 0–1 分，零外部依赖但慢（N 次 LLM 调用）
- `enabled=true, type=jina` — `JinaScoringModel`：云 API，多语言（默认 `jina-reranker-v2-base-multilingual`），快且准；需 `JINA_API_KEY` 环境变量
- 其他可选：`CohereScoringModel`、`langchain4j-onnx-scoring-*`（本地 ONNX 重排，速度好但要下载模型）

**Hybrid Retrieval（通用）** `app.rag.hybrid.enabled` + `tokenizer`:

- `enabled=false`（默认）— 只走向量检索
- `enabled=true` — 同时跑向量检索 + `KeywordContentRetriever`（token-overlap），`DefaultQueryRouter` 路由两路，`DefaultContentAggregator` 用 RRF 融合
- `tokenizer=simple`（默认）— 按字 + 标点切，零依赖，中文召回粗糙
- `tokenizer=hanlp` — HanLP portable，自带词典 + 停用词，中文召回明显更好（推荐中文场景开启）
- 与 `rerank` 可同时开：fan-out 召回多路 → ReRanker 收口
- 需要 `DocumentMirror`（内存镜像）保存被切片后的 segments；超大语料请换 Lucene/ES
- 切换示例：

  ```bash
  mvn spring-boot:run -Dspring-boot.run.arguments=\
    "--app.memory.store=redis,--app.rag.rerank.enabled=true,--app.rag.rerank.candidate-size=20"
  ```

**Reflexion** `app.reflexion.*`:

- Critic 输出结构化 3 维评分：`correctness`（事实准确）/ `completeness`（答全）/ `clarity`（清晰），每维 0.0–1.0，加上一句 `mainIssue`（最该改的点）。维度 + 锚点定义在 `Critic.java` 的 `@SystemMessage` 里，改锚点 ≈ 改打分标准。
- `threshold`（默认 0.75）— **加权聚合分**低于此值触发改进；越高越严
- `weights.{correctness,completeness,clarity}`（默认 0.4/0.4/0.2）— 加权权重，不必归一化（用总和当分母）。比如想强压幻觉就把 correctness 调到 0.6；做面向终端用户的对话场景可以把 clarity 提到 0.4
- `max-attempts`（默认 2）— 最多额外改进多少次（首次生成不计入）
- 改进环节会把 3 维分数 + `mainIssue` 一起喂给 `Answerer.improve` —— 比传单一 feedback 字符串更可执行；`Attempt` 记录里也保留每轮 4 个字段，调试时能看清楚为什么阈值过了或没过
- 调用 `/chat/reflexive` 时不共享主 Assistant 的 ChatMemory 与 RAG，是为了让反思迭代纯粹、可重复。要在反思里用 RAG，自己改 `Answerer` 走 `AiServices.builder().contentRetriever(...)`；同时把 `clarity` 改名为 `groundedness` / `citation` 并调权重。

**PGVector Hybrid 检索** `app.rag.pgvector.search-mode`:

- `VECTOR`（默认）— 纯向量
- `HYBRID` — PGVector 原生向量 + `tsvector` 全文 RRF 融合（`rrf-k` 默认 60，`text-search-config` 默认 `simple`，中文建议改 `chinese` 或外部分词后再入库）

**Chunking 策略** `app.rag.chunking.*`:

- `strategy=recursive`（默认）— `DocumentSplitters.recursive(max-size, overlap)`，按 unit 硬切 + overlap，简单粗暴适合任何文档
- `strategy=markdown-header` — `MarkdownHeaderSplitter` 自实现，每个 chunk 是完整主题；超长 section fallback 到 recursive。三处增强（2026-06-17）：① **自适应标题层级** —— 有 `##+` 按它切（历史行为），否则退到 `#`（纯 H1 文档不再一刀不切成巨块），都没有才整篇 1 段；② **极小 section 合并** `app.rag.chunking.min-section-size`（yml 默认 120 / 代码默认 0=关）—— 连续不足阈值的碎块向后并块，大 section 各自独立；③ **breadcrumb metadata** —— `###` chunk 带上父级路径（`Top > Sub > SubSub`，仅深度>1 注入），`section` 叶子标题不变
- `strategy=parent-child` — `ParentChildSplitter` 自实现，**small-to-big**：child 用 `max-size`/`overlap` 切小块去 embed（召回精准），parent 用 `app.rag.chunking.parent.{strategy,size,overlap}` 切大块（`strategy` 可选 recursive / markdown-header）。检索命中 child 后 `TaggedSourceContentInjector` 按 metadata 的 `parent_text` 换成所属 parent 全文喂模型（上下文完整），多个 child 命中同一 parent 按 `parent_id` 去重、共享 `[doc=file#parentId]` 引用（与 grounding/citation 闭环对齐）。parent 全文随 child 冗余存进 metadata（零新存储 / 重启安全 / 6 后端一致，代价 store 膨胀）。单测 `ParentChildSplitterTest` + `TaggedSourceContentInjectorTest`
- `strategy=semantic` — `SemanticChunkingSplitter` 自实现，按**主题连续性**切：逐句 embed（每句拼前后各 `semantic.buffer-size` 句成窗口平滑噪声）→ 算相邻句 cosine 距离 → 距离超 `semantic.breakpoint-percentile`（默 95）分位的间隙处下刀 → 超 `semantic.max-size` 的语义块 fallback recursive、不足 `semantic.min-size` 的碎块并块。适合无标题结构的长文（纪要/访谈/论文正文）。**复用主 `EmbeddingModel`**（`DocumentSplitterFactory` 注入），代价是入库每句多一次 embed；embedding 后端故障自动降级 recursive（不让入库崩）。单测 `SemanticChunkingSplitterTest`（桩 embedding 令距离可预测）
- `unit=chars`（默认）| `tokens` — **计量单位开关**（`DocumentSplitterFactory`）。`chars` 按字符数，零依赖；`tokens` 给 splitter 挂 `OpenAiTokenCountEstimator`（tiktoken），用 `DocumentSplitters.recursive(size, overlap, estimator)` 三参重载，`max-size`/`overlap` 单位变 token，`MarkdownHeaderSplitter` 的 section 阈值也透传同一 estimator（按 token 计量，不再 char/token 混用）。本地模型（Ollama/bge-m3）不暴露 tokenizer，用 OpenAI 估算（偏差 ~10-15%，chunk 软目标可接受）。**token 模式必须保证 `max-size + overlap ≤ embedding 模型 max input`，否则尾部静默截断**。复用了 `ChatMemoryConfig` 里 `TokenWindowChatMemory` 同款 estimator 思路
- `max-size: 300`（兼容旧 key `max-chars`，`max-size` 优先、缺省回退 `max-chars`）— recursive 模式 chunk 大小目标 / markdown-header section 阈值；单位由 `unit` 决定
- `overlap: 50` — recursive 模式 chunk 重叠（markdown-header 只在 fallback 时用到）
- `min-section-size: 120`（yml 默认 / 代码默认 0=关）— markdown-header 极小 section 合并阈值，单位随 `unit`（tokens 模式建议调小到 ~30）；仅对 markdown-header 生效
- `tokenizer-model: gpt-4o-mini` — `tokens` 模式计数用的 tokenizer；`chars` 模式忽略
- `parent.{strategy,size,overlap}`（默 recursive/1200/0）— **parent-child 专用**：parent（喂上下文的大块）切法与窗口；仅 `strategy=parent-child` 生效
- `semantic.{buffer-size,breakpoint-percentile,max-size,min-size}`（默 1/95/1000/0）— **semantic 专用**：句邻居缓冲 / 断点分位 / 块大小上下限；仅 `strategy=semantic` 生效
- markdown-header 给 segment 加 metadata：`section` 标题 + `index` 顺序号，引用 `[doc=file.md#3]` 对应"第 3 个 section"而不是"第 3 个块"
- 实测对本项目（5 个 chat provider 列在 1 个 `## Section` 里）的可见提升：recursive(300) 召回不全只列 2 个 provider，markdown-header(600) 召回完整 5 个

**Contextual Retrieval（Anthropic）** `app.rag.contextual.*`（默认关，与 chunking 策略**正交**——任何 strategy 都可叠加）：

- `enabled=true` 时入库链在「切分后、embed 前」插一道改写：`ChunkContextualizer`（temp=0 AiService，`buildJudgeChatModel`，**不注册 ChatModel Bean**）给每个 chunk 生成一句「安放回全文」的上下文（消解代词/缩写、点明位置），`ContextualEnricher` 拼到 chunk 前面再 embed → chunk 脱离全文后仍自洽、召回失败率显著降。跟 hybrid(BM25)/rerank 叠加效果更好（Anthropic 原文标配组合）
- 经 `ObjectProvider` 软依赖接入 `RagIngestionService`（批量，per-document 改写）+ `DocumentService`（单上传）；关闭时 Bean 不存在、入库链零回归
- `max-doc-chars: 8000`（喂上下文生成器的文档截断上限，控成本/上下文）/ `min-segments: 2`（单 chunk 文档跳过——整块即全文无歧义）。每 chunk 一次 LLM 调用（一次性入库成本，生产可叠 provider prompt caching 降本）；某块失败保留原文不前缀（不让入库崩）；**串行执行**保 `TenantContext`（token 正确计入租户配额，并行需 MDC 透传=未来项）。单测 `ContextualEnricherTest`

**切分质量指标** `app.rag.metrics.*`（`ChunkMetrics`，始终在线）：每次入库切分完成后打 Micrometer 指标（按 `strategy` tag）：`rag.chunk.size`（尺寸分布 `_count/_sum/_max`）/ `rag.chunk.{total,tiny,oversize}`（碎块·超大块比例）/ `rag.ingest.documents`。换策略/调 max-size 后切分质量可观测，不必人肉看召回。阈值 `tiny-chars`（默 50）/ `oversize-chars`（默 2000），按字符计量（零 tokenizer 依赖）。样例 PromQL 见 `docs/observability.md`。单测 `ChunkMetricsTest`

> 入库链顺带的一处修正：`RagIngestionService` 原本切一次喂 mirror、`EmbeddingStoreIngestor` 内部再切一次 —— 对纯文本切分无所谓，但 semantic 这种「切分阶段就要逐句 embed」的策略等于双倍 embedding 成本。已改成「切一次 → 直接 `embedAll`+`addAll`」，与 `DocumentService` 单上传路径口径一致。

**History-aware retrieval** `app.rag.history-aware.*`:

- `enabled=false`（默认）— 当前 query 直接喂 retriever，多轮对话中代词类 follow-up（"它", "那个"）会召回不到
- `enabled=true` — 用 `CompressingQueryTransformer` 拿 chat history 把当前 query 改写成 self-contained 再去检索
- 跟 query-expansion 自动 chain：两个都开时按 `compress → expand` 顺序（compress 先合并 history，expand 后扩变体；颠倒就毫无意义）
- 自实现 `ChainedQueryTransformer` 把两个 transformer 串成一个（LangChain4j 1.13 的 `RetrievalAugmentor` 只接单个 QueryTransformer）
- 代价：每条 query 多 1 次 LLM call 做 history 压缩
- **跟 expansion 一样，对本项目小 corpus + nomic-embed-text 收益不显著**：实测多轮场景 compressor 真跑了但召回结果跟 baseline 一样。大 corpus + 真多轮对话场景才价值显著

**Query Expansion** `app.rag.query-expansion.*`:

- `enabled=false`（默认）— 单 query 直接召回
- `enabled=true` + `n: 3` — 用 LLM 把 1 个 query 扩成 n 个变体（同义改写 / 加上下文 / 拆子问题），多路并行召回 + `DefaultContentAggregator` RRF 融合
- 走 LangChain4j 内置 `ExpandingQueryTransformer`，注入到 `DefaultRetrievalAugmentor.queryTransformer(...)`
- 跟 **rerank 互补**：expansion 提升召回（让相关 chunk 更可能被检索到），rerank 提升精度（已召回的候选里挑最相关）。生产场景两个叠加
- 代价：每条 query 多 1 次 LLM call 做扩展。**对小 corpus + 中文 embedding 收益有限**（实测 nomic-embed-text 对同义改写已经很包容）；大 corpus / 多语言 / 模糊 query 才显价值

**RAG 引用格式** `TaggedSourceContentInjector`:

- LangChain4j 内置 `DefaultContentInjector` 只把检索片段用换行拼起来，模型看不到来源 id，没法按格式引用。
- `TaggedSourceContentInjector` 把每个 Content 包成 `<source id="文件名#片段号">...</source>`，id 从 `TextSegment.metadata` 的 `file_name` 取，chunk 索引退到顺序号。
- `LangChain4jConfig.retrievalAugmentor` 是**始终构造**的（不再 conditional on rerank/hybrid），目的就是无条件挂这个 injector —— 不然没启 rerank 的默认路径就走 `DefaultContentInjector`，引用格式契约失效。
- 与 `app.assistant.citation-policy` 形成闭环：injector 给模型可引的 id，policy 告诉模型按 `[doc=ID]` 引用。任一缺失模型都不会输出格式化引用。
- `app.rag.min-score`（默认 0.3）控制 cosine 相似度阈值。0.6 之类的高阈值在中文 query 用 `nomic-embed-text` 时召回很低，eval 把这个钉出来过。

**RAG 事实幻觉事后校验（grounding）** `app.rag.grounding.*`:

- 解决的是**事实幻觉**（答案结构完美但内容不被检索资料支撑），跟 Schema（治结构幻觉）、状态机（治动作幻觉）正交。`enabled=false`（默认）时行为与历史完全一致。
- **仅作用于触发了检索的回答**（本轮没召回到 source 直接跳过，不烧 token）。两层叠加：
  - **Layer 0（零 LLM，确定性）** `GroundingService.fabricatedCitations`：答案里 `[doc=ID]` 引用的 id 必须在本轮检索集合里，否则判"编造引用"。靠 `RetrievedSourcesContext`（ThreadLocal，由 `TaggedSourceContentInjector` 注入时写入，仿 `CategoryContext` 套路）拿到检索到的 id。
  - **Layer 1（faithfulness）** `GroundednessChecker`：RAGAS 风格，把答案拆成原子断言逐条对照 `<source>` 判是否被支撑，`groundedScore = 被支撑数 / 总数`；诚实弃答/闲聊无事实断言记 1.0。走独立 temp=0 ChatModel（`LlmConfig.buildJudgeChatModel`，**不注册 ChatModel Bean**，跟 `Critic`/`Judge` 同思路），且 `@ConditionalOnProperty` 只在开启时才构造（关闭零开销）。
- **命中后处置由 `on-fail` 决定**（`app.rag.grounding.on-fail`，默 `warn`）：`warn`（末尾追加 `⚠️ 可信度提示：…。请以原始资料为准。` + WARN 日志，不改写，历史行为）/ `refuse`（用安全弃答话术替换整段，宁可不答）/ `regenerate`（带纠正指令重生成，最多 `max-regenerations` 次，仍不过阈降级为 warn 保住最佳尝试）。`regenerate` 让「验证→生成」真正闭环——需调用方走 `applyToFreshAnswer(Function)` 重载把纠正指令拼进 prompt（`/chat` 已接真正的纠正重生成；老 `Supplier` 签名仍可用，其 regenerate 退化为原样重跑）。流式路径无法重写/重生成，仅 warn。
- `threshold`（默认 0.7）— Layer 1 聚合分低于此值才 warn。
- 接线点：`ChatController./chat` 与 `CategoryChatService`（`/chat/category`）都包了一层 `GroundingService.applyToFreshAnswer(...)`。**`/chat/stream` 流式路径的 grounding 仍未挂**（受 `RetrievedSourcesContext` ThreadLocal 跨流式回调线程边界限制）；但流式 PII 后处理已补 —— 见下「流式后处理」。
- **诚实弃答跳过校验**：答案命中 `ABSTENTION_MARKERS`（"未在文档中找到"等，跟 `citationPolicy` 契约闭环）时直接放行——弃答无事实断言、无可幻觉。eval 的 `grounding-abstain-quiet` 钉出过这个：弱模型（qwen3:8b）的 Layer 1 checker 会把弃答误判成 `groundedScore=0.0` 触发假告警，故用确定性话术识别兜底，不依赖 checker 在弃答上稳定判 1.0。
- Layer 1 校验器异常被吞（降级跳过 Layer 1，不影响答案返回）。

## 多 Agent 协作 / Guardrails / 可观测性

**Multi-Agent** `/chat/multi-agent`：

- `Planner` 把问题拆 1–6 个子任务（结构化输出，内置 3 例 few-shot + 2 反例锚定粒度 + 1 例 DAG 用法）；输出含 `dependsOn` 字段
- **DAG 执行**：`MultiAgentService` 用 Kahn 拓扑排序分层，同层并行（`multiAgentExecutor`，4–8 线程），跨层等待上一层；环检测 → 降级 flat 全并行 + log 警告
- `Worker` 接受 `(task, upstream)` 两参数：upstream 是上游任务输出拼成的 string，没有依赖时传空串
- `Synthesizer` 编织（不是拼接）成最终答案；prompt 含 5 条 synthesis rules + 4 条 forbidden anti-patterns + 1 个完整对比例。明令禁止 `Sub-task 1/[t1]/Based on the synthesis...` 等暴露内部 plan 结构的措辞，要求按用户的 mental model 组织（aspect / 维度 / 步骤），结尾给出 takeaway
- 子线程通过 `MdcCopyingTaskDecorator` 继承 `traceId`，日志能串起来
- `dependsOn` **默认空**（flat 全并行）：仅当 sub-task 指令字面引用另一个 sub-task 输出时才填（"基于 t1 的结果..."）。普通多维度比较 / 独立研究题继续 flat —— 合成由 `Synthesizer` 统一处理

**Plan-and-Execute with Replanning** `app.multi-agent.replan.*`：

- `enabled=false`（默认）— `MultiAgentService.run()` 是 one-shot：`Planner → DAG → Synthesizer → done`，跟历史行为一致
- `enabled=true` — Synthesizer 出 final answer 后**复用 reflexion 的 `Critic`**（temp=0）打 3 维分；加权聚合 < `threshold`（默认 0.75）触发一次 replan
- `Replanner`（`ai/multiagent/Replanner.java`）独立 AiService，看上一轮 plan JSON + final answer + 3 维分 + `mainIssue` 产出**结构性修订过的** plan，再跑一遍 DAG
  - prompt 强制实质改动：禁止原样输出旧 plan；内置 1 例 few-shot（漏 aspect → 加 task + 收紧描述）
  - 系统提示列出 5 种典型修订形态（补 aspect / 写细描述 / 合并重复 / 改 angle / 拆并行）
  - 跟 Critic / Judge 一样走独立 temp=0 ChatModel（`LlmConfig.buildJudgeChatModel`），不注册 ChatModel Bean 避免类型冲突
- `max-replans=1`（默认）— 一次重规划已经覆盖绝大多数 "plan 写偏" case；2 接近极限，再多说明问题本身不可解
- `Run` 结构升级（向后部分兼容）：
  - 顶层 `plan / workerResults / finalAnswer` 仍存在，指向**最后一次 attempt**（eval harness 查 `tasks: N` 字面的逻辑不破）
  - 新增 `attempts: List<Attempt>`：每个 `Attempt(n, plan, workerResults, finalAnswer, critique, aggregate)` 含本轮 critique（关闭 replan 时 `critique=null, aggregate=NaN`）
  - 新增 `acceptedByThreshold: boolean`：replan 关时恒 true；开时表示最后一轮分数是否过阈
- **stream 端点已接 replan**（`/chat/multi-agent/stream`）：`runStream` 拆成自递归的 `streamAttempt`——每个 attempt emit `plan`→`worker-result`→`synthesis-token`，replan 开启且聚合分 < threshold 且未达 `max-replans` 时 emit `critique`+`replan` 事件、用 `Replanner` 修订 plan 后**流式**再跑一轮，末 `done` 带全部 attempts。replan 关闭时行为与旧版一致（单 attempt、不评分）。评分/修订跑在 Synthesizer 流式回调线程上，`TenantContext` 跨此边界透传是已知弱点（token 仍全局计量）
- token 成本：开启 = +1 次 Critic call（恒定） + 0~`max-replans` 次 `(Replanner + 一整轮 DAG worker fan-out + Synthesizer)`。粗算 worst case ≈ 2.5× one-shot

何时该开：

- Eval harness 跑 multi-agent case 发现 final answer 经常漏 aspect / 描述太空 / 用户问 3 方面只答 2 个
- 单次 plan 在某些 corner case 上稳定失败（比如要"先列再深挖"但 Planner 没用 DAG）
- 不该用作"省 prompt 工程"的便利按钮：先把 Planner / Worker / Synthesizer 的 prompt 调到能过 baseline 再开 replan

**Output Guardrails** `@OutputGuardrails(PiiGuardrail.class, maxRetries=2)`：

- 已挂在 `Assistant.chat()`。检测 email / 中国手机号 / 身份证号；命中就 `reprompt` 让模型重写为 `[REDACTED]`。PII 规则抽成共享 `ai/guardrail/PiiDetector`（非流式 guardrail + 流式后处理复用同一套）
- 输入侧用 `@InputGuardrails(SomeInputGuardrail.class)` 同理

**流式后处理（Stream post-processing）** `ai/guardrail/StreamGuard`：

- 流式路径（`/chat/stream` + A2A `message/stream`）的 token 逐个发出，**无法像非流式 guardrail 那样重写**——已发的收不回。故只做 **append-only 告警**：缓冲完整答案 → `StreamGuard.piiWarningOrNull` 命中 PII 则追加 warning 事件（`/chat/stream` 是 `event: warning`；A2A 是告警 artifact）
- **A2A input-required**：`StreamGuard.looksLikeClarifyingQuestion`（保守启发式：明确澄清话术 + 问号结尾 + 较短）判回复是澄清式提问时，A2A 流终态置 `INPUT_REQUIRED`（`app.a2a.detect-input-required`，默开）
- **断连取消**：两条 stream 路径注册 `emitter.onCompletion/onTimeout/onError` → `cancelled` 标记，客户端断开后停止转发 + 跳过后处理。**限制**：`TokenStream.start()` 返 void、无取消句柄，无法中止上游 LLM 生成（仍跑完），只省转发/后处理
- grounding 流式后校验已补：用 `TokenStream.onRetrieved` 捕获检索片段（绕开 `RetrievedSourcesContext` ThreadLocal 跨线程问题），收口时 `GroundingService.streamWarningOrNull`（Layer 0 引用核对 + Layer 1 faithfulness）命中追加 `grounding-warning` 事件/artifact。Layer 1 开启时收口多一次 temp=0 LLM 调用（延迟尾增）
- 单测 `StreamGuardTest`（PII 命中 + 澄清式提问识别）

**Observability**（详见 `docs/observability.md`）：

- `LoggingChatModelListener` — 每次 LLM 调用打一行 `model / duration_ms / tokens_in/out/total`
- `MetricsChatModelListener` — 自己写的最小实现（`langchain4j-micrometer` 还未发到 Maven Central），用 `MeterRegistry` 直接打点：`gen_ai.client.requests`（counter）、`gen_ai.client.operation.duration`（timer）、`gen_ai.client.token.usage`（counter，按 input/output 拆 tag；Anthropic 额外打 `cache_read`/`cache_write` —— 见下 prompt caching）、`gen_ai.client.errors`
- **listener 通过 `LlmConfig` 构造器注入 `List<ChatModelListener>` 灌到每个 chat builder**。之前注释说 "starter 自动 wire"，但项目改成手动建 ChatModel 后绕开了 starter，metrics 其实没记录 —— 这是 hardening 时修的 silent bug
- `TraceIdFilter` — 每个 HTTP 请求生成 8 位 `traceId` 进 MDC、回写 `X-Trace-Id` 响应头；日志 pattern 已带 `[%X{traceId:-}]`
- Prometheus 抓取：`/actuator/prometheus`
- Grafana dashboard：`docs/grafana-dashboard.json` 提供 7 个 panel（req rate / latency p50p95p99 / token spend / error rate by type / etc），导入即用

**Health Check**：

- `LlmHealthIndicator` + `EmbeddingHealthIndicator` 自定义 Actuator indicator，对当前 provider 的 base-url 做 1s TCP 探测（不烧 token、不需要 api-key 有效）
- 暴露在 `/actuator/health/llm` `/actuator/health/embedding` 单独可查
- `management.endpoint.health.group.readiness.include=readinessState,llm,embedding` 把 LLM 后端可达性挂进 K8s readinessProbe
- 需要 `management.health.probes.enabled=true`（已默配）才有 `readinessState`/`livenessState`

**Retry**：

- 每个 chat / embedding builder 都接受 `maxRetries`（默认 3），针对 429 / 5xx / 超时自动退避
- 按 provider 独立配：`app.llm.<provider>.max-retries` / `app.embedding.<provider>.max-retries`
- 注意：重试是 LangChain4j 客户端内部行为，`gen_ai_client_requests_total` 反映的是逻辑次数不是物理 HTTP 次数

## MCP（Model Context Protocol）

`app.mcp.enabled=true` 后，`McpConfig` 会按 `transport` 创建 `McpClient`（stdio 或 streamable http），并把工具桥到 `McpAssistant`（独立 AiService，不带 ChatMemory/RAG，只为干净演示工具调用）。

- stdio 例：`command: ["npx","-y","@modelcontextprotocol/server-filesystem","/tmp/demo"]`
- http 例：`url: http://localhost:3001/mcp`
- 调用：`POST /chat/mcp` body `{"message":"列出 /tmp/demo 下的文件"}`

模型必须支持 function/tool calling 才能用上 MCP 工具（Ollama 上 `llama3.1+ / qwen2.5+` OK）。

## 评测 Harness

- 黄金集放 `src/main/resources/eval/eval-cases.json`，每条：`{id, question, mustInclude:[], mustNotInclude:[]}`
- 跑：`curl -X POST localhost:8080/eval/run` 或 `POST /eval/run-cases` 传临时集
- 每条用 `Judge`（LLM-as-judge AiService，结构化输出 `Judgment{score, coversAllRequiredFacts, violatesForbidden, reasoning}`）评分
- pass 条件：`coversAllRequiredFacts && !violatesForbidden && score >= 0.6`
- 返回 `Summary{total, passed, passRate, averageScore, totalDurationMs, results[]}`
- 改 prompt / 模型 / RAG 配置后跑一遍，看 passRate 和 averageScore 漂没漂

### Judge 噪声控制

为了 prompt A/B 时能看到**真实差异**而不是 Judge 噪声，做了几层隔离：

1. **客观字段走规则匹配**：`coversAllRequiredFacts` / `violatesForbidden` 在 `EvaluationRunner` 里用 `answer.contains(...)` 算，**不让 Judge LLM 判**（它会瞎波动且死板地认字面）。Judge 只负责 `score` 和 `reasoning`。
2. **Judge 用独立的 temp=0 ChatModel**：`LlmConfig.buildJudgeChatModel(props)` 显式 `temperature=0`，让"同样的 (Q, A) 多次评分"给出同一个 score。**注意 LangChain4j auto-discover 不允许两个 ChatModel Bean 共存**（不认 @Primary、autowireCandidate=false 也不顶用），所以 Judge 的 model 是 `EvalConfig` 直接调 `llmConfig.buildJudgeChatModel(...)` 构造的，**没注册成 Bean**。
3. **注入 `today` 给 Judge**：Judge LLM 不知道现在日期（按训练 cutoff 推），评测时间相关答案会判错。`EvaluationRunner.run()` 取 `LocalDate.now()` 传 `@V("today")`。Judge 系统提示里写了三条 today 使用规则：真实当前时间用 today / 题面有"假设当前是 X"时用题面 / 系统有 clock-tool 所以时间戳不算"瞎编"。
4. **Judge 不重复审 MUST_*** ：harness 已经规则匹配过了，Judge 系统提示明令禁止"再判一遍 missing 'X'"。`Default to 1.0 when ... do not be stingy by default` 避免主观通胀扣分。

剩下的方差是 **Assistant 侧的**（默认 temperature=0.7）—— 想看 Assistant 真实差异需要多 run 取均值，目前 harness 没做。

### eval 暴露的两个真 bug（这套测试钉出来的）

1. **eval-cases.json 测试自相矛盾**：`tool-days-until` 早期题面带"请直接给数字"但 `mustInclude:["天"]` —— 模型听话就 fail。修法：去掉"请直接给数字"。
2. **`citationPolicy` 误套用**：旧版 `"没有检索到相关内容时明确说『资料里没有提到 X』"` 被模型无差别套到所有问答上，连用户**直接提供**的信息也加这种 disclaimer，PII 整理类问题就被加上"资料里没有提到张三的联系信息"自相矛盾的前言。修法：拆 3 种互斥情况（检索到/明确指向知识库但没检索到/其他），见 `AssistantProperties.citationPolicy`。

教训：**eval 是 prompt 的回归告警器**。8/8 全过不重要，重要的是改一处 prompt 之后 8 个分数怎么漂 —— 漂下去说明改坏了，漂上去说明改对了，全 1.0 也说明这套 case 不够锐利，该加难一点的（refuse-hallucination、对抗性、跨语言）。

### case 集设计与 judgeHint

当前 26 条 case 分布：

- 20 条走 `Assistant.chat`：8 happy / 7 adversarial / 3 工具变种 / 2 格式 + 语言
- 3 条走 `/extract/ticket`：CRITICAL / LOW / HIGH 三档优先级判断
- 2 条走 `/chat/multi-agent`：多维比较 (tasks=3) / trivial 不过拆 (tasks=1)
- 1 条走 `/chat/reflexive`：清晰技术定义题，应一次过

`EvalCase` 加了可选 `judgeHint` 字段：**只用于 Judge 看 (question, answer) 无法自己推断"正确行为是什么"的 case**。例：

- `pii-redaction`：Judge 不知道 system 强制 PII redaction，会把"已脱敏处理"当 lazy refusal 扣分 → hint 说明 redaction 是合规
- `cite-no-context`：Judge 不知道当前调用没装 RAG，会把"未在文档中找到"当 refusal → hint 说明这是设计意图

**禁止用 judgeHint 直接喂答案**——那等于让 Judge 抄答案，eval 退化成自检。只用来注入"Judge 没法看到的领域规则/系统配置"。

20-case × 3-run 实测：Judge 侧 σ=0 全部，剩下的方差来自 Assistant 自身（temp=0.7）—— 比如 `tool-must-not-fire` 有时算成 `02:30`（真 bug，eval 正确捕捉），`pii-redaction` 偶尔多加前言。要进一步压方差，要么降 Assistant 温度（损失泛化），要么 multi-run 取均值（推荐）。

### 并行执行

`EvalConfig` 装了独立的 `evalExecutor` 线程池（**不复用 `multiAgentExecutor`** —— 复用同一池在 case type 是 `multi-agent` 时会死锁：eval 线程占着池等 worker，但 worker 又要从同一池拿 thread）。

- `app.eval.concurrency`（默认 4）控制池大小
- `EvaluationRunner.run()` 把每个 case 当一个 `CompletableFuture` 投到池里，case 内部 N 个 run 仍顺序，case 之间并发
- MDC 通过 `MdcCopyingTaskDecorator` 透传到 eval-N 子线程，日志的 traceId 跟得上
- 设 `app.eval.concurrency=1` 退回顺序（debug 或对照 baseline 时用）

实测 26 cases × 2 runs：concurrency=4 wall-clock **75s vs 顺序 188s（2.5×）**。理论上限是 4×，但 `multiagent-multi-aspect` 这种 case 单独要 23s 卡在尾巴上 —— 真要进一步加速，可以按 case 预期耗时长短做负载排序，或者把内部 N 个 run 也并行（要更细的 chatId 隔离）。

### 跨 endpoint：type dispatch

`EvalCase` 有可选 `type` 字段（默认 `"chat"`），让同一套 harness 覆盖多个 endpoint：

| type | 调用 | "answer" 喂给 Judge 的形式 | 用途 |
| --- | --- | --- | --- |
| `chat`（默认） | `Assistant.chat(...)` | 模型回复原文 | 主对话 |
| `graph` | `Assistant.chat(...)`（同 chat dispatch） | 模型回复原文 | GraphRAG：校验多跳关系（mustInclude 查桥接实体）/ 实体聚合 / 不编造关系。独立集只为隔离前置（建图 + 多跳 case）。需 `app.rag.graph.enabled` + `app.eval.auto-ingest` |
| `grounded` | `GroundingService.applyToFreshAnswer(() -> Assistant.chat(...))` | 模型回复原文（命中闸门时末尾带 `⚠️ 可信度提示`） | 测 RAG 事实幻觉的事后校验闸门 |
| `extract` | `Extractor.extractTicket(question)` | Ticket POJO 序列化的 JSON | 结构化抽取（mustInclude 可查 `"priority":"CRITICAL"` 等字面） |
| `multi-agent` | `MultiAgentService.run(question)` | `tasks: N\n<子任务列表>\n---\n<finalAnswer>` | 同时校验拆分粒度（mustInclude 查 `tasks: 3`）和最终答案 |
| `reflexive` | `ReflexiveService.chatReflexive(question)` | `attempts: N, accepted: true\n---\n<finalAnswer>` | 同时校验反思迭代行为（attempts/accepted）和最终答案 |
| `sql` | `NlToSqlService.ask(question)` | `guardBlocked: B\nsql: ...\nrowCount: N\n---\n<解读>` | NL2SQL：校验护栏拦截（mustInclude 查 `guardBlocked: true`）/ 中文枚举 / 租户隔离 / 解读数字。需 `app.nl2sql.enabled` + MySQL demo 库 + tool-calling 模型 |
| `a2a` | `A2aService.dispatch("message/send", ...)`（chat skill 同步） | 序列化的 JSON-RPC response | A2A：校验 dispatch + skill 路由 + 协议映射（mustInclude 查 `"role":"agent"` / `"error"`）。需 `app.a2a.enabled` |
| `workflow` | `WorkflowService.start(chatId, question, ...)` | `status: ...\npriority: ...\n---\n<reply>` | 退款工作流：校验进审批（mustInclude 查 `status: WAITING_APPROVAL`）/ 优先级 / 工单抽取。需 `app.workflow.enabled` + MySQL |
| `agent` | `DeepAgentService.run(question)` | `stopReason: ...\nsteps: N\n---\n<finalAnswer>` | 深度 Agent：校验开放式循环正常完成（mustInclude 查 `stopReason: DONE`，挡 LOOP/MAX_STEPS）/ 步数 / 最终答案。需 `app.deep-agent.enabled` + tool-calling 模型 |

dispatch 在 `EvaluationRunner.invokeByType()`，加新 type 在 switch 加一支 + 在 EvalCase 文档里登记即可。`sql`/`a2a`/`workflow`/`agent` 的服务经 `ObjectProvider` 软依赖注入：没开对应 profile 时为 null，跑到该 type 的 case 才报清晰错误（不影响 default 集）。各有独立黄金集文件 `eval-cases-{sql,a2a,workflow,graph,agent}.json`，`/eval/run?set=` 选（`graph` 服务无软依赖，只需开 `app.rag.graph.enabled`+`auto-ingest`；`agent` 需 `app.deep-agent.enabled`）。

**baseline CI 门禁**：`Baseline`（全局 + per-case 合格线）+ `BaselineGate`（纯函数对照，带浮点容差，基线里有但本次缺席的 case 也判回归，防"偷偷删 case 让门禁变绿"）。`POST /eval/gate?set=&runs=` 有回归返 HTTP 422（CI fail），`POST /eval/baseline` 从实测 run 减 slack 生成基线落盘。`scripts/eval-gate.sh` 封装 curl + 退出码。基线生成/对照逻辑被 `BaselineGateTest` 确定性覆盖，黄金集 JSON 结构被 `GoldenSetsTest` 校验（CI 挡 JSON 笔误）。

**grounded 类型的两条 case 有前置**（`grounding-supported-quiet` / `grounding-abstain-quiet`）：

- 它跟 `chat` 的唯一区别是包了一层 `GroundingService` —— 闸门只在 `app.rag.grounding.enabled=true` 时运行，否则等价于 `chat`（直通）。
- 两条都依赖检索召回，需 `app.eval.auto-ingest=true`（或先手动 `/rag/ingest`）才有 source 可校验。
- 都测的是"闸门**不该响**的场景"（充分支撑的答案 / 诚实弃答），用 `mustNotInclude: ["可信度提示"]` 守住 warn 模式最怕的**误报**。`grounding-supported-quiet` 若偶发响了，多半是 Layer 0 引用 id 对不上或 Layer 1 偏严——这是关于闸门松紧的信号，不是测试 bug。
- 跑法：`mvn spring-boot:run -Dspring-boot.run.arguments="--app.rag.grounding.enabled=true,--app.eval.auto-ingest=true"` 后 `POST /eval/run?runs=3`。

> 注：当前默认黄金集还含 `rag-citation-*` / `rag-no-match` 等依赖 ingest 的 RAG case，所以"开 auto-ingest 跑"本就是 RAG 相关 case 的标准前置。

结构化输出之所以序列化成 string 喂 Judge：

- 让 mustInclude/mustNotInclude 这种规则匹配能用（找 JSON 子串或前缀行）
- Judge 不用区分类型，配合 `judgeHint` 就能理解上下文（"answer 是 Ticket JSON，按抽取语义判分"）
- 加新 endpoint type 时不用动 Judge 接口

### Multi-run

`POST /eval/run?runs=N`（默认 1）让每个 case 顺序跑 N 次。返回结构：

```json
{
  "totalCases": 20, "runsPerCase": 3, "totalRuns": 60,
  "passedRuns": 60, "overallPassRate": 1.0, "averageScore": 0.993,
  "totalDurationMs": 155500,
  "cases": [
    {
      "caseId": "tool-current-time",
      "runs": 3, "passedCount": 3, "passRate": 1.0,
      "avgScore": 0.867, "scoreStdev": 0.189,
      "attempts": [ /* 每次的 EvalResult */ ]
    }, ...
  ]
}
```

- **看 `scoreStdev` 而不是单次分数**：σ > 0.1 说明 Assistant 给出不稳定答案，σ ≈ 0 才是真稳定
- **prompt A/B 推荐 `runs=3~5`**：3 次能区分"真改进" vs "运气好一次"；5 次以上对 LLM 调用成本敏感
- **runs=1 是 smoke test**：快但不可靠，单次 snapshot 容易误报
- 每个 case 内部 N 个 run 仍顺序（保证 chatId memory 隔离的简化，不要并行）；case 之间并行，见下
