# CLAUDE.md

这份文档给在本仓库工作的 Claude Code 使用，描述项目的技术栈、结构、约定与常用命令。

## 配套文档

- `PROMPT_JOURNEY.md`（项目根目录）— prompt 工程 + eval harness + 生产化的完整演化日志，从 demo 到生产可用
- `docs/roadmap.md` — 待完善项 / 按 ROI 分档 / "触发信号 → 该做什么"决策表
- `docs/observability.md` — Prometheus / Grafana / Health Check 接入说明
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
| `anthropic` | `ANTHROPIC_API_KEY` | `claude-haiku-4-5` | 可改 `claude-sonnet-4-6` / `claude-opus-4-7` |
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
| POST | `/eval/run?runs=N` | 跑 `resources/eval/eval-cases.json` 黄金集，每 case 跑 N 次（默认 1）；返回 per-case avg/σ/passRate + 整体 |
| POST | `/eval/run-cases?runs=N` | body 传 `EvalCase[]` 跑临时集（N 同上） |
| GET  | `/actuator/health` | Spring Boot Actuator |
| GET  | `/actuator/metrics/gen_ai.client.token.usage` | LLM token 用量（Micrometer） |
| GET  | `/actuator/prometheus` | Prometheus scrape 端点 |
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
- `DorisEmbeddingStore` 是社区/自实现版本，已支持 add / search / remove **和 metadata filter**（`DorisFilterTranslator` 把 `IsEqualTo` / `IsIn` / `And` / `Or` / `Not` 等翻译成 `get_json_string(metadata,'$.key') = ?` 形式的 SQL；JSON key 用 `[A-Za-z0-9_.-]+` 白名单校验防注入）。生产用法仍建议补：批量 Stream Load、连接池。
- **HTTP client 冲突**：classpath 里同时有 `langchain4j-http-client-spring-restclient` 和 `langchain4j-http-client-jdk` 两个 SPI 实现，LangChain4j 会抛 `Conflict: multiple HTTP clients found`。`LangChain4jApplication.main()` 里用 `System.setProperty("langchain4j.http.clientBuilderFactory", "dev.langchain4j.http.client.jdk.JdkHttpClientBuilderFactory")` 显式锁定 JDK 实现。要换回 Spring RestClient 改这一行即可，不要删。
- **Ollama starter 与 Spring Boot 3.3.5 不兼容**：`langchain4j-ollama-spring-boot-starter` 1.13.x 的 `OllamaEmbeddingModel` 自动装配引用了 Spring Boot 3.4+ 才有的 `org.springframework.boot.http.client.ClientHttpRequestFactorySettings`，3.3.5 下会 `NoClassDefFoundError`。因此 yml 里**不要**配 `langchain4j.ollama.embedding-model.*`（也不要配 `chat-model` / `streaming-chat-model`），所有 Ollama Bean 都在 `LlmConfig` 里手动 `OllamaChatModel.builder()` / `OllamaEmbeddingModel.builder()` 构建。升级 Spring Boot 到 3.4+ 后可以考虑回到 starter 自动装配，但目前自管比较省事。

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
- `summary` — 自定义 `SummarizingChatMemory`：超过 `max-messages` 时，把旧消息（保留最近 `summary.keep-recent` 条之外的）用 LLM 压缩成单条 `SystemMessage`；每次压缩一次额外 LLM 调用

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

- `strategy=recursive`（默认）— `DocumentSplitters.recursive(max-chars, overlap)`，按字符数硬切 + overlap，简单粗暴适合任何文档
- `strategy=markdown-header` — `MarkdownHeaderSplitter` 自实现：按 `(?m)(?=^##+ )` 切 section，每个 chunk 是完整主题；超长 section fallback 到 recursive
- `max-chars: 300` — recursive 模式的 chunk 大小目标；markdown-header 模式的 section 长度阈值
- `overlap: 50` — recursive 模式 chunk 重叠（markdown-header 只在 fallback 时用到）
- markdown-header 给 segment 加 metadata：`section` 标题 + `index` 顺序号，引用 `[doc=file.md#3]` 对应"第 3 个 section"而不是"第 3 个 300-char 块"
- 实测对本项目（5 个 chat provider 列在 1 个 `## Section` 里）的可见提升：recursive(300) 召回不全只列 2 个 provider，markdown-header(600) 召回完整 5 个

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

## 多 Agent 协作 / Guardrails / 可观测性

**Multi-Agent** `/chat/multi-agent`：

- `Planner` 把问题拆 1–6 个子任务（结构化输出，内置 3 例 few-shot + 2 反例锚定粒度 + 1 例 DAG 用法）；输出含 `dependsOn` 字段
- **DAG 执行**：`MultiAgentService` 用 Kahn 拓扑排序分层，同层并行（`multiAgentExecutor`，4–8 线程），跨层等待上一层；环检测 → 降级 flat 全并行 + log 警告
- `Worker` 接受 `(task, upstream)` 两参数：upstream 是上游任务输出拼成的 string，没有依赖时传空串
- `Synthesizer` 编织（不是拼接）成最终答案；prompt 含 5 条 synthesis rules + 4 条 forbidden anti-patterns + 1 个完整对比例。明令禁止 `Sub-task 1/[t1]/Based on the synthesis...` 等暴露内部 plan 结构的措辞，要求按用户的 mental model 组织（aspect / 维度 / 步骤），结尾给出 takeaway
- 子线程通过 `MdcCopyingTaskDecorator` 继承 `traceId`，日志能串起来
- `dependsOn` **默认空**（flat 全并行）：仅当 sub-task 指令字面引用另一个 sub-task 输出时才填（"基于 t1 的结果..."）。普通多维度比较 / 独立研究题继续 flat —— 合成由 `Synthesizer` 统一处理

**Output Guardrails** `@OutputGuardrails(PiiGuardrail.class, maxRetries=2)`：

- 已挂在 `Assistant.chat()`。检测 email / 中国手机号 / 身份证号；命中就 `reprompt` 让模型重写为 `[REDACTED]`
- 流式 `chatStream` 暂未挂 guardrail（流式 guardrail 会缓冲整段，按需要再加）
- 输入侧用 `@InputGuardrails(SomeInputGuardrail.class)` 同理

**Observability**（详见 `docs/observability.md`）：

- `LoggingChatModelListener` — 每次 LLM 调用打一行 `model / duration_ms / tokens_in/out/total`
- `MetricsChatModelListener` — 自己写的最小实现（`langchain4j-micrometer` 还未发到 Maven Central），用 `MeterRegistry` 直接打点：`gen_ai.client.requests`（counter）、`gen_ai.client.operation.duration`（timer）、`gen_ai.client.token.usage`（counter，按 input/output 拆 tag）、`gen_ai.client.errors`
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

`EvalCase` 有可选 `type` 字段（默认 `"chat"`），让同一套 harness 覆盖 4 个 endpoint：

| type | 调用 | "answer" 喂给 Judge 的形式 | 用途 |
| --- | --- | --- | --- |
| `chat`（默认） | `Assistant.chat(...)` | 模型回复原文 | 主对话 |
| `extract` | `Extractor.extractTicket(question)` | Ticket POJO 序列化的 JSON | 结构化抽取（mustInclude 可查 `"priority":"CRITICAL"` 等字面） |
| `multi-agent` | `MultiAgentService.run(question)` | `tasks: N\n<子任务列表>\n---\n<finalAnswer>` | 同时校验拆分粒度（mustInclude 查 `tasks: 3`）和最终答案 |
| `reflexive` | `ReflexiveService.chatReflexive(question)` | `attempts: N, accepted: true\n---\n<finalAnswer>` | 同时校验反思迭代行为（attempts/accepted）和最终答案 |

dispatch 在 `EvaluationRunner.invokeByType()`，加新 type 在 switch 加一支 + 在 EvalCase 文档里登记即可。

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
