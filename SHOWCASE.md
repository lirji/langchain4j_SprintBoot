# 项目展示指引（面试导览）

> 这份文档是给**面试官/评审**看的项目导览：30 秒抓住定位，5 分钟看懂架构与能力，10 分钟能聊到技术深度。
> 想看能力清单去 `CAPABILITIES.md`，想看设计演化去 `CLAUDE.md` / `PROMPT_JOURNEY.md`，想看某模块细节去 `docs/*.md`。
> 在线交互版（可点击每处看详情）：https://claude.ai/code/artifact/366e86f0-feaf-4ffe-b075-475c1d7fc3d0?via=auto_preview
> 离线交互版：`SHOWCASE.html`（单文件、双击即开，无需联网。自主度光谱 9 行 + 能力矩阵 6 卡 + 技术亮点 6 卡共 21 处可点击展开详情）

---

## 0. 电梯陈述（30 秒）

**一个带自主深度 Agent 能力的企业级 LLM 应用平台**，基于 LangChain4j + Spring Boot（Java 21）。

它不是"一个 Demo"，而是在**同一套生产级工程基线**（多 provider 热切换 / 多租户隔离 / token 配额 / 限流 / 审计 / 可观测 / 自动化 eval）上，把 LLM 应用从**可控管道**（RAG、NL2SQL、结构化抽取）→ **Agentic Workflow**（Anthropic 5 种 workflow 模式全覆盖）→ **自主 Agent**（开放式 plan→act→observe 深度 Agent）的**完整光谱**都落地了。

**一句话记忆点**：*从 RAG 到 Agent 的全栈落地，且每一层都做了生产硬化和自动化质量回归。*

**规模**：254 个 Java 源文件 · 45 个确定性单测类（285 测试全绿，纯逻辑不连模型）· LangChain4j 1.13.1 · Spring Boot 3.3.5。

---

## 1. 一图看懂架构

```
┌──────────────────────────────────────────────────────────────────────┐
│  客户端 / 渠道    REST · SSE 流式 · 飞书 · 语音(ASR/TTS) · A2A 协议      │
├──────────────────────────────────────────────────────────────────────┤
│  接入与安全（横切）                                                      │
│  X-Api-Key 鉴权 · 多租户隔离 · 限流 · per-tenant token 配额             │
│  · prompt 注入检测 · PII 脱敏 · 审计日志 · TraceId 贯穿                  │
├──────────────────────────────────────────────────────────────────────┤
│  能力编排层                                                             │
│  ┌────────────┐ ┌──────────────────┐ ┌────────────┐ ┌──────────────┐  │
│  │ 对话 + RAG │ │ Workflow 模式    │ │ 自主 Agent │ │ 业务场景     │  │
│  │ 多轮记忆   │ │ Chaining/Routing │ │ deep-agent │ │ NL2SQL/ChatBI│  │
│  │ 混合检索   │ │ Voting/MultiAgent│ │ plan→act→  │ │ Flowable工作流│  │
│  │ rerank     │ │ Reflexion        │ │ observe    │ │ 知识库/多模态 │  │
│  │ grounding  │ │ (Anthropic 全套) │ │ Browser-use│ │ 语音/长期记忆 │  │
│  └────────────┘ └──────────────────┘ └────────────┘ └──────────────┘  │
├──────────────────────────────────────────────────────────────────────┤
│  模型抽象层（三向解耦，换任一不影响其余）                                │
│  ChatModel: ollama/openai/anthropic/gemini/deepseek/vllm               │
│  Embedding: ollama / openai-compat(bge-m3)  ·  向量库 ×6               │
│  ChatMemory: 消息窗/token窗/摘要窗  ·  Redis 持久化                     │
├──────────────────────────────────────────────────────────────────────┤
│  质量与可观测（贯穿全栈）                                                │
│  Eval Harness + Baseline Gate(CI门禁) · Micrometer/Prometheus/Grafana  │
│  · LLM/Embedding Health Check · 结构化日志                             │
└──────────────────────────────────────────────────────────────────────┘
```

**架构上的三个关键设计**（面试可展开）：
1. **模型三向解耦**——chat / embedding / 向量库各自独立开关，换 provider = 改一行配置 + 重启，业务代码零改动（`LlmConfig` / `EmbeddingModelConfig` / `EmbeddingStoreConfig`）。
2. **能力默认关、按需装配**——绝大多数高级能力（Agent / NL2SQL / GraphRAG / 工作流 / 语音 / Voting…）都 `@ConditionalOnProperty` 默认关，开一个装一套 Bean、关了零开销零回归。
3. **质量左移**——LLM 行为回归不靠人肉，靠 Eval Harness + Baseline CI 门禁；确定性逻辑靠 285 个纯 JVM 单测。

---

## 2. 六大技术亮点（面试主打）

> 每个都按「**是什么 / 为什么这么做 / 踩过的坑**」组织——面试官爱问"为什么"和"遇到什么问题"。

### ① 多 Provider 统一抽象
- **是什么**：6 家 chat provider（Ollama/OpenAI/Anthropic/Gemini/DeepSeek/vLLM）+ 2 类 embedding + 6 种向量库，全部一套接口、配置切换。
- **为什么**：生产要能在"本地零成本调试 → 云 API → 自建 vLLM"之间平滑迁移；不同 provider 的 prompt 偏好差异用 `app.assistant.overrides.<provider>` 分别覆盖。
- **踩坑**：LangChain4j starter 与 Spring Boot 3.3.5 的 `NoClassDefFoundError`、两个 HTTP client SPI 冲突、两个 ChatModel Bean 不能共存——都在 `CLAUDE.md`「注意事项」里有记录和解法。

### ② RAG 的深度（不止"检索+拼接"）
- **混合检索**（向量 + BM25 关键词，RRF 融合）· **reranking**（LLM/Jina/Cohere）· **5 种 chunking 策略**（recursive/markdown-header/parent-child/semantic + char/token 计量）· **GraphRAG**（实体-关系图谱补多跳盲区）· **Contextual Retrieval**（Anthropic，chunk 入库前 LLM 加上下文）。
- **grounding 事实幻觉校验**：Layer 0 确定性核对 `[doc=ID]` 引用是否编造 + Layer 1 RAGAS 式 faithfulness 打分，命中可 warn/refuse/regenerate（**验证接回生成的闭环**）。
- **为什么**：这些正是 RAG 从"能跑"到"可信"的关键，面试区分度高。详见 `docs/knowledge-base.md` / `docs/graphrag.md` / `docs/rag-interview-notes.md`。

### ③ 自主 Agent + Loop Engineering（前沿认知）
- **是什么**：`deep-agent` 开放式 plan→act→observe 循环，模型每步自己决定动作，可插拔工具（RAG/NL2SQL/MCP/Browser-use）。
- **循环工程化**（把 demo 级 `while(调模型)` 做成生产级）：**三维预算**（步数/墙钟/token 任一超限即停）· **滑窗循环检测**（抓 A→B→A→B 震荡）· **scratchpad 跨步工作记忆**（溢出可 LLM 摘要压缩）· **brain 单步重试** · **深度受限子 Agent** · **取消感知**。
- **为什么**：这是"Agent 真正难的地方在循环本身而非 prompt"的落地，面试聊 Agent 稳定性/成本控制时是硬货。详见 `docs/deep-agent.md`。

### ④ Anthropic Workflow 模式全覆盖（架构认知）
- Anthropic《Building Effective Agents》的 **5 种 workflow + agent** 逐一落地并映射到代码：Prompt Chaining(`ai/chaining`) / Routing(`ai/routing`) / Parallelization·Sectioning+Voting(`multiagent`+`ai/voting`) / Orchestrator-Workers(`multiagent`) / Evaluator-Optimizer(`reflexion`) / Agent(`deep-agent`)。
- **为什么**：能清晰区分"workflow（预定义编排）vs agent（自主）"、并说清各自适用场景，直接体现体系化认知。详见 `docs/workflow-patterns.md`。

### ⑤ NL2SQL 的 6 层安全护栏（安全与生产思维）
- 自然语言 → SQL → **只读执行** → 解读的受控链路，6 层护栏：L1 只读账号 / L2 语句白名单 / L3 表白名单 / L4 强制 LIMIT / L5 超时 / L6 租户谓词，外加数字 grounding 核对答案数字 ∈ 查询结果。
- **为什么**：LLM 生成 SQL 直连生产库是高危动作，这套护栏体现"给 LLM 能力也给它笼子"的安全意识。详见 `docs/nl2sql.md`。

### ⑥ Eval Harness + Baseline CI 门禁（工程严谨度，最加分）
- **把 prompt 当代码测**：黄金集 + LLM-as-Judge（客观字段规则匹配、主观分 temp=0 稳定打分）+ multi-run 看方差 + **baseline 门禁**（回归返 HTTP 422 卡 CI，还挡"偷偷删 case 让门禁变绿"）。
- **为什么**：绝大多数候选人改 prompt 靠"感觉"，这套把 LLM 行为纳入可回归、可门禁的工程体系——是资深信号。详见 `CLAUDE.md`「评测 Harness」。

---

## 3. 自主度光谱（一眼看清 Agent vs Workflow）

| 自主度 | 模块 | 是 Agent 吗 |
| --- | --- | --- |
| 可控管道 | RAG / NL2SQL / 抽取 / 视觉 | ❌ 确定性链路 |
| 确定性编排 | Flowable 工作流（退款审批） | ❌ 业务流程，反 Agent |
| Prompt Chaining | `ai/chaining` `/chat/chain` | ❌ 预定义顺序编排 |
| Routing | `ai/routing` `/chat/auto` | ❌ 分类分派 |
| 工具自主 | `Assistant` + `@Tool` | 🟡 模型决定调不调工具 |
| Evaluator-Optimizer | `reflexion` `/chat/reflexive` | 🟡 固定反馈循环 |
| Voting | `ai/voting` `/chat/vote` | 🟡 并行取共识 |
| Orchestrator-Workers | `multiagent` `/chat/multi-agent` | 🟡 固定 DAG 编排 |
| **自主 Agent** | **`deep-agent` `/agent/run`** | ✅ **开放式 plan→act→observe** |

> 面试话术：*"生产里很多场景要的是**可控管道**而非自主 Agent（NL2SQL 要护栏、审批要合规流程）。我把两种范式都做了，按场景选型——`deep-agent` 是其中真正自主的那个。"*

---

## 4. 生产就绪清单（体现"能上线"）

已落地的业务化基线 #1–#10（详见 `docs/production-hardening.md`）：

- **多租户隔离**（数据 / 向量 / 会话 / 配额按 tenant 隔离）
- **限流 + per-tenant 日 token 配额**（三组件闭环 + 运维快照端点）
- **文档生命周期**（上传/版本覆盖/删除，Tika 解析 PDF/Office）
- **prompt 注入检测 + PII 脱敏 + 审计日志**
- **长任务异步化**（投后台 + 轮询/SSE/Webhook 三种取回）
- **可观测**：Micrometer → Prometheus → Grafana（现成 7-panel dashboard）+ LLM/Embedding Health Check 挂 K8s readiness
- **工作流引擎硬化**（超时驳回 / 幂等 / 补偿 / outbox+DLQ / 合规删除…）

---

## 5. 五分钟 Live Demo 脚本

> 前置：`ollama pull llama3.1 && ollama pull nomic-embed-text`，然后 `mvn spring-boot:run`（本机 8080 被占用加 `-Dspring-boot.run.arguments=--server.port=8081`）。

```bash
# ① RAG + 引用：入库 → 带 [doc=来源] 提问
curl -X POST localhost:8080/rag/ingest
curl -X POST 'localhost:8080/chat?chatId=demo' -H 'Content-Type: application/json' \
  -d '{"message":"根据文档介绍这个项目的 RAG 能力，并标注来源"}'

# ② 工具调用：模型自主决定调用时间工具
curl -X POST 'localhost:8080/chat?chatId=demo' -H 'Content-Type: application/json' \
  -d '{"message":"现在几点？时区 Asia/Shanghai"}'

# ③ 自主 Agent：看完整 plan→act→observe trace + stopReason
#    启动加 --app.deep-agent.enabled=true --app.llm.ollama.model-name=qwen2.5
curl -X POST 'localhost:8080/agent/run' -H 'Content-Type: application/json' \
  -d '{"goal":"用工具查出当前时间，再算出距离今年国庆还有多少天"}'

# ④ 质量回归：一条命令看整套黄金集的 passRate / 分数方差
curl -X POST 'localhost:8080/eval/run?runs=3'

# ⑤ 可观测：token 用量 / Prometheus 指标
curl localhost:8080/actuator/metrics/gen_ai.client.token.usage
```

> 想演示更"重"的：`/chat/sql`（NL2SQL 护栏）、`/chat/multi-agent/stream`（SSE 流式多 Agent + replan）、`/chat/vote`（投票共识）、`/chat/vision`（看图问答）——各自开对应 `app.*.enabled` 即可。

---

## 6. 按岗位裁剪你的讲法

| 面试岗位 | 主打亮点 | 打开的开关 / 端点 |
| --- | --- | --- |
| **RAG / 知识库工程** | 混合检索 + rerank + 5 种 chunking + GraphRAG + grounding 幻觉校验 | `/rag/ingest` `/chat` `/chat/category`；`docs/knowledge-base.md` `docs/graphrag.md` |
| **AI Agent 工程** | deep-agent + Loop Engineering（三维预算/循环检测/scratchpad 压缩）+ Anthropic workflow 全覆盖 | `/agent/run` `/chat/multi-agent`；`docs/deep-agent.md` `docs/workflow-patterns.md` |
| **LLM 应用 / 全栈** | 多 provider 抽象 + 生产硬化 #1–#10 + Eval CI 门禁 | `/eval/gate` `/actuator/*`；`docs/production-hardening.md` |
| **ChatBI / 数据方向** | NL2SQL 6 层护栏 + 数字 grounding | `/chat/sql`；`docs/nl2sql.md` |
| **平台 / 架构** | 三向解耦 + 按需装配 + 自主度光谱认知 | 本文 §1/§3；`CAPABILITIES.md` |

---

## 7. 预设面试问答（答案在哪）

| 面试官可能问 | 一句话答 + 出处 |
| --- | --- |
| 你怎么防止 RAG 幻觉？ | 三层：Schema 治结构幻觉、状态机治动作幻觉、**grounding 事后校验**治事实幻觉（Layer 0 引用核对 + Layer 1 faithfulness）。`docs/*` + `ai/grounding` |
| Agent 怎么防止跑飞 / 死循环 / 烧钱？ | **三维预算**（步数/墙钟/token）+ **滑窗循环检测** + brain 重试，任一超限优雅停。`docs/deep-agent.md` |
| 换个大模型要改多少代码？ | 一行配置 `app.llm.provider` + 环境变量,业务代码零改。`LlmConfig` |
| prompt 改了怎么保证不退步？ | Eval Harness 多跑取均值 + **baseline CI 门禁**（回归 422 卡住合并）。`CLAUDE.md`「评测 Harness」 |
| 多租户怎么隔离？ | tenant 前缀贯穿会话/向量/配额/审计,`X-Api-Key`→`TenantContext`。`docs/production-hardening.md` |
| workflow 和 agent 有什么区别？ | workflow=预定义代码路径编排 LLM,agent=LLM 自主决定流程;本项目两者都做。`docs/workflow-patterns.md` |
| 让 LLM 写 SQL 直连库不危险吗？ | 6 层护栏（只读账号/语句·表白名单/强制 LIMIT/超时/租户谓词）+ 数字 grounding。`docs/nl2sql.md` |

---

## 8. 想更进一步

- 想要**可视化在线展示页**（发给面试官点开就能看的网页版架构图/能力卡片），我可以另做一个 Artifact 页面。
- 想针对**某个具体岗位 JD** 定制一份 2 页速讲稿 / 深挖某一亮点的技术问答,告诉我岗位方向即可。
