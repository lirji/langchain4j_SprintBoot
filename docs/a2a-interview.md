# A2A 协议面试速答稿

> A2A（Agent2Agent，Google 2025 年提的 Agent 间互操作协议）。**本项目已落地 A2A Server MVP**（`a2a` 包，`app.a2a.enabled`，默认关；落地说明见 `docs/a2a.md`），覆盖三种调用方式。这份是面试概念稿 + 本项目落地答法。
> 配套：MCP 落地说明见 `CLAUDE.md`「MCP」节，token 面试稿见 `docs/token-control-interview.md`，RAG 见 `docs/rag-interview-notes.md`。

## 一句话总览

> A2A 底层是 **JSON-RPC 2.0 over HTTP(S)**，按客户端怎么拿结果分三种调用方式：**同步请求-响应 / SSE 流式 / Webhook 异步推**；剩下是任务生命周期管理（查、取消、重订阅）。传输层 v0.2+ 扩成 JSON-RPC / gRPC / REST 三种 binding。它解决 Agent↔Agent 横向协作，跟 MCP（Agent↔工具，纵向）互补。

---

## Q1（核心）「A2A 有哪几种调用方式？」

底层基于 JSON-RPC 2.0 over HTTP(S)，按客户端 Agent 怎么拿结果分三种：

| 模式 | 核心方法 | 机制 | 适用场景 |
| --- | --- | --- | --- |
| **同步请求-响应** | `message/send` | 阻塞，一次拿回 `Task` 或 `Message` | 短任务、能秒回的 |
| **流式（SSE）** | `message/stream` | Server-Sent Events，增量推 `TaskStatusUpdateEvent` / `TaskArtifactUpdateEvent` | 长输出要 token-by-token / 中间进度 |
| **异步 + 推送（Webhook）** | `tasks/pushNotificationConfig/set` | 客户端登记 webhook URL，任务跑完 / 状态变更服务端 push 回去 | 超长任务、客户端可能断连 |

配套的任务管理方法：

- `tasks/get` —— **轮询**任务状态（不支持 streaming 或断连后兜底）
- `tasks/cancel` —— 取消运行中任务
- `tasks/resubscribe` —— SSE 断线后重新订阅
- `tasks/pushNotificationConfig/get` —— 查已配的推送

> 一句话记忆：**同步 / SSE 流式 / Webhook 异步推**，剩下都是任务生命周期管理（查、取消、重订阅）。

---

## Q2（传输层进阶）「A2A 的传输绑定有哪些？」

A2A 较新版本（v0.2+）从单一 JSON-RPC 扩成**三种传输 binding**，语义一致、可协商：

1. **JSON-RPC 2.0 over HTTP**（最初的、最通用）
2. **gRPC**（强类型、高性能，protobuf）
3. **HTTP + JSON / REST**（普通 RESTful 风格）

Agent 在 **Agent Card**（`/.well-known/agent-card.json`，旧版 `agent.json`）里声明自己支持哪些传输和能力（是否支持 streaming、push notification 等），客户端据此协商。

---

## Q3（发现机制）「客户端怎么知道一个 Agent 能干什么、怎么调？」

靠 **Agent Card**（服务发现）：

- 默认放在 `/.well-known/agent-card.json`，是一份 JSON 元数据。
- 声明：name / description / 服务 endpoint URL / 支持的传输 / capabilities（streaming、pushNotifications）/ skills（能力清单）/ 认证方式（OAuth、API Key 等）。
- 客户端先拉 Card → 据此决定用哪种调用方式、带什么 auth。

---

## Q4（任务状态机）「A2A 的 Task 生命周期是怎样的？」

A2A 用 **Task** 作为一次协作的载体（不是单次 message），有明确状态机：

```
submitted ──► working ──┬──► completed   （成功，产出 artifacts）
                        ├──► failed      （失败）
                        ├──► canceled    （被 tasks/cancel）
                        └──► input-required ──► （回到 working，等客户端补输入）
```

要点：

- **`input-required`** 是 A2A 比普通 RPC 强的地方 —— 远端 Agent 跑到一半可以反问、要客户端补信息，再继续。多轮协作天然支持。
- 任务产出叫 **Artifact**（可以是文本、文件、结构化数据），通过 `TaskArtifactUpdateEvent` 增量推或终态一次性给。
- 状态变更通过 `TaskStatusUpdateEvent` 推（流式）或 `tasks/get` 拉（轮询）。

---

## Q5（必被对比）「A2A 和 MCP 什么区别？」

| | MCP | A2A |
| --- | --- | --- |
| 连接对象 | Agent ↔ **工具 / 资源**（纵向，"给模型挂能力"） | Agent ↔ **Agent**（横向，"让自治体协作"） |
| 对方是什么 | 一组确定性的 function / resource | **黑盒自治体**（有自己的模型、记忆、工具） |
| 暴露内部？ | 暴露工具 schema | **不暴露**内部状态/工具，只通过 Agent Card + Task 协作 |
| 本项目 | ✅ 已集成（`ai/mcp`，`app.mcp.enabled`） | ✅ 已落地 MVP（`a2a`，`app.a2a.enabled`） |

> 两者互补：一个 Agent 用 **MCP 接工具**，对外用 **A2A 跟别的 Agent 协作**。面试别把它们对立起来。

---

## Q6（设计/落地题）「给这个项目接 A2A 是怎么做的？」

把现有 `Assistant` 包成一个 **A2A Server**（`a2a` 包），**复用已有基建、零新依赖**，已实际落地：

1. **暴露 Agent Card**：`GET /.well-known/agent-card.json` 声明 skills（chat / multi-agent）、endpoint、streaming + pushNotifications、apiKey auth（复用 `ApiKeyAuthFilter`，安全链放行此发现端点）。
2. **`message/send`（chat）** → 同步接 `Assistant.chat`，guardrail + grounding 自动生效，返回 agent `Message`。
3. **`message/stream`（chat）** → 复用 `chatStream` 的 **TokenStream + SseEmitter**，每帧成 A2A `status-update` / `artifact-update` 事件（包在 JSON-RPC response 里）。
4. **Webhook 异步推** → multi-agent 走 `AsyncTaskService` 异步 Task，终态由 `A2aPushNotifier` 回推 A2A `Task`；**刻意跟现有 `WebhookDispatcher` 隔离**（A2A 任务不写 `AsyncTask.webhookUrl`，push 配置单独存 `A2aPushNotificationStore`），两条通道不重复触发。复用 `app.async.webhook.*` 的 HMAC/重试。
5. **Task 状态机** → `A2aMapper` 把内部 `TaskStatus` 翻成 A2A `TaskState`（`PENDING→submitted`…）；`input-required` 预留位本期未接线。
6. **多租户/配额** → `/a2a` 走 `ApiKeyAuthFilter`→`TenantContext`，限流 family=`a2a`、token 预算纳入 `LLM_FAMILIES`，自动按租户计。

> 亮点：项目的 **SSE 流式**和 **Webhook 推送**两块基建刚好对应 A2A 的「流式」和「异步推」，不是从零搭；协议层全手写 record + JSON-RPC dispatcher，零新依赖。落地细节见 `docs/a2a.md`。

---

## 速记

- **三种调用方式**：`message/send`（同步）/ `message/stream`（SSE 流式）/ `pushNotificationConfig`（Webhook 异步推）。
- **三种传输**：JSON-RPC / gRPC / REST。
- **发现**：Agent Card（`/.well-known/agent-card.json`）。
- **载体**：Task（状态机带 `input-required` 支持多轮反问），产出是 Artifact。
- **vs MCP**：MCP 接工具（纵向），A2A 接 Agent（横向），互补。
