# Model Cascade / 成本路由（Cost Routing）落地

`ai/cascade` 包，`app.llm.cascade.enabled`（默认关），零新依赖。

## 解决什么

同一批问题，绝大多数是简单的、便宜（小）模型就能答好；只有少数难题才值得付强（大）模型的钱。
**Model Cascade** 把这个直觉工程化：

1. 先用**便宜模型**（`cheap-model`）作答；
2. `ConfidenceGate` 判定这条便宜答案是否「够可信」；
3. 够用 → 直接返回便宜结果（省钱）；不够用 → **升级**到**强模型**（`strong-model`）重答。

结果：大量简单问题被便宜模型消化，强模型只在需要时才被调用，整体 token 成本显著下降，
质量兜底不塌（难题仍走强模型）。指标 `llm.cascade{served=cheap|strong}` 让「这次省没省钱」一眼可见。

与 RAG / 记忆 / 工作流**正交** —— 级联是纯「模型选择」层。

## 开关与配置

`app.llm.cascade.*`（默认全关）：

| key | 默认 | 作用 |
| --- | --- | --- |
| `enabled` | `false` | 总开关。关闭时整个 `CascadeConfig` 不装配、零开销 |
| `cheap-model` | 空 | 便宜模型名（覆盖当前 provider 的默认 model-name）。空 = 用 provider 默认 model |
| `strong-model` | 空 | 强模型名。空 = 用 provider 默认 model（此时退化为「便宜=强」，仅演示不省钱） |
| `confidence-threshold` | `0.6` | 自评置信阈值，**仅 `self-rating=true` 时生效**。自评分 < 此值 → 升级 |
| `min-answer-chars` | `8` | 便宜答案短于此字符数 → 判低置信 → 升级（空答 / 截断兜底） |
| `self-rating` | `false` | 启发式之外再加一道 temp=0 自评（多一次便宜模型调用换精度）。默认关 |
| `uncertainty-markers` | 见下 | 命中即判低置信的拒答 / 不确定措辞（中英混排），可在 yml 覆盖 |

`cheap-model` / `strong-model` 是**同一个 provider** 下的两个模型名，例：

- ollama：`qwen2.5:3b` vs `qwen2.5:14b`
- openai：`gpt-4o-mini` vs `gpt-4o`
- anthropic：`claude-haiku-4-5` vs `claude-sonnet-4-6`

换 provider（`app.llm.provider`）时级联自动跟随 —— 两个底层模型都以当前 provider 的配置为底，
只替换 model-name。

## 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/chat/cascade` | body `{"message":"..."}` → 便宜先答、低置信才升级强模型。返回 `{question, answer, served, cheapConfident}`。走 `/chat` 同套鉴权链（`X-Api-Key` + 多租户 + 限流 + 配额） |

返回字段：

- `answer` — 最终答案
- `served` — `"cheap"` \| `"strong"`，谁最终作答（成本可见）
- `cheapConfident` — 便宜模型是否被判置信（`false` = 发生了升级）

## 置信判定（ConfidenceGate）

纯确定性启发式（无 LLM，可单测）：

1. **空 / 过短**（`< min-answer-chars`）→ 低置信；
2. **命中不确定 / 拒答标记**（`uncertainty-markers`，如「我不确定」「无法回答」「i'm not sure」「unable to」）→ 低置信。

可选增强（`self-rating=true`）：启发式通过后，再让便宜模型对自己答案 temp=0 自评一个 0–1 分，
低于 `confidence-threshold` 也判低置信。自评失败（解析不出分数 / 异常）保守判低置信（升级）。

自评模型走 `LlmConfig#buildJudgeChatModel` 出来的 temp=0 模型，**不是**注册的 ChatModel Bean。

## 关键设计

- **CascadeChatModel implements `dev.langchain4j.model.chat.ChatModel`** —— 包裹 cheap + strong，
  任何吃 `ChatModel` 的地方都能透明用上级联（`chat(ChatRequest)` 被 override 成级联逻辑）。
- **但它故意不注册成 Bean**：它是 `ChatModel` 类型，一旦成 Bean，容器里就有 2 个 ChatModel Bean
  （主 `chatModel` + 它），`langchain4j-spring` 的 `AiServicesAutoConfig` 按
  `getBeanNamesForType(ChatModel.class)` 枚举、数量 >1 直接抛 `IllegalConfigurationException`，
  整个 `@AiService`（Assistant）装配崩掉。做法：cheap/strong/cascade 全 `new` 在 `CascadeService`
  Bean 内部，只暴露 `CascadeService`（非 ChatModel 类型）。与 `VisionConfig` / `AgentBrain`
  「私有模型不进容器」同套路。
- **两个底层模型都灌了 `ChatModelListener`**（`buildJudgeChatModel` 内 `.listeners(listeners)`）——
  token 照常计入 Prometheus 指标 + 当前租户日配额，级联不绕过配额。
- **便宜模型触发工具调用**（`hasToolExecutionRequests()`）时：没有可判的文本，直接返回便宜结果、
  交回上层工具循环，不升级。
- `buildJudgeChatModel` 是 temp=0 确定性构建，对成本路由是可取的（同问同答、可复现）。

## 指标

`llm.cascade{served=cheap|strong}` counter（`CascadeChatModel` 直接打点到 `MeterRegistry`）。
样例 PromQL —— 便宜模型命中率（省钱比例）：

```promql
sum(rate(llm_cascade_total{served="cheap"}[5m]))
  / sum(rate(llm_cascade_total[5m]))
```

## 怎么跑

以 ollama 为例（先 `ollama pull qwen2.5:3b` + `ollama pull qwen2.5:14b`）：

```bash
APP_LLM_CASCADE_ENABLED=true \
APP_LLM_CASCADE_CHEAP_MODEL=qwen2.5:3b \
APP_LLM_CASCADE_STRONG_MODEL=qwen2.5:14b \
mvn spring-boot:run

# 简单问题：便宜模型应答得住 → served=cheap
curl -X POST localhost:8080/chat/cascade -H 'Content-Type: application/json' \
  -d '{"message":"1+1 等于几？"}'

# 便宜模型答不好 / 拒答 → served=strong
curl -X POST localhost:8080/chat/cascade -H 'Content-Type: application/json' \
  -d '{"message":"用一段话严谨推导欧拉公式的几何直觉"}'
```

> 多参数覆盖用环境变量（relaxed binding 稳），别堆 `-Dspring-boot.run.arguments` 逗号（见 CLAUDE.md 注意事项）。

开自评：额外加 `APP_LLM_CASCADE_SELF_RATING=true`（会对便宜答案多发一次 temp=0 调用）。

## 单测（确定性，不连模型）

- `CascadeChatModelTest`（6 case）：低置信升级 strong / 高置信保留 cheap（strong 不被调用）/
  空或过短升级 / 指标跨调用累加 / `CascadeService` 透传 served 明细 / null registry 不抛。两个桩 ChatModel。
- `ConfidenceGateTest`（7 case）：null / 过短 / 命中标记 / 长答置信 / 自评低于阈升级 / 自评高于阈保留 /
  自评不可解析保守判低。桩自评模型。

## 未来项

- **多级级联**（cheap → mid → strong，N 段）而非两段。
- **按题型路由**：结合 `ai/routing` 的 classifier，简单意图直接走 cheap、复杂意图直接 strong，省掉「先便宜再判」的第一跳。
- **流式级联**：当前只覆盖非流式；流式下「先便宜后升级」需要先缓冲判置信再决定是否重开强模型流。
- **自评用独立小判官模型**而非便宜模型自评（弱模型自评偏乐观）。
