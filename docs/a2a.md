# A2A（Agent2Agent）Server 落地

> 让本服务作为一个 **A2A Server** 被其他 Agent 以标准协议发现和调用（Agent↔Agent 横向协作，与 MCP 的 Agent↔工具纵向连接互补）。默认关（`app.a2a.enabled=false`），零新依赖，复用现有 `async` Task 引擎 + 安全/多租户/配额链。
> 面试速答稿见 `docs/a2a-interview.md`。

## 三种调用方式

| 方式 | JSON-RPC method | 实现 | 复用 |
| --- | --- | --- | --- |
| **同步** | `message/send`（chat skill） | 阻塞调 `Assistant.chat`，返回 agent `Message`（回复像澄清式提问时返回 `input-required` 的 `Task`） | `Assistant.chat` + grounding + guardrail |
| **流式** | `message/stream`（chat skill） | SSE：`status-update(working)` → 逐 token `artifact-update` → 收口（PII 告警 artifact）→ `status-update(completed / input-required)` | `Assistant.chatStream`（`TokenStream`）+ `StreamGuard` |
| **异步 + Webhook** | `message/send`（multi-agent skill）+ `tasks/pushNotificationConfig/set` | 建异步 Task，终态把 A2A `Task` 回推到客户端 url | `AsyncTaskService` + `A2aPushNotifier` |

任务管理：`tasks/get`（轮询）、`tasks/cancel`、`tasks/pushNotificationConfig/get`。

## 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/.well-known/agent-card.json` | 服务发现（**安全链放行，免鉴权**） |
| POST | `/a2a` | JSON-RPC 2.0 单端点；`message/stream` 返回 `text/event-stream`，其余返回 `application/json`。需 `X-Api-Key` |

## 协议↔内部模型映射

- **Task 状态**：`PENDING→submitted` / `RUNNING→working` / `SUCCEEDED→completed` / `FAILED→failed` / `CANCELLED→canceled`（`A2aMapper`）。`input-required` 已在 **`message/stream` 收口** 和 **`message/send` 同步路径** 都接线（回复经 `StreamGuard.looksLikeClarifyingQuestion` 判为澄清式提问时产出；`app.a2a.detect-input-required` 默开）。
- **结果**：multi-agent 的 `Run.finalAnswer()` 摊成 text `Artifact`；失败信息挂到 `status.message`。
- **会话**：A2A `contextId` → 带租户前缀的 ChatMemory key（`tenant:a2a:<contextId>`），跟 `ChatController.scopedChatId` 一致。

## 关键设计

- **零新依赖**：协议数据类型全是手写 Java `record`（`a2a/protocol/`），JSON-RPC 信封 + Agent Card 自己拼。贴合项目 vLLM 复用 `OpenAiChatModel`、webhook 用 JDK `HttpClient` 的一贯风格，规避 SPI/依赖冲突。
- **两条 webhook 通道隔离**：A2A 异步任务**不写** `AsyncTask.webhookUrl`（现有 `WebhookDispatcher` 因此跳过），push 配置单独存 `A2aPushNotificationStore`，由 `A2aPushNotifier` 按 A2A payload 回推。两者都监听 `TaskEvent`，各管各的 task，不重复触发。复用 `app.async.webhook.*` 的 HMAC/超时/重试。
- **安全复用**：`/a2a` 走 `ApiKeyAuthFilter` → `TenantContext`，多租户、限流（family=`a2a`）、token 预算（纳入 `LLM_FAMILIES`）自动生效；Agent Card 在 `securitySchemes` 声明 apiKey（header `X-Api-Key`）。
- **开关**：`A2aController` 用 `@ConditionalOnProperty(app.a2a.enabled)` 挂端点；`A2aProperties` 无条件注册（避免缺 bean 启动失败）。

## 关键文件

- `a2a/protocol/`：`AgentCard` / `JsonRpc*` / `A2aMessage` / `Part` / `A2aTask` / `Artifact` / `TaskState` / `MessageSendParams` / `PushNotificationConfig` / `Task*UpdateEvent` 等 record
- `a2a/A2aService`：JSON-RPC 分派 + chat 同步 + tasks/* + Agent Card 拼装
- `a2a/A2aStreamService`：`message/stream` 的 SSE 成帧
- `a2a/A2aMapper`：`AsyncTask ↔ A2aTask` 翻译（纯函数，单测覆盖）
- `a2a/A2aPushNotifier` + `A2aPushNotificationStore`：异步终态 webhook 回推
- `controller/A2aController`：`/a2a` + Agent Card 端点
- 接线改动：`security/SecurityConfig`（放行 agent-card）、`RateLimitFilter.familyOf`（+`a2a`）、`TokenBudgetGuardFilter.LLM_FAMILIES`（+`a2a`）

## 单测

- `A2aMapperTest`：状态机全枚举映射 / `Run→artifact` / SUCCEEDED·FAILED·RUNNING 三态 `toA2aTask`
- `A2aDispatchTest`：未知 method→`-32601` / 缺 id→`-32602` / 空 text→`-32602` / skill 解析 / Agent Card 字段 / kind 序列化

## 怎么跑（需起 Ollama）

```bash
APP_A2A_ENABLED=true mvn spring-boot:run
# 本地若 8080 被占（apollo），加 --server.port=8081

# 1) 服务发现
curl localhost:8080/.well-known/agent-card.json

# 2) 同步 message/send（chat）
curl -X POST localhost:8080/a2a -H 'X-Api-Key: <key>' -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":"1","method":"message/send",
       "params":{"message":{"role":"user","parts":[{"kind":"text","text":"用三句话介绍 LangChain4j"}],"metadata":{"skill":"chat"}}}}'

# 3) 流式 message/stream（SSE，逐 token；每帧是包着 status-update/artifact-update 的 JSON-RPC response）
curl -N -X POST localhost:8080/a2a -H 'X-Api-Key: <key>' -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":"2","method":"message/stream",
       "params":{"message":{"role":"user","parts":[{"kind":"text","text":"介绍一下 RAG"}],"metadata":{"skill":"chat"}}}}'

# 4) 异步 multi-agent：先拿 taskId
curl -X POST localhost:8080/a2a -H 'X-Api-Key: <key>' -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":"3","method":"message/send",
       "params":{"message":{"role":"user","parts":[{"kind":"text","text":"对比 PGVector / Milvus / Qdrant"}],"metadata":{"skill":"multi-agent"}},
                 "configuration":{"pushNotificationConfig":{"url":"https://webhook.site/<id>","token":"abc"}}}}'
# 轮询：method=tasks/get params={"id":"<taskId>"} 直到 status.state=completed；或 webhook.site 收终态回推
```

## 流式收口（2026-06-17 补）

`message/stream` 在 `onCompleteResponse` 缓冲完整答案后做两件事（`A2aStreamService` + `StreamGuard`）：

- **input-required**：若回复像「澄清式提问」（`StreamGuard.looksLikeClarifyingQuestion` 保守启发式：明确澄清话术 + 问号结尾 + 较短），终态置 `INPUT_REQUIRED` 而非 `COMPLETED`，给客户端标准多轮续问语义。开关 `app.a2a.detect-input-required`（默开）。
- **PII / grounding 后处理**：流式 token 已逐个发出无法重写，故命中 PII（`StreamGuard.piiWarningOrNull`）或 RAG 不忠实（`GroundingService.streamWarningOrNull`，用 `TokenStream.onRetrieved` 捕获 source）时**追加告警 artifact**（append-only），跟 `/chat/stream` 同款。
- **断连取消**：注册 `emitter.onCompletion/onTimeout/onError` → `cancelled` 标记，客户端断开后停止向死 emitter 转发 + 跳过收口。**限制**：langchain4j 1.13 `TokenStream.start()` 返 void、无取消句柄，**无法真正中止上游 LLM 生成**（仍跑完），只省转发/后处理开销。

详见 `docs/roadmap.md`「已落地优化 2026-06-17（三）」。

## 已知边界（MVP）

- `input-required` 在 `message/stream` 收口 + `message/send` 同步路径都接了（启发式判定澄清式提问）；工作流人工审批接入仍未接。
- `message/stream` 只服务 chat skill（multi-agent 的流式进度推送未做，走异步 Task + webhook/轮询）。
- 流式断连不能中止上游 LLM 生成（`TokenStream` 无取消句柄），只停转发。
- Part 只支持 `text`（`file`/`data` 预留 kind 字段）。
- Task / push 配置进程内存（`TaskStore` / `A2aPushNotificationStore`），多实例换 Redis（同 `TokenBudgetTracker` 演进路径）。
